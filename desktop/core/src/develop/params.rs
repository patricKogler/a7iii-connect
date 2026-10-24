//! One photo's edit, in the phone's format (`v=2`, one `key=value` per line), so an edit made on
//! either side opens unchanged on the other. Names, ranges and defaults are the phone's
//! (engine/Params.kt and engine/Mask.kt).

use std::collections::BTreeMap;

/// Lightroom's panels; they also drive "paste selected settings".
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Group {
    Light,
    Curve,
    Color,
    Mixer,
    Grading,
    Effects,
    Detail,
    Optics,
    Geometry,
    Crop,
}

/// One adjustable value. A NaN default means "unset" (white balance: As Shot).
#[derive(Clone, Copy, Debug)]
pub struct Param {
    pub key: &'static str,
    pub label: &'static str,
    pub group: Group,
    pub min: f32,
    pub max: f32,
    pub default: f32,
    pub decimals: u8,
}

impl Param {
    pub fn is_switch(&self) -> bool {
        self.key.starts_with("on.")
    }
}

macro_rules! params {
    ($($name:ident = $key:literal, $label:literal, $group:ident, $min:expr, $max:expr, $def:expr, $dec:expr;)*) => {
        $(pub const $name: Param = Param { key: $key, label: $label, group: Group::$group, min: $min, max: $max, default: $def, decimals: $dec };)*
        /// Every global parameter, in the phone's order.
        pub const ALL: &[Param] = &[$($name),*];
    };
}

const NAN: f32 = f32::NAN;

pub mod p {
    use super::*;

