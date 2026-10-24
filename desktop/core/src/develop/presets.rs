//! Saved looks (the phone's engine/Presets.kt). User presets set every slider of their groups,
//! like Lightroom's "include" boxes. Built-in ones replace the whole look (profile, tone, curves,
//! color, effects), so trying one after another swaps them instead of stacking, and they leave the
//! photo's own corrections alone: exposure, white balance, detail, optics, geometry, crop and
//! masks.
//!
//! User presets live beside the library in `.shootback/presets/<name>.txt`, in the phone's format:
//! a `groups=` line, then the settings. Sync carries them both ways.

use super::params::{p, Curve, Group, Param, Profile, Settings};
use crate::library::{write_atomic, STATE_DIR};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq)]
pub struct Preset {
    pub name: String,
    pub settings: Settings,
    /// The panels a user preset sets; None for a built-in look.
    pub groups: Option<Vec<Group>>,
    /// One of the film stocks, listed apart from the other looks.
    pub film: bool,
}

/// The panels a built-in look owns.
const LOOK_GROUPS: [Group; 6] = [Group::Light, Group::Curve, Group::Color, Group::Mixer, Group::Grading, Group::Effects];

/// Whether a built-in look sets `key`: its panels' sliders, except the photo's corrections.
fn look_key(key: &str) -> bool {
    p::by_key(key).is_some_and(|q| LOOK_GROUPS.contains(&q.group) && ![p::EXPOSURE.key, p::TEMPERATURE.key, p::TINT.key].contains(&q.key))
}

impl Preset {
    pub fn apply_to(&self, current: &Settings) -> Settings {
        if let Some(groups) = &self.groups {
            return current.paste_from(&self.settings, groups);
        }
        let mut values = current.values.clone();
        values.retain(|k, _| !look_key(k));
        values.extend(self.settings.values.iter().filter(|(k, _)| look_key(k)).map(|(k, v)| (k.clone(), *v)));
        Settings { values, profile: self.settings.profile, curves: self.settings.curves.clone(), lut: self.settings.lut.clone(), ..current.clone() }
    }

    /// Whether `current` already wears this look.
    pub fn is_applied(&self, current: &Settings) -> bool {
        self.apply_to(current) == *current
    }
}

fn c(points: &[(f32, f32)]) -> Curve {
    Curve { points: points.to_vec() }
}

fn with(mut s: Settings, values: &[(Param, f32)]) -> Settings {
    for (q, v) in values {
        s.set(q, *v);
    }
    s
}

fn look(name: &str, profile: Profile, curve: Option<Curve>, values: &[(Param, f32)]) -> Preset {
    let mut s = with(Settings { profile, ..Settings::default() }, values);
    if let Some(curve) = curve {
        s.curves[0] = curve;
    }
    Preset { name: name.into(), settings: s, groups: None, film: false }
}

/// A film stock: a master curve and, for its colour casts, red / green / blue curves.
fn film(name: &str, profile: Profile, curve: Curve, rgb: Option<[Curve; 3]>, values: &[(Param, f32)]) -> Preset {
    let mut s = with(Settings { profile, ..Settings::default() }, values);
    s.curves[0] = curve;
    if let Some([r, g, b]) = rgb {
        s.curves[1] = r;
        s.curves[2] = g;
        s.curves[3] = b;
    }
    Preset { name: name.into(), settings: s, groups: None, film: true }
}

