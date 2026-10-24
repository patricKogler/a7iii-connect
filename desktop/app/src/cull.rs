//! Culling, as on the phone: the shots dealt one at a time to pick, reject or skip, bursts and
//! near-duplicates as one stack settled two at a time. Behind it, on a thread, what every shot
//! looks like (for stacking) and what's in it (faces, focus) is worked out once and kept beside the
//! library, the shots coming up next first.

use crate::images::{self, THUMB};
use crate::state::Filter;
use shootback_core::ai::{self, tensor::Rgb};
use shootback_core::cull::{self, best, CullStore, Duel, FaceInfo, Frame, Insight, Look};
use shootback_core::library::{self, Shot};
use shootback_core::marks::Mark;
use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;

/// Long edge of the picture faces and focus are judged on, as on the phone.
const EDGE: u32 = 1600;
/// Faces narrower than this many pixels (at `EDGE`) are too small to recognise.
const MIN_FACE_PX: f32 = 56.0;
/// Faces turned further than this (`Face::turned`, about 35 degrees) are profiles: not recognised.
const TURNED: f32 = 0.55;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Pick,
    Reject,
    Skip,
}

/// One decision, with the marks it replaced so undo can put them back.
struct Step {
    pos: usize,
    previous: Vec<(String, Option<Mark>)>,
}

enum Msg {
    Deck(Vec<Vec<Shot>>),
    Known(HashMap<String, Insight>),
    Insight(String, Insight),
    /// Faces couldn't be looked at (the model didn't download, say); focus still is.
    NoFaces(String),
}

/// A stack being settled head to head: `order` is the stack with its likely best first, and the
/// duel's indices point into it.
pub struct Contest {
    pub pos: usize,
    pub order: Vec<usize>,
    pub duel: Duel,
}

pub struct Cull {
    /// What's being culled: the shots to review, the picks (to narrow them down) or an album.
    pub scope: Filter,
    /// Stacks in the order they're dealt; None while similar shots are being found.
    pub deck: Option<Vec<Vec<Shot>>>,
    pub pos: usize,
    undo: Vec<Step>,
    pub decided: HashMap<String, Verdict>,
    pub contest: Option<Contest>,
    pub insights: HashMap<String, Insight>,
    pub faces_error: Option<String>,
    /// Zoomed in to check focus: the point of the photo in the middle (0..1 each way).
    pub zoom: Option<[f32; 2]>,
    /// The point of the photo under the pointer, to zoom in on.
    pub hover: Option<[f32; 2]>,
    started: bool,
    rx: Option<Receiver<Msg>>,
    cancel: Arc<AtomicBool>,
    /// Fraction of the shots looked at for stacking, as f32 bits.
    progress: Arc<AtomicU32>,
    /// Where the deck is, so the worker looks at what comes up next first.
    at: Arc<AtomicUsize>,
}

impl Cull {
    pub fn new() -> Cull {
        Cull {
            scope: Filter::Review,
            deck: None,
            pos: 0,
            undo: Vec::new(),
            decided: HashMap::new(),
            contest: None,
            insights: HashMap::new(),
            faces_error: None,
            zoom: None,
            hover: None,
            started: false,
            rx: None,
            cancel: Arc::default(),
            progress: Arc::default(),
            at: Arc::default(),
        }
    }

    pub fn started(&self) -> bool {
        self.started
    }

    pub fn progress(&self) -> f32 {
        f32::from_bits(self.progress.load(Ordering::Relaxed))
    }

    /// Stops the worker and forgets the deck (the library changed, or culling starts over).
    pub fn reset(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
        let scope = self.scope.clone();
        *self = Cull::new();
        self.scope = scope;
    }

    /// Deals `shots` (newest day first, each day in the order taken) and starts looking at them.
    pub fn start(&mut self, ctx: &eframe::egui::Context, root: &Path, shots: Vec<Shot>) {
        self.reset();
        self.started = true;
        let (tx, rx) = channel();
        self.rx = Some(rx);
        let (cancel, progress, at, root, ctx) = (self.cancel.clone(), self.progress.clone(), self.at.clone(), root.to_path_buf(), ctx.clone());
        std::thread::Builder::new().name("cull".into()).spawn(move || work(&root, shots, &tx, &cancel, &progress, &at, &ctx)).expect("cull thread");
    }

