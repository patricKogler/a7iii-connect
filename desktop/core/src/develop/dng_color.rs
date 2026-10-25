//! A DNG's colour as the spec describes it: up to two XYZ-to-camera matrices, each for one light,
//! mixed by the inverse of the shot's colour temperature, and the as-shot white given either as a
//! camera neutral or as a CIE xy white. Mirrors the phone's DngColor.kt.

use super::color::{self, CameraColor, Mat3, MAX_K, MIN_K};

pub struct Calibration {
    pub matrix: Option<Mat3>,
    pub calibration: Option<Mat3>,
    pub illuminant: Option<i64>,
}

const D65_K: f64 = 6504.0;
const FALLBACK: [f32; 3] = [2.0, 1.0, 1.6];

/// EXIF LightSource codes to Kelvin.
fn kelvin_of(code: i64) -> Option<f64> {
    Some(match code {
        1 | 4 | 9 => 5500.0,
        2 => 4200.0,
        3 => 2850.0,
        10 => 6500.0,
        11 => 7500.0,
        12 => 6430.0,
        13 => 5000.0,
        14 => 4150.0,
        15 => 3525.0,
        17 => 2856.0,
        18 => 4874.0,
        19 => 6774.0,
        20 => 5503.0,
        21 => D65_K,
        22 => 7504.0,
        23 => 5003.0,
        24 => 3200.0,
        _ => return None,
    })
}

/// Correlated colour temperature of a CIE xy white (McCamy), kept to a sensible range.
pub fn cct(x: f64, y: f64) -> f64 {
    let n = (x - 0.3320) / (0.1858 - y);
    (449.0 * n.powi(3) + 3525.0 * n * n + 6823.3 * n + 5520.33).clamp(MIN_K, MAX_K)
}

fn multipliers(n: [f64; 3]) -> [f32; 3] {
    [(n[1] / n[0]) as f32, 1.0, (n[1] / n[2]) as f32]
}

/// The XYZ-to-camera matrix for this shot and its as-shot multipliers (R, G, B with G = 1).
pub fn resolve(
    c1: &Calibration,
    c2: &Calibration,
    analog_balance: Option<[f64; 3]>,
    neutral: Option<[f64; 3]>,
    white_xy: Option<[f64; 2]>,
) -> (Option<Mat3>, [f32; 3]) {
    let one = |c: &Calibration| c.matrix.map(|m| c.calibration.map_or(m, |cc| color::mul(&cc, &m)));
    let (m1, m2) = (one(c1), one(c2));
    let (k1, k2) = (c1.illuminant.and_then(kelvin_of), c2.illuminant.and_then(kelvin_of));

    // Linear in mireds between the two calibrated lights.
    let xyz_to_cam = |kelvin: f64| -> Option<Mat3> {
        let m = match (m1, m2, k1, k2) {
            (None, m, _, _) => m,
            (Some(a), Some(b), Some(ka), Some(kb)) if ka != kb => {
                let (lo, hi) = (ka.min(kb), ka.max(kb));
                let t = kelvin.clamp(lo, hi);
                let w = (1.0 / t - 1.0 / hi) / (1.0 / lo - 1.0 / hi);
                let (warm, cool) = if ka < kb { (a, b) } else { (b, a) };
                Some(std::array::from_fn(|i| warm[i] * w + cool[i] * (1.0 - w)))
            }
            (Some(a), Some(b), _, kb) => Some(if kb == Some(D65_K) { b } else { a }),
            (Some(a), None, _, _) => Some(a),
        }?;
        Some(analog_balance.map_or(m, |ab| color::mul(&color::diag(ab[0], ab[1], ab[2]), &m)))
    };

    if let Some(n) = neutral {
        // The light the neutral stands for decides the matrix, which decides the light: a few rounds settle it.
        let Some(mut m) = xyz_to_cam(D65_K) else { return (None, multipliers(n)) };
        for _ in 0..4 {
            let xyz = color::apply(&color::invert(&m), n);
            let s = xyz[0] + xyz[1] + xyz[2];
            if s > 0.0 {
                m = xyz_to_cam(cct(xyz[0] / s, xyz[1] / s)).unwrap_or(m);
            }
        }
        return (Some(m), multipliers(n));
    }
    if let Some([x, y]) = white_xy {
        let Some(m) = xyz_to_cam(cct(x, y)) else { return (None, FALLBACK) };
        let n = color::apply(&m, [x / y, 1.0, (1.0 - x - y) / y]);
        if n.iter().all(|&v| v > 0.0) {
            return (Some(m), multipliers(n));
        }
    }
    match xyz_to_cam(D65_K) {
        Some(m) => (Some(m), CameraColor::new(m).multipliers(6500.0, 0.0).map(|v| v as f32)),
        None => (None, FALLBACK),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // A Pixel 8a shot under tungsten light.
    const CM1: Mat3 = [1.1599, -0.6349, 0.0242, -0.3076, 1.1736, 0.1491, 0.0019, 0.1402, 0.6484];
    const CM2: Mat3 = [0.8395, -0.1991, -0.1213, -0.585, 1.4801, 0.1018, -0.188, 0.4021, 0.4593];

    fn pixel() -> (Calibration, Calibration) {
        (
            Calibration { matrix: Some(CM1), calibration: Some(color::diag(0.9957, 1.0, 0.9745)), illuminant: Some(17) },
            Calibration { matrix: Some(CM2), calibration: Some(color::diag(0.9959, 1.0, 0.9816)), illuminant: Some(21) },
        )
    }

    #[test]
    fn a_pixels_white_xy_gives_its_tungsten_balance() {
        let (a, d65) = pixel();
        let (m, mul) = resolve(&a, &d65, Some([1.0; 3]), None, Some([0.426327, 0.404567]));
        assert!((0.6..1.6).contains(&mul[0]) && mul[2] > 1.8, "{mul:?}");
        let (kelvin, _) = CameraColor::new(m.unwrap()).temperature_of(mul);
        assert!((kelvin - 2950.0).abs() < 250.0, "{kelvin}");
    }

    #[test]
    fn a_neutral_and_its_xy_agree() {
        let (a, d65) = pixel();
        let (x, y) = (0.426327, 0.404567);
        let (m, mul) = resolve(&a, &d65, None, None, Some([x, y]));
        let n = color::apply(&m.unwrap(), [x / y, 1.0, (1.0 - x - y) / y]);
        let (_, again) = resolve(&a, &d65, None, Some(n), None);
        for c in 0..3 {
            assert!((mul[c] - again[c]).abs() < 0.01, "{mul:?} {again:?}");
        }
    }

    #[test]
    fn daylight_uses_the_d65_matrix() {
        let (a, d65) = pixel();
        let (m, _) = resolve(&a, &d65, None, None, Some([0.3127, 0.3290]));
        let want = color::mul(&color::diag(0.9959, 1.0, 0.9816), &CM2);
        assert!(m.unwrap().iter().zip(want).all(|(a, b)| (a - b).abs() < 0.02));
    }
}
