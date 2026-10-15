//! The develop pipeline for one band of output rows, a line-for-line port of Kotlin's
//! `Look.render` with a `FullSampler` source. Parameters arrive in a flat array laid out by
//! `Look.nativeParams()`; keep the two in step.

use crate::filters;
use rayon::prelude::*;

pub mod idx {
    pub const NOISE: usize = 0;
    pub const NOISE_DETAIL: usize = 1;
    pub const COLOR_NOISE: usize = 2;
    pub const DEHAZE: usize = 3;
    pub const SHADOWS: usize = 4;
    pub const HIGHLIGHTS: usize = 5;
    pub const CLARITY: usize = 6;
    pub const TEXTURE: usize = 7;
    pub const NEEDS_GUIDE: usize = 8;
    pub const EV: usize = 9;
    pub const MONO: usize = 10;
    pub const SAT: usize = 11;
    pub const VIB: usize = 12;
    pub const HAS_MIXER: usize = 13;
    pub const MIX_HUE: usize = 14;
    pub const MIX_SAT: usize = 22;
    pub const MIX_LUM: usize = 30;
    pub const BAND_CENTERS: usize = 38;
    pub const HAS_GRADING: usize = 46;
    pub const GRADE_OFFSET: usize = 47;
    pub const GRADE_LUM: usize = 59;
    pub const BLENDING: usize = 63;
    pub const BALANCE: usize = 64;
    pub const DEFRINGE: usize = 65;
    pub const VIGNETTE: usize = 66;
    pub const VIG_MID: usize = 67;
    pub const VIG_FEATHER: usize = 68;
    pub const VIG_ROUND: usize = 69;
    pub const VIG_HIGHLIGHTS: usize = 70;
    pub const SHARPEN: usize = 71;
    pub const SHARPEN_RADIUS: usize = 72;
    pub const SHARPEN_DETAIL: usize = 73;
    pub const SHARPEN_MASKING: usize = 74;
    pub const GRAIN: usize = 75;
    pub const GRAIN_SIZE: usize = 76;
    pub const GRAIN_ROUGH: usize = 77;
    pub const MATRIX: usize = 78;
    pub const COUNT: usize = 87;
}

const LN2: f32 = 0.6931472;
const LUT_SIZE: usize = 4096;
const LUT_MAX_F: f32 = 64.0;
const SCENE_GREY: f64 = 0.12;

#[inline]
fn luma(r: f32, g: f32, b: f32) -> f32 {
    0.2126 * r + 0.7152 * g + 0.0722 * b
}
#[inline]
fn log2(v: f32) -> f32 {
    v.max(1e-5).ln() / LN2
}
#[inline]
fn exp2(v: f32) -> f32 {
    (v * LN2).exp()
}
#[inline]
fn smooth(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

pub struct Look<'a> {
    pub p: &'a [f32],
    pub tone: &'a [f32],
    /// Red, green, blue point curves, 1024 values each, or empty.
    pub curves: &'a [f32],
}

pub struct Guide<'a> {
    pub width: usize,
    pub height: usize,
    pub base: &'a [f32],
    pub medium: &'a [f32],
    pub dark: &'a [f32],
    pub air: [f32; 3],
}

impl Guide<'_> {
    fn at(&self, map: &[f32], x: f32, y: f32) -> f32 {
        let xc = x.clamp(0.0, self.width as f32 - 1.0);
        let yc = y.clamp(0.0, self.height as f32 - 1.0);
        let x0 = xc as usize;
        let y0 = yc as usize;
        let x1 = (x0 + 1).min(self.width - 1);
        let y1 = (y0 + 1).min(self.height - 1);
        let fx = xc - x0 as f32;
        let fy = yc - y0 as f32;
        let w = self.width;
        let a = map[y0 * w + x0] + (map[y0 * w + x1] - map[y0 * w + x0]) * fx;
        let b = map[y1 * w + x0] + (map[y1 * w + x1] - map[y1 * w + x0]) * fx;
        a + (b - a) * fy
    }
}