/// Hand-tuned film stocks (the phone's Presets.FILM).
pub fn films() -> Vec<Preset> {
    vec![
        film(
            "Portra 400",
            Profile::Neutral,
            c(&[(0.0, 0.05), (0.25, 0.25), (0.6, 0.63), (1.0, 0.96)]),
            Some([c(&[(0.0, 0.02), (0.5, 0.52), (1.0, 1.0)]), Curve::default(), c(&[(0.0, 0.03), (0.5, 0.49), (1.0, 0.95)])]),
            &[
                (p::CONTRAST, -8.0),
                (p::HIGHLIGHTS, -20.0),
                (p::SHADOWS, 12.0),
                (p::SATURATION, -8.0),
                (p::VIBRANCE, 8.0),
                (p::MIX_HUE[0], 6.0),
                (p::MIX_HUE[1], 4.0),
                (p::MIX_SAT[1], -6.0),
                (p::MIX_LUM[1], 8.0),
                (p::MIX_HUE[3], 22.0),
                (p::MIX_SAT[3], -25.0),
                (p::MIX_LUM[3], 6.0),
                (p::MIX_SAT[5], -12.0),
                (p::GRAIN, 14.0),
                (p::GRAIN_SIZE, 30.0),
            ],
        ),
        film(
            "Ektar 100",
            Profile::Vivid,
            c(&[(0.0, 0.0), (0.25, 0.21), (0.75, 0.8), (1.0, 1.0)]),
            Some([c(&[(0.0, 0.0), (0.5, 0.52), (1.0, 1.0)]), Curve::default(), Curve::default()]),
            &[
                (p::CONTRAST, 12.0),
                (p::HIGHLIGHTS, -15.0),
                (p::BLACKS, -8.0),
                (p::VIBRANCE, 15.0),
                (p::MIX_SAT[0], 15.0),
                (p::MIX_HUE[1], -6.0),
                (p::MIX_SAT[5], 18.0),
                (p::MIX_LUM[5], -10.0),
                (p::MIX_HUE[3], 8.0),
                (p::GRADE_HUE[0], 215.0),
                (p::GRADE_SAT[0], 8.0),
                (p::GRAIN, 6.0),
                (p::GRAIN_SIZE, 15.0),
            ],
        ),
        film(
            "Gold 200",
            Profile::Color,
            c(&[(0.0, 0.04), (0.25, 0.24), (0.75, 0.78), (1.0, 0.97)]),
            Some([c(&[(0.0, 0.02), (0.5, 0.53), (1.0, 1.0)]), c(&[(0.0, 0.01), (0.5, 0.51), (1.0, 0.99)]), c(&[(0.0, 0.0), (0.5, 0.45), (1.0, 0.9)])]),
            &[
                (p::HIGHLIGHTS, -15.0),
                (p::SATURATION, -4.0),
                (p::VIBRANCE, 10.0),
                (p::MIX_HUE[1], -4.0),
                (p::MIX_SAT[2], 10.0),
                (p::MIX_HUE[3], 18.0),
                (p::MIX_SAT[3], -15.0),
                (p::MIX_SAT[5], -10.0),
                (p::GRAIN, 18.0),
                (p::GRAIN_SIZE, 35.0),
                (p::VIGNETTE, -8.0),
            ],
        ),
        film(
            "Fuji 400H",
            Profile::Neutral,
            c(&[(0.0, 0.06), (0.3, 0.33), (0.7, 0.74), (1.0, 0.97)]),
            Some([c(&[(0.0, 0.0), (0.5, 0.49), (1.0, 0.98)]), c(&[(0.0, 0.03), (0.5, 0.51), (1.0, 1.0)]), c(&[(0.0, 0.07), (0.5, 0.52), (1.0, 1.0)])]),
            &[
                (p::CONTRAST, -12.0),
                (p::HIGHLIGHTS, -10.0),
                (p::SHADOWS, 15.0),
                (p::WHITES, 6.0),
                (p::SATURATION, -10.0),
                (p::MIX_HUE[0], -6.0),
                (p::MIX_SAT[1], -10.0),
                (p::MIX_LUM[1], 6.0),
                (p::MIX_HUE[3], 35.0),
                (p::MIX_SAT[3], -15.0),
                (p::MIX_LUM[3], 10.0),
                (p::MIX_HUE[4], -10.0),
                (p::GRAIN, 14.0),
                (p::GRAIN_SIZE, 30.0),
            ],
        ),
        film(
            "Superia 400",
            Profile::Color,
            c(&[(0.0, 0.03), (0.25, 0.22), (0.75, 0.79), (1.0, 0.98)]),
            Some([c(&[(0.0, 0.0), (0.5, 0.49), (1.0, 1.0)]), c(&[(0.0, 0.04), (0.5, 0.51), (1.0, 1.0)]), c(&[(0.0, 0.04), (0.5, 0.5), (1.0, 0.96)])]),
            &[
                (p::CONTRAST, 8.0),
                (p::VIBRANCE, 6.0),
                (p::MIX_HUE[3], 25.0),
                (p::MIX_SAT[3], 8.0),
                (p::MIX_SAT[1], -5.0),
                (p::MIX_HUE[5], -8.0),
                (p::GRAIN, 24.0),
                (p::GRAIN_SIZE, 40.0),
                (p::GRAIN_ROUGHNESS, 55.0),
            ],
        ),
        film(
            "CineStill 800T",
            Profile::Neutral,
            c(&[(0.0, 0.05), (0.3, 0.27), (0.7, 0.74), (1.0, 0.97)]),
            Some([c(&[(0.0, 0.0), (0.5, 0.48), (0.85, 0.88), (1.0, 1.0)]), Curve::default(), c(&[(0.0, 0.08), (0.5, 0.55), (1.0, 0.94)])]),
            &[
                (p::CONTRAST, 10.0),
                (p::HIGHLIGHTS, -25.0),
                (p::SATURATION, -6.0),
                (p::VIBRANCE, 8.0),
                (p::GRADE_HUE[0], 195.0),
                (p::GRADE_SAT[0], 20.0),
                (p::GRADE_HUE[2], 25.0),
                (p::GRADE_SAT[2], 18.0),
                (p::MIX_SAT[0], 12.0),
                (p::MIX_HUE[2], -10.0),
                (p::MIX_SAT[3], -20.0),
                (p::GRAIN, 28.0),
                (p::GRAIN_SIZE, 45.0),
                (p::GRAIN_ROUGHNESS, 55.0),
                (p::VIGNETTE, -10.0),
            ],
        ),
        film(
            "Tri-X 400",
            Profile::Monochrome,
            c(&[(0.0, 0.02), (0.25, 0.19), (0.75, 0.82), (1.0, 1.0)]),
            None,
            &[
                (p::CONTRAST, 25.0),
                (p::BLACKS, -10.0),
                (p::CLARITY, 12.0),
                (p::MIX_LUM[0], 8.0),
                (p::MIX_LUM[1], 12.0),
                (p::MIX_LUM[5], -15.0),
                (p::GRAIN, 32.0),
                (p::GRAIN_SIZE, 40.0),
                (p::GRAIN_ROUGHNESS, 65.0),
                (p::VIGNETTE, -8.0),
            ],
        ),
        film(
            "HP5 Plus",
            Profile::Monochrome,
            c(&[(0.0, 0.05), (0.3, 0.29), (0.7, 0.72), (1.0, 0.96)]),
            None,
            &[
                (p::CONTRAST, 5.0),
                (p::HIGHLIGHTS, -15.0),
                (p::SHADOWS, 10.0),
                (p::MIX_LUM[1], 8.0),
                (p::MIX_LUM[5], -8.0),
                (p::GRAIN, 28.0),
                (p::GRAIN_SIZE, 45.0),
                (p::GRAIN_ROUGHNESS, 50.0),
            ],
        ),
    ]
}

