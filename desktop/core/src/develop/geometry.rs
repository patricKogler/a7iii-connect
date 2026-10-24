//! Where each output pixel comes from: crop, 90-degree turns and flips, straighten, Geometry
//! (perspective, rotate, scale, offset) and lens corrections, composed into one inverse map from
//! output position to source position (the phone's Geometry.kt). Units are pixels of the
//! full-size upright source, so one map serves the preview and the export.

use super::params::{p, Settings};
use super::raw::LensData;
use std::f64::consts::PI;

#[derive(Clone, Debug)]
pub struct Geometry {
    pub src_w: f64,
    pub src_h: f64,
    hd: f64,
    rot90: i32,
    flip_h: bool,
    flip_v: bool,
    /// Frame = the source after 90-degree turns.
    pub frame_w: f64,
    pub frame_h: f64,
    crop: [f64; 4],
    pub out_w: f64,
    pub out_h: f64,
    cos_a: f64,
    sin_a: f64,
    vert: f64,
    horiz: f64,
    off_x: f64,
    off_y: f64,
    manual_k: f64,
    use_lens: bool,
    knots: Vec<f64>,
    dist_g: Vec<f64>,
    dist_r: Vec<f64>,
    dist_b: Vec<f64>,
    vig: Vec<f64>,
    manual_vig: f64,
    manual_vig_mid: f64,
    scale: f64,
    pub is_identity: bool,
    pub has_vignette_correction: bool,
    has_chromatic_shift: bool,
}

impl Geometry {
    /// `ignore_crop`: show the whole frame (while the crop box is being moved).
    pub fn new(s: &Settings, src_w: f64, src_h: f64, lens: Option<&LensData>, ignore_crop: bool) -> Geometry {
        let hd = (src_w / 2.0).hypot(src_h / 2.0);
        let rot90 = (s.get(&p::ROTATE_90) as i32).rem_euclid(4);
        let (frame_w, frame_h) = if rot90 % 2 == 1 { (src_h, src_w) } else { (src_w, src_h) };
        let crop = if ignore_crop {
            [0.0, 0.0, 1.0, 1.0]
        } else {
            [s.get(&p::CROP_LEFT) as f64, s.get(&p::CROP_TOP) as f64, s.get(&p::CROP_RIGHT) as f64, s.get(&p::CROP_BOTTOM) as f64]
        };
        let angle = (s.get(&p::STRAIGHTEN) + s.get(&p::ROTATE)) as f64 * PI / 180.0;
        let use_lens = lens.is_some() && s.on(&p::LENS_PROFILE);
        let n = lens.map_or(0, |l| l.distortion.len());
        let mut g = Geometry {
            src_w,
            src_h,
            hd,
            rot90,
            flip_h: s.on(&p::FLIP_H),
            flip_v: s.on(&p::FLIP_V),
            frame_w,
            frame_h,
            crop,
            out_w: ((crop[2] - crop[0]) * frame_w).max(16.0),
            out_h: ((crop[3] - crop[1]) * frame_h).max(16.0),
            cos_a: (-angle).cos(),
            sin_a: (-angle).sin(),
            vert: s.get(&p::VERTICAL) as f64 / 100.0 * 0.35,
            horiz: s.get(&p::HORIZONTAL) as f64 / 100.0 * 0.35,
            off_x: s.get(&p::OFFSET_X) as f64 / 100.0 * 0.2,
            off_y: s.get(&p::OFFSET_Y) as f64 / 100.0 * 0.2,
            manual_k: -s.get(&p::MANUAL_DISTORTION) as f64 / 100.0 * 0.2,
            use_lens,
            knots: (0..n).map(|i| (i as f64 + 0.5) / (n as f64 - 1.0)).collect(),
            dist_g: vec![0.0; n],
            dist_r: vec![0.0; n],
            dist_b: vec![0.0; n],
            vig: vec![0.0; n],
            manual_vig: s.get(&p::MANUAL_VIGNETTING) as f64 / 100.0,
            manual_vig_mid: 0.25 + s.get(&p::MANUAL_VIGNETTING_MIDPOINT) as f64 / 100.0 * 0.6,
            scale: 1.0,
            is_identity: false,
            has_vignette_correction: false,
            has_chromatic_shift: false,
        };
        if let (true, Some(lens)) = (use_lens, lens) {
            let d_amt = s.get(&p::LENS_DISTORTION) as f64 / 100.0;
            let v_amt = s.get(&p::LENS_VIGNETTING) as f64 / 100.0;
            let ca = s.on(&p::REMOVE_CA);
            for i in 0..n {
                g.dist_g[i] = d_amt * lens.distortion[i] as f64 * 2f64.powi(-14) + 1.0;
                g.dist_r[i] = g.dist_g[i] * if ca { lens.ca_red[i] as f64 * 2f64.powi(-21) + 1.0 } else { 1.0 };
                g.dist_b[i] = g.dist_g[i] * if ca { lens.ca_blue[i] as f64 * 2f64.powi(-21) + 1.0 } else { 1.0 };
                g.vig[i] = 2f64.powf(0.5 - 2f64.powf(v_amt * lens.vignetting[i] as f64 * 2f64.powi(-13) - 1.0));
            }
        }
        // Scale that keeps the corners filled after straightening and lens correction.
        let a = angle.abs();
        let rot = ((src_w * a.cos() + src_h * a.sin()) / src_w).max((src_w * a.sin() + src_h * a.cos()) / src_h);
        let mut lens_scale: f64 = 1.0;
        if use_lens || g.manual_k != 0.0 {
            for r in [1.0, src_w / 2.0 / hd, src_h / 2.0 / hd] {
                lens_scale = lens_scale.max(g.radial(r));
            }
        }
        g.scale = s.get(&p::SCALE) as f64 / 100.0 * rot * lens_scale;
        g.is_identity = rot90 == 0
            && !g.flip_h
            && !g.flip_v
            && crop == [0.0, 0.0, 1.0, 1.0]
            && angle == 0.0
            && g.vert == 0.0
            && g.horiz == 0.0
            && g.off_x == 0.0
            && g.off_y == 0.0
            && g.manual_k == 0.0
            && !use_lens
            && s.get(&p::SCALE) == 100.0;
        g.has_vignette_correction = use_lens || g.manual_vig != 0.0;
        g.has_chromatic_shift = use_lens && s.on(&p::REMOVE_CA);
        g
    }

