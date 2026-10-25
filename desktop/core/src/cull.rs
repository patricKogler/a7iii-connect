//! Culling, ported from the phone so both judge shots alike: bursts and near-duplicates stacked
//! together (Similar.kt), how sharp the sharpest part of a shot is (Sharpness.kt), what looking
//! at a shot found (Insights.kt, in the same file format) and the likely best of a stack (Best.kt).

use crate::library::{write_atomic, STATE_DIR};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

/// What a picture looks like, small enough to keep for every shot: 512 edge bits (is each pixel
/// of a 17x17 grey thumbnail brighter than its right neighbour, and than the one below) and a
/// 64-bin colour histogram (4 levels each of red, green and blue).
#[derive(Clone, Debug, PartialEq)]
pub struct Look {
    pub edges: [u64; 8],
    pub colours: [u8; 64],
}

impl Look {
    pub const SIDE: usize = 17;
    /// The histogram's bins add up to (about) this.
    const COLOUR_TOTAL: u32 = 255;

    /// From `SIDE`x`SIDE` RGB bytes, row by row.
    pub fn of(rgb: &[u8]) -> Look {
        let s = Self::SIDE;
        assert_eq!(rgb.len(), s * s * 3);
        let grey: Vec<u32> = rgb.as_chunks::<3>().0.iter().map(|c| c[0] as u32 * 299 + c[1] as u32 * 587 + c[2] as u32 * 114).collect();
        let mut edges = [0u64; 8];
        let mut bit = 0;
        let mut put = |on: bool| {
            if on {
                edges[bit / 64] |= 1 << (bit % 64);
            }
            bit += 1;
        };
        for y in 0..s - 1 {
            for x in 0..s - 1 {
                put(grey[y * s + x] > grey[y * s + x + 1]);
            }
        }
        for y in 0..s - 1 {
            for x in 0..s - 1 {
                put(grey[y * s + x] > grey[(y + 1) * s + x]);
            }
        }
        let mut counts = [0u32; 64];
        for c in rgb.as_chunks::<3>().0 {
            counts[(c[0] >> 6) as usize * 16 + (c[1] >> 6) as usize * 4 + (c[2] >> 6) as usize] += 1;
        }
        let n = (s * s) as u32;
        Look { edges, colours: std::array::from_fn(|i| (counts[i] * Self::COLOUR_TOTAL / n) as u8) }
    }

    /// Share of edge bits that differ: 0 identical, about 0.5 for unrelated pictures.
    pub fn edge_distance(&self, o: &Look) -> f32 {
        let n: u32 = self.edges.iter().zip(&o.edges).map(|(a, b)| (a ^ b).count_ones()).sum();
        n as f32 / (8.0 * 64.0)
    }

    /// How much of the colour histogram would have to move: 0 the same colours, 1 none shared.
    pub fn colour_distance(&self, o: &Look) -> f32 {
        let d: i32 = self.colours.iter().zip(&o.colours).map(|(&a, &b)| (a as i32 - b as i32).abs()).sum();
        d as f32 / (2.0 * Self::COLOUR_TOTAL as f32)
    }

    pub fn encode(&self) -> String {
        let e: String = self.edges.iter().map(|w| format!("{w:016x}")).collect();
        let c: String = self.colours.iter().map(|b| format!("{b:02x}")).collect();
        format!("{e}:{c}")
    }

    pub fn decode(s: &str) -> Option<Look> {
        let (e, c) = s.split_once(':')?;
        if e.len() != 128 || c.len() != 128 {
            return None;
        }
        let mut edges = [0u64; 8];
        for (i, w) in edges.iter_mut().enumerate() {
            *w = u64::from_str_radix(e.get(i * 16..i * 16 + 16)?, 16).ok()?;
        }
        let mut colours = [0u8; 64];
        for (i, b) in colours.iter_mut().enumerate() {
            *b = u8::from_str_radix(c.get(i * 2..i * 2 + 2)?, 16).ok()?;
        }
        Some(Look { edges, colours })
    }
}

