//! HDR merge and focus stacking: several raws of one scene made into one new raw, so everything
//! the editor does with a raw still works on the result.
//!
//! The frames are lined up first (handheld brackets shift and turn a little; focus brackets
//! grow or shrink as the focus moves): a shift, turn and scale is found on their greys, coarse
//! to fine. Then each photosite of the result is made from the same colour's photosites of every
//! frame. An HDR merge takes each frame by how much light it caught, leaving out what clipped
//! and what moved; a focus stack takes each place from the frame that's sharpest there.
//! The result is written as a 16-bit raw beside the frames.

use crate::develop::image::box_blur;
use crate::develop::look::encode_srgb;
use crate::develop::raw::RawImage;
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Hdr,
    Focus,
}

/// What a merge made: the raw, and the exposure (in stops) that shows it as bright as the
/// frame it was lined up on.
pub struct Merged {
    pub raw: RawImage,
    pub exposure: f32,
    /// The frame it was lined up on (its time and shooting details are the result's).
    pub base: usize,
}

/// Where a frame's photosites are, from the result's: p' = A (p - centre) + centre + shift,
/// A a turn and scale.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Warp {
    pub a: f64,
    pub b: f64,
    pub tx: f64,
    pub ty: f64,
}

impl Warp {
    pub const NONE: Warp = Warp { a: 1.0, b: 0.0, tx: 0.0, ty: 0.0 };

    fn apply(&self, x: f64, y: f64, cx: f64, cy: f64) -> (f64, f64) {
        let (dx, dy) = (x - cx, y - cy);
        (self.a * dx - self.b * dy + cx + self.tx, self.b * dx + self.a * dy + cy + self.ty)
    }

    /// The same warp on a picture `k` times as large.
    fn scaled(&self, k: f64) -> Warp {
        Warp { tx: self.tx * k, ty: self.ty * k, ..*self }
    }
}

/// A grey picture, one value per 2x2 Bayer cell.
#[derive(Clone)]
struct Grey {
    w: usize,
    h: usize,
    v: Vec<f32>,
}

impl Grey {
    fn at(&self, x: f64, y: f64) -> Option<f32> {
        if x < 0.0 || y < 0.0 || x > (self.w - 1) as f64 || y > (self.h - 1) as f64 {
            return None;
        }
        let (x0, y0) = (x as usize, y as usize);
        let (x1, y1) = ((x0 + 1).min(self.w - 1), (y0 + 1).min(self.h - 1));
        let (fx, fy) = ((x - x0 as f64) as f32, (y - y0 as f64) as f32);
        let p = |x, y| self.v[y * self.w + x];
        Some((p(x0, y0) * (1.0 - fx) + p(x1, y0) * fx) * (1.0 - fy) + (p(x0, y1) * (1.0 - fx) + p(x1, y1) * fx) * fy)
    }

    fn half(&self) -> Grey {
        let (w, h) = (self.w / 2, self.h / 2);
        let v = (0..w * h)
            .map(|i| {
                let (x, y) = (i % w * 2, i / w * 2);
                (self.v[y * self.w + x] + self.v[y * self.w + x + 1] + self.v[(y + 1) * self.w + x] + self.v[(y + 1) * self.w + x + 1]) / 4.0
            })
            .collect();
        Grey { w, h, v }
    }

    fn of(r: &RawImage) -> Grey {
        let (w, h) = (r.width / 2, r.height / 2);
        let v = (0..w * h)
            .into_par_iter()
            .map(|i| {
                let (x, y) = (i % w * 2, i / w * 2);
                (r.level(y, x) + r.level(y, x + 1) + r.level(y + 1, x) + r.level(y + 1, x + 1)) / 4.0
            })
            .collect();
        Grey { w, h, v }
    }
}

/// Bright enough to line up by and not clipped.
fn usable(v: f32) -> bool {
    (0.004..0.85).contains(&v)
}

