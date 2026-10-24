//! Pictures in and out of the networks: the photo as a model expects to see it, resizing, the
//! planar layouts tensors use, and running a session on plain vectors.

use crate::develop::image::box_blur;
use crate::develop::look::{lerp_table, Look, Source, TONE_MAX};
use crate::develop::Settings;
use ort::session::Session;
use ort::value::Tensor;
use rayon::prelude::*;

/// sRGB-encoded pixels, 0..1, interleaved RGB.
#[derive(Clone, Debug)]
pub struct Rgb {
    pub width: usize,
    pub height: usize,
    pub data: Vec<f32>,
}

impl Rgb {
    pub fn at(&self, x: usize, y: usize) -> [f32; 3] {
        let i = (y * self.width + x) * 3;
        [self.data[i], self.data[i + 1], self.data[i + 2]]
    }

    pub fn luminance(&self) -> Vec<f32> {
        self.data.chunks_exact(3).map(|c| 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]).collect()
    }

    /// Bilinear resize; shrinking first averages boxes so detail doesn't alias.
    pub fn resized(&self, w: usize, h: usize) -> Rgb {
        let planes: Vec<Vec<f32>> = (0..3).map(|c| self.data.iter().skip(c).step_by(3).copied().collect()).collect();
        let out: Vec<Vec<f32>> = planes.iter().map(|p| resize_map(p, self.width, self.height, w, h)).collect();
        Rgb { width: w, height: h, data: (0..w * h).flat_map(|i| [out[0][i], out[1][i], out[2][i]]).collect() }
    }

    /// From 8-bit sRGB bytes, 3 a pixel.
    pub fn from_bytes(rgb: &[u8], width: usize, height: usize) -> Rgb {
        Rgb { width, height, data: rgb.iter().map(|&v| v as f32 / 255.0).collect() }
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        self.data.iter().map(|v| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8).collect()
    }

    /// Channels first (C, H, W), each channel as (v - mean) / std.
    pub fn planar(&self, mean: [f32; 3], std: [f32; 3]) -> Vec<f32> {
        let n = self.width * self.height;
        let mut out = vec![0.0; 3 * n];
        for c in 0..3 {
            for i in 0..n {
                out[c * n + i] = (self.data[i * 3 + c] - mean[c]) / std[c];
            }
        }
        out
    }
}

/// The photo upright with the default look (no edits): what the finders look at, as the phone
/// does. At the preview's size, at most `max_edge` on the long side.
pub fn display(source: &Source, max_edge: usize) -> Rgb {
    let mut img = source.image.downscaled(max_edge);
    let look = Look::new(source, &Settings::default());
    look.linear(&mut img);
    let tone = &look.tone;
    let t = |v: f32| lerp_table(tone, (v / TONE_MAX as f32).max(0.0).sqrt());
    let n = img.width * img.height;
    let data: Vec<f32> = (0..n).into_par_iter().flat_map_iter(|i| [t(img.r[i]), t(img.g[i]), t(img.b[i])]).collect();
    Rgb { width: img.width, height: img.height, data }
}

/// Bilinear resize of one channel; shrinking averages boxes first.
pub fn resize_map(src: &[f32], sw: usize, sh: usize, dw: usize, dh: usize) -> Vec<f32> {
    let k = (sw / dw.max(1)).min(sh / dh.max(1));
    let pre;
    let src = if k >= 2 {
        let mut b = vec![0.0; src.len()];
        box_blur(src, &mut b, sw, sh, k / 2);
        pre = b;
        &pre[..]
    } else {
        src
    };
    let (fx, fy) = (sw as f32 / dw as f32, sh as f32 / dh as f32);
    (0..dh)
        .into_par_iter()
        .flat_map_iter(|y| {
            let sy = ((y as f32 + 0.5) * fy - 0.5).clamp(0.0, (sh - 1) as f32);
            let (y0, ty) = (sy as usize, sy.fract());
            let y1 = (y0 + 1).min(sh - 1);
            (0..dw).map(move |x| {
                let sx = ((x as f32 + 0.5) * fx - 0.5).clamp(0.0, (sw - 1) as f32);
                let (x0, tx) = (sx as usize, sx.fract());
                let x1 = (x0 + 1).min(sw - 1);
                let a = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx;
                let b = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx;
                a + (b - a) * ty
            })
        })
        .collect()
}

/// The guided filter (He et al.) with a separate guide, the phone's Filters.guidedBy: smooths
/// `src` but makes its edges follow those of `guide`.
pub fn guided_by(guide: &[f32], src: &[f32], w: usize, h: usize, r: usize, eps: f32) -> Vec<f32> {
    let n = w * h;
    let blur = |v: &[f32]| {
        let mut o = vec![0.0; n];
        box_blur(v, &mut o, w, h, r);
        o
    };
    let mi = blur(guide);
    let mp = blur(src);
    let ip = blur(&(0..n).map(|i| guide[i] * src[i]).collect::<Vec<_>>());
    let ii = blur(&(0..n).map(|i| guide[i] * guide[i]).collect::<Vec<_>>());
    let a: Vec<f32> = (0..n).map(|i| (ip[i] - mi[i] * mp[i]) / (ii[i] - mi[i] * mi[i] + eps)).collect();
    let b: Vec<f32> = (0..n).map(|i| mp[i] - a[i] * mi[i]).collect();
    let (a, b) = (blur(&a), blur(&b));
    (0..n).map(|i| a[i] * guide[i] + b[i]).collect()
}

/// An input to [`run`]: name, shape and values.
pub enum Input<'a> {
    F32(&'a str, Vec<i64>, Vec<f32>),
    I32(&'a str, Vec<i64>, Vec<i32>),
    I64(&'a str, Vec<i64>, Vec<i64>),
}

/// A model's outputs, each as (shape, values).
pub type Outputs = Vec<(Vec<i64>, Vec<f32>)>;

/// Runs `session` on plain vectors and hands back every output.
pub fn run(session: &mut Session, inputs: Vec<Input>) -> Result<Outputs, String> {
    let mut values: Vec<(std::borrow::Cow<str>, ort::session::SessionInputValue)> = Vec::new();
    for i in inputs {
        let (name, v): (&str, ort::value::DynValue) = match i {
            Input::F32(n, s, d) => (n, Tensor::from_array((s, d)).map_err(|e| e.to_string())?.into_dyn()),
            Input::I32(n, s, d) => (n, Tensor::from_array((s, d)).map_err(|e| e.to_string())?.into_dyn()),
            Input::I64(n, s, d) => (n, Tensor::from_array((s, d)).map_err(|e| e.to_string())?.into_dyn()),
        };
        values.push((name.to_string().into(), v.into()));
    }
    let outputs = session.run(values).map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for (_, v) in outputs.iter() {
        let (shape, data) = v.try_extract_tensor::<f32>().map_err(|e| e.to_string())?;
        out.push((shape.iter().copied().collect(), data.to_vec()));
    }
    Ok(out)
}

pub fn sigmoid(v: f32) -> f32 {
    1.0 / (1.0 + (-v).exp())
}