/// The looks, then the film stocks (the phone's Presets.BUILT_IN), "None" first.
pub fn built_in() -> Vec<Preset> {
    let mut all = vec![
        look("None", Profile::Color, None, &[]),
        look("Natural", Profile::Color, None, &[(p::VIBRANCE, 15.0), (p::CLARITY, 8.0), (p::TEXTURE, 8.0)]),
        look("Punchy", Profile::Vivid, None, &[(p::CONTRAST, 20.0), (p::VIBRANCE, 20.0), (p::CLARITY, 15.0), (p::DEHAZE, 6.0), (p::BLACKS, -10.0)]),
        look(
            "Bright & airy",
            Profile::Color,
            Some(c(&[(0.0, 0.05), (0.5, 0.56), (1.0, 1.0)])),
            &[
                (p::CONTRAST, -15.0),
                (p::HIGHLIGHTS, -25.0),
                (p::SHADOWS, 35.0),
                (p::WHITES, 12.0),
                (p::VIBRANCE, 10.0),
                (p::SATURATION, -6.0),
                (p::CLARITY, -8.0),
                (p::MIX_LUM[1], 10.0),
                (p::MIX_SAT[1], -8.0),
                (p::MIX_LUM[3], 8.0),
            ],
        ),
        look(
            "Golden hour",
            Profile::Color,
            None,
            &[
                (p::CONTRAST, 8.0),
                (p::HIGHLIGHTS, -20.0),
                (p::SHADOWS, 10.0),
                (p::VIBRANCE, 18.0),
                (p::GRADE_HUE[1], 38.0),
                (p::GRADE_SAT[1], 14.0),
                (p::GRADE_HUE[2], 45.0),
                (p::GRADE_SAT[2], 25.0),
                (p::MIX_SAT[1], 12.0),
                (p::MIX_HUE[2], -8.0),
                (p::VIGNETTE, -10.0),
            ],
        ),
        look(
            "Cool & clean",
            Profile::Neutral,
            None,
            &[
                (p::CONTRAST, 12.0),
                (p::WHITES, 8.0),
                (p::BLACKS, -8.0),
                (p::VIBRANCE, 8.0),
                (p::SATURATION, -10.0),
                (p::GRADE_HUE[0], 210.0),
                (p::GRADE_SAT[0], 14.0),
                (p::GRADE_HUE[2], 200.0),
                (p::GRADE_SAT[2], 6.0),
                (p::MIX_SAT[1], -15.0),
                (p::MIX_SAT[5], 10.0),
                (p::CLARITY, 6.0),
            ],
        ),
        look(
            "Matte",
            Profile::Neutral,
            Some(c(&[(0.0, 0.07), (0.25, 0.24), (0.75, 0.78), (1.0, 0.96)])),
            &[(p::CONTRAST, -10.0), (p::SATURATION, -12.0), (p::GRAIN, 12.0)],
        ),
        look(
            "Faded",
            Profile::Neutral,
            Some(c(&[(0.0, 0.14), (0.3, 0.32), (0.7, 0.72), (1.0, 0.92)])),
            &[
                (p::CONTRAST, -18.0),
                (p::SATURATION, -25.0),
                (p::VIBRANCE, 5.0),
                (p::GRADE_HUE[0], 160.0),
                (p::GRADE_SAT[0], 10.0),
                (p::GRADE_HUE[2], 50.0),
                (p::GRADE_SAT[2], 10.0),
                (p::GRAIN, 15.0),
            ],
        ),
        look(
            "Warm film",
            Profile::Color,
            Some(c(&[(0.0, 0.04), (0.5, 0.5), (1.0, 0.97)])),
            &[
                (p::GRADE_HUE[0], 200.0),
                (p::GRADE_SAT[0], 18.0),
                (p::GRADE_HUE[2], 42.0),
                (p::GRADE_SAT[2], 22.0),
                (p::SATURATION, -8.0),
                (p::GRAIN, 22.0),
                (p::GRAIN_SIZE, 30.0),
                (p::VIGNETTE, -12.0),
            ],
        ),
        look(
            "Portrait film",
            Profile::Neutral,
            Some(c(&[(0.0, 0.05), (0.25, 0.25), (0.75, 0.77), (1.0, 0.97)])),
            &[
                (p::CONTRAST, -5.0),
                (p::HIGHLIGHTS, -15.0),
                (p::SHADOWS, 10.0),
                (p::VIBRANCE, 6.0),
                (p::SATURATION, -10.0),
                (p::MIX_HUE[1], 5.0),
                (p::MIX_SAT[1], -10.0),
                (p::MIX_LUM[1], 8.0),
                (p::MIX_HUE[3], 20.0),
                (p::MIX_SAT[3], -20.0),
                (p::GRADE_HUE[0], 180.0),
                (p::GRADE_SAT[0], 8.0),
                (p::GRADE_HUE[2], 35.0),
                (p::GRADE_SAT[2], 12.0),
                (p::CLARITY, -5.0),
                (p::GRAIN, 14.0),
            ],
        ),
        look(
            "Chrome",
            Profile::Neutral,
            Some(c(&[(0.0, 0.0), (0.25, 0.21), (0.75, 0.8), (1.0, 1.0)])),
            &[
                (p::CONTRAST, 10.0),
                (p::SATURATION, -18.0),
                (p::VIBRANCE, -5.0),
                (p::MIX_SAT[0], 8.0),
                (p::MIX_LUM[5], -15.0),
                (p::MIX_SAT[5], -10.0),
                (p::MIX_HUE[3], 10.0),
                (p::MIX_SAT[3], -25.0),
                (p::GRADE_HUE[0], 200.0),
                (p::GRADE_SAT[0], 8.0),
                (p::CLARITY, 10.0),
            ],
        ),
        look(
            "Teal & orange",
            Profile::Color,
            None,
            &[
                (p::GRADE_HUE[0], 190.0),
                (p::GRADE_SAT[0], 30.0),
                (p::GRADE_HUE[2], 35.0),
                (p::GRADE_SAT[2], 28.0),
                (p::MIX_HUE[5], -15.0),
                (p::MIX_SAT[1], 10.0),
                (p::CONTRAST, 10.0),
            ],
        ),
        look(
            "Cinematic",
            Profile::Neutral,
            Some(c(&[(0.0, 0.05), (0.3, 0.26), (0.7, 0.74), (1.0, 0.95)])),
            &[
                (p::CONTRAST, 12.0),
                (p::HIGHLIGHTS, -30.0),
                (p::SHADOWS, 5.0),
                (p::SATURATION, -15.0),
                (p::GRADE_HUE[0], 195.0),
                (p::GRADE_SAT[0], 22.0),
                (p::GRADE_HUE[2], 38.0),
                (p::GRADE_SAT[2], 15.0),
                (p::MIX_SAT[2], -20.0),
                (p::MIX_SAT[3], -30.0),
                (p::MIX_HUE[3], 25.0),
                (p::VIGNETTE, -15.0),
            ],
        ),
        look(
            "Moody",
            Profile::Neutral,
            None,
            &[
                (p::CONTRAST, 15.0),
                (p::HIGHLIGHTS, -40.0),
                (p::WHITES, -10.0),
                (p::SHADOWS, -10.0),
                (p::SATURATION, -20.0),
                (p::CLARITY, 12.0),
                (p::VIGNETTE, -25.0),
                (p::GRADE_HUE[0], 215.0),
                (p::GRADE_SAT[0], 15.0),
            ],
        ),
        look(
            "Night city",
            Profile::Color,
            None,
            &[
                (p::CONTRAST, 15.0),
                (p::HIGHLIGHTS, -35.0),
                (p::BLACKS, -12.0),
                (p::VIBRANCE, 15.0),
                (p::GRADE_HUE[0], 225.0),
                (p::GRADE_SAT[0], 25.0),
                (p::GRADE_HUE[2], 30.0),
                (p::GRADE_SAT[2], 15.0),
                (p::MIX_HUE[2], -12.0),
                (p::MIX_SAT[6], 15.0),
                (p::MIX_SAT[7], 15.0),
                (p::CLARITY, 10.0),
                (p::DEHAZE, 8.0),
            ],
        ),
        look(
            "Landscape",
            Profile::Vivid,
            None,
            &[
                (p::HIGHLIGHTS, -35.0),
                (p::SHADOWS, 25.0),
                (p::WHITES, 10.0),
                (p::BLACKS, -12.0),
                (p::VIBRANCE, 15.0),
                (p::MIX_LUM[5], -15.0),
                (p::MIX_SAT[5], 10.0),
                (p::MIX_HUE[3], 10.0),
                (p::CLARITY, 18.0),
                (p::TEXTURE, 15.0),
                (p::DEHAZE, 15.0),
            ],
        ),
        look("B&W", Profile::Monochrome, None, &[(p::CONTRAST, 10.0), (p::CLARITY, 10.0)]),
        look(
            "B&W contrast",
            Profile::Monochrome,
            None,
            &[(p::CONTRAST, 40.0), (p::CLARITY, 25.0), (p::BLACKS, -15.0), (p::WHITES, 10.0), (p::GRAIN, 18.0), (p::MIX_LUM[5], -30.0), (p::MIX_LUM[2], 15.0)],
        ),
        look("B&W soft", Profile::Monochrome, Some(c(&[(0.0, 0.06), (1.0, 0.95)])), &[(p::CONTRAST, -20.0), (p::HIGHLIGHTS, -20.0), (p::SHADOWS, 20.0)]),
        look(
            "B&W film",
            Profile::Monochrome,
            Some(c(&[(0.0, 0.03), (0.25, 0.22), (0.75, 0.79), (1.0, 0.98)])),
            &[
                (p::CONTRAST, 15.0),
                (p::MIX_LUM[0], 10.0),
                (p::MIX_LUM[1], 15.0),
                (p::MIX_LUM[5], -20.0),
                (p::GRAIN, 30.0),
                (p::GRAIN_SIZE, 35.0),
                (p::GRAIN_ROUGHNESS, 60.0),
                (p::VIGNETTE, -10.0),
            ],
        ),
        look(
            "Sepia",
            Profile::Monochrome,
            Some(c(&[(0.0, 0.05), (1.0, 0.96)])),
            &[(p::CONTRAST, 5.0), (p::GRADE_HUE[3], 38.0), (p::GRADE_SAT[3], 22.0), (p::GRAIN, 15.0), (p::VIGNETTE, -15.0)],
        ),
    ];
    all.extend(films());
    all
}