    params! {
        EXPOSURE = "exposure", "Exposure", Light, -5.0, 5.0, 0.0, 2;
        CONTRAST = "contrast", "Contrast", Light, -100.0, 100.0, 0.0, 0;
        HIGHLIGHTS = "highlights", "Highlights", Light, -100.0, 100.0, 0.0, 0;
        SHADOWS = "shadows", "Shadows", Light, -100.0, 100.0, 0.0, 0;
        WHITES = "whites", "Whites", Light, -100.0, 100.0, 0.0, 0;
        BLACKS = "blacks", "Blacks", Light, -100.0, 100.0, 0.0, 0;
        LUT_AMOUNT = "lut.amount", "Amount", Light, 0.0, 100.0, 100.0, 0;
        CURVE_HIGHLIGHTS = "curveHighlights", "Highlights", Curve, -100.0, 100.0, 0.0, 0;
        CURVE_LIGHTS = "curveLights", "Lights", Curve, -100.0, 100.0, 0.0, 0;
        CURVE_DARKS = "curveDarks", "Darks", Curve, -100.0, 100.0, 0.0, 0;
        CURVE_SHADOWS = "curveShadows", "Shadows", Curve, -100.0, 100.0, 0.0, 0;
        TEMPERATURE = "temperature", "Temp", Color, 2000.0, 25000.0, NAN, 0;
        TINT = "tint", "Tint", Color, -150.0, 150.0, NAN, 0;
        VIBRANCE = "vibrance", "Vibrance", Color, -100.0, 100.0, 0.0, 0;
        SATURATION = "saturation", "Saturation", Color, -100.0, 100.0, 0.0, 0;
        MIX_RED_HUE = "mix.red.hue", "Red", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_ORANGE_HUE = "mix.orange.hue", "Orange", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_YELLOW_HUE = "mix.yellow.hue", "Yellow", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_GREEN_HUE = "mix.green.hue", "Green", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_AQUA_HUE = "mix.aqua.hue", "Aqua", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_BLUE_HUE = "mix.blue.hue", "Blue", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_PURPLE_HUE = "mix.purple.hue", "Purple", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_MAGENTA_HUE = "mix.magenta.hue", "Magenta", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_RED_SAT = "mix.red.sat", "Red", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_ORANGE_SAT = "mix.orange.sat", "Orange", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_YELLOW_SAT = "mix.yellow.sat", "Yellow", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_GREEN_SAT = "mix.green.sat", "Green", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_AQUA_SAT = "mix.aqua.sat", "Aqua", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_BLUE_SAT = "mix.blue.sat", "Blue", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_PURPLE_SAT = "mix.purple.sat", "Purple", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_MAGENTA_SAT = "mix.magenta.sat", "Magenta", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_RED_LUM = "mix.red.lum", "Red", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_ORANGE_LUM = "mix.orange.lum", "Orange", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_YELLOW_LUM = "mix.yellow.lum", "Yellow", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_GREEN_LUM = "mix.green.lum", "Green", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_AQUA_LUM = "mix.aqua.lum", "Aqua", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_BLUE_LUM = "mix.blue.lum", "Blue", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_PURPLE_LUM = "mix.purple.lum", "Purple", Mixer, -100.0, 100.0, 0.0, 0;
        MIX_MAGENTA_LUM = "mix.magenta.lum", "Magenta", Mixer, -100.0, 100.0, 0.0, 0;
        GRADE_SHADOWS_HUE = "grade.shadows.hue", "Hue", Grading, 0.0, 360.0, 0.0, 0;
        GRADE_MIDTONES_HUE = "grade.midtones.hue", "Hue", Grading, 0.0, 360.0, 0.0, 0;
        GRADE_HIGHLIGHTS_HUE = "grade.highlights.hue", "Hue", Grading, 0.0, 360.0, 0.0, 0;
        GRADE_GLOBAL_HUE = "grade.global.hue", "Hue", Grading, 0.0, 360.0, 0.0, 0;
        GRADE_SHADOWS_SAT = "grade.shadows.sat", "Saturation", Grading, 0.0, 100.0, 0.0, 0;
        GRADE_MIDTONES_SAT = "grade.midtones.sat", "Saturation", Grading, 0.0, 100.0, 0.0, 0;
        GRADE_HIGHLIGHTS_SAT = "grade.highlights.sat", "Saturation", Grading, 0.0, 100.0, 0.0, 0;
        GRADE_GLOBAL_SAT = "grade.global.sat", "Saturation", Grading, 0.0, 100.0, 0.0, 0;
        GRADE_SHADOWS_LUM = "grade.shadows.lum", "Luminance", Grading, -100.0, 100.0, 0.0, 0;
        GRADE_MIDTONES_LUM = "grade.midtones.lum", "Luminance", Grading, -100.0, 100.0, 0.0, 0;
        GRADE_HIGHLIGHTS_LUM = "grade.highlights.lum", "Luminance", Grading, -100.0, 100.0, 0.0, 0;
        GRADE_GLOBAL_LUM = "grade.global.lum", "Luminance", Grading, -100.0, 100.0, 0.0, 0;
        GRADE_BLENDING = "grade.blending", "Blending", Grading, 0.0, 100.0, 50.0, 0;
        GRADE_BALANCE = "grade.balance", "Balance", Grading, -100.0, 100.0, 0.0, 0;
        TEXTURE = "texture", "Texture", Effects, -100.0, 100.0, 0.0, 0;
        CLARITY = "clarity", "Clarity", Effects, -100.0, 100.0, 0.0, 0;
        DEHAZE = "dehaze", "Dehaze", Effects, -100.0, 100.0, 0.0, 0;
        VIGNETTE = "vignette", "Vignette", Effects, -100.0, 100.0, 0.0, 0;
        VIGNETTE_MIDPOINT = "vignette.midpoint", "Midpoint", Effects, 0.0, 100.0, 50.0, 0;
        VIGNETTE_FEATHER = "vignette.feather", "Feather", Effects, 0.0, 100.0, 50.0, 0;
        VIGNETTE_ROUNDNESS = "vignette.roundness", "Roundness", Effects, -100.0, 100.0, 0.0, 0;
        VIGNETTE_HIGHLIGHTS = "vignette.highlights", "Highlights", Effects, 0.0, 100.0, 0.0, 0;
        GRAIN = "grain", "Grain", Effects, 0.0, 100.0, 0.0, 0;
        GRAIN_SIZE = "grain.size", "Size", Effects, 0.0, 100.0, 25.0, 0;
        GRAIN_ROUGHNESS = "grain.roughness", "Roughness", Effects, 0.0, 100.0, 50.0, 0;
        SHARPEN = "sharpen", "Sharpening", Detail, 0.0, 150.0, 0.0, 0;
        SHARPEN_RADIUS = "sharpen.radius", "Radius", Detail, 0.5, 3.0, 1.0, 1;
        SHARPEN_DETAIL = "sharpen.detail", "Detail", Detail, 0.0, 100.0, 25.0, 0;
        SHARPEN_MASKING = "sharpen.masking", "Masking", Detail, 0.0, 100.0, 0.0, 0;
        NOISE = "noise", "Noise reduction", Detail, 0.0, 100.0, 0.0, 0;
        NOISE_DETAIL = "noise.detail", "Detail", Detail, 0.0, 100.0, 50.0, 0;
        COLOR_NOISE = "colorNoise", "Color noise reduction", Detail, 0.0, 100.0, 0.0, 0;
        AI_DENOISE = "ai.denoise", "AI denoise", Detail, 0.0, 100.0, 0.0, 0;
        LENS_PROFILE = "on.lensProfile", "Use lens corrections", Optics, 0.0, 1.0, 1.0, 0;
        REMOVE_CA = "on.removeCA", "Remove chromatic aberration", Optics, 0.0, 1.0, 1.0, 0;
        LENS_DISTORTION = "lens.distortion", "Distortion", Optics, 0.0, 200.0, 100.0, 0;
        LENS_VIGNETTING = "lens.vignetting", "Vignetting", Optics, 0.0, 200.0, 100.0, 0;
        MANUAL_DISTORTION = "manual.distortion", "Distortion", Optics, -100.0, 100.0, 0.0, 0;
        MANUAL_VIGNETTING = "manual.vignetting", "Vignetting", Optics, -100.0, 100.0, 0.0, 0;
        MANUAL_VIGNETTING_MIDPOINT = "manual.vignetting.midpoint", "Midpoint", Optics, 0.0, 100.0, 50.0, 0;
        DEFRINGE = "defringe", "Defringe", Optics, 0.0, 100.0, 0.0, 0;
        VERTICAL = "geo.vertical", "Vertical", Geometry, -100.0, 100.0, 0.0, 0;
        HORIZONTAL = "geo.horizontal", "Horizontal", Geometry, -100.0, 100.0, 0.0, 0;
        ROTATE = "geo.rotate", "Rotate", Geometry, -10.0, 10.0, 0.0, 1;
        SCALE = "geo.scale", "Scale", Geometry, 50.0, 150.0, 100.0, 0;
        OFFSET_X = "geo.x", "X offset", Geometry, -100.0, 100.0, 0.0, 0;
        OFFSET_Y = "geo.y", "Y offset", Geometry, -100.0, 100.0, 0.0, 0;
        CROP_LEFT = "crop.left", "", Crop, 0.0, 1.0, 0.0, 4;
        CROP_TOP = "crop.top", "", Crop, 0.0, 1.0, 0.0, 4;
        CROP_RIGHT = "crop.right", "", Crop, 0.0, 1.0, 1.0, 4;
        CROP_BOTTOM = "crop.bottom", "", Crop, 0.0, 1.0, 1.0, 4;
        STRAIGHTEN = "crop.angle", "Straighten", Crop, -45.0, 45.0, 0.0, 1;
        ROTATE_90 = "crop.rot90", "", Crop, 0.0, 3.0, 0.0, 0;
        FLIP_H = "crop.flipH", "", Crop, 0.0, 1.0, 0.0, 0;
        FLIP_V = "crop.flipV", "", Crop, 0.0, 1.0, 0.0, 0;
    }

