//! Upscale on export: RealPLKSR doubles or quadruples the developed photo. The model takes fixed
//! tiles (512 pixels for 2x, 256 for 4x), so the photo is cut into overlapping ones; each gives
//! the middle of its result, where it saw enough around it, and neighbours fade into each other
//! where they meet, so there are no seams.

use super::catalog::UPSCALE;
use super::tensor::{self, Input};
use std::sync::atomic::{AtomicBool, Ordering};

/// The most pixels an upscaled photo may have (a 24 MP photo doubled is 96 MP).
pub const MAX_PIXELS: usize = 200_000_000;
/// JPEG's longest side.
const MAX_SIDE: usize = 65_500;

/// Whether a photo `w` by `h` can be upscaled `factor` times.
pub fn fits(w: usize, h: usize, factor: usize) -> bool {
    w * factor * h * factor <= MAX_PIXELS && w.max(h) * factor <= MAX_SIDE
}

/// The model's tile for `factor`: its file and the side it reads.
fn tile(factor: usize) -> Result<(&'static str, usize), String> {
    match factor {
        2 => Ok(("x2.onnx", 512)),
        4 => Ok(("x4.onnx", 256)),
        _ => Err(format!("can't upscale {factor} times")),
    }
}

/// Where the tiles start along a side of `len` pixels, `side` long and overlapping by
/// `overlap`, and the part of the side each one gives (from, to).
fn tiles(len: usize, side: usize, overlap: usize) -> Vec<(usize, usize, usize)> {
    if len <= side {
        return vec![(0, 0, len)];
    }
    let step = side - 2 * overlap;
    let mut starts: Vec<usize> = (0..).map(|k| k * step).take_while(|&s| s + side < len).collect();
    starts.push(len - side);
    starts.dedup();
    let n = starts.len();
    (0..n)
        .map(|i| {
            let from = if i == 0 { 0 } else { (starts[i - 1] + side + starts[i]) / 2 };
            let to = if i + 1 == n { len } else { (starts[i] + side + starts[i + 1]) / 2 };
            (starts[i], from, to)
        })
        .collect()
}

/// How much tile `t` (of `all`, as `tiles` gives them) gives at `s` (in its side's pixels): all of
/// it in its own part, fading out over `fade` pixels either side of where it meets the next.
fn share(all: &[(usize, usize, usize)], t: usize, s: f32, fade: f32) -> f32 {
    let (_, from, to) = all[t];
    let rise = if t == 0 { 1.0 } else { ((s - from as f32 + fade) / (2.0 * fade)).clamp(0.0, 1.0) };
    let fall = if t + 1 == all.len() { 1.0 } else { ((to as f32 + fade - s) / (2.0 * fade)).clamp(0.0, 1.0) };
    rise * fall
}

