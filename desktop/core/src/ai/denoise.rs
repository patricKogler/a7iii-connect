//! AI raw denoise: RawNIND's UtNet2 (as darktable packages it) takes the sensor's photosites, four
//! to a cell, and gives back clean RGB at full size, denoising and demosaicing in one go. It
//! looks at 512x512 cells at a time with a margin either side, so tiles meet without seams.

use super::catalog::RAW_DENOISE;
use super::tensor::{self, Input};
use crate::develop::raw::RawImage;
use std::sync::atomic::{AtomicBool, Ordering};

/// Cells (2x2 photosites) the model looks at, a side.
const TILE: usize = 512;
/// Cells either side of a tile that are looked at but not kept.
const OVERLAP: usize = 64;
const STEP: usize = TILE - 2 * OVERLAP;

/// The raw's active area denoised, interleaved RGB16 with the as-shot white balance, as
/// `RawImage::demosaic` gives it. `progress` gets the fraction done; `cancel` stops it.
pub fn raw(raw: &RawImage, progress: &dyn Fn(f32), cancel: &AtomicBool) -> Result<Vec<u16>, String> {
    // The model wants red top left: start a row or column in when the sensor doesn't.
    let (dy, dx) = [(0, 0), (0, 1), (1, 0), (1, 1)]
        .into_iter()
        .find(|&(y, x)| raw.color(raw.top + y, raw.left + x) == 0 && raw.color(raw.top + y + 1, raw.left + x + 1) == 2)
        .ok_or("this sensor's colour pattern isn't one AI denoise knows")?;
    let (ph, pw) = ((raw.height - dy) / 2, (raw.width - dx) / 2);
    if ph < 16 || pw < 16 {
        return Err("the photo is too small to denoise".into());
    }
    // Red, green, green, blue planes of cells.
    let n = ph * pw;
    let mut cells = vec![0.0f32; 4 * n];
    for y in 0..ph {
        for x in 0..pw {
            let (r, c) = (dy + 2 * y, dx + 2 * x);
            cells[y * pw + x] = raw.level(r, c);
            cells[n + y * pw + x] = raw.level(r, c + 1);
            cells[2 * n + y * pw + x] = raw.level(r + 1, c);
            cells[3 * n + y * pw + x] = raw.level(r + 1, c + 1);
        }
    }

    let session = super::session(&RAW_DENOISE, "bayer.onnx")?;
    let (oh, ow) = (2 * ph, 2 * pw);
    let mut out = vec![0.0f32; 3 * oh * ow];
    let (ny, nx) = (ph.div_ceil(STEP), pw.div_ceil(STEP));
    let mut tile = vec![0.0f32; 4 * TILE * TILE];
    for ty in 0..ny {
        for tx in 0..nx {
            if cancel.load(Ordering::Relaxed) {
                return Err("cancelled".into());
            }
            let (cy, cx) = (ty * STEP, tx * STEP);
            for ch in 0..4 {
                for y in 0..TILE {
                    let sy = mirror(cy as isize + y as isize - OVERLAP as isize, ph);
                    for x in 0..TILE {
                        let sx = mirror(cx as isize + x as isize - OVERLAP as isize, pw);
                        tile[(ch * TILE + y) * TILE + x] = cells[ch * n + sy * pw + sx];
                    }
                }
            }
            let mut result = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("input", vec![1, 4, TILE as i64, TILE as i64], tile.clone())])?;
            let (shape, got) = result.pop().ok_or("the denoise model gave nothing back")?;
            let side = 2 * TILE;
            if shape != [1, 3, side as i64, side as i64] {
                return Err(format!("the denoise model gave back {shape:?}"));
            }
            // Keep the tile's core, without the overlap.
            let (core_h, core_w) = (STEP.min(ph - cy), STEP.min(pw - cx));
            for ch in 0..3 {
                for y in 0..2 * core_h {
                    let src = &got[(ch * side + 2 * OVERLAP + y) * side + 2 * OVERLAP..][..2 * core_w];
                    out[(ch * oh + 2 * cy + y) * ow + 2 * cx..][..2 * core_w].copy_from_slice(src);
                }
            }
            progress((ty * nx + tx + 1) as f32 / (ny * nx) as f32);
        }
    }

    let fix = colour_match(&cells, &out, ph, pw);
    let (w, h) = (raw.width, raw.height);
    let wb = raw.info.as_shot;
    let mut rgb = vec![0u16; w * h * 3];
    for y in 0..h {
        // The row or column the model didn't see (when red started a step in) copies its neighbour.
        let oy = y.saturating_sub(dy).min(oh - 1);
        for x in 0..w {
            let ox = x.saturating_sub(dx).min(ow - 1);
            let o = std::array::from_fn::<f32, 3, _>(|c| out[(c * oh + oy) * ow + ox]);
            for c in 0..3 {
                let v = (fix[c][0] * o[0] + fix[c][1] * o[1] + fix[c][2] * o[2]) * wb[c];
                rgb[(y * w + x) * 3 + c] = (v.clamp(0.0, 1.0) * 65535.0 + 0.5) as u16;
            }
        }
    }
    Ok(rgb)
}