/// How much brighter `frame` is than `base`: the middle of their ratios where both are usable.
fn gain(base: &Grey, frame: &Grey, warp: &Warp) -> f64 {
    let (cx, cy) = ((base.w as f64 - 1.0) / 2.0, (base.h as f64 - 1.0) / 2.0);
    let step = ((base.w * base.h) / 200_000).max(1);
    let mut ratios: Vec<f32> = (0..base.w * base.h)
        .step_by(step)
        .filter_map(|i| {
            let b = base.v[i];
            let (x, y) = warp.apply((i % base.w) as f64, (i / base.w) as f64, cx, cy);
            let f = frame.at(x, y)?;
            (usable(b) && usable(f) && b > 0.01).then(|| f / b)
        })
        .collect();
    if ratios.len() < 50 {
        return 1.0;
    }
    ratios.sort_by(|a, b| a.total_cmp(b));
    ratios[ratios.len() / 2] as f64
}

/// The warp that lines `frame` up on `base` (both greys, `frame` brought to `base`'s
/// brightness by `k`).
fn align(base: &Grey, frame: &Grey, k: f32) -> Warp {
    let tone = |g: &Grey, k: f32| Grey { v: g.v.iter().map(|&v| if usable(v) { (v * k).sqrt() } else { f32::NAN }).collect(), ..*g };
    let mut levels = vec![(tone(base, 1.0), tone(frame, k))];
    let (mut b, mut f) = (base.clone(), frame.clone());
    while b.w.max(b.h) > 400 {
        b = b.half();
        f = f.half();
        levels.push((tone(&b, 1.0), tone(&f, k)));
    }
    // At the coarsest, the best whole-pixel shift.
    let (cb, cf) = levels.last().unwrap();
    let reach = (cb.w.max(cb.h) / 12).max(4) as i64;
    let mut best = (f64::MAX, 0, 0);
    for dy in -reach..=reach {
        for dx in -reach..=reach {
            let (mut sum, mut n) = (0.0, 0usize);
            for y in (0..cb.h).step_by(2) {
                for x in (0..cb.w).step_by(2) {
                    let (fx, fy) = (x as i64 + dx, y as i64 + dy);
                    if fx < 0 || fy < 0 || fx >= cf.w as i64 || fy >= cf.h as i64 {
                        continue;
                    }
                    let d = (cb.v[y * cb.w + x] - cf.v[fy as usize * cf.w + fx as usize]).abs();
                    if d.is_finite() {
                        sum += d as f64;
                        n += 1;
                    }
                }
            }
            if n > cb.w * cb.h / 16 && sum / (n as f64) < best.0 {
                best = (sum / n as f64, dx, dy);
            }
        }
    }
    let mut warp = Warp { tx: best.1 as f64, ty: best.2 as f64, ..Warp::NONE };
    for (i, (b, f)) in levels.iter().enumerate().rev() {
        refine(b, f, &mut warp);
        if i > 0 {
            warp = warp.scaled(2.0);
        }
    }
    warp
}

