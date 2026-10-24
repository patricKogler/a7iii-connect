//! Scene-linear pixels and the filters every local adjustment is built from (the phone's
//! Image.kt and Filters.kt).

use rayon::prelude::*;

/// Scene-linear RGB, white balanced to the camera's as-shot setting (or sRGB for JPEGs) and
/// normalised so 1.0 is the sensor's clipping point.
#[derive(Clone, Debug)]
pub struct LinearImage {
    pub width: usize,
    pub height: usize,
    pub r: Vec<f32>,
    pub g: Vec<f32>,
    pub b: Vec<f32>,
}

/// sRGB 8-bit to linear.
pub fn srgb_to_linear_table() -> [f32; 256] {
    std::array::from_fn(|i| {
        let v = i as f64 / 255.0;
        (if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }) as f32
    })
}

impl LinearImage {
    pub fn new(width: usize, height: usize) -> LinearImage {
        let n = width * height;
        LinearImage { width, height, r: vec![0.0; n], g: vec![0.0; n], b: vec![0.0; n] }
    }

    /// From 8-bit sRGB, 3 bytes a pixel (a decoded JPEG).
    pub fn from_srgb8(rgb: &[u8], width: usize, height: usize) -> LinearImage {
        let t = srgb_to_linear_table();
        let mut img = LinearImage::new(width, height);
        img.r.par_iter_mut().zip(img.g.par_iter_mut()).zip(img.b.par_iter_mut()).enumerate().for_each(|(i, ((r, g), b))| {
            *r = t[rgb[i * 3] as usize];
            *g = t[rgb[i * 3 + 1] as usize];
            *b = t[rgb[i * 3 + 2] as usize];
        });
        img
    }

    /// Area-averaged copy whose longer edge is at most `max_edge`.
    pub fn downscaled(&self, max_edge: usize) -> LinearImage {
        let scale = max_edge as f64 / self.width.max(self.height) as f64;
        if scale >= 1.0 {
            return self.clone();
        }
        let w = ((self.width as f64 * scale) as usize).max(1);
        let h = ((self.height as f64 * scale) as usize).max(1);
        let mid = resample_rows(self, w);
        resample_cols(&mid, h)
    }

    /// Copy turned upright for an EXIF orientation (1-8).
    pub fn oriented(self, orientation: u8) -> LinearImage {
        if orientation <= 1 || orientation > 8 {
            return self;
        }
        let (w, h) = (self.width, self.height);
        let swap = orientation >= 5;
        let (ow, oh) = if swap { (h, w) } else { (w, h) };
        let mut out = LinearImage::new(ow, oh);
        let src = |x: usize, y: usize| -> usize {
            let (sx, sy) = match orientation {
                2 => (w - 1 - x, y),
                3 => (w - 1 - x, h - 1 - y),
                4 => (x, h - 1 - y),
                5 => (y, x),
                6 => (y, h - 1 - x),
                7 => (w - 1 - y, h - 1 - x),
                _ => (w - 1 - y, x),
            };
            sy * w + sx
        };
        for (plane, from) in [(&mut out.r, &self.r), (&mut out.g, &self.g), (&mut out.b, &self.b)] {
            plane.par_chunks_mut(ow).enumerate().for_each(|(y, row)| {
                for (x, v) in row.iter_mut().enumerate() {
                    *v = from[src(x, y)];
                }
            });
        }
        out
    }
}

fn weights(src: usize, dst: usize) -> Vec<(usize, Vec<f32>)> {
    let scale = src as f64 / dst as f64;
    (0..dst)
        .map(|i| {
            let a = i as f64 * scale;
            let b = (i + 1) as f64 * scale;
            let first = a.floor() as usize;
            let last = (src - 1).min(b.ceil() as usize - 1);
            let ws = (first..=last).map(|k| ((b.min((k + 1) as f64) - a.max(k as f64)) / scale) as f32).collect();
            (first, ws)
        })
        .collect()
}

fn resample_rows(src: &LinearImage, w: usize) -> LinearImage {
    let ws = weights(src.width, w);
    let mut dst = LinearImage::new(w, src.height);
    for (plane, from) in [(&mut dst.r, &src.r), (&mut dst.g, &src.g), (&mut dst.b, &src.b)] {
        plane.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
            let so = y * src.width;
            for (x, v) in row.iter_mut().enumerate() {
                let (first, wk) = &ws[x];
                *v = wk.iter().enumerate().map(|(k, w)| from[so + first + k] * w).sum();
            }
        });
    }
    dst
}