/// The model's output comes at a gain of its own and with its colours a little off from the
/// sensor's. The matrix that takes it back to the photosites' colours, fitted on 8x8-cell
/// averages (where the noise has averaged out), leaving out clipped ones.
fn colour_match(cells: &[f32], out: &[f32], ph: usize, pw: usize) -> [[f32; 3]; 3] {
    const B: usize = 8;
    let (n, oh, ow) = (ph * pw, 2 * ph, 2 * pw);
    // Normal equations: (O^T O) M^T = O^T I.
    let mut oo = [[0f64; 3]; 3];
    let mut oi = [[0f64; 3]; 3];
    for by in 0..ph / B {
        for bx in 0..pw / B {
            let (mut i, mut o) = ([0f64; 3], [0f64; 3]);
            let mut clipped = false;
            for y in by * B..(by + 1) * B {
                for x in bx * B..(bx + 1) * B {
                    let k = y * pw + x;
                    let v = [cells[k], cells[n + k], cells[2 * n + k], cells[3 * n + k]];
                    clipped |= v.iter().any(|&v| v > 0.95);
                    i[0] += v[0] as f64;
                    i[1] += (v[1] + v[2]) as f64 / 2.0;
                    i[2] += v[3] as f64;
                }
            }
            for y in 2 * by * B..2 * (by + 1) * B {
                for x in 2 * bx * B..2 * (bx + 1) * B {
                    for (c, o) in o.iter_mut().enumerate() {
                        *o += out[(c * oh + y) * ow + x] as f64 / 4.0;
                    }
                }
            }
            if clipped {
                continue;
            }
            for a in 0..3 {
                for b in 0..3 {
                    oo[a][b] += o[a] * o[b];
                    oi[a][b] += o[a] * i[b];
                }
            }
        }
    }
    let Some(inv) = invert(oo) else { return [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]] };
    // Row c of the matrix: the weights of the output's channels for the sensor's channel c.
    std::array::from_fn(|c| std::array::from_fn(|k| (0..3).map(|j| inv[k][j] * oi[j][c]).sum::<f64>() as f32))
}

fn invert(m: [[f64; 3]; 3]) -> Option<[[f64; 3]; 3]> {
    let det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    if det.abs() < 1e-18 {
        return None;
    }
    let c = |a: usize, b: usize, x: usize, y: usize| m[a][b] * m[x][y] - m[a][y] * m[x][b];
    Some([
        [c(1, 1, 2, 2) / det, -c(0, 1, 2, 2) / det, c(0, 1, 1, 2) / det],
        [-c(1, 0, 2, 2) / det, c(0, 0, 2, 2) / det, -c(0, 0, 1, 2) / det],
        [c(1, 0, 2, 1) / det, -c(0, 0, 2, 1) / det, c(0, 0, 1, 1) / det],
    ])
}

/// `i` reflected into 0..n (numpy's "reflect"), for reading past the edges.
fn mirror(i: isize, n: usize) -> usize {
    let n = n as isize;
    let mut i = i;
    while i < 0 || i >= n {
        i = if i < 0 { -i } else { 2 * n - 2 - i };
    }
    i as usize
}

#[cfg(test)]
mod tests {
    #[test]
    fn invert_undoes_a_matrix() {
        let m = [[2.0, 1.0, 0.0], [0.5, 3.0, 1.0], [0.0, 1.0, 4.0]];
        let i = super::invert(m).unwrap();
        for (a, row) in m.iter().enumerate() {
            for b in 0..3 {
                let v: f64 = row.iter().zip(&i).map(|(x, col)| x * col[b]).sum();
                assert!((v - if a == b { 1.0 } else { 0.0 }).abs() < 1e-9);
            }
        }
    }

    #[test]
    fn mirror_reflects_at_both_ends() {
        assert_eq!(super::mirror(-1, 10), 1);
        assert_eq!(super::mirror(-3, 10), 3);
        assert_eq!(super::mirror(10, 10), 8);
        assert_eq!(super::mirror(4, 10), 4);
    }
}