    /// Takes in what the worker found; true when something changed.
    pub fn poll(&mut self) -> bool {
        let Some(rx) = &self.rx else { return false };
        let mut changed = false;
        while let Ok(m) = rx.try_recv() {
            changed = true;
            match m {
                Msg::Deck(d) => self.deck = Some(d),
                Msg::Known(k) => self.insights.extend(k),
                Msg::Insight(k, i) => {
                    self.insights.insert(k, i);
                }
                Msg::NoFaces(e) => self.faces_error = Some(e),
            }
        }
        changed
    }

    pub fn top(&self) -> Option<&Vec<Shot>> {
        self.deck.as_ref()?.get(self.pos)
    }

    fn insights_of(&self, stack: &[Shot]) -> Vec<Option<&Insight>> {
        stack.iter().map(|s| self.insights.get(&s.key())).collect()
    }

    /// Short notes on a shot: "Eyes closed", and in a stack "Sharpest" or "Softer".
    pub fn notes(&self, shot: &Shot, stack: &[Shot]) -> Vec<String> {
        let others: Vec<Option<&Insight>> = stack.iter().filter(|s| s.key() != shot.key()).map(|s| self.insights.get(&s.key())).collect();
        best::notes(self.insights.get(&shot.key()), &others)
    }

    /// Keeps the head to head in step with the top of the deck; records a stack once it's settled.
    pub fn follow(&mut self, lib: &mut crate::state::Library) {
        self.at.store(self.pos, Ordering::Relaxed);
        let Some(top) = self.top().cloned() else {
            self.contest = None;
            return;
        };
        if top.len() < 2 {
            self.contest = None;
            return;
        }
        if self.contest.as_ref().is_none_or(|c| c.pos != self.pos) {
            let order = best::order(&self.insights_of(&top));
            self.contest = Some(Contest { pos: self.pos, duel: Duel::new(order.len()), order });
            self.zoom = None;
        }
        let c = self.contest.as_ref().unwrap();
        if c.duel.done() {
            let kept: Vec<String> = c.duel.kept().iter().map(|&i| top[c.order[i]].key()).collect();
            let verdicts = top.iter().map(|s| (s.key(), if kept.contains(&s.key()) { Verdict::Pick } else { Verdict::Reject })).collect();
            self.record(lib, verdicts);
        }
    }

    /// Decides the top of the deck: every shot in it gets `v`.
    pub fn decide(&mut self, lib: &mut crate::state::Library, v: Verdict) {
        let Some(top) = self.top() else { return };
        let verdicts = top.iter().map(|s| (s.key(), v)).collect();
        self.record(lib, verdicts);
    }

    fn record(&mut self, lib: &mut crate::state::Library, verdicts: Vec<(String, Verdict)>) {
        self.undo.push(Step { pos: self.pos, previous: verdicts.iter().map(|(k, _)| (k.clone(), lib.marks.get(k))).collect() });
        for (k, v) in verdicts {
            match v {
                Verdict::Pick => lib.set_mark(&k, Some(Mark::Pick)),
                Verdict::Reject => lib.set_mark(&k, Some(Mark::Reject)),
                Verdict::Skip => {}
            }
            self.decided.insert(k, v);
        }
        self.contest = None;
        self.zoom = None;
        self.pos += 1;
    }

    pub fn can_undo(&self) -> bool {
        self.contest.as_ref().is_some_and(|c| c.duel.can_undo()) || !self.undo.is_empty()
    }

    /// Takes back the last choice in the head to head, or else the last decision.
    pub fn undo(&mut self, lib: &mut crate::state::Library) {
        if let Some(c) = self.contest.as_mut().filter(|c| c.duel.can_undo()) {
            c.duel.undo();
            return;
        }
        let Some(step) = self.undo.pop() else { return };
        for (k, m) in step.previous {
            lib.set_mark(&k, m);
            self.decided.remove(&k);
        }
        self.contest = None;
        self.zoom = None;
        self.pos = step.pos;
    }

