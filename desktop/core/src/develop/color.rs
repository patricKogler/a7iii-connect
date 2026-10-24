//! How a camera's RGB relates to CIE XYZ, how a white balance in Kelvin and tint becomes channel
//! multipliers, and the matrix from white-balanced RGB to linear sRGB (the phone's Color.kt:
//! dcraw/LibRaw's single D65 matrix with Adobe-style Kelvin and tint).

pub type Mat3 = [f64; 9];

pub fn mul(a: &Mat3, b: &Mat3) -> Mat3 {
    std::array::from_fn(|i| {
        let (r, c) = (i / 3, i % 3);
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    })
}

pub fn apply(m: &Mat3, v: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|r| m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2])
}

pub fn invert(m: &Mat3) -> Mat3 {
    let [a, b, c, d, e, f, g, h, i] = *m;
    let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    let det = if det.abs() < 1e-12 { 1e-12 } else { det };
    [
        (e * i - f * h) / det,
        (c * h - b * i) / det,
        (b * f - c * e) / det,
        (f * g - d * i) / det,
        (a * i - c * g) / det,
        (c * d - a * f) / det,
        (d * h - e * g) / det,
        (b * g - a * h) / det,
        (a * e - b * d) / det,
    ]
}

pub fn diag(x: f64, y: f64, z: f64) -> Mat3 {
    [x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z]
}

pub const SRGB_TO_XYZ: Mat3 = [0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041];

pub const MIN_K: f64 = 2000.0;
pub const MAX_K: f64 = 25000.0;
const TINT_SCALE: f64 = 3000.0;

/// Adobe's D65 XYZ-to-camera matrices (x10000), as published in dcraw/LibRaw.
const MATRICES: [(&str, [i32; 9]); 4] = [
    ("ILCE-7M3", [7374, -2389, -551, -5435, 13162, 2519, -1006, 1795, 6552]),
    ("ILCE-7M4", [7460, -2365, -588, -5687, 13442, 2474, -624, 1156, 6584]),
    ("ILCE-7RM3", [6640, -1847, -503, -5238, 13010, 2474, -993, 1673, 6527]),
    ("ILCE-9", [6389, -1703, -378, -4562, 12265, 2587, -670, 1489, 6550]),
];

#[derive(Clone, Debug)]
pub struct CameraColor {
    xyz_to_cam: Mat3,
    cam_to_xyz: Mat3,
    /// White-balanced camera RGB to linear sRGB; maps (1,1,1) to (1,1,1).
    pub rgb_from_cam: Mat3,
}

impl CameraColor {
    pub fn new(xyz_to_cam: Mat3) -> CameraColor {
        let mut cam_rgb = mul(&xyz_to_cam, &SRGB_TO_XYZ);
        for r in 0..3 {
            let sum = cam_rgb[r * 3] + cam_rgb[r * 3 + 1] + cam_rgb[r * 3 + 2];
            for c in 0..3 {
                cam_rgb[r * 3 + c] /= sum;
            }
        }
        CameraColor { xyz_to_cam, cam_to_xyz: invert(&xyz_to_cam), rgb_from_cam: invert(&cam_rgb) }
    }

    /// For JPEGs: the "camera" is sRGB itself.
    pub fn srgb() -> CameraColor {
        CameraColor::new(invert(&SRGB_TO_XYZ))
    }

    pub fn for_camera(model: &str) -> CameraColor {
        let m = MATRICES.iter().find(|(n, _)| *n == model.trim()).unwrap_or(&MATRICES[0]).1;
        CameraColor::new(std::array::from_fn(|i| m[i] as f64 / 10000.0))
    }

    /// Channel multipliers (G = 1) that make a light of `kelvin` and `tint` neutral.
    pub fn multipliers(&self, kelvin: f64, tint: f64) -> [f64; 3] {
        let (u, v) = uv_of(kelvin, tint);
        let d = 2.0 * u - 8.0 * v + 4.0;
        let x = 3.0 * u / d;
        let y = 2.0 * v / d;
        let n = apply(&self.xyz_to_cam, [x / y, 1.0, (1.0 - x - y) / y]);
        [n[1] / n[0], 1.0, n[1] / n[2]]
    }