    /// The colour mixer's eight bands and their centre hues.
    pub const MIX_BANDS: [(&str, f32); 8] =
        [("red", 0.0), ("orange", 30.0), ("yellow", 60.0), ("green", 120.0), ("aqua", 180.0), ("blue", 240.0), ("purple", 275.0), ("magenta", 315.0)];
    pub const MIX_HUE: [Param; 8] = [MIX_RED_HUE, MIX_ORANGE_HUE, MIX_YELLOW_HUE, MIX_GREEN_HUE, MIX_AQUA_HUE, MIX_BLUE_HUE, MIX_PURPLE_HUE, MIX_MAGENTA_HUE];
    pub const MIX_SAT: [Param; 8] = [MIX_RED_SAT, MIX_ORANGE_SAT, MIX_YELLOW_SAT, MIX_GREEN_SAT, MIX_AQUA_SAT, MIX_BLUE_SAT, MIX_PURPLE_SAT, MIX_MAGENTA_SAT];
    pub const MIX_LUM: [Param; 8] = [MIX_RED_LUM, MIX_ORANGE_LUM, MIX_YELLOW_LUM, MIX_GREEN_LUM, MIX_AQUA_LUM, MIX_BLUE_LUM, MIX_PURPLE_LUM, MIX_MAGENTA_LUM];
    /// Colour grading's zones: shadows, midtones, highlights, global.
    pub const GRADE_ZONES: [&str; 4] = ["Shadows", "Midtones", "Highlights", "Global"];
    pub const GRADE_HUE: [Param; 4] = [GRADE_SHADOWS_HUE, GRADE_MIDTONES_HUE, GRADE_HIGHLIGHTS_HUE, GRADE_GLOBAL_HUE];
    pub const GRADE_SAT: [Param; 4] = [GRADE_SHADOWS_SAT, GRADE_MIDTONES_SAT, GRADE_HIGHLIGHTS_SAT, GRADE_GLOBAL_SAT];
    pub const GRADE_LUM: [Param; 4] = [GRADE_SHADOWS_LUM, GRADE_MIDTONES_LUM, GRADE_HIGHLIGHTS_LUM, GRADE_GLOBAL_LUM];

    pub fn by_key(key: &str) -> Option<&'static Param> {
        ALL.iter().find(|p| p.key == key)
    }
}

/// The base look before the sliders, like Lightroom's profiles: the filmic curve's contrast and
/// a saturation boost for raws; JPEGs are already rendered, so only Monochrome changes them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Profile {
    Color,
    Vivid,
    Neutral,
    Monochrome,
}

impl Profile {
    pub const ALL: [Profile; 4] = [Profile::Color, Profile::Vivid, Profile::Neutral, Profile::Monochrome];