/// Sensor-oriented 16-bit RGB pixels (all or part of the photo).
pub struct Source<'a> {
    pub data: &'a [u16],
    pub width: usize,
    pub height: usize,
    pub left: f64,
    pub top: f64,
    pub sensor_width: f64,
    pub sensor_height: f64,
    pub orientation: i32,
}

impl Source<'_> {
    fn sample(&self, c: usize, ux: f64, uy: f64) -> f32 {
        let (w_, h_) = (self.sensor_width, self.sensor_height);
        let (sx, sy) = match self.orientation {
            2 => (w_ - ux, uy),
            3 => (w_ - ux, h_ - uy),
            4 => (ux, h_ - uy),
            5 => (uy, ux),
            6 => (uy, h_ - ux),
            7 => (w_ - uy, h_ - ux),
            8 => (w_ - uy, ux),
            _ => (ux, uy),
        };
        let x = sx - 0.5 - self.left;
        let y = sy - 0.5 - self.top;
        let w = self.width;
        let h = self.height;
        if x < -1.0 || y < -1.0 || x > w as f64 || y > h as f64 {
            return 0.0;
        }
        let xc = x.clamp(0.0, w as f64 - 1.0);
        let yc = y.clamp(0.0, h as f64 - 1.0);
        let x0 = xc as usize;
        let y0 = yc as usize;
        let x1 = (x0 + 1).min(w - 1);
        let y1 = (y0 + 1).min(h - 1);
        let fx = (xc - x0 as f64) as f32;
        let fy = (yc - y0 as f64) as f32;
        let d = self.data;
        let at = |px: usize, py: usize| d[(py * w + px) * 3 + c] as f32 * (1.0 / 65535.0);
        let a = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx;
        let b = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx;
        a + (b - a) * fy
    }
}

/// The geometry's inverse map sampled every `step` output pixels: 6 source coordinates (R, G, B
/// x/y in upright full-size pixels) per node, plus an optional lens vignetting gain.
pub struct Grid<'a> {
    pub x0: f32,
    pub y0: f32,
    pub nx: usize,
    pub ny: usize,
    pub step: f32,
    pub map: &'a [f32],
    pub vig: &'a [f32],
}

impl Grid<'_> {
    fn lookup(&self, fx: f32, fy: f32, out: &mut [f64; 6]) -> f32 {
        let gx = ((fx - self.x0) / self.step).clamp(0.0, (self.nx - 1) as f32);
        let gy = ((fy - self.y0) / self.step).clamp(0.0, (self.ny - 1) as f32);
        let i0 = (gx as usize).min(self.nx.saturating_sub(2));
        let j0 = (gy as usize).min(self.ny.saturating_sub(2));
        let i1 = (i0 + 1).min(self.nx - 1);
        let j1 = (j0 + 1).min(self.ny - 1);
        let tx = (gx - i0 as f32) as f64;
        let ty = (gy - j0 as f32) as f64;
        let n00 = (j0 * self.nx + i0) * 6;
        let n10 = (j0 * self.nx + i1) * 6;
        let n01 = (j1 * self.nx + i0) * 6;
        let n11 = (j1 * self.nx + i1) * 6;
        let m = self.map;
        for k in 0..6 {
            let a = m[n00 + k] as f64 + (m[n10 + k] as f64 - m[n00 + k] as f64) * tx;
            let b = m[n01 + k] as f64 + (m[n11 + k] as f64 - m[n01 + k] as f64) * tx;
            out[k] = a + (b - a) * ty;
        }
        if self.vig.is_empty() {
            return 1.0;
        }
        let v = self.vig;
        let a = v[j0 * self.nx + i0] + (v[j0 * self.nx + i1] - v[j0 * self.nx + i0]) * tx as f32;
        let b = v[j1 * self.nx + i0] + (v[j1 * self.nx + i1] - v[j1 * self.nx + i0]) * tx as f32;
        a + (b - a) * ty as f32
    }
}

