//! The Remove tool: what's painted out is filled in by LaMa from its surroundings. Only a square
//! around the stroke is looked at (at 512x512), so a dust spot costs the same on any photo, and the
//! fill is blended in with a soft edge.

use super::catalog::INPAINT;
use super::tensor::{self, resize_map, Input};
use crate::develop::params::Removal;

const SIZE: usize = 512;

/// The fill for one removal: a rectangle of the photo (left, top, width, height in pixels), its
/// new colours (linear RGB, row by row) and how much of them to take (0..1).
#[derive(Clone, Debug)]
pub struct Patch {
    pub x: usize,
    pub y: usize,
    pub w: usize,
    pub h: usize,
    pub rgb: Vec<[f32; 3]>,
    pub alpha: Vec<f32>,
}

impl Patch {
    /// The pixels the fill changes: where, the new colour and how much of it to take.
    pub fn pixels(&self) -> impl Iterator<Item = (usize, usize, [f32; 3], f32)> + '_ {
        (0..self.w * self.h).filter(|&k| self.alpha[k] > 0.0).map(|k| (self.x + k % self.w, self.y + k / self.w, self.rgb[k], self.alpha[k]))
    }
}

/// Where a removal lies in a picture `w` by `h` that is the upright photo turned back by EXIF
/// `orientation` (1 for the upright picture itself): its points in pixels and its radius.
pub fn place(r: &Removal, w: usize, h: usize, orientation: u8) -> (Vec<[f32; 2]>, f32) {
    let pts = r
        .points
        .iter()
        .map(|&[u, v]| {
            let [x, y] = match orientation {
                2 => [1.0 - u, v],
                3 => [1.0 - u, 1.0 - v],
                4 => [u, 1.0 - v],
                5 => [v, u],
                6 => [v, 1.0 - u],
                7 => [1.0 - v, 1.0 - u],
                8 => [1.0 - v, u],
                _ => [u, v],
            };
            [x * w as f32, y * h as f32]
        })
        .collect();
    (pts, r.radius * w.max(h) as f32)
}

/// How far a pixel is from the stroke through `pts`.
fn distance(p: [f32; 2], pts: &[[f32; 2]]) -> f32 {
    if pts.len() == 1 {
        return ((p[0] - pts[0][0]).powi(2) + (p[1] - pts[0][1]).powi(2)).sqrt();
    }
    pts.windows(2)
        .map(|s| {
            let (a, b) = (s[0], s[1]);
            let (dx, dy) = (b[0] - a[0], b[1] - a[1]);
            let len = dx * dx + dy * dy;
            let t = if len > 0.0 { (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len).clamp(0.0, 1.0) } else { 0.0 };
            ((p[0] - a[0] - t * dx).powi(2) + (p[1] - a[1] - t * dy).powi(2)).sqrt()
        })
        .fold(f32::MAX, f32::min)
}

/// The fill for a stroke through `pts` (pixels) `radius` wide, in a picture `w` by `h` read by
/// `get` (linear RGB).
pub fn fill(get: impl Fn(usize, usize) -> [f32; 3], w: usize, h: usize, pts: &[[f32; 2]], radius: f32) -> Result<Patch, String> {
    let session = super::session(&INPAINT, "lama.onnx")?;
    // A square around the stroke with room to see what's around it.
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for p in pts {
        (x0, y0, x1, y1) = (x0.min(p[0]), y0.min(p[1]), x1.max(p[0]), y1.max(p[1]));
    }
    let side = ((x1 - x0).max(y1 - y0) + 2.0 * radius).max(radius * 8.0).max(96.0) * 1.6;
    let side = side.min(w.min(h) as f32);
    let (cx, cy) = ((x0 + x1) / 2.0, (y0 + y1) / 2.0);
    let left = (cx - side / 2.0).clamp(0.0, w as f32 - side) as usize;
    let top = (cy - side / 2.0).clamp(0.0, h as f32 - side) as usize;
    let s = side as usize;
    let (cw, ch) = (s.min(w - left), s.min(h - top));

    // The crop, brought to display brightness for the model: scaled so its bright end is near
    // white, then gamma.
    let mut crop = vec![[0f32; 3]; cw * ch];
    for j in 0..ch {
        for i in 0..cw {
            crop[j * cw + i] = get(left + i, top + j);
        }
    }
    let mut lum: Vec<f32> = crop.iter().map(|c| c[0].max(c[1]).max(c[2])).collect();
    lum.sort_by(|a, b| a.total_cmp(b));
    let gain = 1.0 / lum[lum.len() * 99 / 100].max(0.02);
    let mut planes = vec![vec![0f32; cw * ch]; 3];
    for (k, c) in crop.iter().enumerate() {
        for ch_ in 0..3 {
            planes[ch_][k] = (c[ch_] * gain).clamp(0.0, 1.0).powf(1.0 / 2.2);
        }
    }
    // What's painted out (a little wider, so its own edge goes too), and the soft edge to blend by.
    let mut hard = vec![0f32; cw * ch];
    let mut alpha = vec![0f32; cw * ch];
    for j in 0..ch {
        for i in 0..cw {
            let d = distance([(left + i) as f32 + 0.5, (top + j) as f32 + 0.5], pts);
            let k = j * cw + i;
            hard[k] = if d <= radius * 1.25 + 1.0 { 1.0 } else { 0.0 };
            alpha[k] = (1.0 - (d - radius) / (radius * 0.25 + 1.0)).clamp(0.0, 1.0);
        }
    }
    let mut image = Vec::with_capacity(3 * SIZE * SIZE);
    for p in &planes {
        image.extend(resize_map(p, cw, ch, SIZE, SIZE));
    }
    let mask: Vec<f32> = resize_map(&hard, cw, ch, SIZE, SIZE).into_iter().map(|v| if v > 0.01 { 1.0 } else { 0.0 }).collect();
    let shape = |c: i64| vec![1, c, SIZE as i64, SIZE as i64];
    let mut out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("image", shape(3), image), Input::F32("mask", shape(1), mask)])?;
    let (_, got) = out.pop().ok_or("the remove model gave nothing back")?;
    if got.len() != 3 * SIZE * SIZE {
        return Err("the remove model gave back the wrong size".into());
    }
    let back: Vec<Vec<f32>> = (0..3).map(|c| resize_map(&got[c * SIZE * SIZE..(c + 1) * SIZE * SIZE], SIZE, SIZE, cw, ch)).collect();
    let rgb = (0..cw * ch).map(|k| std::array::from_fn(|c| (back[c][k] / 255.0).clamp(0.0, 1.0).powf(2.2) / gain)).collect();
    Ok(Patch { x: left, y: top, w: cw, h: ch, rgb, alpha })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removals_turn_with_the_photo() {
        let r = Removal { radius: 0.01, points: vec![[0.25, 0.1]] };
        // Upright 400x300 shown from a sensor picture 300x400 turned clockwise (orientation 6).
        let (p, rad) = place(&r, 300, 400, 6);
        assert_eq!(p[0], [0.1 * 300.0, 0.75 * 400.0]);
        assert_eq!(rad, 4.0);
        assert_eq!(place(&r, 400, 300, 1).0[0], [100.0, 30.0]);
    }

    #[test]
    fn distance_is_to_the_nearest_part_of_the_stroke() {
        let pts = [[0.0, 0.0], [10.0, 0.0]];
        assert_eq!(distance([5.0, 3.0], &pts), 3.0);
        assert_eq!(distance([13.0, 4.0], &pts), 5.0);
    }
}