    pub fn name(self) -> &'static str {
        match self {
            Profile::Color => "COLOR",
            Profile::Vivid => "VIVID",
            Profile::Neutral => "NEUTRAL",
            Profile::Monochrome => "MONOCHROME",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Profile::Color => "Color",
            Profile::Vivid => "Vivid",
            Profile::Neutral => "Neutral",
            Profile::Monochrome => "Monochrome",
        }
    }

    pub fn contrast(self) -> f64 {
        match self {
            Profile::Color => 1.45,
            Profile::Vivid => 1.65,
            Profile::Neutral => 1.2,
            Profile::Monochrome => 1.5,
        }
    }

    pub fn saturation(self) -> f32 {
        match self {
            Profile::Color => 1.10,
            Profile::Vivid => 1.28,
            Profile::Neutral => 1.0,
            Profile::Monochrome => 0.0,
        }
    }

    fn parse(s: &str) -> Profile {
        Profile::ALL.into_iter().find(|p| p.name() == s).unwrap_or(Profile::Color)
    }
}

/// Lightroom's white balance presets (Kelvin, tint).
pub const WHITE_BALANCES: [(&str, f32, f32); 6] = [
    ("Daylight", 5500.0, 10.0),
    ("Cloudy", 6500.0, 10.0),
    ("Shade", 7500.0, 10.0),
    ("Tungsten", 2850.0, 0.0),
    ("Fluorescent", 3800.0, 21.0),
    ("Flash", 5500.0, 0.0),
];

/// A point tone curve: control points (x, y) in 0..1, sorted by x.
#[derive(Clone, Debug, PartialEq)]
pub struct Curve {
    pub points: Vec<(f32, f32)>,
}

impl Default for Curve {
    fn default() -> Self {
        Curve { points: vec![(0.0, 0.0), (1.0, 1.0)] }
    }
}

impl Curve {
    pub fn is_identity(&self) -> bool {
        self.points == [(0.0, 0.0), (1.0, 1.0)]
    }

    pub fn encode(&self) -> String {
        self.points.iter().map(|(x, y)| format!("{},{}", kotlin_float(*x), kotlin_float(*y))).collect::<Vec<_>>().join(";")
    }

    pub fn decode(s: &str) -> Curve {
        let pts: Option<Vec<(f32, f32)>> = s
            .split(';')
            .map(|p| {
                let mut it = p.split(',');
                Some((it.next()?.trim().parse().ok()?, it.next()?.trim().parse().ok()?))
            })
            .collect();
        match pts {
            Some(mut v) if !v.is_empty() => {
                v.sort_by(|a, b| a.0.total_cmp(&b.0));
                Curve { points: v }
            }
            _ => Curve::default(),
        }
    }

    /// Monotone cubic (Fritsch-Carlson) through the points, sampled into `n` values.
    pub fn table(&self, n: usize) -> Vec<f32> {
        let mut pts = self.points.clone();
        pts.sort_by(|a, b| a.0.total_cmp(&b.0));
        if pts.len() < 2 {
            return (0..n).map(|i| i as f32 / (n - 1) as f32).collect();
        }
        let xs: Vec<f64> = pts.iter().map(|p| p.0 as f64).collect();
        let ys: Vec<f64> = pts.iter().map(|p| p.1 as f64).collect();
        let k = pts.len();
        let d: Vec<f64> = (0..k - 1).map(|i| (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]).max(1e-6)).collect();
        let mut m = vec![0.0; k];
        m[0] = d[0];
        m[k - 1] = d[k - 2];
        for i in 1..k - 1 {
            m[i] = if d[i - 1] * d[i] <= 0.0 { 0.0 } else { (d[i - 1] + d[i]) / 2.0 };
        }
        for i in 0..k - 1 {
            if d[i] == 0.0 {
                m[i] = 0.0;
                m[i + 1] = 0.0;
                continue;
            }
            let a = m[i] / d[i];
            let b = m[i + 1] / d[i];
            let s = a * a + b * b;
            if s > 9.0 {
                let t = 3.0 / s.sqrt();
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
        (0..n)
            .map(|j| {
                let x = j as f64 / (n - 1) as f64;
                let v = if x <= xs[0] {
                    ys[0]
                } else if x >= xs[k - 1] {
                    ys[k - 1]
                } else {
                    let mut i = 0;
                    while i < k - 2 && x > xs[i + 1] {
                        i += 1;
                    }
                    let h = xs[i + 1] - xs[i];
                    let t = (x - xs[i]) / h;
                    let t2 = t * t;
                    let t3 = t2 * t;
                    (2.0 * t3 - 3.0 * t2 + 1.0) * ys[i] + (t3 - 2.0 * t2 + t) * h * m[i] + (-2.0 * t3 + 3.0 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                };
                (v as f32).clamp(0.0, 1.0)
            })
            .collect()
    }
}

/// The shapes a mask can take, like Lightroom's mask tools. The phone knows the first four; the
/// others are the computer's, and the phone keeps them as they are.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MaskKind {
    Linear,
    Radial,
    Subject,
    Sky,
    /// Painted with the brush.
    Brush,
    /// Something clicked on, selected by the object model.
    Object,
    /// A range of distances, from the depth model.
    Depth,
}

impl MaskKind {
    pub const ALL: [MaskKind; 7] = [MaskKind::Linear, MaskKind::Radial, MaskKind::Brush, MaskKind::Subject, MaskKind::Sky, MaskKind::Object, MaskKind::Depth];

