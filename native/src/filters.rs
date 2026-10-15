//! Box blur, three-pass Gaussian and the self-guided filter, as in Kotlin's `Filters`.

use rayon::prelude::*;

/// Box blur of radius `r` (window 2r+1) with clamped edges, in place.
pub fn box_blur(buf: &mut [f32], w: usize, h: usize, r: usize) {
    if r == 0 || w == 0 || h == 0 {
        return;
    }
    let inv = 1.0 / (2 * r + 1) as f32;
    let ri = r as isize;
    let mut tmp = vec![0f32; w * h];
    tmp.par_chunks_mut(w).zip(buf.par_chunks(w)).for_each(|(t, s)| {
        let mut sum = 0f32;
        for k in -ri..=ri {
            sum += s[k.clamp(0, w as isize - 1) as usize];
        }
        for x in 0..w {
            t[x] = sum * inv;
            sum += s[(x + r + 1).min(w - 1)] - s[(x as isize - ri).max(0) as usize];
        }
    });
    // Columns in blocks of 64, each walking memory row by row.
    let block = 64;
    let blocks = (w + block - 1) / block;
    let cols: Vec<Vec<f32>> = (0..blocks)
        .into_par_iter()
        .map(|bi| {
            let x0 = bi * block;
            let n = (w - x0).min(block);
            let mut out = vec![0f32; n * h];
            let mut sums = vec![0f32; n];
            for k in -ri..=ri {
                let row = k.clamp(0, h as isize - 1) as usize * w;
                for i in 0..n {
                    sums[i] += tmp[row + x0 + i];
                }
            }
            for y in 0..h {
                let add = (y + r + 1).min(h - 1) * w;
                let sub = (y as isize - ri).max(0) as usize * w;
                for i in 0..n {
                    out[y * n + i] = sums[i] * inv;
                    sums[i] += tmp[add + x0 + i] - tmp[sub + x0 + i];
                }
            }
            out
        })
        .collect();
    buf.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (bi, c) in cols.iter().enumerate() {
            let x0 = bi * block;
            let n = (w - x0).min(block);
            row[x0..x0 + n].copy_from_slice(&c[y * n..y * n + n]);
        }
    });
}

/// Three box passes: close to a Gaussian with sigma about `r`.
pub fn blur(buf: &mut [f32], w: usize, h: usize, r: f32) {
    let b = (r * 0.95).max(0.0) as usize;
    if b == 0 {
        return;
    }
    box_blur(buf, w, h, b);
    box_blur(buf, w, h, b);
    box_blur(buf, w, h, b);
}

/// Self-guided filter (He et al.) of `src` into a new buffer.
pub fn guided(src: &[f32], w: usize, h: usize, r: usize, eps: f32) -> Vec<f32> {
    let mut mean = src.to_vec();
    box_blur(&mut mean, w, h, r);
    let mut sq: Vec<f32> = src.par_iter().map(|v| v * v).collect();
    box_blur(&mut sq, w, h, r);
    sq.par_iter_mut().zip(mean.par_iter_mut()).for_each(|(s, m)| {
        let mm = *m;
        let v = *s - mm * mm;
        let a = v / (v + eps);
        *s = a;
        *m = mm - a * mm;
    });
    box_blur(&mut sq, w, h, r);
    box_blur(&mut mean, w, h, r);
    src.par_iter().zip(sq.par_iter().zip(mean.par_iter())).map(|(s, (a, b))| a * s + b).collect()
}