const BURST_MS: i64 = 2_000;
const SIMILAR_MS: i64 = 60_000;
/// Share of edge bits that may differ, and how far apart the colours may be (0 to 1).
const EDGES: f32 = 0.22;
const COLOURS: f32 = 0.30;
const BURST_EDGES: f32 = 0.30;
const BURST_COLOURS: f32 = 0.40;
/// Extra slack against the stack's first frame.
const DRIFT: f32 = 0.06;
/// Zooming in or out by more than this makes a different picture.
const FOCAL_RATIO: f32 = 1.15;

/// A shot as stacking sees it.
pub struct Frame<'a> {
    pub taken_millis: i64,
    pub look: Option<&'a Look>,
    pub portrait: Option<bool>,
    pub focal: Option<f32>,
}

/// Whether two looks are close enough to stack; `slack` loosens both limits.
pub fn alike(a: Option<&Look>, b: Option<&Look>, burst: bool, slack: f32) -> bool {
    let (Some(a), Some(b)) = (a, b) else { return false };
    let edges = if burst { BURST_EDGES } else { EDGES } + slack;
    let colours = if burst { BURST_COLOURS } else { COLOURS } + slack;
    a.edge_distance(b) <= edges && a.colour_distance(b) <= colours
}

/// Same orientation and about the same focal length, as far as EXIF says.
fn same_framing(a: &Frame, b: &Frame) -> bool {
    if let (Some(pa), Some(pb)) = (a.portrait, b.portrait) {
        if pa != pb {
            return false;
        }
    }
    if let (Some(fa), Some(fb)) = (a.focal, b.focal) {
        if fa > 0.0 && fb > 0.0 && fa.max(fb) / fa.min(fb) > FOCAL_RATIO {
            return false;
        }
    }
    true
}

/// Bursts and near-duplicates: `frames` (in the order they were taken) as stacks of their
/// indices, single shots as stacks of one. A frame joins the previous one's stack when it came
/// within a minute and looks alike, both to the previous frame and (a little more loosely) to the
/// stack's first, so a slow pan can't chain unrelated views together. Frames within two seconds
/// get more slack, since a burst follows a moving subject.
pub fn stacks(frames: &[Frame]) -> Vec<Vec<usize>> {
    let mut out: Vec<Vec<usize>> = Vec::new();
    for (i, f) in frames.iter().enumerate() {
        let together = out.last().is_some_and(|stack| {
            let prev = &frames[*stack.last().unwrap()];
            let first = &frames[stack[0]];
            let gap = f.taken_millis - prev.taken_millis;
            if gap > SIMILAR_MS || !same_framing(prev, f) {
                return false;
            }
            let burst = gap <= BURST_MS;
            alike(prev.look, f.look, burst, 0.0) && alike(first.look, f.look, burst, DRIFT)
        });
        match out.last_mut() {
            Some(stack) if together => stack.push(i),
            _ => out.push(vec![i]),
        }
    }
    out
}

/// How sharp the sharpest part of a picture is: the photo cut into tiles, each tile's detail
/// measured (the energy of a Laplacian after a light blur, so noise counts less) and the best
/// tiles averaged. A shallow depth of field doesn't count against a shot: what matters is whether
/// the part in focus is crisp. Scores compare shots of one scene; they mean little across scenes.
/// `grey` is 0..1, row by row; `region` (left, top, right, bottom pixels) limits it to a face.
pub fn sharpness(grey: &[f32], w: usize, h: usize, region: Option<[usize; 4]>) -> f32 {
    const TILE: usize = 24;
    const BEST: f32 = 0.08;
    let [l, t, r, b] = region.unwrap_or([1, 1, w.saturating_sub(1), h.saturating_sub(1)]);
    let (l, t, r, b) = (l.max(1), t.max(1), r.min(w.saturating_sub(1)), b.min(h.saturating_sub(1)));
    if r < l + 3 || b < t + 3 {
        return 0.0;
    }
    // A face is small: make its tiles smaller too, so there are enough of them.
    let tile = TILE.min(4.max((r - l).min(b - t) / 4));
    let cols = (r - l).div_ceil(tile);
    let rows = (b - t).div_ceil(tile);
    let mut sums = vec![0f64; cols * rows];
    let mut counts = vec![0u32; cols * rows];
    let blur = |x: usize, y: usize| {
        let i = y * w + x;
        let g = grey;
        (4.0 * g[i] + 2.0 * (g[i - 1] + g[i + 1] + g[i - w] + g[i + w]) + g[i - w - 1] + g[i - w + 1] + g[i + w - 1] + g[i + w + 1]) / 16.0
    };
    for y in t..b {
        if y < 2 || y + 2 >= h {
            continue;
        }
        for x in l..r {
            if x < 2 || x + 2 >= w {
                continue;
            }
            let lap = 4.0 * blur(x, y) - blur(x - 1, y) - blur(x + 1, y) - blur(x, y - 1) - blur(x, y + 1);
            let i = (y - t) / tile * cols + (x - l) / tile;
            sums[i] += (lap * lap) as f64;
            counts[i] += 1;
        }
    }
    let mut energies: Vec<f64> = (0..sums.len()).filter(|&i| counts[i] > 0).map(|i| sums[i] / counts[i] as f64).collect();
    if energies.is_empty() {
        return 0.0;
    }
    energies.sort_by(|a, b| b.total_cmp(a));
    let n = 1.max((energies.len() as f32 * BEST) as usize);
    // Square root, so twice as blurry reads about half the score; scaled to roughly 0..100.
    ((energies[..n].iter().sum::<f64>() / n as f64).sqrt() * 1000.0) as f32
}