    /// The top stack wasn't really similar: its shots come one at a time.
    pub fn split(&mut self) {
        let pos = self.pos;
        if let Some(deck) = self.deck.as_mut() {
            if deck.get(pos).is_some_and(|s| s.len() > 1) {
                let stack = deck.remove(pos);
                for (i, s) in stack.into_iter().enumerate() {
                    deck.insert(pos + i, vec![s]);
                }
            }
        }
        self.contest = None;
    }

    pub fn count(&self, v: Verdict) -> usize {
        self.decided.values().filter(|&&d| d == v).count()
    }
}

fn work(root: &Path, shots: Vec<Shot>, tx: &Sender<Msg>, cancel: &AtomicBool, progress: &AtomicU32, at: &AtomicUsize, ctx: &eframe::egui::Context) {
    let store = CullStore::new(root);
    let mut known = store.insights();
    let _ = tx.send(Msg::Known(known.clone()));
    let mut shots = shots;
    shots.sort_by(|a, b| a.taken_millis().cmp(&b.taken_millis()).then_with(|| a.primary().name.cmp(&b.primary().name)));

    // What each shot looks like, a few at a time.
    let looks = store.looks();
    let done = AtomicUsize::new(0);
    let threads = std::thread::available_parallelism().map_or(2, |n| n.get()).clamp(1, 4);
    let mut seen: Vec<(Option<Look>, Option<f32>)> = vec![(None, None); shots.len()];
    std::thread::scope(|scope| {
        for (t, part) in seen.chunks_mut(shots.len().div_ceil(threads).max(1)).enumerate() {
            let (shots, looks, store, done) = (&shots, &looks, &store, &done);
            let first = t * shots.len().div_ceil(threads).max(1);
            scope.spawn(move || {
                for (j, slot) in part.iter_mut().enumerate() {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    let shot = &shots[first + j];
                    let key = shot.key();
                    let look = looks.get(&key).cloned().or_else(|| {
                        let l = look_of(shot)?;
                        let _ = store.add_look(&key, &l);
                        Some(l)
                    });
                    *slot = (look, focal(shot));
                    let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                    progress.store((n as f32 / shots.len() as f32).to_bits(), Ordering::Relaxed);
                    ctx.request_repaint();
                }
            });
        }
    });
    if cancel.load(Ordering::Relaxed) {
        return;
    }
    let frames: Vec<Frame> = shots
        .iter()
        .zip(&seen)
        .map(|(s, (look, focal))| Frame {
            taken_millis: s.taken_millis(),
            look: look.as_ref(),
            portrait: Some(matches!(s.display().orientation, 5..=8)),
            focal: *focal,
        })
        .collect();
    let mut deck: Vec<Vec<Shot>> = cull::stacks(&frames).into_iter().map(|st| st.into_iter().map(|i| shots[i].clone()).collect()).collect();
    // Newest day first; within a day, in the order taken.
    deck.sort_by_key(|st| std::cmp::Reverse(day(st[0].taken_millis())));
    let _ = tx.send(Msg::Deck(deck.clone()));
    ctx.request_repaint();

    // Faces and focus, from where the deck is onwards.
    let mut faces = true;
    let mut tried: std::collections::HashSet<String> = Default::default();
    loop {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
        let from = at.load(Ordering::Relaxed).min(deck.len());
        let next = deck[from..].iter().chain(&deck[..from]).flatten().find(|s| {
            let k = s.key();
            !tried.contains(&k) && known.get(&k).is_none_or(|i| i.version != Insight::VERSION)
        });
        let Some(shot) = next.cloned() else { return };
        let key = shot.key();
        tried.insert(key.clone());
        // A shot that can't be decoded counts as looked at (with nothing found), for now.
        let (insight, with_faces) = analyse(&shot, &mut faces, tx)
            .unwrap_or_else(|| (Insight { faces: Vec::new(), sharp: 0.0, labels: Vec::new(), text: String::new(), version: Insight::VERSION }, false));
        // Without faces it's only kept for now, so they're looked for next time.
        if with_faces {
            let _ = store.add_insight(&key, &insight);
        }
        known.insert(key.clone(), insight.clone());
        let _ = tx.send(Msg::Insight(key, insight));
        ctx.request_repaint();
    }
}