    pub fn name(self) -> &'static str {
        match self {
            MaskKind::Linear => "linear",
            MaskKind::Radial => "radial",
            MaskKind::Subject => "subject",
            MaskKind::Sky => "sky",
            MaskKind::Brush => "brush",
            MaskKind::Object => "object",
            MaskKind::Depth => "depth",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            MaskKind::Linear => "Linear gradient",
            MaskKind::Radial => "Radial gradient",
            MaskKind::Subject => "Subject",
            MaskKind::Sky => "Sky",
            MaskKind::Brush => "Brush",
            MaskKind::Object => "Object",
            MaskKind::Depth => "Depth range",
        }
    }

    /// Placed by hand, with handles on the photo.
    pub fn shaped(self) -> bool {
        matches!(self, MaskKind::Linear | MaskKind::Radial)
    }

    /// Carries a map of its own (painted, or picked by clicks), kept beside the edit by its id.
    pub fn has_own_map(self) -> bool {
        matches!(self, MaskKind::Brush | MaskKind::Object)
    }
}

/// A click that picks an Object mask: where, as fractions of the upright photo, and whether it
/// adds to the selection or takes away.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pick {
    pub x: f32,
    pub y: f32,
    pub add: bool,
}

/// The sliders a mask carries.
pub mod local {
    use super::{Group, Param};

    const fn l(key: &'static str, label: &'static str, min: f32, max: f32, decimals: u8) -> Param {
        Param { key, label, group: Group::Light, min, max, default: 0.0, decimals }
    }

    pub const EXPOSURE: Param = l("exposure", "Exposure", -4.0, 4.0, 2);
    pub const CONTRAST: Param = l("contrast", "Contrast", -100.0, 100.0, 0);
    pub const HIGHLIGHTS: Param = l("highlights", "Highlights", -100.0, 100.0, 0);
    pub const SHADOWS: Param = l("shadows", "Shadows", -100.0, 100.0, 0);
    pub const TEMP: Param = l("temp", "Temp", -100.0, 100.0, 0);
    pub const TINT: Param = l("tint", "Tint", -100.0, 100.0, 0);
    pub const SATURATION: Param = l("saturation", "Saturation", -100.0, 100.0, 0);
    pub const CLARITY: Param = l("clarity", "Clarity", -100.0, 100.0, 0);
    pub const DEHAZE: Param = l("dehaze", "Dehaze", -100.0, 100.0, 0);

    /// In the order the phone's editor lists them.
    pub const ALL: [Param; 9] = [EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, TEMP, TINT, SATURATION, CLARITY, DEHAZE];
    /// The order of a mask's sliders in the shader's mask data (from offset 10).
    pub const SHADER_ORDER: [Param; 9] = [EXPOSURE, TEMP, TINT, CONTRAST, HIGHLIGHTS, SHADOWS, CLARITY, DEHAZE, SATURATION];

    pub fn by_key(key: &str) -> Option<&'static Param> {
        ALL.iter().find(|p| p.key == key)
    }
}

/// One local adjustment. Positions are fractions of the upright photo's width and height, before
/// crop and lens corrections. Linear: full at (x0, y0), gone at (x1, y1). Radial: centre
/// (x0, y0), radii x1 (of the width) and y1 (of the height), turned by `angle` degrees. Depth:
/// nearness from x0 to x1 (0 farthest, 1 nearest), softened by `feather`. Brush and Object masks
/// keep their map in a file named by `id`; an Object mask also keeps the clicks that made it,
/// and `grain`: how much smaller (below 0) or larger than the first guess its selection is.
#[derive(Clone, Debug, PartialEq)]
pub struct Mask {
    pub kind: MaskKind,
    pub x0: f32,
    pub y0: f32,
    pub x1: f32,
    pub y1: f32,
    pub angle: f32,
    pub feather: f32,
    pub invert: bool,
    pub exclude_subject: bool,
    pub values: BTreeMap<String, f32>,
    /// Names this mask's own map (Brush and Object masks).
    pub id: String,
    pub picks: Vec<Pick>,
    pub grain: i8,
}