/// Gauss-Newton steps on the warp's four numbers, weighing down what doesn't match (things
/// that moved).
fn refine(b: &Grey, f: &Grey, warp: &mut Warp) {
    let (cx, cy) = ((b.w as f64 - 1.0) / 2.0, (b.h as f64 - 1.0) / 2.0);
    let step = ((b.w * b.h) / 150_000).max(1);
    let norm = b.w.max(b.h) as f64 / 2.0;
    for _ in 0..12 {
        let mut hm = [[0f64; 4]; 4];
        let mut g = [0f64; 4];
        for i in (0..b.w * b.h).step_by(step) {
            let (x, y) = ((i % b.w) as f64, (i / b.w) as f64);
            let bv = b.v[i];
            if !bv.is_finite() {
                continue;
            }
            let (qx, qy) = warp.apply(x, y, cx, cy);
            let (Some(c), Some(l), Some(r), Some(u), Some(d)) = (f.at(qx, qy), f.at(qx - 1.0, qy), f.at(qx + 1.0, qy), f.at(qx, qy - 1.0), f.at(qx, qy + 1.0))
            else {
                continue;
            };
            let (gx, gy) = (((r - l) / 2.0) as f64, ((d - u) / 2.0) as f64);
            let res = (c - bv) as f64;
            if !(res.is_finite() && gx.is_finite() && gy.is_finite()) {
                continue;
            }
            let wgt = if res.abs() < 0.04 { 1.0 } else { 0.04 / res.abs() };
            let (dx, dy) = ((x - cx) / norm, (y - cy) / norm);
            let j = [gx * dx + gy * dy, -gx * dy + gy * dx, gx, gy];
            for p in 0..4 {
                g[p] += wgt * j[p] * res;
                for q in 0..4 {
                    hm[p][q] += wgt * j[p] * j[q];
                }
            }
        }
        let Some(delta) = solve(hm, g) else { return };
        warp.a -= delta[0] / norm;
        warp.b -= delta[1] / norm;
        warp.tx -= delta[2];
        warp.ty -= delta[3];
        if delta[2].abs() + delta[3].abs() < 0.005 {
            return;
        }
    }
}

/// `m x = v`, by elimination.
fn solve(mut m: [[f64; 4]; 4], mut v: [f64; 4]) -> Option<[f64; 4]> {
    for c in 0..4 {
        let p = (c..4).max_by(|&a, &b| m[a][c].abs().total_cmp(&m[b][c].abs()))?;
        if m[p][c].abs() < 1e-12 {
            return None;
        }
        m.swap(c, p);
        v.swap(c, p);
        for r in 0..4 {
            if r != c {
                let k = m[r][c] / m[c][c];
                let pivot = m[c];
                for (a, b) in m[r].iter_mut().zip(pivot) {
                    *a -= k * b;
                }
                v[r] -= k * v[c];
            }
        }
    }
    Some(std::array::from_fn(|i| v[i] / m[i][i]))
}

/// How far a photosite is from clipping: 1 well below, 0 at it.
fn unclipped(top: f32) -> f32 {
    ((0.92 - top) / 0.17).clamp(0.0, 1.0)
}

/// A frame's photosite of the colour at the result's (`x`, `y`), at the frame's (`fx`, `fy`):
/// from the 4x4 nearest of that colour (Catmull-Rom, which keeps more detail than a straight
/// line between two), with the brightest of the middle four (for telling clipping).
fn sample(r: &RawImage, x: usize, y: usize, fx: f64, fy: f64) -> Option<(f32, f32)> {
    let (px, py) = ((x & 1) as f64, (y & 1) as f64);
    let (u, v) = ((fx - px) / 2.0, (fy - py) / 2.0);
    let (cw, ch) = ((r.width - (x & 1)).div_ceil(2), (r.height - (y & 1)).div_ceil(2));
    if u < 0.0 || v < 0.0 || u > (cw - 1) as f64 || v > (ch - 1) as f64 {
        return None;
    }
    let (m, n) = (u as usize, v as usize);
    let (a, b) = ((u - m as f64) as f32, (v - n as f64) as f32);
    if a == 0.0 && b == 0.0 {
        let p = r.level(2 * n + (y & 1), 2 * m + (x & 1));
        return Some((p, p));
    }
    let at = |m: i64, n: i64| r.level(2 * n.clamp(0, ch as i64 - 1) as usize + (y & 1), 2 * m.clamp(0, cw as i64 - 1) as usize + (x & 1));
    let weights = |t: f32| {
        let (t2, t3) = (t * t, t * t * t);
        [(-t3 + 2.0 * t2 - t) / 2.0, (3.0 * t3 - 5.0 * t2 + 2.0) / 2.0, (-3.0 * t3 + 4.0 * t2 + t) / 2.0, (t3 - t2) / 2.0]
    };
    let (wx, wy) = (weights(a), weights(b));
    let (m, n) = (m as i64, n as i64);
    let mut val = 0.0;
    for (j, wj) in wy.iter().enumerate() {
        let row: f32 = wx.iter().enumerate().map(|(i, wi)| wi * at(m + i as i64 - 1, n + j as i64 - 1)).sum();
        val += wj * row;
    }
    let top = at(m, n).max(at(m + 1, n)).max(at(m, n + 1)).max(at(m + 1, n + 1));
    Some((val.max(0.0), top))
}