fn day(millis: i64) -> i64 {
    millis.div_euclid(86_400_000)
}

/// The shot as a 17x17 thumbnail's look.
fn look_of(shot: &Shot) -> Option<Look> {
    let show = shot.display();
    let img = images::decode(&show.path, THUMB, show.orientation)?;
    let [w, h] = img.size;
    let s = Look::SIDE;
    let mut rgb = vec![0u8; s * s * 3];
    for y in 0..s {
        for x in 0..s {
            let (x0, x1) = (x * w / s, ((x + 1) * w / s).max(x * w / s + 1));
            let (y0, y1) = (y * h / s, ((y + 1) * h / s).max(y * h / s + 1));
            let mut sum = [0u32; 3];
            for yy in y0..y1 {
                for xx in x0..x1 {
                    let c = img.pixels[yy * w + xx];
                    sum[0] += c.r() as u32;
                    sum[1] += c.g() as u32;
                    sum[2] += c.b() as u32;
                }
            }
            let n = ((x1 - x0) * (y1 - y0)) as u32;
            for c in 0..3 {
                rgb[(y * s + x) * 3 + c] = (sum[c] / n) as u8;
            }
        }
    }
    Some(Look::of(&rgb))
}

/// Focal length in mm, from EXIF.
fn focal(shot: &Shot) -> Option<f32> {
    let m = library::read_meta(&shot.display().path);
    m.focal_length?.split_whitespace().next()?.parse().ok()
}

/// Faces and focus in a shot. The second value is false when faces couldn't be looked for.
fn analyse(shot: &Shot, faces_ok: &mut bool, tx: &Sender<Msg>) -> Option<(Insight, bool)> {
    let show = shot.display();
    let img = images::decode(&show.path, EDGE, show.orientation)?;
    let [w, h] = img.size;
    let grey: Vec<f32> = img.pixels.iter().map(|c| (0.299 * c.r() as f32 + 0.587 * c.g() as f32 + 0.114 * c.b() as f32) / 255.0).collect();
    let overall = cull::sharpness(&grey, w, h, None);
    let mut infos = Vec::new();
    let mut with_faces = false;
    if *faces_ok {
        let rgb: Vec<u8> = img.pixels.iter().flat_map(|c| [c.r(), c.g(), c.b()]).collect();
        let px = Rgb::from_bytes(&rgb, w, h);
        match ai::faces::find(&px) {
            Ok(found) => {
                with_faces = true;
                for f in found {
                    let (fw, fh) = (w as f32, h as f32);
                    let b = [(f.b[0] / fw).clamp(0.0, 1.0), (f.b[1] / fh).clamp(0.0, 1.0), (f.b[2] / fw).clamp(0.0, 1.0), (f.b[3] / fh).clamp(0.0, 1.0)];
                    // The middle of the face: its edges are often hair and background.
                    let (bw, bh) = ((b[2] - b[0]) * fw, (b[3] - b[1]) * fh);
                    let region = [
                        (b[0] * fw + bw * 0.15) as usize,
                        (b[1] * fh + bh * 0.15) as usize,
                        (b[2] * fw - bw * 0.15) as usize,
                        (b[3] * fh - bh * 0.15) as usize,
                    ];
                    let sharp = if region[2] < region[0] + 8 || region[3] < region[1] + 8 { 0.0 } else { cull::sharpness(&grey, w, h, Some(region)) };
                    let embedding = (f.width() >= MIN_FACE_PX && f.turned() <= TURNED).then(|| ai::faces::embed(&px, &f).ok()).flatten();
                    infos.push(FaceInfo { b, eyes_open: None, smile: None, sharp, embedding });
                }
            }
            Err(e) => {
                *faces_ok = false;
                let _ = tx.send(Msg::NoFaces(e));
            }
        }
    }
    let main = infos.iter().max_by(|a, b| a.width().total_cmp(&b.width())).map(|f| f.sharp);
    Some((Insight { sharp: main.unwrap_or(overall), faces: infos, labels: Vec::new(), text: String::new(), version: Insight::VERSION }, with_faces))
}