    fn spline(&self, table: &[f64], r: f64) -> f64 {
        let n = self.knots.len();
        if n == 0 {
            return 1.0;
        }
        if r <= self.knots[0] {
            return table[0];
        }
        for i in 1..n {
            if r <= self.knots[i] {
                let t = (r - self.knots[i - 1]) / (self.knots[i] - self.knots[i - 1]);
                return table[i - 1] + (table[i] - table[i - 1]) * t;
            }
        }
        table[n - 1]
    }

    fn radial(&self, r: f64) -> f64 {
        (if self.use_lens { self.spline(&self.dist_g, r) } else { 1.0 }) * (1.0 + self.manual_k * r * r)
    }

    /// Maps output pixel (ox, oy) to source positions for red, green and blue (they differ only
    /// with chromatic-aberration correction): [rx, ry, gx, gy, bx, by].
    pub fn map(&self, ox: f64, oy: f64) -> [f64; 6] {
        let (sw, sh) = (self.src_w, self.src_h);
        let mut fx = self.crop[0] * self.frame_w + ox;
        let mut fy = self.crop[1] * self.frame_h + oy;
        if self.flip_h {
            fx = self.frame_w - fx;
        }
        if self.flip_v {
            fy = self.frame_h - fy;
        }
        let (x, y) = match self.rot90 {
            1 => (fy, sh - fx),
            2 => (sw - fx, sh - fy),
            3 => (sw - fy, fx),
            _ => (fx, fy),
        };
        let mut qx = (x - sw / 2.0) / self.hd;
        let mut qy = (y - sh / 2.0) / self.hd;
        let rx = qx * self.cos_a - qy * self.sin_a;
        let ry = qx * self.sin_a + qy * self.cos_a;
        qx = rx / self.scale - self.off_x;
        qy = ry / self.scale - self.off_y;
        let w = 1.0 - self.vert * qy - self.horiz * qx;
        qx /= w;
        qy /= w;
        let r = (qx * qx + qy * qy).sqrt();
        let m = 1.0 + self.manual_k * r * r;
        let at = |k: f64| (qx * k * self.hd + sw / 2.0, qy * k * self.hd + sh / 2.0);
        let g = if self.use_lens { self.spline(&self.dist_g, r) * m } else { m };
        let (gx, gy) = at(g);
        if self.has_chromatic_shift {
            let (rx, ry) = at(self.spline(&self.dist_r, r) * m);
            let (bx, by) = at(self.spline(&self.dist_b, r) * m);
            [rx, ry, gx, gy, bx, by]
        } else {
            [gx, gy, gx, gy, gx, gy]
        }
    }

    /// The output position that [`map`](Self::map) sends to source pixel (sx, sy), by Newton
    /// steps (for drawing mask handles).
    pub fn unmap(&self, sx: f64, sy: f64) -> (f64, f64) {
        let (mut x, mut y) = (self.out_w / 2.0, self.out_h / 2.0);
        for _ in 0..30 {
            let m = self.map(x, y);
            let (fx, fy) = (m[2] - sx, m[3] - sy);
            if fx.abs() + fy.abs() < 1e-4 {
                break;
            }
            let mx = self.map(x + 1.0, y);
            let (a, c) = (mx[2] - sx - fx, mx[3] - sy - fy);
            let my = self.map(x, y + 1.0);
            let (b, d) = (my[2] - sx - fx, my[3] - sy - fy);
            let det = a * d - b * c;
            if det.abs() < 1e-12 {
                break;
            }
            x -= (d * fx - b * fy) / det;
            y -= (a * fy - c * fx) / det;
        }
        (x, y)
    }

    /// Brightness gain that undoes lens (and manual) vignetting at a source pixel.
    pub fn vignette_gain(&self, sx: f64, sy: f64) -> f32 {
        let r = (sx - self.src_w / 2.0).hypot(sy - self.src_h / 2.0) / self.hd;
        let mut g = 1.0;
        if self.use_lens {
            g /= self.spline(&self.vig, r).max(1e-4);
        }
        if self.manual_vig != 0.0 {
            let t = (r / self.manual_vig_mid).min(1.6);
            g *= 1.0 + self.manual_vig * 0.8 * t * t * t * t / 4.0;
        }
        g as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_edit_maps_straight_through() {
        let g = Geometry::new(&Settings::default(), 600.0, 400.0, None, false);
        assert!(g.is_identity);
        let m = g.map(100.5, 30.5);
        assert!((m[2] - 100.5).abs() < 1e-9 && (m[3] - 30.5).abs() < 1e-9);
    }

    #[test]
    fn crop_and_turn_round_trip() {
        let s = Settings::default().with(&p::ROTATE_90, 1.0).with(&p::CROP_LEFT, 0.1).with(&p::STRAIGHTEN, 3.0);
        let g = Geometry::new(&s, 600.0, 400.0, None, false);
        assert_eq!(g.frame_w, 400.0);
        let m = g.map(50.0, 60.0);
        let (x, y) = g.unmap(m[2], m[3]);
        assert!((x - 50.0).abs() < 1e-3 && (y - 60.0).abs() < 1e-3);
    }
}
