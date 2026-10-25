//! Everything an edit needs at render time, precomputed: the colour matrix, tone tables, mixer
//! and grading coefficients, the guide maps and the CPU-side tools (the phone's Pipeline.kt).
//! The per-pixel pipeline itself runs on the GPU, from [`ShaderParams`].

use super::color::{self, CameraColor};
use super::geometry::Geometry;
use super::image::{self, LinearImage};
use super::params::{local, p, MaskKind, Param, Profile, Settings};
use super::raw::LensData;
use rayon::prelude::*;
use std::f64::consts::PI;

/// A photo ready to render: upright scene-linear preview pixels plus what colours them.
#[derive(Clone)]
pub struct Source {
    pub image: LinearImage,
    pub color: CameraColor,
    /// Multipliers already applied to `image` (as-shot WB for raws, 1,1,1 for JPEGs).
    pub baked: [f32; 3],
    pub is_raw: bool,
    /// Full-size upright dimensions; geometry works in these units.
    pub full_width: usize,
    pub full_height: usize,
    pub lens: Option<LensData>,
    /// Stops the render adds on top of Exposure (a DNG's BaselineExposure).
    pub baseline: f64,
    /// A DNG's own base tone curve (ProfileToneCurve: linear in, linear out), used by the Color profile.
    pub tone_curve: Option<Vec<[f32; 2]>>,
    /// The scene's darkest level (flare and the sensor's floor, in white-balanced camera units),
    /// taken off before the tone curve so blacks go black. 0 for photos that render their own.
    pub black_floor: f32,
}

impl Source {
    /// The as-shot Kelvin and tint, shown while the photo uses "As Shot".
    pub fn as_shot(&self) -> (f64, f64) {
        if self.is_raw {
            self.color.temperature_of(self.baked)
        } else {
            (6500.0, 0.0)
        }
    }

    pub fn geometry(&self, s: &Settings, ignore_crop: bool) -> Geometry {
        Geometry::new(s, self.full_width as f64, self.full_height as f64, self.lens.as_ref(), ignore_crop)
    }

    pub fn with_image(&self, image: LinearImage) -> Source {
        Source { image, ..self.clone() }
    }
}

pub const TONE_SIZE: usize = 4096;
pub const TONE_MAX: f64 = 64.0;
/// Correctly metered mid grey in raw (about 3 stops under clip) and where it lands on screen.
pub const SCENE_GREY: f64 = 0.12;
const DISPLAY_GREY: f64 = 0.20;
pub const MASK_MAX: usize = 8;
pub const MASK_STRIDE: usize = 19;
/// Edge of the guide maps, for preview and export alike.
pub const GUIDE_EDGE: usize = 512;