/// `rgb` (`w` by `h`, 8-bit) made `factor` (2 or 4) times larger. `progress` gets the fraction
/// done; `cancel` stops it early.
pub fn upscale(rgb: &[u8], w: usize, h: usize, factor: usize, progress: &dyn Fn(f32), cancel: &AtomicBool) -> Result<Vec<u8>, String> {
    if !fits(w, h, factor) {
        return Err(format!("{w} x {h} is too large to upscale {factor} times"));
    }
    let (file, side) = tile(factor)?;
    let session = super::session(&UPSCALE, file)?;
    let overlap = side / 8;
    let fade = overlap / 2;
    let (cols, rows) = (tiles(w, side, overlap), tiles(h, side, overlap));
    let (ow, oh, big) = (w * factor, h * factor, side * factor);
    let mut out = vec![0u8; ow * oh * 3];
    // A row of tiles adds into `band`, output rows from `band_top`; the rows no later tile
    // reaches are then written out, and the rest carried on.
    let mut band_top = 0;
    let mut band: Vec<f32> = Vec::new();
    let total = (cols.len() * rows.len()) as f32;
    let mut done = 0.0;
    let mut input = vec![0f32; 3 * side * side];
    let along = |all: &[(usize, usize, usize)], t: usize, len: usize| -> (usize, usize, Vec<f32>) {
        let (start, from, to) = all[t];
        let first = if t == 0 { 0 } else { (from - fade) * factor };
        let last = if t + 1 == all.len() { len * factor } else { (to + fade) * factor };
        let weights = (first..last).map(|o| share(all, t, (o as f32 + 0.5) / factor as f32, fade as f32)).collect();
        (first - start * factor, last - start * factor, weights)
    };
    for (r, &(y0, _, _)) in rows.iter().enumerate() {
        let (ya, yb, wy) = along(&rows, r, h);
        let band_bottom = y0 * factor + yb;
        band.resize((band_bottom - band_top) * ow * 3, 0.0);
        for (c, &(x0, _, _)) in cols.iter().enumerate() {
            if cancel.load(Ordering::Relaxed) {
                return Err("cancelled".into());
            }
            // Past the photo's edges (a photo smaller than a tile) its last pixels repeat.
            for j in 0..side {
                let y = (y0 + j).min(h - 1);
                for i in 0..side {
                    let p = (y * w + (x0 + i).min(w - 1)) * 3;
                    for ch in 0..3 {
                        input[(ch * side + j) * side + i] = rgb[p + ch] as f32 / 255.0;
                    }
                }
            }
            let mut got = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("input", vec![1, 3, side as i64, side as i64], input.clone())])?;
            let (_, px) = got.pop().ok_or("the upscale model gave nothing back")?;
            if px.len() != 3 * big * big {
                return Err("the upscale model gave back the wrong size".into());
            }
            let (xa, xb, wx) = along(&cols, c, w);
            for (j, wj) in (ya..yb).zip(&wy) {
                let row = (y0 * factor + j - band_top) * ow;
                for (i, wi) in (xa..xb).zip(&wx) {
                    let k = wj * wi;
                    let o = (row + x0 * factor + i) * 3;
                    for ch in 0..3 {
                        band[o + ch] += k * px[(ch * big + j) * big + i].clamp(0.0, 1.0);
                    }
                }
            }
            done += 1.0;
            progress(done / total);
        }
        // Rows the next row of tiles doesn't reach are finished.
        let finished = rows.get(r + 1).map_or(oh, |&(_, from, _)| (from - fade) * factor);
        let n = (finished - band_top) * ow * 3;
        for (d, v) in out[band_top * ow * 3..finished * ow * 3].iter_mut().zip(&band[..n]) {
            *d = (v * 255.0 + 0.5).min(255.0) as u8;
        }
        band.drain(..n);
        band_top = finished;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tiles_cover_the_side_once() {
        for len in [100, 512, 513, 1000, 6000] {
            let t = tiles(len, 512, 32);
            assert_eq!(t[0].1, 0);
            assert_eq!(t.last().unwrap().2, len);
            for p in t.windows(2) {
                assert_eq!(p[0].2, p[1].1);
            }
            // Each tile gives only pixels it read, away from its inner edges.
            for (i, &(start, from, to)) in t.iter().enumerate() {
                assert!(from >= start && to <= start + 512.min(len));
                if i > 0 {
                    assert!(from >= start + 32);
                }
                if i + 1 < t.len() {
                    assert!(to + 32 <= start + 512);
                }
            }
        }
    }

    #[test]
    fn neighbours_fade_into_each_other() {
        for len in [700, 1000, 6000] {
            let t = tiles(len, 512, 64);
            for s in 0..len {
                let x = s as f32 + 0.5;
                let sum: f32 = (0..t.len()).map(|i| share(&t, i, x, 32.0)).sum();
                assert!((sum - 1.0).abs() < 1e-4, "{len} at {s}: {sum}");
            }
        }
    }

    #[test]
    fn big_photos_are_refused() {
        assert!(fits(6000, 4000, 2));
        assert!(!fits(6000, 4000, 4));
    }
}