/// Where this render sits: [width] x [height] output pixels, part of a [frame_width] x
/// [frame_height] frame at [scale] output pixels per full-size pixel.
pub struct Frame {
    pub width: usize,
    pub height: usize,
    pub frame_width: usize,
    pub frame_height: usize,
    pub origin_x: usize,
    pub origin_y: usize,
    pub scale: f64,
}

impl Look<'_> {
    fn g(&self, i: usize) -> f32 {
        self.p[i]
    }
    fn noise_radius(&self, scale: f64) -> usize {
        ((scale * (1.0 + self.g(idx::NOISE) as f64 * 3.0)) as i32).max(1) as usize
    }
    fn color_noise_radius(&self, scale: f64) -> f32 {
        ((scale * (2.0 + self.g(idx::COLOR_NOISE) as f64 * 10.0)) as f32).max(1.0)
    }
    fn texture_radius(&self, scale: f64) -> f32 {
        ((scale * 3.0) as f32).max(0.7)
    }
    fn sharpen_radius(&self, scale: f64) -> f32 {
        ((self.g(idx::SHARPEN_RADIUS) as f64 * scale) as f32).max(0.6)
    }

    pub fn margin(&self, scale: f64) -> usize {
        let mut r = 0.0f64;
        if self.g(idx::NOISE) > 0.0 { r = r.max(self.noise_radius(scale) as f64 * 2.0); }
        if self.g(idx::COLOR_NOISE) > 0.0 { r = r.max(self.color_noise_radius(scale) as f64 * 3.0); }
        if self.g(idx::TEXTURE) != 0.0 { r = r.max(self.texture_radius(scale) as f64 * 3.0); }
        if self.g(idx::SHARPEN) > 0.0 { r = r.max(self.sharpen_radius(scale) as f64 * 3.0); }
        if self.g(idx::DEFRINGE) > 0.0 { r = r.max(2.0); }
        r.ceil() as usize + 2
    }

    fn lookup(&self, x: f32) -> f32 {
        if x <= 0.0 {
            return 0.0;
        }
        let t = (x / LUT_MAX_F).sqrt() * LUT_SIZE as f32;
        if t >= LUT_SIZE as f32 {
            return self.tone[LUT_SIZE];
        }
        let i = t as usize;
        let f = t - i as f32;
        self.tone[i] + (self.tone[i + 1] - self.tone[i]) * f
    }

    /// Renders output rows [y0, y1) into `out` (ARGB, row-major, frame.width per row).
    pub fn render(&self, guide: Option<&Guide>, src: &Source, grid: &Grid, fr: &Frame, y0: usize, y1: usize, out: &mut [i32]) {
        let w = fr.width;
        let hh = fr.height;
        let scale = fr.scale;
        let m = self.margin(scale);
        let top = y0.saturating_sub(m);
        let bottom = hh.min(y1 + m);
        let rows = bottom - top;
        let n = w * rows;
        let mut r = vec![0f32; n];
        let mut g = vec![0f32; n];
        let mut b = vec![0f32; n];

        // Sample through the geometry, then white balance / colour matrix / exposure.
        let mx = &self.p[idx::MATRIX..idx::MATRIX + 9];
        r.par_chunks_mut(w)
            .zip(g.par_chunks_mut(w))
            .zip(b.par_chunks_mut(w))
            .enumerate()
            .for_each(|(i, ((rr, gg), bb))| {
                let y = (fr.origin_y + top + i).min(fr.frame_height - 1);
                let mut coords = [0f64; 6];
                for x in 0..w {
                    let gain = grid.lookup((fr.origin_x + x) as f32, y as f32, &mut coords);
                    let cr = src.sample(0, coords[0], coords[1]) * gain;
                    let cg = src.sample(1, coords[2], coords[3]) * gain;
                    let cb = src.sample(2, coords[4], coords[5]) * gain;
                    rr[x] = (mx[0] * cr + mx[1] * cg + mx[2] * cb).max(0.0);
                    gg[x] = (mx[3] * cr + mx[4] * cg + mx[5] * cb).max(0.0);
                    bb[x] = (mx[6] * cr + mx[7] * cg + mx[8] * cb).max(0.0);
                }
            });

        // --- Noise reduction (linear) ---
        let color_noise = self.g(idx::COLOR_NOISE);
        if color_noise > 0.0 {
            let mut cr = vec![0f32; n];
            let mut cb = vec![0f32; n];
            for i in 0..n {
                let l = luma(r[i], g[i], b[i]);
                cr[i] = r[i] - l;
                cb[i] = b[i] - l;
            }
            let rad = self.color_noise_radius(scale);
            filters::blur(&mut cr, w, rows, rad);
            filters::blur(&mut cb, w, rows, rad);
            for i in 0..n {
                let l = luma(r[i], g[i], b[i]);
                let nr = (l + cr[i]).max(0.0);
                let nb = (l + cb[i]).max(0.0);
                r[i] = nr;
                b[i] = nb;
                g[i] = ((l - 0.2126 * nr - 0.0722 * nb) / 0.7152).max(0.0);
            }
        }
        let noise = self.g(idx::NOISE);
        if noise > 0.0 {
            let p: Vec<f32> = (0..n).map(|i| luma(r[i], g[i], b[i]).sqrt()).collect();
            let eps = (noise * noise * 0.004 * (1.6 - self.g(idx::NOISE_DETAIL))).max(1e-6);
            let q = filters::guided(&p, w, rows, self.noise_radius(scale), eps);
            for i in 0..n {
                let before = p[i] * p[i];
                if before > 1e-6 {
                    let f = q[i] * q[i] / before;
                    r[i] *= f;
                    g[i] *= f;
                    b[i] *= f;
                }
            }
        }

        let (gx, gy) = match guide {
            Some(gd) => (gd.width as f32 / fr.frame_width as f32, gd.height as f32 / fr.frame_height as f32),
            None => (0.0, 0.0),
        };
        let ox = fr.origin_x as f32;
        let oy = (top + fr.origin_y) as f32;
        let ev = self.g(idx::EV);

        // --- Dehaze ---
        let dehaze = self.g(idx::DEHAZE);
        if let (true, Some(gd)) = (dehaze != 0.0, guide) {
            let gain = exp2(ev);
            let air = [gd.air[0] * gain, gd.air[1] * gain, gd.air[2] * gain];
            let amax = gd.air[0].max(gd.air[1].max(gd.air[2]));
            r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(j, ((rr, gg), bb))| {
                let yy = (oy + j as f32 + 0.5) * gy - 0.5;
                for x in 0..w {
                    let d = gd.at(gd.dark, (ox + x as f32 + 0.5) * gx - 0.5, yy);
                    if dehaze > 0.0 {
                        let t = (1.0 - dehaze * 0.9 * d / amax).max(0.15);
                        rr[x] = ((rr[x] - air[0]) / t + air[0]).max(0.0);
                        gg[x] = ((gg[x] - air[1]) / t + air[1]).max(0.0);
                        bb[x] = ((bb[x] - air[2]) / t + air[2]).max(0.0);
                    } else {
                        let k = -dehaze * 0.6;
                        rr[x] += (air[0] - rr[x]) * k;
                        gg[x] += (air[1] - gg[x]) * k;
                        bb[x] += (air[2] - bb[x]) * k;
                    }
                }
            });
        }

        // --- Local tone ---
        let shadows = self.g(idx::SHADOWS);
        let highlights = self.g(idx::HIGHLIGHTS);
        let clarity = self.g(idx::CLARITY);
        let texture = self.g(idx::TEXTURE);
        let needs_guide = self.g(idx::NEEDS_GUIDE) > 0.5;
        if needs_guide && guide.is_some() && (shadows != 0.0 || highlights != 0.0 || clarity != 0.0) || texture != 0.0 {
            let lum: Vec<f32> = (0..n).map(|i| log2(luma(r[i], g[i], b[i]))).collect();
            let small = if texture != 0.0 {
                let mut s = lum.clone();
                filters::blur(&mut s, w, rows, self.texture_radius(scale));
                Some(s)
            } else {
                None
            };
            let mid = log2(SCENE_GREY as f32);
            let tex_amt = texture * 0.9 * ((scale * 2.5) as f32).max(0.4).min(1.0);
            r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(j, ((rr, gg), bb))| {
                let yy = (oy + j as f32 + 0.5) * gy - 0.5;
                for x in 0..w {
                    let i = j * w + x;
                    let l = lum[i];
                    let mut d = 0f32;
                    if let (true, Some(gd)) = (needs_guide, guide) {
                        let gxx = (ox + x as f32 + 0.5) * gx - 0.5;
                        let base = gd.at(gd.base, gxx, yy) + ev;
                        if shadows != 0.0 { d += shadows * 1.7 / (1.0 + ((base - mid + 1.2) * 1.6).exp()); }
                        if highlights != 0.0 { d += highlights * 1.7 / (1.0 + (-(base - mid - 0.6) * 1.6).exp()); }
                        if clarity != 0.0 {
                            let med = gd.at(gd.medium, gxx, yy) + ev;
                            let z = (l - mid) / 3.5;
                            let w_mid = (-(z * z)).exp();
                            d += clarity * 0.7 * (l - med).clamp(-2.0, 2.0) * w_mid;
                        }
                    }
                    if let Some(s) = &small {
                        d += tex_amt * (l - s[i]).clamp(-1.5, 1.5);
                    }
                    if d != 0.0 {
                        let f = exp2(d);
                        rr[x] *= f;
                        gg[x] *= f;
                        bb[x] *= f;
                    }
                }
            });
        }

        // --- Tone, curves, colour (display space) ---
        let has_curves = self.curves.len() >= 3 * 1024;
        r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).for_each(|((rr, gg), bb)| {
            for x in 0..w {
                let mut c = [self.lookup(rr[x]), self.lookup(gg[x]), self.lookup(bb[x])];
                if has_curves {
                    for k in 0..3 {
                        c[k] = lerp_table(&self.curves[k * 1024..(k + 1) * 1024], c[k]);
                    }
                }
                self.color(&mut c);
                rr[x] = c[0];
                gg[x] = c[1];
                bb[x] = c[2];
            }
        });

        if self.g(idx::DEFRINGE) > 0.0 {
            self.defringe(&mut r, &mut g, &mut b, w, rows);
        }

        // --- Vignette ---
        let vignette = self.g(idx::VIGNETTE);
        if vignette != 0.0 {
            let fw = fr.frame_width as f32;
            let fh = fr.frame_height as f32;
            let vr = self.g(idx::VIG_ROUND);
            let ax = if vr >= 0.0 { 1.0 + (fw / fh - 1.0) * vr } else { 1.0 };
            let ay = if vr >= 0.0 { 1.0 } else { 1.0 - vr * 0.6 };
            let e0 = 0.3 + self.g(idx::VIG_MID) * 0.7;
            let e1 = e0 + 0.05 + self.g(idx::VIG_FEATHER) * 0.9;
            let vh = self.g(idx::VIG_HIGHLIGHTS);
            r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(j, ((rr, gg), bb))| {
                let v = ((oy + j as f32 + 0.5) / fh * 2.0 - 1.0) / ay;
                for x in 0..w {
                    let u = ((ox + x as f32 + 0.5) / fw * 2.0 - 1.0) * ax / ax.max(1.0);
                    let t = smooth(e0, e1, (u * u + v * v).sqrt() / 1.4142 * 1.25);
                    if t <= 0.0 {
                        continue;
                    }
                    if vignette < 0.0 {
                        let l = luma(rr[x], gg[x], bb[x]);
                        let f = 1.0 + vignette * t * (1.0 - vh * l * l);
                        rr[x] *= f;
                        gg[x] *= f;
                        bb[x] *= f;
                    } else {
                        let k = vignette * t;
                        rr[x] += (1.0 - rr[x]) * k;
                        gg[x] += (1.0 - gg[x]) * k;
                        bb[x] += (1.0 - bb[x]) * k;
                    }
                }
            });
        }

        // --- Sharpening ---
        let sharpen = self.g(idx::SHARPEN);
        if sharpen > 0.0 {
            let l: Vec<f32> = (0..n).map(|i| luma(r[i], g[i], b[i])).collect();
            let mut bl = l.clone();
            filters::blur(&mut bl, w, rows, self.sharpen_radius(scale));
            let amt = sharpen * 1.4 * ((scale * 2.5) as f32).max(0.3).min(1.0);
            let limit = 0.02 + 0.25 * self.g(idx::SHARPEN_DETAIL);
            let t0 = self.g(idx::SHARPEN_MASKING) * 0.06;
            r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(j, ((rr, gg), bb))| {
                for x in 0..w {
                    let i = j * w + x;
                    let mut d = (l[i] - bl[i]).clamp(-limit, limit) * amt;
                    if t0 > 0.0 {
                        let xl = x.saturating_sub(1);
                        let xr = (x + 1).min(w - 1);
                        let yu = j.saturating_sub(1);
                        let yd = (j + 1).min(rows - 1);
                        let grad = (l[j * w + xr] - l[j * w + xl]).abs() + (l[yd * w + x] - l[yu * w + x]).abs();
                        d *= smooth(t0 * 0.5, t0 * 1.5 + 1e-4, grad);
                    }
                    rr[x] += d;
                    gg[x] += d;
                    bb[x] += d;
                }
            });
        }

        // --- Grain ---
        let grain = self.g(idx::GRAIN);
        if grain > 0.0 {
            let cell = 1.0 + self.g(idx::GRAIN_SIZE) as f64 * 4.0;
            let rough = self.g(idx::GRAIN_ROUGH);
            r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(j, ((rr, gg), bb))| {
                let fy = (oy as f64 + j as f64 + 0.5) / scale / cell;
                for x in 0..w {
                    let fx = (ox as f64 + x as f64 + 0.5) / scale / cell;
                    let mut nz = value_noise(fx, fy) * (1.0 - rough) + hash((fx * 3.0).floor() as i32, (fy * 3.0).floor() as i32) * rough;
                    let l = luma(rr[x], gg[x], bb[x]);
                    nz *= grain * 0.16 * (0.35 + 2.6 * l * (1.0 - l));
                    rr[x] += nz;
                    gg[x] += nz;
                    bb[x] += nz;
                }
            });
        }

        let off = y0 - top;
        out.par_chunks_mut(w).take(y1 - y0).enumerate().for_each(|(j, o)| {
            let si = (j + off) * w;
            for x in 0..w {
                o[x] = (0xff << 24) | (to8(r[si + x]) << 16) | (to8(g[si + x]) << 8) | to8(b[si + x]);
            }
        });
    }

    fn color(&self, p: &mut [f32; 3]) {
        let (mut r, mut g, mut b) = (p[0], p[1], p[2]);
        let mono = self.g(idx::MONO) > 0.5;
        let has_mixer = self.g(idx::HAS_MIXER) > 0.5;
        let mx = r.max(g.max(b));
        let mn = r.min(g.min(b));
        let chroma = mx - mn;
        if (has_mixer || mono) && chroma > 1e-4 {
            let hue = hue_of(r, g, b, mx, chroma);
            let (mut dh, mut ds, mut dl) = (0f32, 0f32, 0f32);
            for k in 0..8 {
                let wgt = self.band_weight(k, hue);
                if wgt == 0.0 {
                    continue;
                }
                dh += wgt * self.p[idx::MIX_HUE + k];
                ds += wgt * self.p[idx::MIX_SAT + k];
                dl += wgt * self.p[idx::MIX_LUM + k];
            }
            let l = luma(r, g, b);
            let sat_now = chroma / (1.0 - (mx + mn - 1.0).abs()).max(1e-4);
            if mono {
                let grey = (l + dl * 0.35 * sat_now.min(1.0)).clamp(0.0, 1.0);
                *p = [grey, grey, grey];
                self.grade(p, grey);
                return;
            }
            if dh != 0.0 {
                let rgb = hsv_to_rgb((hue + dh * 30.0 + 360.0) % 360.0, chroma / mx.max(1e-4), mx);
                r = rgb[0];
                g = rgb[1];
                b = rgb[2];
            }
            if ds != 0.0 {
                let ll = luma(r, g, b);
                let f = 1.0 + ds;
                r = ll + (r - ll) * f;
                g = ll + (g - ll) * f;
                b = ll + (b - ll) * f;
            }
            if dl != 0.0 {
                let shift = dl * 0.3 * sat_now.min(1.0);
                r += shift;
                g += shift;
                b += shift;
            }
        } else if mono {
            let grey = luma(r, g, b);
            *p = [grey, grey, grey];
            self.grade(p, grey);
            return;
        }
        let sat = self.g(idx::SAT);
        let vib = self.g(idx::VIB);
        if sat != 1.0 || vib != 0.0 {
            let l = luma(r, g, b);
            let c = r.max(g.max(b)) - r.min(g.min(b));
            let f = sat * (1.0 + vib * (1.0 - c) * (1.0 - c));
            r = l + (r - l) * f;
            g = l + (g - l) * f;
            b = l + (b - l) * f;
        }
        *p = [r, g, b];
        self.grade(p, luma(r, g, b));
    }

    fn grade(&self, p: &mut [f32; 3], l: f32) {
        if self.g(idx::HAS_GRADING) < 0.5 {
            return;
        }
        let pivot = (0.5 - self.g(idx::BALANCE) * 0.3).clamp(0.15, 0.85);
        let width = 0.15 + self.g(idx::BLENDING) * 0.6;
        let ws = 1.0 - smooth(pivot - width, pivot, l);
        let wh = smooth(pivot, pivot + width, l);
        let wm = (1.0 - ws - wh).max(0.0);
        let weights = [ws, wm, wh, 1.0];
        for z in 0..4 {
            let wz = weights[z];
            if wz == 0.0 {
                continue;
            }
            let o = &self.p[idx::GRADE_OFFSET + z * 3..idx::GRADE_OFFSET + z * 3 + 3];
            let dl = self.p[idx::GRADE_LUM + z] * 0.2 * wz;
            p[0] += o[0] * wz + dl;
            p[1] += o[1] * wz + dl;
            p[2] += o[2] * wz + dl;
        }
    }

    fn band_weight(&self, k: usize, hue: f32) -> f32 {
        let c = self.p[idx::BAND_CENTERS + k];
        let prev = self.p[idx::BAND_CENTERS + (k + 7) % 8];
        let next = self.p[idx::BAND_CENTERS + (k + 1) % 8];
        let d_prev = (c - prev + 360.0) % 360.0;
        let d_next = (next - c + 360.0) % 360.0;
        let d = ((hue - c + 540.0) % 360.0) - 180.0;
        if d >= 0.0 { (1.0 - d / d_next).max(0.0) } else { (1.0 + d / d_prev).max(0.0) }
    }

    fn defringe(&self, r: &mut [f32], g: &mut [f32], b: &mut [f32], w: usize, h: usize) {
        let amount = self.g(idx::DEFRINGE);
        let l: Vec<f32> = (0..w * h).map(|i| luma(r[i], g[i], b[i])).collect();
        r.par_chunks_mut(w).zip(g.par_chunks_mut(w)).zip(b.par_chunks_mut(w)).enumerate().for_each(|(y, ((rr, gg), bb))| {
            for x in 0..w {
                let xl = x.saturating_sub(1);
                let xr = (x + 1).min(w - 1);
                let yu = y.saturating_sub(1);
                let yd = (y + 1).min(h - 1);
                let grad = (l[y * w + xr] - l[y * w + xl]).abs() + (l[yd * w + x] - l[yu * w + x]).abs();
                if grad < 0.08 {
                    continue;
                }
                let mx = rr[x].max(gg[x].max(bb[x]));
                let c = mx - rr[x].min(gg[x].min(bb[x]));
                if c < 0.03 {
                    continue;
                }
                let hue = hue_of(rr[x], gg[x], bb[x], mx, c);
                let fringe = (1.0 - (hue - 290.0).abs() / 45.0).max(1.0 - (hue - 110.0).abs() / 35.0).clamp(0.0, 1.0);
                let k = amount * fringe * smooth(0.08, 0.25, grad);
                if k <= 0.0 {
                    continue;
                }
                let li = l[y * w + x];
                rr[x] += (li - rr[x]) * k;
                gg[x] += (li - gg[x]) * k;
                bb[x] += (li - bb[x]) * k;
            }
        });
    }
}