impl Mask {
    pub fn new(kind: MaskKind) -> Mask {
        Mask {
            kind,
            x0: 0.5,
            y0: 0.5,
            x1: 0.5,
            y1: 0.5,
            angle: 0.0,
            feather: 50.0,
            invert: false,
            exclude_subject: false,
            values: BTreeMap::new(),
            id: String::new(),
            picks: Vec::new(),
            grain: 0,
        }
    }

    /// A Brush or Object mask with a new id for its map.
    pub fn with_map(kind: MaskKind) -> Mask {
        let mut b = [0u8; 6];
        let _ = getrandom::fill(&mut b);
        Mask { id: b.iter().map(|v| format!("{v:02x}")).collect(), ..Mask::new(kind) }
    }

    /// The nearest part of the photo, softly: a starting Depth mask.
    pub fn depth() -> Mask {
        Mask { x0: 0.6, x1: 1.0, feather: 30.0, ..Mask::new(MaskKind::Depth) }
    }

    /// A gradient from the top down to just above the middle: the usual sky darkening.
    pub fn linear() -> Mask {
        Mask { x0: 0.5, y0: 0.15, x1: 0.5, y1: 0.5, ..Mask::new(MaskKind::Linear) }
    }

    /// A circle around the centre, `aspect` = the photo's width / height.
    pub fn radial(aspect: f32) -> Mask {
        Mask { x0: 0.5, y0: 0.5, x1: 0.25, y1: 0.25 * aspect, ..Mask::new(MaskKind::Radial) }
    }

    pub fn get(&self, p: &Param) -> f32 {
        self.values.get(p.key).copied().unwrap_or(p.default)
    }

    pub fn set(&mut self, p: &Param, v: f32) {
        let c = v.clamp(p.min, p.max);
        if c == p.default {
            self.values.remove(p.key);
        } else {
            self.values.insert(p.key.to_string(), c);
        }
    }

    pub fn uses_subject(&self) -> bool {
        self.kind == MaskKind::Subject || self.exclude_subject
    }

    pub fn uses_sky(&self) -> bool {
        self.kind == MaskKind::Sky
    }

    pub fn encode(&self) -> String {
        let f = kotlin_float;
        let mut s = self.kind.name().to_string();
        if matches!(self.kind, MaskKind::Linear | MaskKind::Radial) {
            s += &format!(";x0={};y0={};x1={};y1={}", f(self.x0), f(self.y0), f(self.x1), f(self.y1));
        }
        if self.kind == MaskKind::Radial {
            s += &format!(";angle={};feather={}", f(self.angle), f(self.feather));
        }
        if self.kind == MaskKind::Depth {
            s += &format!(";x0={};x1={};feather={}", f(self.x0), f(self.x1), f(self.feather));
        }
        if !self.id.is_empty() {
            s += &format!(";id={}", self.id);
        }
        if !self.picks.is_empty() {
            let picks: Vec<String> = self.picks.iter().map(|p| format!("{}_{}_{}", f(p.x), f(p.y), if p.add { 1 } else { 0 })).collect();
            s += &format!(";picks={}", picks.join(","));
        }
        if self.grain != 0 {
            s += &format!(";grain={}", self.grain);
        }
        if self.invert {
            s += ";invert=1";
        }
        if self.exclude_subject {
            s += ";excludeSubject=1";
        }
        for (k, v) in &self.values {
            s += &format!(";{k}={}", f(*v));
        }
        s
    }

    pub fn decode(text: &str) -> Option<Mask> {
        let mut parts = text.split(';');
        let name = parts.next()?.trim();
        let kind = MaskKind::ALL.into_iter().find(|k| k.name() == name)?;
        let mut m = Mask::new(kind);
        for part in parts {
            let Some((k, v)) = part.split_once('=') else { continue };
            match k.trim() {
                "id" => {
                    m.id = v.trim().chars().filter(|c| c.is_ascii_alphanumeric()).collect();
                    continue;
                }
                "picks" => {
                    m.picks = v
                        .split(',')
                        .filter_map(|p| {
                            let mut it = p.split('_').map(|n| n.trim().parse::<f32>());
                            match (it.next(), it.next(), it.next()) {
                                (Some(Ok(x)), Some(Ok(y)), Some(Ok(a))) => Some(Pick { x, y, add: a >= 0.5 }),
                                _ => None,
                            }
                        })
                        .collect();
                    continue;
                }
                _ => {}
            }
            let (k, Ok(v)) = (k.trim(), v.trim().parse::<f32>()) else { continue };
            if k.is_empty() {
                continue;
            }
            match k {
                "x0" => m.x0 = v,
                "y0" => m.y0 = v,
                "x1" => m.x1 = v,
                "y1" => m.y1 = v,
                "angle" => m.angle = v,
                "feather" => m.feather = v.clamp(0.0, 100.0),
                "invert" => m.invert = v >= 0.5,
                "excludeSubject" => m.exclude_subject = v >= 0.5,
                "grain" => m.grain = (v.round() as i8).clamp(-2, 2),
                _ => {
                    if let Some(p) = local::by_key(k) {
                        if v != p.default {
                            m.values.insert(k.to_string(), v.clamp(p.min, p.max));
                        }
                    }
                }
            }
        }
        Some(m)
    }
}