/// Group names as the phone writes them.
pub fn group_name(g: Group) -> &'static str {
    match g {
        Group::Light => "LIGHT",
        Group::Curve => "CURVE",
        Group::Color => "COLOR",
        Group::Mixer => "MIXER",
        Group::Grading => "GRADING",
        Group::Effects => "EFFECTS",
        Group::Detail => "DETAIL",
        Group::Optics => "OPTICS",
        Group::Geometry => "GEOMETRY",
        Group::Crop => "CROP",
    }
}

pub const GROUPS: [Group; 10] =
    [Group::Light, Group::Curve, Group::Color, Group::Mixer, Group::Grading, Group::Effects, Group::Detail, Group::Optics, Group::Geometry, Group::Crop];

pub fn dir(root: &Path) -> PathBuf {
    root.join(STATE_DIR).join("presets")
}

/// A preset's file text, as the phone writes it.
pub fn encode(settings: &Settings, groups: &[Group]) -> String {
    let names: Vec<&str> = groups.iter().map(|g| group_name(*g)).collect();
    format!("groups={}\n{}", names.join(","), Settings::default().paste_from(settings, groups).encode())
}

pub fn decode(name: &str, text: &str) -> Preset {
    let groups = text
        .lines()
        .find_map(|l| l.strip_prefix("groups="))
        .map_or(GROUPS.to_vec(), |g| g.split(',').filter_map(|n| GROUPS.into_iter().find(|x| group_name(*x) == n.trim())).collect());
    Preset { name: name.into(), settings: Settings::decode(text), groups: Some(groups), film: false }
}