/// A face in a shot. `b` is left, top, right, bottom as fractions of the upright picture.
#[derive(Clone, Debug, PartialEq)]
pub struct FaceInfo {
    pub b: [f32; 4],
    /// How open the more closed eye is, 0..1; None when it couldn't tell (the computer can't).
    pub eyes_open: Option<f32>,
    pub smile: Option<f32>,
    /// `sharpness` of the face.
    pub sharp: f32,
    /// Who it is, as a unit vector; None for faces too small or too turned.
    pub embedding: Option<Vec<f32>>,
}

impl FaceInfo {
    pub fn width(&self) -> f32 {
        self.b[2] - self.b[0]
    }
}

/// What looking at a shot found, in the phone's insights.txt format.
#[derive(Clone, Debug, PartialEq)]
pub struct Insight {
    pub faces: Vec<FaceInfo>,
    /// The sharpness that matters: the biggest face's when there are faces, else the sharpest part.
    pub sharp: f32,
    pub labels: Vec<String>,
    pub text: String,
    pub version: i64,
}

impl Insight {
    /// The phone's ShotInsight.VERSION.
    pub const VERSION: i64 = 1;

    pub fn encode(&self) -> String {
        let mut o = json!({ "v": self.version, "s": self.sharp as f64 });
        if !self.labels.is_empty() {
            o["l"] = json!(self.labels);
        }
        if !self.text.is_empty() {
            o["t"] = json!(self.text);
        }
        if !self.faces.is_empty() {
            o["f"] = Value::Array(
                self.faces
                    .iter()
                    .map(|f| {
                        let mut v = json!({ "b": f.b.iter().map(|x| (*x as f64 * 10000.0).round() / 10000.0).collect::<Vec<_>>(), "s": f.sharp as f64 });
                        if let Some(e) = f.eyes_open {
                            v["e"] = json!(e as f64);
                        }
                        if let Some(m) = f.smile {
                            v["m"] = json!(m as f64);
                        }
                        if let Some(e) = &f.embedding {
                            v["id"] = json!(pack_embedding(e));
                        }
                        v
                    })
                    .collect(),
            );
        }
        o.to_string()
    }