/// One photo's edit. Only values that differ from their default are stored.
#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub values: BTreeMap<String, f32>,
    pub profile: Profile,
    /// Point curves: master, red, green, blue.
    pub curves: [Curve; 4],
    /// Local adjustments, applied in order on top of the global sliders.
    pub masks: Vec<Mask>,
    /// A film look from a LUT, by its id; None for none.
    pub lut: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { values: BTreeMap::new(), profile: Profile::Color, curves: Default::default(), masks: Vec::new(), lut: None }
    }
}

impl Settings {
    pub fn get(&self, p: &Param) -> f32 {
        self.values.get(p.key).copied().unwrap_or(p.default)
    }

    pub fn on(&self, p: &Param) -> bool {
        self.get(p) >= 0.5
    }

    pub fn set(&mut self, p: &Param, v: f32) {
        let c = if v.is_nan() { v } else { v.clamp(p.min, p.max) };
        if c == p.default || (c.is_nan() && p.default.is_nan()) {
            self.values.remove(p.key);
        } else {
            self.values.insert(p.key.to_string(), c);
        }
    }

    pub fn with(mut self, p: &Param, v: f32) -> Settings {
        self.set(p, v);
        self
    }

    pub fn temperature(&self) -> Option<f32> {
        self.values.get(p::TEMPERATURE.key).copied()
    }

    pub fn tint(&self) -> Option<f32> {
        self.values.get(p::TINT.key).copied()
    }

    pub fn set_white_balance(&mut self, kelvin: Option<f32>, tint: Option<f32>) {
        self.values.remove(p::TEMPERATURE.key);
        self.values.remove(p::TINT.key);
        if let Some(k) = kelvin {
            self.values.insert(p::TEMPERATURE.key.into(), k.clamp(p::TEMPERATURE.min, p::TEMPERATURE.max));
        }
        if let Some(t) = tint {
            self.values.insert(p::TINT.key.into(), t.clamp(p::TINT.min, p::TINT.max));
        }
    }

    /// Back to defaults for one panel.
    pub fn reset(&mut self, group: Group) {
        self.values.retain(|k, _| p::by_key(k).is_none_or(|p| p.group != group));
        match group {
            Group::Light => {
                self.profile = Profile::Color;
                self.lut = None;
            }
            Group::Curve => self.curves = Default::default(),
            _ => {}
        }
    }

    pub fn is_default_group(&self, group: Group) -> bool {
        self.values.keys().all(|k| p::by_key(k).is_none_or(|p| p.group != group))
            && (group != Group::Curve || self.curves.iter().all(Curve::is_identity))
            && (group != Group::Light || self.profile == Profile::Color && self.lut.is_none())
    }

    pub fn is_default(&self) -> bool {
        *self == Settings::default()
    }

    /// These settings with `groups` taken from `from` (Lightroom's selective paste). Masks
    /// belong to one photo's content, so they stay.
    pub fn paste_from(&self, from: &Settings, groups: &[Group]) -> Settings {
        let in_groups = |k: &String| p::by_key(k).is_some_and(|p| groups.contains(&p.group));
        let mut values: BTreeMap<String, f32> = self.values.iter().filter(|(k, _)| !in_groups(k)).map(|(k, v)| (k.clone(), *v)).collect();
        values.extend(from.values.iter().filter(|(k, _)| in_groups(k)).map(|(k, v)| (k.clone(), *v)));
        let light = groups.contains(&Group::Light);
        Settings {
            values,
            profile: if light { from.profile } else { self.profile },
            curves: if groups.contains(&Group::Curve) { from.curves.clone() } else { self.curves.clone() },
            masks: self.masks.clone(),
            lut: if light { from.lut.clone() } else { self.lut.clone() },
        }
    }

    pub fn encode(&self) -> String {
        let mut s = String::from("v=2\n");
        s += &format!("profile={}\n", self.profile.name());
        if let Some(l) = &self.lut {
            s += &format!("lut={l}\n");
        }
        for (k, v) in &self.values {
            s += &format!("{k}={}\n", kotlin_float(*v));
        }
        for (i, c) in self.curves.iter().enumerate() {
            if !c.is_identity() {
                s += &format!("curve.{i}={}\n", c.encode());
            }
        }
        for (i, m) in self.masks.iter().enumerate() {
            s += &format!("mask.{i}={}\n", m.encode());
        }
        s
    }