fn luma(r: f32, g: f32, b: f32) -> f32 {
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

fn log2(v: f32) -> f32 {
    v.max(1e-5).log2()
}

fn smooth(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

pub fn encode_srgb(v: f64) -> f64 {
    if v <= 0.0031308 {
        v * 12.92
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}

fn encode_f(v: f32) -> f32 {
    let c = v.clamp(0.0, 1.0);
    if c <= 0.0031308 {
        c * 12.92
    } else {
        1.055 * c.powf(1.0 / 2.4) - 0.055
    }
}

/// The darkest level a photo really has: the 0.1st percentile of its darkest channel, capped so a
/// low-key shot keeps its shadows.
pub fn black_floor(img: &LinearImage) -> f32 {
    let step = (img.r.len() / 50_000).max(1);
    let mut v: Vec<f32> = (0..img.r.len()).step_by(step).map(|i| img.r[i].min(img.g[i]).min(img.b[i])).collect();
    if v.is_empty() {
        return 0.0;
    }
    let k = v.len() / 1000;
    let (_, floor, _) = v.select_nth_unstable_by(k, f32::total_cmp);
    floor.clamp(0.0, 0.02)
}

/// Rolls scene values above a knee smoothly into 0..1, so a camera's 0..1 tone curve keeps
/// highlight detail instead of clipping it.
fn soft_clip(x: f64) -> f64 {
    const KNEE: f64 = 0.75;
    if x <= KNEE {
        x.max(0.0)
    } else {
        KNEE + (1.0 - KNEE) * (1.0 - (-(x - KNEE) / (1.0 - KNEE)).exp())
    }
}

/// A DNG ProfileToneCurve (points sorted by input) at `x`, linear in between.
fn profile_curve(curve: &[[f32; 2]], x: f64) -> f64 {
    let i = curve.partition_point(|p| (p[0] as f64) < x);
    if i == 0 {
        return curve[0][1] as f64;
    }
    if i >= curve.len() {
        return curve[curve.len() - 1][1] as f64;
    }
    let ([x0, y0], [x1, y1]) = (curve[i - 1], curve[i]);
    let t = if x1 > x0 { (x - x0 as f64) / (x1 - x0) as f64 } else { 0.0 };
    y0 as f64 + (y1 - y0) as f64 * t
}

pub fn lerp_table(t: &[f32], v: f32) -> f32 {
    let x = v.clamp(0.0, 1.0) * (t.len() - 1) as f32;
    let i = (t.len() - 2).min(x as usize);
    t[i] + (t[i + 1] - t[i]) * (x - i as f32)
}

pub fn hue_of(r: f32, g: f32, b: f32, mx: f32, c: f32) -> f32 {
    let h = if mx == r {
        let h = (g - b) / c;
        if h < 0.0 {
            h + 6.0
        } else {
            h
        }
    } else if mx == g {
        (b - r) / c + 2.0
    } else {
        (r - g) / c + 4.0
    };
    h * 60.0
}

pub fn hsv_to_rgb(h: f32, s: f32, v: f32) -> [f32; 3] {
    let hh = (h % 360.0 + 360.0) % 360.0 / 60.0;
    let i = hh as i32;
    let f = hh - i as f32;
    let (p, q, t) = (v * (1.0 - s), v * (1.0 - s * f), v * (1.0 - s * (1.0 - f)));
    match i {
        0 => [v, t, p],
        1 => [q, v, p],
        2 => [p, v, t],
        3 => [p, q, v],
        4 => [t, p, v],
        _ => [v, p, q],
    }
}

/// Low-resolution maps for the large-radius adjustments (Shadows/Highlights base layer, Clarity,
/// Dehaze), made from the preview at a fixed small size so the export looks like the screen.
#[derive(Clone)]
pub struct Guide {
    pub width: usize,
    pub height: usize,
    pub base: Vec<f32>,
    pub medium: Vec<f32>,
    pub dark: Vec<f32>,
    pub air: [f32; 3],
}

/// The per-pixel parameters the GPU shader reads.
#[derive(Clone)]
pub struct ShaderParams {
    pub matrix: [f32; 9],
    pub tone: Vec<f32>,
    pub curves: Option<[Vec<f32>; 3]>,
    pub ev: f32,
    pub shadows: f32,
    pub highlights: f32,
    pub clarity: f32,
    pub dehaze: f32,
    pub texture: f32,
    pub texture_radius: f32,
    pub mono: bool,
    pub sat: f32,
    pub vib: f32,
    pub has_mixer: bool,
    pub mix_hue: [f32; 8],
    pub mix_sat: [f32; 8],
    pub mix_lum: [f32; 8],
    pub band_centers: [f32; 8],
    pub has_grading: bool,
    pub grade_offset: [f32; 12],
    pub grade_lum: [f32; 4],
    pub blending: f32,
    pub balance: f32,
    pub vignette: f32,
    pub vig_mid: f32,
    pub vig_feather: f32,
    pub vig_round: f32,
    pub vig_highlights: f32,
    pub sharpen: f32,
    pub sharpen_radius: f32,
    pub sharpen_limit: f32,
    pub sharpen_masking: f32,
    pub grain: f32,
    pub grain_cell: f32,
    pub grain_rough: f32,
    pub scale: f32,
    /// Masks the shader evaluates: none when no mask moves a slider.
    pub mask_count: usize,
    /// All the masks, for showing one as an overlay even before it does anything.
    pub mask_total: usize,
    pub masks: [f32; MASK_MAX * MASK_STRIDE],
    pub source_size: [f32; 2],
    pub uses_subject: bool,
    pub uses_sky: bool,
    pub uses_depth: bool,
    pub lut_amount: f32,
}

/// One edit on one photo, ready to render.
pub struct Look {
    s: Settings,
    raw: bool,
    tone_curve: Option<Vec<[f32; 2]>>,
    /// [`Source::black_floor`] at this look's exposure.
    floor: f64,
    full: (f64, f64),
    pub matrix: [f32; 9],
    scene_matrix: [f32; 9],
    scene_inverse: [f32; 9],
    pub tone: Vec<f32>,
    rgb_curves: Option<[Vec<f32>; 3]>,
    masks: [f32; MASK_MAX * MASK_STRIDE],
    mask_count: usize,
    mask_total: usize,
    needs_guide: bool,
}

impl Look {
    pub fn new(source: &Source, s: &Settings) -> Look {
        let c = &source.color;
        let (k0, t0) = source.as_shot();
        let kelvin = s.temperature().map_or(k0, |v| v as f64);
        let tint = s.tint().map_or(t0, |v| v as f64);
        let target = c.multipliers(kelvin, tint);
        let reference = c.multipliers(k0, t0);
        let rel: [f64; 3] = std::array::from_fn(|i| target[i] / reference[i]);
        // Keep brightness steady while the balance moves.
        let norm = 0.30 * rel[0] + 0.59 * rel[1] + 0.11 * rel[2];
        let gain = 2f64.powf(s.get(&p::EXPOSURE) as f64 + source.baseline);
        let m = color::mul(&c.rgb_from_cam, &color::diag(rel[0] / norm, rel[1] / norm, rel[2] / norm));
        let inv = color::invert(&c.rgb_from_cam);

        let (masks, mask_total, mask_used) = mask_params(s, source.full_width as f64, source.full_height as f64);
        let any_mask = mask_used.iter().any(|u| *u);
        let mask_count = if any_mask { mask_total } else { 0 };
        let uses = |i: usize| any_mask && mask_used[i];
        let needs_guide = s.get(&p::SHADOWS) != 0.0
            || s.get(&p::HIGHLIGHTS) != 0.0
            || s.get(&p::CLARITY) != 0.0
            || s.get(&p::DEHAZE) != 0.0
            || uses(5)
            || uses(4)
            || uses(6)
            || uses(7);

        let mut look = Look {
            s: s.clone(),
            raw: source.is_raw,
            tone_curve: source.tone_curve.clone().filter(|_| source.is_raw && s.profile == Profile::Color),
            floor: source.black_floor as f64 * gain,
            full: (source.full_width as f64, source.full_height as f64),
            matrix: std::array::from_fn(|i| (m[i] * gain) as f32),
            scene_matrix: std::array::from_fn(|i| c.rgb_from_cam[i] as f32),
            scene_inverse: std::array::from_fn(|i| inv[i] as f32),
            tone: vec![0.0; TONE_SIZE + 1],
            rgb_curves: if s.curves[1..].iter().all(|c| c.is_identity()) {
                None
            } else {
                Some([s.curves[1].table(1024), s.curves[2].table(1024), s.curves[3].table(1024)])
            },
            masks,
            mask_count,
            mask_total,
            needs_guide,
        };
        look.build_tone();
        look
    }

    fn v(&self, param: &Param) -> f32 {
        self.s.get(param)
    }

    /// Scene-linear to display value 0..1 (sRGB encoded): the profile's filmic sigmoid (camera mid
    /// grey to display mid grey, clip point to white), Whites/Blacks/Contrast, the parametric
    /// curve regions, then the master point curve.
    fn build_tone(&mut self) {
        let s = &self.s;
        let white = 2f64.powf(-s.get(&p::WHITES) as f64 / 100.0 * 1.2);
        let black_point = -s.get(&p::BLACKS) as f64 / 100.0 * 0.04;
        let n = s.profile.contrast();
        let mn = SCENE_GREY.powf(n);
        let cn = white.powf(n);
        let k = mn * cn * (DISPLAY_GREY - 1.0) / (mn - DISPLAY_GREY * cn);
        let a = (cn + k) / cn;
        let contrast = s.get(&p::CONTRAST) as f64 / 100.0 * 0.6;
        let regions = [p::CURVE_SHADOWS, p::CURVE_DARKS, p::CURVE_LIGHTS, p::CURVE_HIGHLIGHTS].map(|r| s.get(&r) as f64 / 100.0);
        let master = (!s.curves[0].is_identity()).then(|| s.curves[0].table(1024));
        let mut prev: f64 = 0.0;
        for i in 0..=TONE_SIZE {
            let t = i as f64 / TONE_SIZE as f64;
            let x = (t * t * TONE_MAX - self.floor).max(0.0);
            let mut y = if let Some(curve) = &self.tone_curve {
                profile_curve(curve, soft_clip(x / white))
            } else if self.raw {
                let xn = x.powf(n);
                a * xn / (xn + k)
            } else {
                x / white
            };
            y = (y - black_point) / (1.0 - black_point);
            let mut v = encode_srgb(y.clamp(0.0, 1.0));
            v += contrast * (0.5 - 0.5 * (PI * v).cos() - v);
            for (r, amount) in regions.iter().enumerate() {
                if *amount == 0.0 {
                    continue;
                }
                let c = 0.125 + 0.25 * r as f64;
                let d = (v - c) / 0.25;
                if d.abs() < 1.0 {
                    v += amount * 0.1 * 0.5 * (1.0 + (PI * d).cos());
                }
            }
            v = v.clamp(0.0, 1.0);
            if let Some(m) = &master {
                v = lerp_table(m, v as f32) as f64;
            }
            v = v.max(prev);
            prev = v;
            self.tone[i] = v as f32;
        }
    }

    /// White balance, colour matrix and exposure: camera RGB to linear sRGB, in place.
    pub fn linear(&self, img: &mut LinearImage) {
        let m = self.matrix;
        img.r.par_iter_mut().zip(img.g.par_iter_mut()).zip(img.b.par_iter_mut()).for_each(|((r, g), b)| {
            let (cr, cg, cb) = (*r, *g, *b);
            *r = (m[0] * cr + m[1] * cg + m[2] * cb).max(0.0);
            *g = (m[3] * cr + m[4] * cg + m[5] * cb).max(0.0);
            *b = (m[6] * cr + m[7] * cg + m[8] * cb).max(0.0);
        });
    }

    pub fn needs_guide(&self) -> bool {
        self.needs_guide
    }

    /// Builds the guide this look needs from the preview, or None when no large-radius adjustment
    /// is on. It's made at exposure 0, so moving Exposure doesn't rebuild it.
    pub fn guide(&self, source: &Source, geom: &Geometry) -> Option<Guide> {
        if !self.needs_guide {
            return None;
        }
        let flat = Look::new(source, &self.s.clone().with(&p::EXPOSURE, 0.0));
        let mut img = sample(&source.image, geom, Some(GUIDE_EDGE), None);
        flat.linear(&mut img);
        let (w, h) = (img.width, img.height);
        let n = w * h;
        let lum: Vec<f32> = (0..n).map(|i| log2(luma(img.r[i], img.g[i], img.b[i]))).collect();
        let mut dark: Vec<f32> = (0..n).map(|i| img.r[i].min(img.g[i]).min(img.b[i])).collect();
        let diag = (w as f64).hypot(h as f64);
        let base = image::guided(&lum, w, h, ((diag * 0.035) as usize).max(2), 0.35);
        let medium = image::guided(&lum, w, h, ((diag * 0.012) as usize).max(2), 0.04);
        // Airlight: the colour of the haziest (brightest dark-channel) pixels.
        let mut order: Vec<usize> = (0..n).collect();
        order.sort_by(|&a, &b| dark[b].total_cmp(&dark[a]));
        order.truncate((n / 500).max(1));
        let mut air = [0f32; 3];
        for &i in &order {
            air[0] += img.r[i];
            air[1] += img.g[i];
            air[2] += img.b[i];
        }
        for a in &mut air {
            *a = (*a / order.len() as f32).max(0.05);
        }
        image::blur(&mut dark, w, h, (diag * 0.012) as f32);
        Some(Guide { width: w, height: h, base, medium, dark, air })
    }

    fn noise(&self) -> f32 {
        self.v(&p::NOISE) / 100.0
    }

    fn color_noise(&self) -> f32 {
        self.v(&p::COLOR_NOISE) / 100.0
    }

    fn sharpen_amount(&self) -> f32 {
        self.v(&p::SHARPEN) / 100.0 + if self.raw { 0.25 } else { 0.0 }
    }

    fn noise_radius(&self, scale: f64) -> usize {
        ((scale * (1.0 + self.noise() as f64 * 3.0)) as usize).max(1)
    }

    fn color_noise_radius(&self, scale: f64) -> f32 {
        ((scale * (2.0 + self.color_noise() as f64 * 10.0)) as f32).max(1.0)
    }

    fn texture_radius(&self, scale: f64) -> f32 {
        ((scale * 3.0) as f32).max(0.7)
    }

    fn sharpen_radius(&self, scale: f64) -> f32 {
        ((self.v(&p::SHARPEN_RADIUS) as f64 * scale) as f32).max(0.6)
    }

    /// How far (in output pixels) the scene must reach past a rendered tile: [`prepare`]'s filters
    /// plus the shader's neighbour taps.
    pub fn margin(&self, scale: f64) -> usize {
        let mut prep = 0.0;
        if self.noise() > 0.0 {
            prep += self.noise_radius(scale) as f64 * 2.0;
        }
        if self.color_noise() > 0.0 {
            prep += self.color_noise_radius(scale) as f64 * 3.0;
        }
        if self.v(&p::DEFRINGE) > 0.0 {
            prep += 2.0;
        }
        let mut taps: f64 = 0.0;
        if self.v(&p::TEXTURE) != 0.0 {
            taps = taps.max(self.texture_radius(scale) as f64 * 3.0);
        }
        if self.sharpen_amount() > 0.0 {
            taps = taps.max(self.sharpen_radius(scale) as f64 * 3.0 + 1.0);
        }
        (prep + taps).ceil() as usize + 2
    }

    /// Whether [`prepare`] changes anything: noise reduction or defringe is on.
    pub fn prepares(&self) -> bool {
        self.noise() > 0.0 || self.color_noise() > 0.0 || self.v(&p::DEFRINGE) > 0.0
    }

    /// What [`prepare`] depends on besides the pixels and scale, for caching prepared scenes.
    pub fn prepare_key(&self) -> [u32; 4] {
        [self.noise(), self.v(&p::NOISE_DETAIL), self.color_noise(), self.v(&p::DEFRINGE)].map(f32::to_bits)
    }

    /// Noise reduction and defringe on scene pixels (camera RGB, in place) before they go to the
    /// GPU: they need neighbourhoods the shader can't afford per pixel. They work in linear sRGB at
    /// the as-shot balance and exposure 0, so moving those sliders doesn't redo them.
    pub fn prepare(&self, img: &mut LinearImage, scale: f64) {
        if !self.prepares() {
            return;
        }
        let (w, h) = (img.width, img.height);
        let n = w * h;
        convert(img, &self.scene_matrix, true);
        let color_noise = self.color_noise();
        if color_noise > 0.0 {
            let mut cr = vec![0.0; n];
            let mut cb = vec![0.0; n];
            for i in 0..n {
                let l = luma(img.r[i], img.g[i], img.b[i]);
                cr[i] = img.r[i] - l;
                cb[i] = img.b[i] - l;
            }
            let rad = self.color_noise_radius(scale);
            image::blur(&mut cr, w, h, rad);
            image::blur(&mut cb, w, h, rad);
            for i in 0..n {
                let l = luma(img.r[i], img.g[i], img.b[i]);
                let nr = (l + cr[i]).max(0.0);
                let nb = (l + cb[i]).max(0.0);
                img.r[i] = nr;
                img.b[i] = nb;
                img.g[i] = ((l - 0.2126 * nr - 0.0722 * nb) / 0.7152).max(0.0);
            }
        }
        let noise = self.noise();
        if noise > 0.0 {
            let pv: Vec<f32> = (0..n).map(|i| luma(img.r[i], img.g[i], img.b[i]).sqrt()).collect();
            let eps = (noise * noise * 0.004 * (1.6 - self.v(&p::NOISE_DETAIL) / 100.0)).max(1e-6);
            let qv = image::guided(&pv, w, h, self.noise_radius(scale), eps);
            for i in 0..n {
                let before = pv[i] * pv[i];
                if before > 1e-6 {
                    let f = qv[i] * qv[i] / before;
                    img.r[i] *= f;
                    img.g[i] *= f;
                    img.b[i] *= f;
                }
            }
        }
        let defringe = self.v(&p::DEFRINGE) / 100.0;
        if defringe > 0.0 {
            defringe_pass(img, defringe);
        }
        convert(img, &self.scene_inverse, false);
    }

    /// Everything the GPU shader needs for these settings at render `scale` (output pixels per
    /// full-size pixel).
    pub fn shader_params(&self, scale: f64) -> ShaderParams {
        let s = &self.s;
        let g = |param: &Param| s.get(param);
        let mono = s.profile == Profile::Monochrome;
        let sat = (if self.raw || mono { s.profile.saturation() } else { 1.0 }) * (1.0 + g(&p::SATURATION) / 100.0);
        let mix_hue = p::MIX_HUE.map(|q| g(&q) / 100.0);
        let mix_sat = p::MIX_SAT.map(|q| g(&q) / 100.0);
        let mix_lum = p::MIX_LUM.map(|q| g(&q) / 100.0);
        let has_mixer = mix_hue.iter().chain(&mix_sat).chain(&mix_lum).any(|v| *v != 0.0);
        let offsets: [[f32; 3]; 4] = std::array::from_fn(|z| tint_offset(g(&p::GRADE_HUE[z]), g(&p::GRADE_SAT[z]) / 100.0));
        let grade_lum = p::GRADE_LUM.map(|q| g(&q) / 100.0);
        let has_grading = offsets.iter().flatten().any(|v| *v != 0.0) || grade_lum.iter().any(|v| *v != 0.0);
        let guide = |v: f32| if self.needs_guide { v } else { 0.0 };
        let sharpen = self.sharpen_amount();
        let detail_scale = |min: f32| ((scale * 2.5) as f32).max(min).min(1.0);
        ShaderParams {
            matrix: self.matrix,
            tone: self.tone.clone(),
            curves: self.rgb_curves.clone(),
            ev: g(&p::EXPOSURE),
            shadows: guide(g(&p::SHADOWS) / 100.0),
            highlights: guide(g(&p::HIGHLIGHTS) / 100.0),
            clarity: guide(g(&p::CLARITY) / 100.0),
            dehaze: g(&p::DEHAZE) / 100.0,
            texture: g(&p::TEXTURE) / 100.0 * 0.9 * detail_scale(0.4),
            texture_radius: self.texture_radius(scale),
            mono,
            sat,
            vib: g(&p::VIBRANCE) / 100.0,
            has_mixer,
            mix_hue,
            mix_sat,
            mix_lum,
            band_centers: p::MIX_BANDS.map(|b| b.1),
            has_grading,
            grade_offset: std::array::from_fn(|i| offsets[i / 3][i % 3]),
            grade_lum,
            blending: g(&p::GRADE_BLENDING) / 100.0,
            balance: g(&p::GRADE_BALANCE) / 100.0,
            vignette: g(&p::VIGNETTE) / 100.0,
            vig_mid: g(&p::VIGNETTE_MIDPOINT) / 100.0,
            vig_feather: g(&p::VIGNETTE_FEATHER) / 100.0,
            vig_round: g(&p::VIGNETTE_ROUNDNESS) / 100.0,
            vig_highlights: g(&p::VIGNETTE_HIGHLIGHTS) / 100.0,
            sharpen: if sharpen > 0.0 { sharpen * 1.4 * detail_scale(0.3) } else { 0.0 },
            sharpen_radius: self.sharpen_radius(scale),
            sharpen_limit: 0.02 + 0.25 * g(&p::SHARPEN_DETAIL) / 100.0,
            sharpen_masking: g(&p::SHARPEN_MASKING) / 100.0 * 0.06,
            grain: g(&p::GRAIN) / 100.0,
            grain_cell: (1.0 + g(&p::GRAIN_SIZE) as f64 / 100.0 * 4.0) as f32,
            grain_rough: g(&p::GRAIN_ROUGHNESS) / 100.0,
            scale: scale as f32,
            mask_count: self.mask_count,
            mask_total: self.mask_total,
            masks: self.masks,
            source_size: [self.full.0 as f32, self.full.1 as f32],
            uses_subject: s.masks.iter().take(MASK_MAX).any(|m| m.uses_subject()),
            uses_sky: s.masks.iter().take(MASK_MAX).any(|m| m.uses_sky()),
            uses_depth: s.masks.iter().take(MASK_MAX).any(|m| m.kind == MaskKind::Depth),
            lut_amount: if s.lut.is_some() { g(&p::LUT_AMOUNT) / 100.0 } else { 0.0 },
        }
    }
}

/// Hue (degrees) and strength to an RGB offset with no luminance, for colour grading.
fn tint_offset(hue: f32, amount: f32) -> [f32; 3] {
    if amount == 0.0 {
        return [0.0; 3];
    }
    let rgb = hsv_to_rgb(hue, 1.0, 1.0);
    let l = luma(rgb[0], rgb[1], rgb[2]);
    rgb.map(|c| (c - l) * amount * 0.3)
}

/// The masks flattened as the shader reads them: [`MASK_STRIDE`] floats each, shapes in
/// full-size pixels, then the sliders in [`local::SHADER_ORDER`]. Masks with a map of their own
/// (kind 5) carry its slot in the maps texture instead of a position, see [`map_slots`]; Depth
/// masks (kind 6) their range of nearness.
fn mask_params(s: &Settings, w: f64, h: f64) -> ([f32; MASK_MAX * MASK_STRIDE], usize, [bool; 9]) {
    let mut out = [0f32; MASK_MAX * MASK_STRIDE];
    let mut used = [false; 9];
    let count = s.masks.len().min(MASK_MAX);
    let slots = map_slots(s);
    for (k, m) in s.masks.iter().take(count).enumerate() {
        let o = k * MASK_STRIDE;
        out[o] = match m.kind {
            MaskKind::Linear => 1.0,
            MaskKind::Radial => 2.0,
            MaskKind::Subject => 3.0,
            MaskKind::Sky => 4.0,
            MaskKind::Brush | MaskKind::Object => 5.0,
            MaskKind::Depth => 6.0,
        };
        out[o + 1] = (m.x0 as f64 * w) as f32;
        out[o + 2] = (m.y0 as f64 * h) as f32;
        out[o + 3] = (m.x1 as f64 * w) as f32;
        out[o + 4] = (m.y1 as f64 * h) as f32;
        if let Some(slot) = slots[k] {
            out[o + 1] = slot as f32;
        }
        if m.kind == MaskKind::Depth {
            out[o + 1] = m.x0.min(m.x1);
            out[o + 2] = m.x0.max(m.x1);
        }
        if m.kind == MaskKind::Radial {
            out[o + 3] = out[o + 3].max(1.0);
            out[o + 4] = out[o + 4].max(1.0);
        }
        let a = m.angle as f64 * PI / 180.0;
        out[o + 5] = a.cos() as f32;
        out[o + 6] = a.sin() as f32;
        out[o + 7] = (m.feather / 100.0).max(0.02);
        out[o + 8] = if m.invert { 1.0 } else { 0.0 };
        out[o + 9] = if m.exclude_subject { 1.0 } else { 0.0 };
        for (i, param) in local::SHADER_ORDER.iter().enumerate() {
            let v = m.get(param) * if i == 0 { 1.0 } else { 0.01 };
            out[o + 10 + i] = v;
            if v != 0.0 {
                used[i] = true;
            }
        }
    }
    (out, count, used)
}

/// Which slot of the maps texture each mask's own map takes (Brush and Object masks, in order).
pub fn map_slots(s: &Settings) -> [Option<usize>; MASK_MAX] {
    let mut slots = [None; MASK_MAX];
    let mut next = 0;
    for (k, m) in s.masks.iter().take(MASK_MAX).enumerate() {
        if m.kind.has_own_map() {
            slots[k] = Some(next);
            next += 1;
        }
    }
    slots
}

fn convert(img: &mut LinearImage, m: &[f32; 9], clamp: bool) {
    img.r.par_iter_mut().zip(img.g.par_iter_mut()).zip(img.b.par_iter_mut()).for_each(|((r, g), b)| {
        let (cr, cg, cb) = (*r, *g, *b);
        let mut nr = m[0] * cr + m[1] * cg + m[2] * cb;
        let mut ng = m[3] * cr + m[4] * cg + m[5] * cb;
        let mut nb = m[6] * cr + m[7] * cg + m[8] * cb;
        if clamp {
            nr = nr.max(0.0);
            ng = ng.max(0.0);
            nb = nb.max(0.0);
        }
        *r = nr;
        *g = ng;
        *b = nb;
    });
}

/// Purple and green fringes along high-contrast edges lose their colour. Edges and hues are judged
/// on sRGB-encoded values; the colour comes out linearly.
fn defringe_pass(img: &mut LinearImage, amount: f32) {
    let (w, h) = (img.width, img.height);
    let n = w * h;
    let dr: Vec<f32> = img.r.iter().map(|v| encode_f(*v)).collect();
    let dg: Vec<f32> = img.g.iter().map(|v| encode_f(*v)).collect();
    let db: Vec<f32> = img.b.iter().map(|v| encode_f(*v)).collect();
    let l: Vec<f32> = (0..n).map(|i| luma(dr[i], dg[i], db[i])).collect();
    for y in 0..h {
        for x in 0..w {
            let i = y * w + x;
            let (xl, xr) = (x.saturating_sub(1), (x + 1).min(w - 1));
            let (yu, yd) = (y.saturating_sub(1), (y + 1).min(h - 1));
            let grad = (l[y * w + xr] - l[y * w + xl]).abs() + (l[yd * w + x] - l[yu * w + x]).abs();
            if grad < 0.08 {
                continue;
            }
            let mx = dr[i].max(dg[i]).max(db[i]);
            let c = mx - dr[i].min(dg[i]).min(db[i]);
            if c < 0.03 {
                continue;
            }
            let hue = hue_of(dr[i], dg[i], db[i], mx, c);
            let fringe = (1.0 - (hue - 290.0).abs() / 45.0).max(1.0 - (hue - 110.0).abs() / 35.0).clamp(0.0, 1.0);
            let k = amount * fringe * smooth(0.08, 0.25, grad);
            if k <= 0.0 {
                continue;
            }
            let li = luma(img.r[i], img.g[i], img.b[i]);
            img.r[i] += (li - img.r[i]) * k;
            img.g[i] += (li - img.g[i]) * k;
            img.b[i] += (li - img.b[i]) * k;
        }
    }
}

/// Bilinear sample of one plane, zero well outside it.
#[inline]
fn bilinear(c: &[f32], w: usize, h: usize, x: f64, y: f64) -> f32 {
    if x < -1.0 || y < -1.0 || x > w as f64 || y > h as f64 {
        return 0.0;
    }
    let xc = x.clamp(0.0, w as f64 - 1.0);
    let yc = y.clamp(0.0, h as f64 - 1.0);
    let (x0, y0) = (xc as usize, yc as usize);
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (fx, fy) = ((xc - x0 as f64) as f32, (yc - y0 as f64) as f32);
    let a = c[y0 * w + x0] + (c[y0 * w + x1] - c[y0 * w + x0]) * fx;
    let b = c[y1 * w + x0] + (c[y1 * w + x1] - c[y1 * w + x0]) * fx;
    a + (b - a) * fy
}

/// A part of the output frame at `scale` (output pixels per full-size pixel).
#[derive(Clone, Copy, Debug)]
pub struct Region {
    pub scale: f64,
    pub x0: i64,
    pub y0: i64,
    pub width: usize,
    pub height: usize,
}

/// The output frame's size at a scale.
pub fn frame_size(geom: &Geometry, scale: f64) -> (usize, usize) {
    (((geom.out_w * scale) as usize).max(1), ((geom.out_h * scale) as usize).max(1))
}

/// The natural scale for rendering `src` through `geom`, capped so the long edge fits `max_edge`.
pub fn natural_scale(src: &LinearImage, geom: &Geometry, max_edge: Option<usize>) -> f64 {
    let natural = src.width as f64 / geom.src_w;
    match max_edge {
        None => natural,
        Some(e) => natural.min(e as f64 / geom.out_w.max(geom.out_h)),
    }
}

/// Samples an upright preview through a geometry: the whole frame at its natural scale (capped
/// by `max_edge`), or one region of it. Rows outside the frame are clamped.
pub fn sample(src: &LinearImage, geom: &Geometry, max_edge: Option<usize>, region: Option<Region>) -> LinearImage {
    let scale = region.map_or_else(|| natural_scale(src, geom, max_edge), |r| r.scale);
    let (fw, fh) = frame_size(geom, scale);
    let (w, h, ox, oy) = region.map_or((fw, fh, 0, 0), |r| (r.width, r.height, r.x0, r.y0));
    if region.is_none() && geom.is_identity && w == src.width && h == src.height {
        return src.clone();
    }
    let to_src = src.width as f64 / geom.src_w;
    let mut out = LinearImage::new(w, h);
    let rows: Vec<(Vec<f32>, Vec<f32>, Vec<f32>)> = (0..h)
        .into_par_iter()
        .map(|i| {
            let y = (oy + i as i64).clamp(0, fh as i64 - 1) as f64;
            let mut r = vec![0.0; w];
            let mut g = vec![0.0; w];
            let mut b = vec![0.0; w];
            let py = (y + 0.5) / scale;
            for x in 0..w {
                let m = geom.map((ox as f64 + x as f64 + 0.5) / scale, py);
                r[x] = bilinear(&src.r, src.width, src.height, m[0] * to_src - 0.5, m[1] * to_src - 0.5);
                g[x] = bilinear(&src.g, src.width, src.height, m[2] * to_src - 0.5, m[3] * to_src - 0.5);
                b[x] = bilinear(&src.b, src.width, src.height, m[4] * to_src - 0.5, m[5] * to_src - 0.5);
                if geom.has_vignette_correction {
                    let k = geom.vignette_gain(m[2], m[3]);
                    r[x] *= k;
                    g[x] *= k;
                    b[x] *= k;
                }
            }
            (r, g, b)
        })
        .collect();
    for (i, (r, g, b)) in rows.into_iter().enumerate() {
        out.r[i * w..(i + 1) * w].copy_from_slice(&r);
        out.g[i * w..(i + 1) * w].copy_from_slice(&g);
        out.b[i * w..(i + 1) * w].copy_from_slice(&b);
    }
    out
}

/// A full-resolution photo as the sensor saw it (not yet upright), interleaved RGB16.
pub struct FullImage {
    pub width: usize,
    pub height: usize,
    pub data: Vec<u16>,
    pub orientation: u8,
}

impl FullImage {
    /// Upright size.
    pub fn upright(&self) -> (usize, usize) {
        if self.orientation >= 5 {
            (self.height, self.width)
        } else {
            (self.width, self.height)
        }
    }

    /// Sensor position of upright pixel (x, y).
    fn sensor(&self, x: f64, y: f64) -> (f64, f64) {
        let (w, h) = (self.width as f64, self.height as f64);
        match self.orientation {
            2 => (w - x, y),
            3 => (w - x, h - y),
            4 => (x, h - y),
            5 => (y, x),
            6 => (y, h - x),
            7 => (w - y, h - x),
            8 => (w - y, x),
            _ => (x, y),
        }
    }

    fn at(&self, c: usize, x: f64, y: f64) -> f32 {
        let (w, h) = (self.width, self.height);
        if x < -1.0 || y < -1.0 || x > w as f64 || y > h as f64 {
            return 0.0;
        }
        let xc = x.clamp(0.0, w as f64 - 1.0);
        let yc = y.clamp(0.0, h as f64 - 1.0);
        let (x0, y0) = (xc as usize, yc as usize);
        let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
        let (fx, fy) = ((xc - x0 as f64) as f32, (yc - y0 as f64) as f32);
        let v = |x: usize, y: usize| self.data[(y * w + x) * 3 + c] as f32 / 65535.0;
        let a = v(x0, y0) + (v(x1, y0) - v(x0, y0)) * fx;
        let b = v(x0, y1) + (v(x1, y1) - v(x0, y1)) * fx;
        a + (b - a) * fy
    }

    /// Output rows [y0, y0 + rows) of the full-size frame through `geom` (rows outside it are
    /// clamped), all `width` columns.
    pub fn sample_rows(&self, geom: &Geometry, y0: i64, rows: usize) -> LinearImage {
        let (fw, fh) = frame_size(geom, 1.0);
        let mut out = LinearImage::new(fw, rows);
        let lines: Vec<(Vec<f32>, Vec<f32>, Vec<f32>)> = (0..rows)
            .into_par_iter()
            .map(|i| {
                let y = (y0 + i as i64).clamp(0, fh as i64 - 1) as f64 + 0.5;
                let mut r = vec![0.0; fw];
                let mut g = vec![0.0; fw];
                let mut b = vec![0.0; fw];
                for x in 0..fw {
                    let m = geom.map(x as f64 + 0.5, y);
                    let (sx, sy) = self.sensor(m[0], m[1]);
                    r[x] = self.at(0, sx - 0.5, sy - 0.5);
                    let (sx, sy) = self.sensor(m[2], m[3]);
                    g[x] = self.at(1, sx - 0.5, sy - 0.5);
                    let (sx, sy) = self.sensor(m[4], m[5]);
                    b[x] = self.at(2, sx - 0.5, sy - 0.5);
                    if geom.has_vignette_correction {
                        let k = geom.vignette_gain(m[2], m[3]);
                        r[x] *= k;
                        g[x] *= k;
                        b[x] *= k;
                    }
                }
                (r, g, b)
            })
            .collect();
        for (i, (r, g, b)) in lines.into_iter().enumerate() {
            out.r[i * fw..(i + 1) * fw].copy_from_slice(&r);
            out.g[i * fw..(i + 1) * fw].copy_from_slice(&g);
            out.b[i * fw..(i + 1) * fw].copy_from_slice(&b);
        }
        out
    }
}

/// Lightroom's Auto: sets Exposure, Contrast, Highlights, Shadows, Whites, Blacks and Vibrance
/// from the photo's brightness distribution.
pub fn auto_tone(source: &Source, current: &Settings) -> Settings {
    let mut base = current.clone();
    for q in [p::EXPOSURE, p::CONTRAST, p::HIGHLIGHTS, p::SHADOWS, p::WHITES, p::BLACKS] {
        base.set(&q, 0.0);
    }
    let geom = source.geometry(&base, false);
    let mut img = sample(&source.image, &geom, Some(400), None);
    Look::new(source, &base).linear(&mut img);
    let mut logs: Vec<f32> = (0..img.r.len()).map(|i| log2(luma(img.r[i], img.g[i], img.b[i]))).collect();
    logs.sort_by(f32::total_cmp);
    let pct = |q: f64| logs[((logs.len() - 1) as f64 * q) as usize];
    let mid = log2(SCENE_GREY as f32);
    let exposure = (mid + 0.3 - pct(0.5)).clamp(-3.0, 3.0);
    let hi = pct(0.995) + exposure;
    let lo = pct(0.005) + exposure;
    let spread = pct(0.95) - pct(0.05);
    let highlights = if hi > mid + 2.6 { -((hi - mid - 2.6) * 35.0).min(70.0) } else { 0.0 };
    let shadows = if lo < mid - 5.0 { ((mid - 5.0 - lo) * 12.0).min(50.0) } else { 0.0 };
    let whites = ((mid + 2.8 - hi) * 18.0).clamp(-40.0, 40.0);
    let blacks = ((mid - 6.0 - lo) * -6.0).clamp(-30.0, 20.0);
    let contrast = ((6.0 - spread) * 6.0).clamp(-20.0, 30.0);
    let mut out = current.clone();
    out.set(&p::EXPOSURE, (exposure * 100.0).trunc() / 100.0);
    out.set(&p::CONTRAST, contrast.trunc());
    out.set(&p::HIGHLIGHTS, highlights.trunc());
    out.set(&p::SHADOWS, shadows.trunc());
    out.set(&p::WHITES, whites.trunc());
    out.set(&p::BLACKS, blacks.trunc());
    out.set(&p::VIBRANCE, current.get(&p::VIBRANCE).max(10.0));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grey_source(raw: bool) -> Source {
        let mut img = LinearImage::new(64, 48);
        for (i, v) in img.r.iter_mut().enumerate() {
            *v = (i % 64) as f32 / 64.0;
        }
        img.g = img.r.clone();
        img.b = img.r.clone();
        Source {
            image: img,
            color: if raw { CameraColor::for_camera("ILCE-7M3") } else { CameraColor::srgb() },
            baked: [1.0, 1.0, 1.0],
            is_raw: raw,
            full_width: 640,
            full_height: 480,
            lens: None,
            baseline: 0.0,
            tone_curve: None,
            black_floor: 0.0,
        }
    }

    #[test]
    fn tone_maps_mid_grey_near_display_grey() {
        let src = grey_source(true);
        let look = Look::new(&src, &Settings::default());
        let t = (SCENE_GREY / TONE_MAX).sqrt() * TONE_SIZE as f64;
        let v = look.tone[t.round() as usize] as f64;
        assert!((v - encode_srgb(DISPLAY_GREY)).abs() < 0.02, "{v}");
        assert!(look.tone.windows(2).all(|w| w[1] >= w[0]));
    }

    #[test]
    fn jpeg_tone_is_the_srgb_curve() {
        let look = Look::new(&grey_source(false), &Settings::default());
        let t = (0.18f64 / TONE_MAX).sqrt() * TONE_SIZE as f64;
        assert!((look.tone[t.round() as usize] as f64 - encode_srgb(0.18)).abs() < 0.01);
    }

    #[test]
    fn guide_only_when_needed() {
        let src = grey_source(true);
        let geom = src.geometry(&Settings::default(), false);
        assert!(Look::new(&src, &Settings::default()).guide(&src, &geom).is_none());
        let s = Settings::default().with(&p::SHADOWS, 40.0);
        let g = Look::new(&src, &s).guide(&src, &geom).unwrap();
        assert_eq!((g.width, g.height), (64, 48));
    }

    #[test]
    fn auto_tone_brightens_a_dark_photo() {
        let mut src = grey_source(true);
        for v in src.image.r.iter_mut().chain(src.image.g.iter_mut()).chain(src.image.b.iter_mut()) {
            *v *= 0.05;
        }
        let s = auto_tone(&src, &Settings::default());
        assert!(s.get(&p::EXPOSURE) > 0.5);
    }
}