/// Merges `frames` (raws of one scene from one camera). `progress` gets the fraction done.
pub fn merge(kind: Kind, frames: &[RawImage], progress: &dyn Fn(f32), cancel: &AtomicBool) -> Result<Merged, String> {
    if frames.len() < 2 {
        return Err("pick at least two photos".into());
    }
    let (w, h) = (frames[0].width & !1, frames[0].height & !1);
    if frames.iter().any(|f| (f.width & !1, f.height & !1) != (w, h)) {
        return Err("the photos aren't all the same size".into());
    }
    let greys: Vec<Grey> = frames.iter().map(Grey::of).collect();
    progress(0.1);
    // HDR lines up on the middle exposure, focus on the middle frame; brightness is counted
    // from the darkest frame.
    let mean = |g: &Grey| g.v.iter().map(|&v| v as f64).sum::<f64>() / g.v.len() as f64;
    let mut order: Vec<usize> = (0..frames.len()).collect();
    if kind == Kind::Hdr {
        order.sort_by(|&a, &b| mean(&greys[a]).total_cmp(&mean(&greys[b])));
    }
    let base = order[order.len() / 2];
    let darkest = order[0];
    let mut warps = vec![Warp::NONE; frames.len()];
    let mut gains = vec![1.0f64; frames.len()];
    for (n, &i) in order.iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        if i != base {
            // Rough brightness first (good enough to line up by), then exact once lined up.
            let rough = if kind == Kind::Hdr { gain(&greys[base].half().half(), &greys[i].half().half(), &Warp::NONE) } else { 1.0 };
            warps[i] = align(&greys[base], &greys[i], (1.0 / rough) as f32);
            if kind == Kind::Hdr {
                gains[i] = gain(&greys[base], &greys[i], &warps[i]);
            }
        }
        progress(0.1 + 0.4 * (n + 1) as f32 / frames.len() as f32);
    }
    // Brightness relative to the darkest frame.
    let dark = gains[darkest];
    for g in &mut gains {
        *g /= dark;
    }
    let (gw, gh) = (greys[base].w, greys[base].h);
    let (gcx, gcy) = ((gw as f64 - 1.0) / 2.0, (gh as f64 - 1.0) / 2.0);
    // Each frame's grey seen from the result's cells.
    let seen: Vec<Vec<f32>> = (0..frames.len())
        .map(|k| {
            (0..gw * gh)
                .into_par_iter()
                .map(|i| {
                    let (x, y) = warps[k].apply((i % gw) as f64, (i / gw) as f64, gcx, gcy);
                    greys[k].at(x, y).unwrap_or(f32::NAN)
                })
                .collect()
        })
        .collect();
    // How much each frame counts, cell by cell.
    let shares: Vec<Vec<f32>> = match kind {
        Kind::Hdr => (0..frames.len())
            .map(|k| {
                let raw: Vec<f32> = (0..gw * gh)
                    .map(|i| {
                        let (f, b) = (seen[k][i], seen[base][i]);
                        if k == base || !(f > 0.01 && b > 0.01 && usable(f) && usable(b)) {
                            return 1.0;
                        }
                        // Moved: brighter or darker than the base frame says it should be.
                        let d = ((f as f64 / gains[k]) / (b as f64 / gains[base])).ln().abs();
                        (-(d / 0.3).powi(2)).exp() as f32
                    })
                    .collect();
                let mut soft = vec![0.0; raw.len()];
                box_blur(&raw, &mut soft, gw, gh, 2);
                soft
            })
            .collect(),
        Kind::Focus => {
            let r = (gw.max(gh) / 300).max(2);
            let sharp: Vec<Vec<f32>> = seen
                .iter()
                .map(|g| {
                    let t: Vec<f32> = g.iter().map(|&v| if v.is_finite() { v.max(0.0).sqrt() } else { 0.0 }).collect();
                    let lap: Vec<f32> = (0..gw * gh)
                        .map(|i| {
                            let (x, y) = (i % gw, i / gw);
                            if x == 0 || y == 0 || x + 1 == gw || y + 1 == gh || !g[i].is_finite() {
                                return 0.0;
                            }
                            let v = 4.0 * t[i] - t[i - 1] - t[i + 1] - t[i - gw] - t[i + gw];
                            v * v
                        })
                        .collect();
                    let mut soft = vec![0.0; lap.len()];
                    box_blur(&lap, &mut soft, gw, gh, r);
                    soft
                })
                .collect();
            let mut out: Vec<Vec<f32>> = vec![vec![0.0; gw * gh]; frames.len()];
            for i in 0..gw * gh {
                let top = sharp.iter().map(|s| s[i]).fold(0.0, f32::max).max(1e-12);
                let total: f32 = sharp.iter().map(|s| (s[i] / top).powi(4)).sum();
                for k in 0..frames.len() {
                    out[k][i] = (sharp[k][i] / top).powi(4) / total;
                }
            }
            out.into_iter()
                .map(|s| {
                    let mut soft = vec![0.0; s.len()];
                    box_blur(&s, &mut soft, gw, gh, (r / 2).max(1));
                    soft
                })
                .collect()
        }
    };
    if cancel.load(Ordering::Relaxed) {
        return Err("cancelled".into());
    }
    progress(0.6);
    // The warps in photosites: a cell's centre is at 2i + 0.5.
    let (cx, cy) = (2.0 * gcx + 0.5, 2.0 * gcy + 0.5);
    let pwarps: Vec<Warp> = warps.iter().map(|wp| wp.scaled(2.0)).collect();
    let mut data = vec![0u16; w * h];
    data.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let cell_y = (y / 2).min(gh - 1);
        for (x, out) in row.iter_mut().enumerate() {
            let cell = cell_y * gw + (x / 2).min(gw - 1);
            let (mut sum, mut weight) = (0.0f64, 0.0f64);
            let mut fallback = None;
            // Other frames are only seen through interpolation (softer), so the base frame is
            // kept wherever it's well exposed; the others fill in its deep shadows (less noise)
            // and its clipped highlights.
            let need = match sample(&frames[base], x, y, x as f64, y as f64) {
                Some((v, top)) if kind == Kind::Hdr => (1.0 - unclipped(top)).max(((0.06 - v) / 0.05).clamp(0.0, 1.0)) as f64,
                _ => 1.0,
            };
            for (k, f) in frames.iter().enumerate() {
                let (fx, fy) = pwarps[k].apply(x as f64, y as f64, cx, cy);
                let Some((v, top)) = sample(f, x, y, fx, fy) else { continue };
                let share = shares[k][cell] as f64;
                let wk = match kind {
                    Kind::Hdr => {
                        if k == darkest {
                            fallback = Some(v as f64);
                        }
                        gains[k] * unclipped(top) as f64 * share * if k == base { 1.0 } else { need }
                    }
                    Kind::Focus => share,
                };
                sum += wk * v as f64 / gains[k];
                weight += wk;
            }
            let v = if weight > 1e-9 { sum / weight } else { fallback.unwrap_or(0.0) };
            *out = (v.clamp(0.0, 1.0) * 65535.0 + 0.5) as u16;
        }
    });
    progress(1.0);
    let b = &frames[base];
    let cfa = std::array::from_fn(|i| b.color(b.top + i / 2, b.left + i % 2));
    let mut info = b.info.clone();
    (info.black, info.white, info.preview) = ([0; 3], 65535, None);
    let exposure = if kind == Kind::Hdr { gains[base].log2() as f32 } else { 0.0 };
    Ok(Merged { raw: RawImage { info, raw_width: w, data, cfa, left: 0, top: 0, width: w, height: h }, exposure, base })
}