    pub fn decode(s: &str) -> Option<Insight> {
        let o: Value = serde_json::from_str(s).ok()?;
        let num = |v: &Value| v.as_f64().map(|x| x as f32);
        let faces = o
            .get("f")
            .and_then(|f| f.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|f| {
                        let b = f.get("b")?.as_array()?;
                        Some(FaceInfo {
                            b: std::array::from_fn(|i| b.get(i).and_then(num).unwrap_or(0.0)),
                            eyes_open: f.get("e").and_then(num),
                            smile: f.get("m").and_then(num),
                            sharp: f.get("s").and_then(num).unwrap_or(0.0),
                            embedding: f.get("id").and_then(|v| v.as_str()).and_then(unpack_embedding),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        let labels = o.get("l").and_then(|l| l.as_array()).map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect()).unwrap_or_default();
        Some(Insight {
            faces,
            sharp: o.get("s").and_then(num).unwrap_or(0.0),
            labels,
            text: o.get("t").and_then(|t| t.as_str()).unwrap_or_default().to_string(),
            version: o.get("v").and_then(|v| v.as_i64()).unwrap_or(0),
        })
    }
}

/// A unit vector in a byte per value (plenty for telling faces apart), base64.
pub fn pack_embedding(e: &[f32]) -> String {
    base64(&e.iter().map(|v| ((v * 127.0) as i32).clamp(-127, 127) as i8 as u8).collect::<Vec<_>>())
}

pub fn unpack_embedding(s: &str) -> Option<Vec<f32>> {
    let mut v: Vec<f32> = unbase64(s)?.iter().map(|&b| b as i8 as f32 / 127.0).collect();
    crate::ai::faces::normalize(&mut v);
    Some(v)
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn base64(b: &[u8]) -> String {
    let mut out = String::with_capacity(b.len().div_ceil(3) * 4);
    for c in b.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            if i <= c.len() {
                out.push(B64[(n >> (18 - 6 * i) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

fn unbase64(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0);
    for ch in s.bytes().filter(|&c| c != b'=') {
        let v = B64.iter().position(|&b| b == ch)? as u32;
        acc = acc << 6 | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// Every shot's `Insight` and `Look`, kept beside the library: `.shootback/insights.txt` and
/// `.shootback/looks.txt`, a line per shot (key, a tab, then the value), as on the phone. Lines
/// are only added; a later line for the same shot wins.
pub struct CullStore {
    dir: PathBuf,
}

impl CullStore {
    pub fn new(root: &Path) -> CullStore {
        CullStore { dir: root.join(STATE_DIR) }
    }

    fn read<T>(&self, name: &str, parse: impl Fn(&str) -> Option<T>) -> HashMap<String, T> {
        let mut map = HashMap::new();
        let Ok(text) = fs::read_to_string(self.dir.join(name)) else { return map };
        let mut lines = 0;
        for line in text.lines() {
            lines += 1;
            if let Some((k, v)) = line.split_once('\t') {
                if let Some(v) = parse(v) {
                    map.insert(k.to_string(), v);
                }
            }
        }
        // Mostly stale: written again.
        if lines > map.len() * 3 / 2 + 50 {
            let _ = write_atomic(
                &self.dir.join(name),
                text.lines().filter(|l| l.split_once('\t').is_some_and(|(k, _)| map.contains_key(k))).collect::<Vec<_>>().join("\n").as_bytes(),
            );
        }
        map
    }

    fn append(&self, name: &str, key: &str, value: &str) -> std::io::Result<()> {
        fs::create_dir_all(&self.dir)?;
        let mut f = fs::OpenOptions::new().create(true).append(true).open(self.dir.join(name))?;
        writeln!(f, "{key}\t{value}")
    }

    pub fn insights(&self) -> HashMap<String, Insight> {
        self.read("insights.txt", Insight::decode)
    }

    pub fn add_insight(&self, key: &str, i: &Insight) -> std::io::Result<()> {
        self.append("insights.txt", key, &i.encode())
    }

    pub fn looks(&self) -> HashMap<String, Look> {
        self.read("looks.txt", Look::decode)
    }

    pub fn add_look(&self, key: &str, l: &Look) -> std::io::Result<()> {
        self.append("looks.txt", key, &l.encode())
    }
}

/// The likely best of similar shots, and what's wrong with a shot. Only a suggestion.
pub mod best {
    use super::Insight;

    /// An eye this open (0..1) or less counts as closed.
    const CLOSED: f32 = 0.25;
    /// Faces narrower than this share of the picture are background people.
    pub const MAIN_FACE: f32 = 0.05;
    /// Softer than this share of the sharpest in the stack gets "Softer".
    const SOFTER: f32 = 0.75;
    /// The sharpest has to beat the next by this much to be called "Sharpest".
    const CLEAR_LEAD: f32 = 1.1;

    /// People in the shot with their eyes shut (only faces big enough to matter).
    pub fn blinks(i: Option<&Insight>) -> usize {
        i.map_or(0, |i| i.faces.iter().filter(|f| f.width() >= MAIN_FACE && f.eyes_open.unwrap_or(1.0) <= CLOSED).count())
    }

    /// Higher is better; only meaningful against the rest of `stack`.
    pub fn score(i: Option<&Insight>, stack: &[Option<&Insight>]) -> f32 {
        let Some(i) = i else { return 0.0 };
        let sharpest = stack.iter().map(|s| s.map_or(0.0, |s| s.sharp)).fold(0.0, f32::max);
        let sharpest = if sharpest > 0.0 { sharpest } else { 1.0 };
        let smiles: Vec<f32> = i.faces.iter().filter(|f| f.width() >= MAIN_FACE).filter_map(|f| f.smile).collect();
        let smile = if smiles.is_empty() { 0.0 } else { smiles.iter().sum::<f32>() / smiles.len() as f32 };
        i.sharp / sharpest - 0.6 * blinks(Some(i)) as f32 + 0.1 * smile
    }

    /// Indices into `stack` with its likely best first; the rest stay in the order taken.
    pub fn order(stack: &[Option<&Insight>]) -> Vec<usize> {
        let mut out: Vec<usize> = (0..stack.len()).collect();
        if stack.len() < 2 || stack.iter().all(|i| i.is_none()) {
            return out;
        }
        let best = (0..stack.len()).max_by(|&a, &b| score(stack[a], stack).total_cmp(&score(stack[b], stack))).unwrap_or(0);
        out.retain(|&i| i != best);
        out.insert(0, best);
        out
    }

    /// Short notes for a shot: "Eyes closed", and beside similar shots "Sharpest" or "Softer".
    pub fn notes(i: Option<&Insight>, others: &[Option<&Insight>]) -> Vec<String> {
        let Some(i) = i else { return Vec::new() };
        let mut out = Vec::new();
        match blinks(Some(i)) {
            0 => {}
            1 => out.push("Eyes closed".to_string()),
            n => out.push(format!("{n} with eyes closed")),
        }
        let others: Vec<f32> = others.iter().flatten().map(|o| o.sharp).collect();
        if !others.is_empty() && i.sharp > 0.0 {
            let top = others.iter().copied().fold(0.0, f32::max);
            if i.sharp >= top * CLEAR_LEAD {
                out.push("Sharpest".to_string());
            } else if i.sharp < top.max(i.sharp) * SOFTER {
                out.push("Softer".to_string());
            }
        }
        out
    }
}

/// Head to head (the phone's Duel): the best shot so far meets each of the others in turn, and
/// the better one stays on. "Keep both" lets the challenger through as well. With n shots it
/// takes n - 1 choices, each between two pictures. Every step can be undone.
#[derive(Clone, Debug)]
pub struct Duel {
    pub len: usize,
    best: usize,
    next: usize,
    also_kept: Vec<usize>,
    history: Vec<(usize, usize, Vec<usize>)>,
}

impl Duel {
    pub fn new(len: usize) -> Duel {
        Duel { len, best: 0, next: 1, also_kept: Vec::new(), history: Vec::new() }
    }

    pub fn best(&self) -> usize {
        self.best
    }

    /// The shot up against `best`, or None once everyone has had a go.
    pub fn challenger(&self) -> Option<usize> {
        (self.next < self.len).then_some(self.next)
    }

    pub fn done(&self) -> bool {
        self.next >= self.len
    }

    /// Choices made so far, out of `rounds`.
    pub fn round(&self) -> usize {
        self.next.saturating_sub(1)
    }

    pub fn rounds(&self) -> usize {
        self.len.saturating_sub(1)
    }

    pub fn can_undo(&self) -> bool {
        !self.history.is_empty()
    }

    fn push(&mut self) {
        self.history.push((self.best, self.next, self.also_kept.clone()));
    }

    /// The best so far wins (`best_wins`) or the challenger takes its place.
    pub fn choose(&mut self, best_wins: bool) {
        if self.done() {
            return;
        }
        self.push();
        if !best_wins {
            self.best = self.next;
        }
        self.next += 1;
    }

    /// Both stay: the challenger is kept, and the best so far meets the next one.
    pub fn keep_both(&mut self) {
        if self.done() {
            return;
        }
        self.push();
        self.also_kept.push(self.next);
        self.next += 1;
    }

    pub fn undo(&mut self) {
        if let Some((b, n, k)) = self.history.pop() {
            (self.best, self.next, self.also_kept) = (b, n, k);
        }
    }

    /// The shots to keep, the winner first; meaningful once done.
    pub fn kept(&self) -> Vec<usize> {
        let mut out = vec![self.best];
        out.extend(self.also_kept.iter().filter(|&&k| k != self.best));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn look_of(f: impl Fn(usize, usize) -> [u8; 3]) -> Look {
        let s = Look::SIDE;
        Look::of(&(0..s * s).flat_map(|i| f(i % s, i / s)).collect::<Vec<_>>())
    }

    #[test]
    fn looks_round_trip_and_tell_pictures_apart() {
        let a = look_of(|x, y| [(x * 15) as u8, (y * 15) as u8, 100]);
        let b = look_of(|x, y| [(x * 15) as u8, (y * 15 + 3) as u8, 100]);
        let c = look_of(|x, y| [255 - (y * 15) as u8, (x * 7) as u8, 30]);
        assert_eq!(Look::decode(&a.encode()), Some(a.clone()));
        assert!(a.edge_distance(&b) < 0.1 && a.colour_distance(&b) < 0.2);
        assert!(a.edge_distance(&c) > 0.3);
    }

    #[test]
    fn bursts_stack_and_different_views_dont() {
        let a = look_of(|x, y| [(x * 15) as u8, (y * 15) as u8, 100]);
        let c = look_of(|x, y| [255 - (y * 15) as u8, (x * 7) as u8, 30]);
        fn f(t: i64, l: &Look) -> Frame<'_> {
            Frame { taken_millis: t, look: Some(l), portrait: None, focal: None }
        }
        let frames = [f(0, &a), f(500, &a), f(900, &a), f(1200, &c), f(200_000, &c)];
        assert_eq!(stacks(&frames), vec![vec![0, 1, 2], vec![3], vec![4]]);
    }

    #[test]
    fn sharp_beats_blurred() {
        let (w, h) = (96, 64);
        let sharp: Vec<f32> = (0..w * h).map(|i| if (i % w / 4 + i / w / 4) % 2 == 0 { 0.9 } else { 0.1 }).collect();
        let mut soft = vec![0.0; w * h];
        crate::develop::image::box_blur(&sharp, &mut soft, w, h, 2);
        assert!(sharpness(&sharp, w, h, None) > 2.0 * sharpness(&soft, w, h, None));
    }

    #[test]
    fn insights_read_the_phones_format() {
        let phone = r#"{"v":1,"s":12.5,"l":["Car"],"f":[{"b":[0.1,0.2,0.3,0.4],"e":0.1,"m":0.8,"s":20,"id":"f38A"}]}"#;
        let i = Insight::decode(phone).unwrap();
        assert_eq!(i.faces.len(), 1);
        assert_eq!(i.faces[0].eyes_open, Some(0.1));
        let e = i.faces[0].embedding.as_ref().unwrap();
        assert_eq!(e.len(), 3);
        assert!((e.iter().map(|v| v * v).sum::<f32>() - 1.0).abs() < 1e-4);
        assert_eq!(Insight::decode(&i.encode()).unwrap().faces[0].b, i.faces[0].b);
        assert_eq!(best::blinks(Some(&i)), 1);
    }

    #[test]
    fn base64_round_trips() {
        for n in 0..10 {
            let b: Vec<u8> = (0..n).map(|i| (i * 37 + 5) as u8).collect();
            assert_eq!(unbase64(&base64(&b)).unwrap(), b);
        }
        assert_eq!(base64(b"Man"), "TWFu");
        assert_eq!(base64(b"Ma"), "TWE=");
    }

    #[test]
    fn duel_keeps_the_winner_and_both_when_asked() {
        let mut d = Duel::new(4);
        d.choose(true); // 0 beats 1
        d.keep_both(); // 2 stays too
        d.choose(false); // 3 beats 0
        assert!(d.done());
        assert_eq!(d.kept(), vec![3, 2]);
        d.undo();
        assert_eq!(d.challenger(), Some(3));
    }
}
