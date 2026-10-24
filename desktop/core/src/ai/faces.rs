//! Faces: YuNet finds them (with eyes, nose and mouth corners) and SFace tells them apart, the
//! same SFace the phone uses, lined up the same way, so both recognise the same people.

use super::catalog::FACES;
use super::tensor::{self, Input, Rgb};

/// YuNet's input side; pictures are fitted inside, top left.
const SIZE: usize = 640;
const STRIDES: [usize; 3] = [8, 16, 32];
/// A face this likely or more counts.
const SCORE: f32 = 0.7;
/// Boxes overlapping more than this are the same face.
const NMS: f32 = 0.3;

/// SFace's crop side, and where the eyes, nose tip and mouth corners go in it (left to right on
/// the picture): the phone's FaceAlign.
pub const SIDE: usize = 112;
const TEMPLATE: [f32; 10] = [38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041];

/// A face found in a picture, in its pixels.
#[derive(Clone, Debug)]
pub struct Face {
    /// Left, top, right, bottom.
    pub b: [f32; 4],
    pub score: f32,
    /// Both eyes, the nose tip, both mouth corners (x, y each).
    pub points: [[f32; 2]; 5],
}

impl Face {
    pub fn width(&self) -> f32 {
        self.b[2] - self.b[0]
    }

    /// How far the face is turned sideways, 0 facing the camera to 1 in profile: how far off
    /// the nose is from between the eyes, against how far apart they are.
    pub fn turned(&self) -> f32 {
        let [l, r] = if self.points[0][0] <= self.points[1][0] { [self.points[0], self.points[1]] } else { [self.points[1], self.points[0]] };
        let span = (r[0] - l[0]).max(1.0);
        ((self.points[2][0] - (l[0] + r[0]) / 2.0).abs() / (span / 2.0)).min(1.0)
    }
}

/// The faces in `px` (sRGB 0..1), most likely first.
pub fn find(px: &Rgb) -> Result<Vec<Face>, String> {
    let session = super::session(&FACES, "yunet.onnx")?;
    let k = SIZE as f32 / px.width.max(px.height) as f32;
    let (w, h) = (((px.width as f32 * k) as usize).clamp(1, SIZE), ((px.height as f32 * k) as usize).clamp(1, SIZE));
    let small = px.resized(w, h);
    // Blue, green, red planes, 0..255, as OpenCV feeds it; the rest stays black.
    let n = SIZE * SIZE;
    let mut input = vec![0.0f32; 3 * n];
    for y in 0..h {
        for x in 0..w {
            let [r, g, b] = small.at(x, y);
            let i = y * SIZE + x;
            input[i] = b * 255.0;
            input[n + i] = g * 255.0;
            input[2 * n + i] = r * 255.0;
        }
    }
    let out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("input", vec![1, 3, SIZE as i64, SIZE as i64], input)])?;
    if out.len() < 12 {
        return Err("the face model gave too little back".into());
    }
    let mut faces = Vec::new();
    for (s, &stride) in STRIDES.iter().enumerate() {
        let (cls, obj, bbox, kps) = (&out[s].1, &out[3 + s].1, &out[6 + s].1, &out[9 + s].1);
        let cols = SIZE / stride;
        for (idx, (&c, &o)) in cls.iter().zip(obj).enumerate() {
            let score = (c.clamp(0.0, 1.0) * o.clamp(0.0, 1.0)).sqrt();
            if score < SCORE {
                continue;
            }
            let (col, row) = ((idx % cols) as f32, (idx / cols) as f32);
            let st = stride as f32;
            let bb = &bbox[idx * 4..idx * 4 + 4];
            let (cx, cy) = ((col + bb[0]) * st, (row + bb[1]) * st);
            let (bw, bh) = (bb[2].exp() * st, bb[3].exp() * st);
            let kp = &kps[idx * 10..idx * 10 + 10];
            let points = std::array::from_fn(|p| [(kp[2 * p] + col) * st / k, (kp[2 * p + 1] + row) * st / k]);
            faces.push(Face { b: [(cx - bw / 2.0) / k, (cy - bh / 2.0) / k, (cx + bw / 2.0) / k, (cy + bh / 2.0) / k], score, points });
        }
    }
    faces.sort_by(|a, b| b.score.total_cmp(&a.score));
    let mut kept: Vec<Face> = Vec::new();
    for f in faces {
        if kept.iter().all(|k| iou(&k.b, &f.b) <= NMS) {
            kept.push(f);
        }
    }
    Ok(kept)
}

fn iou(a: &[f32; 4], b: &[f32; 4]) -> f32 {
    let w = (a[2].min(b[2]) - a[0].max(b[0])).max(0.0);
    let h = (a[3].min(b[3]) - a[1].max(b[1])).max(0.0);
    let inter = w * h;
    let union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
    if union > 0.0 {
        inter / union
    } else {
        0.0
    }
}