/// A small picture of `raw` for its preview, `exposure` stops brighter, with the highlights
/// rolled off: width, height and sRGB bytes, as the sensor sees it (not turned upright).
pub fn preview_rgb(raw: &RawImage, exposure: f32, edge: usize) -> (usize, usize, Vec<u8>) {
    let img = raw.half_size().downscaled(edge);
    let m = raw.info.color().rgb_from_cam;
    let k = 2f64.powf(exposure as f64);
    let rgb = (0..img.width * img.height)
        .flat_map(|i| {
            let c = [img.r[i] as f64, img.g[i] as f64, img.b[i] as f64];
            (0..3).map(move |r| {
                let v = (m[r * 3] * c[0] + m[r * 3 + 1] * c[1] + m[r * 3 + 2] * c[2]).max(0.0) * k;
                // Soft above 0.8 so merged highlights don't just clip.
                let v = if v < 0.8 { v } else { 0.8 + 0.2 * (1.0 - (-(v - 0.8) / 0.2).exp()) };
                (encode_srgb(v) * 255.0 + 0.5).clamp(0.0, 255.0) as u8
            })
        })
        .collect();
    (img.width, img.height, rgb)
}

/// The file for a merged raw: a TIFF like Sony's (the sensor's photosites as 16-bit values),
/// its levels and white balance in the DNG tags, `exif_from`'s (a frame's file) shooting details
/// and `preview` as the embedded JPEG.
pub fn write_raw(raw: &RawImage, exif_from: &[u8], preview: Option<&[u8]>) -> Result<Vec<u8>, String> {
    use exif::{Context, Field, In, Rational, Tag, Value};
    let pixels: Vec<u8> = raw.data.iter().flat_map(|v| v.to_le_bytes()).collect();
    let (w, h) = (raw.width as u32, raw.height as u32);
    let tag = |n| Tag(Context::Tiff, n);
    let field = |t: Tag, value: Value| Field { tag: t, ifd_num: In::PRIMARY, value };
    let ascii = |s: &str| Value::Ascii(vec![s.as_bytes().to_vec()]);
    let neutral = raw.info.as_shot.map(|m| Rational { num: 1_000_000, denom: (m * 1_000_000.0).round().max(1.0) as u32 });
    let mut fields = vec![
        field(tag(0xfe), Value::Long(vec![0])),
        field(Tag::ImageWidth, Value::Long(vec![w])),
        field(Tag::ImageLength, Value::Long(vec![h])),
        field(Tag::BitsPerSample, Value::Short(vec![16])),
        field(Tag::Compression, Value::Short(vec![1])),
        field(Tag::PhotometricInterpretation, Value::Short(vec![32803])),
        field(Tag::Make, ascii(&raw.info.make)),
        field(Tag::Model, ascii(&raw.info.model)),
        field(Tag::Orientation, Value::Short(vec![raw.info.orientation as u16])),
        field(Tag::SamplesPerPixel, Value::Short(vec![1])),
        field(Tag::RowsPerStrip, Value::Long(vec![h])),
        field(Tag::PlanarConfiguration, Value::Short(vec![1])),
        field(Tag::Software, ascii("Shootback")),
        field(tag(0x828d), Value::Short(vec![2, 2])),
        field(tag(0x828e), Value::Byte(raw.cfa.iter().map(|&c| c as u8).collect())),
        field(tag(0xc61a), Value::Long(vec![raw.info.black[1].max(0) as u32])),
        field(tag(0xc61d), Value::Long(vec![raw.info.white as u32])),
        field(tag(0xc628), Value::Rational(neutral.to_vec())),
    ];
    if let Some(l) = &raw.info.lens {
        let list = |head: usize, parts: &[&Vec<i32>]| {
            Value::SShort(std::iter::once(head as i16).chain(parts.iter().flat_map(|p| p.iter().map(|&v| v as i16))).collect())
        };
        let n = l.distortion.len();
        fields.push(field(tag(0x7032), list(n, &[&l.vignetting])));
        fields.push(field(tag(0x7035), list(2 * n, &[&l.ca_red, &l.ca_blue])));
        fields.push(field(tag(0x7037), list(n, &[&l.distortion])));
    }
    // The frame's shooting details, except Sony's private notes (they point into its own file).
    if let Ok(e) = exif::Reader::new().read_raw(exif_from.to_vec()) {
        for f in e.fields() {
            if f.ifd_num == In::PRIMARY && f.tag.0 == Context::Exif && f.tag != Tag::MakerNote {
                fields.push(f.clone());
            }
        }
    }
    let strips: [&[u8]; 1] = [&pixels];
    let mut writer = exif::experimental::Writer::new();
    for f in &fields {
        writer.push_field(f);
    }
    writer.set_strips(&strips, In::PRIMARY);
    if let Some(j) = preview {
        writer.set_jpeg(j, In::THUMBNAIL);
    }
    let mut out = std::io::Cursor::new(Vec::new());
    writer.write(&mut out, true).map_err(|e| e.to_string())?;
    Ok(out.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::develop::raw::RawInfo;

    /// A made-up scene: soft blobs and fine lines, so there is detail everywhere.
    fn scene(x: f64, y: f64) -> [f64; 3] {
        let base = 0.5 + 0.35 * ((x / 37.0).sin() * (y / 23.0).cos());
        let lines = 0.15 * ((x + 2.0 * y) / 3.1).sin();
        let v = (base + lines).max(0.0);
        [v * 0.9, v, v * 0.7]
    }

    /// The scene through the sensor, `light` times as bright, seen through `warp` (the frame's
    /// point for each of the scene's), and out of focus in the left half (`Some(true)`) or the
    /// right.
    fn frame(w: usize, h: usize, light: f64, warp: Warp, blur: Option<bool>) -> RawImage {
        let (black, white) = (512i32, 16383i32);
        let cfa = [0, 1, 1, 2];
        let (cx, cy) = ((w as f64 - 1.0) / 2.0, (h as f64 - 1.0) / 2.0);
        // The inverse of the warp: from the frame's point to the scene's.
        let det = warp.a * warp.a + warp.b * warp.b;
        let data = (0..w * h)
            .map(|i| {
                let (x, y) = ((i % w) as f64, (i / w) as f64);
                let (dx, dy) = (x - cx - warp.tx, y - cy - warp.ty);
                let (sx, sy) = ((warp.a * dx + warp.b * dy) / det + cx, (-warp.b * dx + warp.a * dy) / det + cy);
                let c = cfa[((i / w) & 1) << 1 | (i % w) & 1];
                let v = if blur.is_some_and(|left| left == (sx < w as f64 / 2.0)) {
                    let mut s = 0.0;
                    for k in 0..25 {
                        s += scene(sx + (k % 5) as f64 * 1.5 - 3.0, sy + (k / 5) as f64 * 1.5 - 3.0)[c];
                    }
                    s / 25.0
                } else {
                    scene(sx, sy)[c]
                };
                ((v * light).min(1.0) * (white - black) as f64 + black as f64).round() as u16
            })
            .collect();
        RawImage { info: RawInfo::plain(black, white, [1.0; 3]), raw_width: w, data, cfa, left: 0, top: 0, width: w, height: h }
    }

    #[test]
    fn the_shift_turn_and_scale_are_found() {
        let truth = Warp { a: 1.01 * 0.004f64.cos(), b: 1.01 * 0.004f64.sin(), tx: 6.5, ty: -3.25 };
        let a = Grey::of(&frame(640, 480, 1.0, Warp::NONE, None));
        let b = Grey::of(&frame(640, 480, 1.0, truth, None));
        // In greys (half the photosites) the shift is half.
        let found = align(&a, &b, 1.0);
        assert!((found.tx - truth.tx / 2.0).abs() < 0.1 && (found.ty - truth.ty / 2.0).abs() < 0.1, "{found:?}");
        assert!((found.a - truth.a).abs() < 0.002 && (found.b - truth.b).abs() < 0.002, "{found:?}");
    }

    #[test]
    fn hdr_keeps_highlights_and_lines_up() {
        let (w, h) = (480, 320);
        let moved = Warp { tx: 4.0, ty: 2.0, ..Warp::NONE };
        // Lined up on the middle exposure; the others moved a little.
        let frames = vec![frame(w, h, 0.5, moved, None), frame(w, h, 2.0, Warp::NONE, None), frame(w, h, 8.0, Warp { tx: -3.0, ..moved }, None)];
        let m = merge(Kind::Hdr, &frames, &|_| {}, &AtomicBool::new(false)).unwrap();
        assert!((m.exposure - 2.0).abs() < 0.05, "exposure {}", m.exposure);
        // In the darkest frame's terms: the scene at half its brightness, nothing clipped.
        let (mut err, mut n) = (0.0, 0);
        for y in 20..h - 20 {
            for x in 20..w - 20 {
                let c = frames[0].color(y, x);
                let want = scene(x as f64, y as f64)[c] * 0.5;
                err += (m.raw.level(y, x) as f64 - want).abs();
                n += 1;
            }
        }
        assert!(err / (n as f64) < 0.004, "error {}", err / n as f64);
    }

    #[test]
    fn focus_takes_the_sharp_parts() {
        let (w, h) = (480, 320);
        let sharp_right = frame(w, h, 1.0, Warp::NONE, Some(true));
        let sharp_left = frame(w, h, 1.0, Warp::NONE, Some(false));
        let frames = vec![sharp_right, sharp_left];
        let m = merge(Kind::Focus, &frames, &|_| {}, &AtomicBool::new(false)).unwrap();
        let near = |a: usize, b: usize| (m.raw.level(160, a) - frames[b].level(160, a)).abs() < 0.02;
        assert!((30..200).filter(|&x| near(x, 1)).count() > 150, "left from the frame sharp on the left");
        assert!((280..450).filter(|&x| near(x, 0)).count() > 150, "right from the frame sharp on the right");
    }

    #[test]
    fn merged_raws_open_like_any_other() {
        let mut r = frame(64, 48, 1.0, Warp::NONE, None);
        (r.info.model, r.info.black, r.info.white, r.info.as_shot) = ("ILCE-7M3".into(), [0; 3], 65535, [2.25, 1.0, 1.5]);
        let file = write_raw(&r, &[], Some(b"\xff\xd8\xff\xd9")).unwrap();
        let info = crate::develop::raw::read_info(&file).unwrap();
        assert_eq!((info.black, info.white, info.model.as_str()), ([0; 3], 65535, "ILCE-7M3"));
        assert!((info.as_shot[0] - 2.25).abs() < 1e-3 && (info.as_shot[2] - 1.5).abs() < 1e-3, "{:?}", info.as_shot);
        assert!(info.preview.is_some());
        let back = crate::develop::raw::decode(&file, info).unwrap();
        assert_eq!(back.data, r.data);
        assert_eq!((back.width, back.height, back.cfa), (64, 48, r.cfa));
    }
}