    /// The Kelvin and tint that `mul` (G = 1) neutralises.
    pub fn temperature_of(&self, mul: [f32; 3]) -> (f64, f64) {
        let xyz = apply(&self.cam_to_xyz, [1.0 / mul[0] as f64, 1.0 / mul[1] as f64, 1.0 / mul[2] as f64]);
        let s = xyz[0] + xyz[1] + xyz[2];
        let (x, y) = (xyz[0] / s, xyz[1] / s);
        let d = -2.0 * x + 12.0 * y + 3.0;
        let (u, v) = (4.0 * x / d, 6.0 * y / d);
        let mut best = 100.0;
        let mut best_dist = f64::MAX;
        let (mut lo, mut hi) = (1e6 / MAX_K, 1e6 / MIN_K);
        for _ in 0..4 {
            let step = (hi - lo) / 64.0;
            for k in 0..=64 {
                let mired = lo + step * k as f64;
                let (lu, lv) = locus(1e6 / mired);
                let dist = (u - lu).hypot(v - lv);
                if dist < best_dist {
                    best_dist = dist;
                    best = mired;
                }
            }
            lo = (1e6 / MAX_K).max(best - step);
            hi = (1e6 / MIN_K).min(best + step);
        }
        let kelvin = 1e6 / best;
        let (lu, lv) = locus(kelvin);
        let (nu, nv) = green_normal(kelvin);
        (kelvin, ((u - lu) * nu + (v - lv) * nv) * TINT_SCALE)
    }
}

/// CIE 1960 uv of the Planckian locus (Kim et al.'s approximation of x, y).
pub fn locus(kelvin: f64) -> (f64, f64) {
    let t = kelvin.clamp(1667.0, 25000.0);
    let (t2, t3) = (t * t, t * t * t);
    let x = if t <= 4000.0 {
        -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
    } else {
        -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
    };
    let (x2, x3) = (x * x, x * x * x);
    let y = if t <= 2222.0 {
        -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
    } else if t <= 4000.0 {
        -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
    } else {
        3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
    };
    let d = -2.0 * x + 12.0 * y + 3.0;
    (4.0 * x / d, 6.0 * y / d)
}

/// Unit normal to the locus at `kelvin`, pointing to green (+v).
fn green_normal(kelvin: f64) -> (f64, f64) {
    let m = 1e6 / kelvin;
    let (u1, v1) = locus(1e6 / (m + 1.0));
    let (u2, v2) = locus(1e6 / (m - 1.0).max(1.0));
    let (du, dv) = (u2 - u1, v2 - v1);
    let len = du.hypot(dv);
    let (mut nu, mut nv) = (-dv / len, du / len);
    if nv < 0.0 {
        nu = -nu;
        nv = -nv;
    }
    (nu, nv)
}

/// Positive tint moves the assumed light toward green, which makes the photo more magenta.
pub fn uv_of(kelvin: f64, tint: f64) -> (f64, f64) {
    let (u, v) = locus(kelvin);
    let (nu, nv) = green_normal(kelvin);
    (u + nu * tint / TINT_SCALE, v + nv * tint / TINT_SCALE)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn white_balance_round_trips() {
        let c = CameraColor::for_camera("ILCE-7M3");
        let m = c.multipliers(5200.0, 8.0);
        let (k, t) = c.temperature_of([m[0] as f32, 1.0, m[2] as f32]);
        assert!((k - 5200.0).abs() < 40.0, "{k}");
        assert!((t - 8.0).abs() < 1.5, "{t}");
    }

    #[test]
    fn srgb_matrix_keeps_white() {
        let c = CameraColor::srgb();
        let w = apply(&c.rgb_from_cam, [1.0, 1.0, 1.0]);
        for v in w {
            assert!((v - 1.0).abs() < 1e-9);
        }
    }
}