/// Who `face` (found in `px`) is, as a unit vector of 128: two pictures of the same person point
/// the same way.
pub fn embed(px: &Rgb, face: &Face) -> Result<Vec<f32>, String> {
    let session = super::session(&FACES, "sface.onnx")?;
    // The template goes left to right on the picture.
    let p = &face.points;
    let mut eyes = [p[0], p[1]];
    eyes.sort_by(|a, b| a[0].total_cmp(&b[0]));
    let mut mouth = [p[3], p[4]];
    mouth.sort_by(|a, b| a[0].total_cmp(&b[0]));
    let src = [eyes[0][0], eyes[0][1], eyes[1][0], eyes[1][1], p[2][0], p[2][1], mouth[0][0], mouth[0][1], mouth[1][0], mouth[1][1]];
    let m = similarity(&src, &TEMPLATE);
    // The crop's pixel (x, y) comes from the inverse of m.
    let det = m[0] * m[4] - m[1] * m[3];
    let inv = [m[4] / det, -m[1] / det, -m[3] / det, m[0] / det];
    let n = SIDE * SIDE;
    let mut input = vec![0.0f32; 3 * n];
    for y in 0..SIDE {
        for x in 0..SIDE {
            let (dx, dy) = (x as f32 + 0.5 - m[2], y as f32 + 0.5 - m[5]);
            let (sx, sy) = (inv[0] * dx + inv[1] * dy - 0.5, inv[2] * dx + inv[3] * dy - 0.5);
            let c = bilinear(px, sx, sy);
            for ch in 0..3 {
                input[ch * n + y * SIDE + x] = c[ch] * 255.0;
            }
        }
    }
    let mut out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("data", vec![1, 3, SIDE as i64, SIDE as i64], input)])?;
    let (_, mut v) = out.pop().ok_or("the face model gave nothing back")?;
    normalize(&mut v);
    Ok(v)
}

pub fn normalize(v: &mut [f32]) {
    let n = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if n > 0.0 {
        v.iter_mut().for_each(|x| *x /= n);
    }
}

/// The picture at a point, bilinear; black outside.
fn bilinear(px: &Rgb, x: f32, y: f32) -> [f32; 3] {
    let (x0, y0) = (x.floor(), y.floor());
    let (fx, fy) = (x - x0, y - y0);
    let mut out = [0.0; 3];
    for (dy, wy) in [(0, 1.0 - fy), (1, fy)] {
        for (dx, wx) in [(0, 1.0 - fx), (1, fx)] {
            let (xi, yi) = (x0 as i64 + dx, y0 as i64 + dy);
            if xi < 0 || yi < 0 || xi >= px.width as i64 || yi >= px.height as i64 {
                continue;
            }
            let c = px.at(xi as usize, yi as usize);
            for ch in 0..3 {
                out[ch] += c[ch] * wx * wy;
            }
        }
    }
    out
}

/// The scale, turn and shift (no mirroring) that best moves points `src` onto `dst` (x, y
/// pairs), least squares: the phone's FaceAlign.similarity. A 2x3 matrix row by row.
pub fn similarity(src: &[f32], dst: &[f32]) -> [f32; 6] {
    let n = src.len() / 2;
    let (mut sx, mut sy, mut dx, mut dy) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for i in 0..n {
        sx += src[2 * i] as f64;
        sy += src[2 * i + 1] as f64;
        dx += dst[2 * i] as f64;
        dy += dst[2 * i + 1] as f64;
    }
    let nf = n as f64;
    (sx, sy, dx, dy) = (sx / nf, sy / nf, dx / nf, dy / nf);
    let (mut num, mut cross, mut den) = (0.0, 0.0, 0.0);
    for i in 0..n {
        let (px, py) = (src[2 * i] as f64 - sx, src[2 * i + 1] as f64 - sy);
        let (qx, qy) = (dst[2 * i] as f64 - dx, dst[2 * i + 1] as f64 - dy);
        num += px * qx + py * qy;
        cross += px * qy - py * qx;
        den += px * px + py * py;
    }
    let (a, b) = (num / den, cross / den);
    [a as f32, -b as f32, (dx - a * sx + b * sy) as f32, b as f32, a as f32, (dy - b * sx - a * sy) as f32]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn similarity_finds_a_known_move() {
        // Twice the size, turned a quarter, shifted.
        let src = [0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.5, 0.5];
        let dst: Vec<f32> = src.chunks(2).flat_map(|p| [10.0 - 2.0 * p[1], 5.0 + 2.0 * p[0]]).collect();
        let m = similarity(&src, &dst);
        for (want, got) in [0.0, -2.0, 10.0, 2.0, 0.0, 5.0].iter().zip(m) {
            assert!((want - got).abs() < 1e-4, "{m:?}");
        }
    }
}