    pub fn decode(text: &str) -> Settings {
        let mut out = Settings::default();
        let mut masks = BTreeMap::new();
        for line in text.lines() {
            let Some(i) = line.find('=') else { continue };
            if i == 0 {
                continue;
            }
            let (k, v) = (line[..i].trim(), line[i + 1..].trim());
            if k == "profile" {
                out.profile = Profile::parse(v);
            } else if k == "lut" {
                out.lut = (!v.is_empty()).then(|| v.to_string());
            } else if let Some(n) = k.strip_prefix("curve.") {
                if let Ok(n @ 0..=3) = n.parse::<usize>() {
                    out.curves[n] = Curve::decode(v);
                }
            } else if let Some(n) = k.strip_prefix("mask.") {
                if let (Ok(n), Some(m)) = (n.parse::<usize>(), Mask::decode(v)) {
                    masks.insert(n, m);
                }
            } else if let (Some(p), Ok(f)) = (p::by_key(k), v.parse::<f32>()) {
                if f != p.default {
                    out.values.insert(k.to_string(), f);
                }
            }
        }
        out.masks = masks.into_values().collect();
        out
    }
}

/// A float written the way Kotlin's `Float.toString` writes it ("10.0", "0.25", "1.0E-4"), so
/// files written here read the same as files written on the phone.
pub fn kotlin_float(v: f32) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    let a = v.abs();
    if a == 0.0 || (1e-3..1e7).contains(&a) {
        let s = format!("{v}");
        if s.contains('.') {
            s
        } else {
            s + ".0"
        }
    } else {
        let s = format!("{v:e}");
        let (m, e) = s.split_once('e').unwrap_or((&s, "0"));
        let m = if m.contains('.') { m.to_string() } else { format!("{m}.0") };
        format!("{m}E{e}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_and_writes_the_phone_format() {
        let text = "v=2\nprofile=VIVID\nlut=kodak\nexposure=0.35\nhighlights=-40.0\ntemperature=5600.0\n\
            curve.0=0.0,0.0;0.5,0.6;1.0,1.0\nmask.0=linear;x0=0.5;y0=0.15;x1=0.5;y1=0.5;exposure=-0.7\n\
            mask.1=radial;x0=0.4;y0=0.5;x1=0.2;y1=0.3;angle=10.0;feather=60.0;invert=1;shadows=20.0\n";
        let s = Settings::decode(text);
        assert_eq!(s.profile, Profile::Vivid);
        assert_eq!(s.lut.as_deref(), Some("kodak"));
        assert_eq!(s.get(&p::EXPOSURE), 0.35);
        assert_eq!(s.get(&p::CONTRAST), 0.0);
        assert_eq!(s.temperature(), Some(5600.0));
        assert!(s.get(&p::TINT).is_nan());
        assert_eq!(s.curves[0].points.len(), 3);
        assert_eq!(s.masks.len(), 2);
        assert!(s.masks[1].invert);
        assert_eq!(s.masks[1].get(&local::SHADOWS), 20.0);
        assert_eq!(s.encode(), text);
        assert_eq!(Settings::decode(&s.encode()), s);
    }

    #[test]
    fn the_computers_masks_round_trip() {
        let mut brush = Mask::with_map(MaskKind::Brush);
        brush.set(&local::EXPOSURE, 0.5);
        let mut object = Mask::with_map(MaskKind::Object);
        object.picks = vec![Pick { x: 0.25, y: 0.5, add: true }, Pick { x: 0.3, y: 0.6, add: false }];
        object.grain = 1;
        let s = Settings { masks: vec![brush, object, Mask::depth()], ..Settings::default() };
        let text = s.encode();
        assert!(text.contains("mask.2=depth;x0=0.6;x1=1.0;feather=30.0\n"), "{text}");
        assert_eq!(Settings::decode(&text), s);
        assert_eq!(s.masks[0].id.len(), 12);
        assert_ne!(s.masks[0].id, s.masks[1].id);
    }

    #[test]
    fn defaults_are_left_out() {
        let s = Settings::default().with(&p::EXPOSURE, 0.0).with(&p::SHARPEN_RADIUS, 1.0).with(&p::CONTRAST, 400.0);
        assert_eq!(s.encode(), "v=2\nprofile=COLOR\ncontrast=100.0\n");
        assert!(Settings::default().is_default());
    }

    #[test]
    fn floats_print_like_kotlin() {
        assert_eq!(kotlin_float(10.0), "10.0");
        assert_eq!(kotlin_float(-0.7), "-0.7");
        assert_eq!(kotlin_float(0.0001), "1.0E-4");
        assert_eq!(kotlin_float(0.25), "0.25");
    }

    #[test]
    fn identity_curve_is_a_straight_line() {
        let t = Curve::default().table(5);
        assert_eq!(t, vec![0.0, 0.25, 0.5, 0.75, 1.0]);
    }
}