/// A name made safe for a file, as the phone does it.
pub fn safe_name(name: &str) -> String {
    let s: String = name.chars().filter(|c| c.is_alphanumeric() || " _-".contains(*c)).collect();
    let s = s.trim();
    if s.is_empty() {
        "Preset".into()
    } else {
        s.into()
    }
}

/// The library's own presets, by name.
pub fn user(root: &Path) -> Vec<Preset> {
    let mut out: Vec<Preset> = fs::read_dir(dir(root))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().strip_suffix(".txt")?.to_string();
            Some(decode(&name, &fs::read_to_string(e.path()).ok()?))
        })
        .collect();
    out.sort_by_key(|p| p.name.to_lowercase());
    out
}

/// Saves `settings`' `groups` as a preset; answers the name it got.
pub fn save(root: &Path, name: &str, settings: &Settings, groups: &[Group]) -> io::Result<String> {
    let name = safe_name(name);
    fs::create_dir_all(dir(root))?;
    write_atomic(&dir(root).join(format!("{name}.txt")), encode(settings, groups).as_bytes())?;
    Ok(name)
}

/// The preset files, by name.
pub fn files(root: &Path) -> Vec<(String, PathBuf)> {
    let mut out: Vec<(String, PathBuf)> = fs::read_dir(dir(root))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| Some((e.file_name().to_string_lossy().strip_suffix(".txt")?.to_string(), e.path())))
        .collect();
    out.sort();
    out
}