fn lerp_table(t: &[f32], v: f32) -> f32 {
    let x = v.clamp(0.0, 1.0) * (t.len() - 1) as f32;
    let i = (x as usize).min(t.len() - 2);
    t[i] + (t[i + 1] - t[i]) * (x - i as f32)
}

fn hue_of(r: f32, g: f32, b: f32, mx: f32, c: f32) -> f32 {
    let h = if mx == r {
        let v = (g - b) / c;
        if v < 0.0 { v + 6.0 } else { v }
    } else if mx == g {
        (b - r) / c + 2.0
    } else {
        (r - g) / c + 4.0
    };
    h * 60.0
}

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> [f32; 3] {
    let hh = ((h % 360.0) + 360.0) % 360.0 / 60.0;
    let i = hh as i32;
    let f = hh - i as f32;
    let p = v * (1.0 - s);
    let q = v * (1.0 - s * f);
    let t = v * (1.0 - s * (1.0 - f));
    match i {
        0 => [v, t, p],
        1 => [q, v, p],
        2 => [p, v, t],
        3 => [p, q, v],
        4 => [t, p, v],
        _ => [v, p, q],
    }
}

fn hash(x: i32, y: i32) -> f32 {
    let mut h = x.wrapping_mul(374761393).wrapping_add(y.wrapping_mul(668265263));
    h = (h ^ ((h as u32 >> 13) as i32)).wrapping_mul(1274126177);
    h ^= (h as u32 >> 16) as i32;
    (h & 0xffff) as f32 / 32768.0 - 1.0
}

fn value_noise(x: f64, y: f64) -> f32 {
    let x0 = x.floor() as i32;
    let y0 = y.floor() as i32;
    let fx = (x - x0 as f64) as f32;
    let fy = (y - y0 as f64) as f32;
    let sx = fx * fx * (3.0 - 2.0 * fx);
    let sy = fy * fy * (3.0 - 2.0 * fy);
    let a = hash(x0, y0) + (hash(x0 + 1, y0) - hash(x0, y0)) * sx;
    let b = hash(x0, y0 + 1) + (hash(x0 + 1, y0 + 1) - hash(x0, y0 + 1)) * sx;
    a + (b - a) * sy
}

#[inline]
fn to8(v: f32) -> i32 {
    ((v * 255.0 + 0.5) as i32).clamp(0, 255)
}