fn resample_cols(src: &LinearImage, h: usize) -> LinearImage {
    let ws = weights(src.height, h);
    let w = src.width;
    let mut dst = LinearImage::new(w, h);
    for (plane, from) in [(&mut dst.r, &src.r), (&mut dst.g, &src.g), (&mut dst.b, &src.b)] {
        plane.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
            let (first, wk) = &ws[y];
            for (x, v) in row.iter_mut().enumerate() {
                *v = wk.iter().enumerate().map(|(k, wt)| from[(first + k) * w + x] * wt).sum();
            }
        });
    }
    dst
}

/// Box blur of radius r (window 2r+1) with clamped edges.
pub fn box_blur(src: &[f32], dst: &mut [f32], w: usize, h: usize, r: usize) {
    if r == 0 {
        dst.copy_from_slice(src);
        return;
    }
    let inv = 1.0 / (2 * r + 1) as f32;
    let mut tmp = vec![0.0f32; w * h];
    tmp.par_chunks_mut(w).enumerate().for_each(|(y, out)| {
        let row = &src[y * w..(y + 1) * w];
        let at = |k: isize| row[k.clamp(0, w as isize - 1) as usize];
        let mut sum: f32 = (-(r as isize)..=r as isize).map(at).sum();
        for (x, o) in out.iter_mut().enumerate() {
            *o = sum * inv;
            sum += at(x as isize + r as isize + 1) - at(x as isize - r as isize);
        }
    });
    // Columns in blocks so each thread walks memory row by row.
    const BLOCK: usize = 64;
    let blocks: Vec<(usize, Vec<f32>)> = (0..w.div_ceil(BLOCK))
        .into_par_iter()
        .map(|b| {
            let x0 = b * BLOCK;
            let n = BLOCK.min(w - x0);
            let mut out = vec![0.0f32; n * h];
            let mut sums = vec![0.0f32; n];
            let row = |k: isize| k.clamp(0, h as isize - 1) as usize * w;
            for k in -(r as isize)..=r as isize {
                let o = row(k);
                for i in 0..n {
                    sums[i] += tmp[o + x0 + i];
                }
            }
            for y in 0..h {
                let add = row(y as isize + r as isize + 1);
                let sub = row(y as isize - r as isize);
                for i in 0..n {
                    out[y * n + i] = sums[i] * inv;
                    sums[i] += tmp[add + x0 + i] - tmp[sub + x0 + i];
                }
            }
            (x0, out)
        })
        .collect();
    for (x0, out) in blocks {
        let n = out.len() / h;
        for y in 0..h {
            dst[y * w + x0..y * w + x0 + n].copy_from_slice(&out[y * n..(y + 1) * n]);
        }
    }
}

fn box_in_place(v: &mut [f32], w: usize, h: usize, r: usize) {
    let src = v.to_vec();
    box_blur(&src, v, w, h, r);
}

/// Three box passes: close to a Gaussian with sigma about r.
pub fn blur(v: &mut [f32], w: usize, h: usize, r: f32) {
    let b = (r * 0.95).max(0.0) as usize;
    for _ in 0..3 {
        if b > 0 {
            box_in_place(v, w, h, b);
        }
    }
}

/// Self-guided filter (He et al.): smooths `src` while keeping edges whose local variance is well
/// above `eps`.
pub fn guided(src: &[f32], w: usize, h: usize, r: usize, eps: f32) -> Vec<f32> {
    let n = w * h;
    let mut mean = vec![0.0; n];
    box_blur(src, &mut mean, w, h, r);
    let sq_in: Vec<f32> = src.iter().map(|v| v * v).collect();
    let mut sq = vec![0.0; n];
    box_blur(&sq_in, &mut sq, w, h, r);
    for i in 0..n {
        let m = mean[i];
        let v = sq[i] - m * m;
        let a = v / (v + eps);
        sq[i] = a;
        mean[i] = m - a * m;
    }
    box_in_place(&mut sq, w, h, r);
    box_in_place(&mut mean, w, h, r);
    (0..n).map(|i| sq[i] * src[i] + mean[i]).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn box_blur_keeps_a_flat_field() {
        let src = vec![0.5f32; 70 * 9];
        let mut dst = vec![0.0; src.len()];
        box_blur(&src, &mut dst, 70, 9, 3);
        assert!(dst.iter().all(|v| (v - 0.5).abs() < 1e-6));
    }

    #[test]
    fn downscale_averages() {
        let mut img = LinearImage::new(4, 2);
        img.r = vec![0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0];
        let d = img.downscaled(2);
        assert_eq!((d.width, d.height), (2, 1));
        assert!(d.r.iter().all(|v| (v - 0.5).abs() < 1e-6));
    }

    #[test]
    fn orientation_6_turns_clockwise() {
        let mut img = LinearImage::new(3, 2);
        img.r = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        let o = img.oriented(6);
        assert_eq!((o.width, o.height), (2, 3));
        assert_eq!(o.r, vec![4.0, 1.0, 5.0, 2.0, 6.0, 3.0]);
    }
}