/// Writes a preset that came from the phone as it is (empty text deletes it), dated `modified_ms`.
pub fn put(root: &Path, name: &str, text: &str, modified_ms: Option<i64>) -> io::Result<()> {
    if text.trim().is_empty() {
        return delete(root, name);
    }
    let f = dir(root).join(format!("{}.txt", safe_name(name)));
    fs::create_dir_all(dir(root))?;
    write_atomic(&f, text.as_bytes())?;
    if let Some(ms) = modified_ms.filter(|m| *m > 0) {
        let t = std::time::UNIX_EPOCH + std::time::Duration::from_millis(ms as u64);
        let _ = fs::File::options().write(true).open(&f).and_then(|h| h.set_modified(t));
    }
    Ok(())
}

pub fn delete(root: &Path, name: &str) -> io::Result<()> {
    match fs::remove_file(dir(root).join(format!("{}.txt", safe_name(name)))) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn built_ins_match_the_phone() {
        let all = built_in();
        assert_eq!(all.len(), 21 + 8);
        assert_eq!(all[0].name, "None");
        assert_eq!(all.iter().filter(|p| p.film).count(), 8);
        let portra = all.iter().find(|p| p.name == "Portra 400").unwrap();
        assert_eq!(portra.settings.profile, Profile::Neutral);
        assert_eq!(portra.settings.get(&p::MIX_SAT[3]), -25.0);
    }

    #[test]
    fn a_look_keeps_corrections_and_swaps_the_rest() {
        let all = built_in();
        let punchy = all.iter().find(|p| p.name == "Punchy").unwrap();
        let matte = all.iter().find(|p| p.name == "Matte").unwrap();
        let photo = Settings::default().with(&p::EXPOSURE, 0.7).with(&p::SHARPEN, 40.0);
        let a = punchy.apply_to(&photo);
        assert!(punchy.is_applied(&a));
        let b = matte.apply_to(&a);
        assert_eq!(b.get(&p::EXPOSURE), 0.7);
        assert_eq!(b.get(&p::SHARPEN), 40.0);
        assert_eq!(b.get(&p::CLARITY), 0.0);
        assert_eq!(b.profile, Profile::Neutral);
    }

    #[test]
    fn user_presets_round_trip_in_the_phone_format() {
        let dir = tempfile::tempdir().unwrap();
        let s = Settings::default().with(&p::CONTRAST, 20.0).with(&p::SHARPEN, 30.0);
        let name = save(dir.path(), "My look!", &s, &[Group::Light]).unwrap();
        assert_eq!(name, "My look");
        let text = fs::read_to_string(super::dir(dir.path()).join("My look.txt")).unwrap();
        assert!(text.starts_with("groups=LIGHT\n"));
        let back = &user(dir.path())[0];
        assert_eq!(back.groups.as_deref(), Some(&[Group::Light][..]));
        assert_eq!(back.settings.get(&p::CONTRAST), 20.0);
        assert_eq!(back.settings.get(&p::SHARPEN), 0.0);
        delete(dir.path(), "My look").unwrap();
        assert!(user(dir.path()).is_empty());
    }
}
