//! The app's looks. Each theme is a palette, a set of three fonts (body, semibold, display) and a
//! few shape tokens: corner radius, line weight and an optional hard shadow. Washi and sumi are the
//! look from the logo, a hanko seal on paper; the others come from the design canvas.
//!
//! The current theme is global: `apply` installs its fonts and visuals and every screen reads
//! `palette(ctx)` while it draws.

use crate::state::ThemeChoice;
use eframe::egui::{self, epaint::Shadow, Color32, FontData, FontDefinitions, FontFamily, FontId, FontTweak, Rounding, Stroke, TextStyle};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

#[derive(Clone, Copy, Debug)]
pub struct Palette {
    pub bg: Color32,
    pub panel: Color32,
    pub border: Color32,
    pub control: Color32,
    pub selected: Color32,
    pub text: Color32,
    pub strong: Color32,
    pub text2: Color32,
    pub text3: Color32,
    /// Fills for the main action: buttons, picks, progress.
    pub seal: Color32,
    /// The accent for text and outlines (brighter than `seal` on dark themes, to stay readable).
    pub accent: Color32,
    pub on_seal: Color32,
    pub good: Color32,
    /// Behind a photo shown large: always a dark neutral, so colours read true.
    pub stage: Color32,
    pub dark: bool,
    /// Corner radius of controls and cards.
    pub radius: f32,
    /// Corner radius of photo tiles.
    pub tile_radius: f32,
    /// Width of outlines.
    pub line: f32,
    /// Offset of a hard drop shadow under buttons and cards; 0 for none.
    pub shadow: f32,
    pub shadow_color: Color32,
    /// Letter spacing for display type, in points at 18 pt.
    pub tracking: f32,
    /// Section labels in capitals.
    pub caps: bool,
}

impl Palette {
    /// The hard shadow for cards and windows, or none.
    pub fn hard_shadow(&self) -> Shadow {
        if self.shadow > 0.0 {
            Shadow { offset: egui::vec2(self.shadow, self.shadow), blur: 0.0, spread: 0.0, color: self.shadow_color }
        } else {
            Shadow::NONE
        }
    }
}

const fn rgb(hex: u32) -> Color32 {
    Color32::from_rgb((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

/// A light theme's shape defaults, to start a palette from.
const BASE: Palette = Palette {
    bg: rgb(0xFFFFFF),
    panel: rgb(0xFFFFFF),
    border: rgb(0xFFFFFF),
    control: rgb(0xFFFFFF),
    selected: rgb(0xFFFFFF),
    text: rgb(0x000000),
    strong: rgb(0x000000),
    text2: rgb(0x000000),
    text3: rgb(0x000000),
    seal: rgb(0x000000),
    accent: rgb(0x000000),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x000000),
    stage: rgb(0x1A1A1A),
    dark: false,
    radius: 6.0,
    tile_radius: 3.0,
    line: 1.0,
    shadow: 0.0,
    shadow_color: rgb(0x000000),
    tracking: 0.0,
    caps: false,
};

pub const WASHI: Palette = Palette {
    bg: rgb(0xF4EFE4),
    panel: rgb(0xEEE7D9),
    border: rgb(0xD6CCB8),
    control: rgb(0xBFB29B),
    selected: rgb(0xE6DCCA),
    text: rgb(0x22201D),
    strong: rgb(0x151412),
    text2: rgb(0x5B544C),
    text3: rgb(0x6B6359),
    seal: rgb(0xB3202A),
    accent: rgb(0xB3202A),
    on_seal: rgb(0xFBF7EF),
    good: rgb(0x3C6E4F),
    stage: rgb(0x34312D),
    radius: 2.0,
    tile_radius: 1.0,
    tracking: 1.0,
    ..BASE
};

pub const SUMI: Palette = Palette {
    bg: rgb(0x161412),
    panel: rgb(0x1D1A17),
    border: rgb(0x34302B),
    control: rgb(0x48423A),
    selected: rgb(0x2C2824),
    text: rgb(0xECE5D7),
    strong: rgb(0xF6F0E4),
    text2: rgb(0xA9A094),
    text3: rgb(0x968D80),
    seal: rgb(0xB3202A),
    accent: rgb(0xE5615A),
    on_seal: rgb(0xFBF7EF),
    good: rgb(0x6FAF86),
    stage: rgb(0x0E0D0C),
    dark: true,
    ..WASHI
};

const AMBER: Palette = Palette {
    bg: rgb(0x111214),
    panel: rgb(0x151618),
    border: rgb(0x26282B),
    control: rgb(0x3A3C40),
    selected: rgb(0x2A2C30),
    text: rgb(0xE8E6E3),
    strong: rgb(0xF2F0ED),
    text2: rgb(0xA9A7A3),
    text3: rgb(0x8D8B87),
    seal: rgb(0xE8A33D),
    accent: rgb(0xE8A33D),
    on_seal: rgb(0x1A1408),
    good: rgb(0x5FBF7F),
    stage: rgb(0x0B0B0C),
    dark: true,
    caps: true,
    ..BASE
};

const GRAPHITE: Palette = Palette {
    bg: rgb(0x161618),
    panel: rgb(0x1C1C1E),
    border: rgb(0x2E2E31),
    control: rgb(0x3A3A3D),
    selected: rgb(0x2C2C2E),
    text: rgb(0xE5E5E7),
    strong: rgb(0xFFFFFF),
    text2: rgb(0x98989F),
    text3: rgb(0x8A8A90),
    seal: rgb(0x0A84FF),
    accent: rgb(0x409CFF),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x30D158),
    stage: rgb(0x0E0E0F),
    dark: true,
    ..BASE
};

const HAIRLINE: Palette = Palette {
    bg: rgb(0x0F0F10),
    panel: rgb(0x0F0F10),
    border: rgb(0x232326),
    control: rgb(0x34343A),
    selected: rgb(0x1F1F22),
    text: rgb(0xEDEDEF),
    strong: rgb(0xFFFFFF),
    text2: rgb(0x8A8A93),
    text3: rgb(0x7E7E87),
    seal: rgb(0xEDEDEF),
    accent: rgb(0xEDEDEF),
    on_seal: rgb(0x0F0F10),
    good: rgb(0x3DD68C),
    stage: rgb(0x0A0A0B),
    dark: true,
    radius: 5.0,
    ..BASE
};

const DAYLIGHT: Palette = Palette {
    bg: rgb(0xFFFFFF),
    panel: rgb(0xF5F5F7),
    border: rgb(0xE5E5EA),
    control: rgb(0xD1D1D6),
    selected: rgb(0xE8E8ED),
    text: rgb(0x1D1D1F),
    strong: rgb(0x000000),
    text2: rgb(0x6E6E73),
    text3: rgb(0x6E6E73),
    seal: rgb(0x0071E3),
    accent: rgb(0x0066CC),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x248A3D),
    stage: rgb(0x1C1C1E),
    radius: 7.0,
    ..BASE
};

const SEAL_DARK: Palette = Palette {
    bg: rgb(0x111111),
    panel: rgb(0x151515),
    border: rgb(0x262626),
    control: rgb(0x363636),
    selected: rgb(0x242424),
    text: rgb(0xE8E6E3),
    strong: rgb(0xFFFFFF),
    text2: rgb(0x9A9894),
    text3: rgb(0x8C8A86),
    seal: rgb(0xC8323A),
    accent: rgb(0xE0525A),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x5FBF7F),
    stage: rgb(0x0B0B0B),
    dark: true,
    ..BASE
};

const MONO: Palette = Palette {
    bg: rgb(0xFFFFFF),
    panel: rgb(0xFAFAFA),
    border: rgb(0xEAEAEA),
    control: rgb(0xD4D4D4),
    selected: rgb(0xF0F0F0),
    text: rgb(0x171717),
    strong: rgb(0x000000),
    text2: rgb(0x666666),
    text3: rgb(0x6B6B6B),
    seal: rgb(0x171717),
    accent: rgb(0x171717),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x1A7F37),
    radius: 5.0,
    ..BASE
};

const SWISS: Palette = Palette {
    bg: rgb(0xFFFFFF),
    panel: rgb(0xFFFFFF),
    border: rgb(0x000000),
    control: rgb(0x000000),
    selected: rgb(0xEFEFEF),
    text: rgb(0x000000),
    strong: rgb(0x000000),
    text2: rgb(0x555555),
    text3: rgb(0x555555),
    seal: rgb(0xD6001C),
    accent: rgb(0xD6001C),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x007A3D),
    radius: 0.0,
    tile_radius: 0.0,
    caps: true,
    ..BASE
};

const BAUHAUS: Palette = Palette {
    bg: rgb(0xF2EDE3),
    panel: rgb(0xEAE3D6),
    border: rgb(0x141414),
    control: rgb(0x141414),
    selected: rgb(0xF2B705),
    text: rgb(0x141414),
    strong: rgb(0x000000),
    text2: rgb(0x4A4740),
    text3: rgb(0x55514A),
    seal: rgb(0xD7262B),
    accent: rgb(0x1F4E9C),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x2E7D4F),
    radius: 0.0,
    tile_radius: 0.0,
    line: 2.0,
    tracking: 0.4,
    caps: true,
    ..BASE
};

const INDUSTRIAL: Palette = Palette {
    bg: rgb(0xE4E2DD),
    panel: rgb(0xD3D0C9),
    border: rgb(0xB9B5AD),
    control: rgb(0x9A978F),
    selected: rgb(0xF1EFEB),
    text: rgb(0x1E1E1E),
    strong: rgb(0x000000),
    text2: rgb(0x55524C),
    text3: rgb(0x5C5953),
    seal: rgb(0xFF5F00),
    accent: rgb(0xC24800),
    on_seal: rgb(0x1E1E1E),
    good: rgb(0x2F7D4A),
    stage: rgb(0x1E1E1E),
    radius: 4.0,
    tile_radius: 2.0,
    tracking: 0.6,
    caps: true,
    ..BASE
};

const NORDIC: Palette = Palette {
    bg: rgb(0xF7F6F3),
    panel: rgb(0xEFEDE8),
    border: rgb(0xE2DFD8),
    control: rgb(0xD2CEC5),
    selected: rgb(0xFFFFFF),
    text: rgb(0x2B2B2B),
    strong: rgb(0x1A1A1A),
    text2: rgb(0x5C5A55),
    text3: rgb(0x6A6862),
    seal: rgb(0x3E6B57),
    accent: rgb(0x3E6B57),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x3E6B57),
    stage: rgb(0x222222),
    radius: 10.0,
    tile_radius: 8.0,
    ..BASE
};

const MATERIAL: Palette = Palette {
    bg: rgb(0xFEF7FF),
    panel: rgb(0xF7F2FA),
    border: rgb(0xE7E0EC),
    control: rgb(0x79747E),
    selected: rgb(0xE8DEF8),
    text: rgb(0x1D1B20),
    strong: rgb(0x1D1B20),
    text2: rgb(0x49454F),
    text3: rgb(0x49454F),
    seal: rgb(0x6750A4),
    accent: rgb(0x6750A4),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x386A20),
    stage: rgb(0x1D1B20),
    radius: 20.0,
    tile_radius: 12.0,
    ..BASE
};

const DECO: Palette = Palette {
    bg: rgb(0x0E1A1A),
    panel: rgb(0x122222),
    border: rgb(0x4A4630),
    control: rgb(0xC9A24B),
    selected: rgb(0x1B2E2C),
    text: rgb(0xEADBB4),
    strong: rgb(0xF5EBCF),
    text2: rgb(0xBBA97E),
    text3: rgb(0xA8986F),
    seal: rgb(0xC9A24B),
    accent: rgb(0xC9A24B),
    on_seal: rgb(0x0E1A1A),
    good: rgb(0x7FB38A),
    stage: rgb(0x081010),
    dark: true,
    radius: 0.0,
    tile_radius: 0.0,
    tracking: 3.0,
    caps: true,
    ..BASE
};

const NEO: Palette = Palette {
    bg: rgb(0xFFF6E0),
    panel: rgb(0xFFFFFF),
    border: rgb(0x111111),
    control: rgb(0x111111),
    selected: rgb(0xFFD23F),
    text: rgb(0x111111),
    strong: rgb(0x000000),
    text2: rgb(0x3A3A3A),
    text3: rgb(0x4A4A4A),
    seal: rgb(0xFF5A36),
    accent: rgb(0xD8401F),
    on_seal: rgb(0x111111),
    good: rgb(0x1E9E5A),
    stage: rgb(0x111111),
    radius: 0.0,
    tile_radius: 0.0,
    line: 2.5,
    shadow: 4.0,
    shadow_color: rgb(0x111111),
    ..BASE
};

const RAW: Palette = Palette {
    bg: rgb(0xFFFFFF),
    panel: rgb(0xFFFFFF),
    border: rgb(0x000000),
    control: rgb(0x767676),
    selected: rgb(0xE9E9ED),
    text: rgb(0x000000),
    strong: rgb(0x000000),
    text2: rgb(0x333333),
    text3: rgb(0x444444),
    seal: rgb(0x0000EE),
    accent: rgb(0x0000EE),
    on_seal: rgb(0xFFFFFF),
    good: rgb(0x008000),
    stage: rgb(0x222222),
    radius: 0.0,
    tile_radius: 0.0,
    ..BASE
};

const PIXEL: Palette = Palette {
    bg: rgb(0x1D2B53),
    panel: rgb(0x000000),
    border: rgb(0x5F574F),
    control: rgb(0xC2C3C7),
    selected: rgb(0x7E2553),
    text: rgb(0xFFF1E8),
    strong: rgb(0xFFFFFF),
    text2: rgb(0xC2C3C7),
    text3: rgb(0xC2C3C7),
    seal: rgb(0xFFEC27),
    accent: rgb(0xFFEC27),
    on_seal: rgb(0x1D2B53),
    good: rgb(0x00E436),
    stage: rgb(0x000000),
    dark: true,
    radius: 0.0,
    tile_radius: 0.0,
    line: 2.0,
    shadow: 3.0,
    shadow_color: rgb(0x000000),
    ..BASE
};

/// One font of a theme, and how much to scale it so it sits with the others.
#[derive(Clone, Copy)]
struct Face(&'static str, &'static [u8], f32);

macro_rules! face {
    ($file:literal) => {
        face!($file, 1.0)
    };
    ($file:literal, $scale:expr) => {
        Face($file, include_bytes!(concat!("../assets/fonts/", $file, ".ttf")), $scale)
    };
}

const INTER: Face = face!("Inter-Regular");
const INTER_SEMI: Face = face!("Inter-SemiBold");

pub struct Theme {
    pub choice: ThemeChoice,
    pub name: &'static str,
    pub group: &'static str,
    pub palette: Palette,
    regular: Face,
    semibold: Face,
    display: Face,
}

const fn theme(choice: ThemeChoice, name: &'static str, group: &'static str, palette: Palette, fonts: [Face; 3]) -> Theme {
    Theme { choice, name, group, palette, regular: fonts[0], semibold: fonts[1], display: fonts[2] }
}

pub static THEMES: [Theme; 17] = [
    theme(ThemeChoice::Washi, "Washi", "Shootback", WASHI, [INTER, INTER_SEMI, face!("ShipporiMincho-SemiBold")]),
    theme(ThemeChoice::Sumi, "Sumi", "Shootback", SUMI, [INTER, INTER_SEMI, face!("ShipporiMincho-SemiBold")]),
    theme(ThemeChoice::Graphite, "Graphite", "Quiet", GRAPHITE, [INTER, INTER_SEMI, INTER_SEMI]),
    theme(ThemeChoice::Hairline, "Hairline dark", "Quiet", HAIRLINE, [INTER, INTER_SEMI, INTER_SEMI]),
    theme(ThemeChoice::Daylight, "Daylight", "Quiet", DAYLIGHT, [INTER, INTER_SEMI, INTER_SEMI]),
    theme(ThemeChoice::SealDark, "Seal dark", "Quiet", SEAL_DARK, [INTER, INTER_SEMI, INTER_SEMI]),
    theme(ThemeChoice::Mono, "Mono light", "Quiet", MONO, [face!("Geist-Regular"), face!("Geist-SemiBold"), face!("Geist-SemiBold")]),
    theme(ThemeChoice::Amber, "Amber", "Quiet", AMBER, [INTER, INTER_SEMI, INTER_SEMI]),
    theme(ThemeChoice::Swiss, "Swiss", "Styled", SWISS, [face!("InterTight-Regular"), face!("InterTight-SemiBold"), face!("InterTight-ExtraBold")]),
    theme(ThemeChoice::Bauhaus, "Bauhaus", "Styled", BAUHAUS, [face!("Jost-Regular", 1.05), face!("Jost-SemiBold", 1.05), face!("Jost-Bold", 1.05)]),
    theme(
        ThemeChoice::Industrial,
        "Industrial",
        "Styled",
        INDUSTRIAL,
        [face!("IBMPlexSans-Regular"), face!("IBMPlexSans-SemiBold"), face!("IBMPlexSans-SemiBold")],
    ),
    theme(ThemeChoice::Nordic, "Nordic", "Styled", NORDIC, [face!("Manrope-Regular"), face!("Manrope-SemiBold"), face!("Manrope-SemiBold")]),
    theme(ThemeChoice::Material, "Material", "Styled", MATERIAL, [face!("Roboto-Regular"), face!("Roboto-Medium"), face!("Roboto-Regular")]),
    theme(
        ThemeChoice::Deco,
        "Art Deco",
        "Styled",
        DECO,
        [face!("JosefinSans-Regular", 1.08), face!("JosefinSans-SemiBold", 1.08), face!("PoiretOne-Regular", 1.15)],
    ),
    theme(ThemeChoice::Neo, "Neobrutalism", "Loud", NEO, [face!("SpaceGrotesk-Regular"), face!("SpaceGrotesk-Bold"), face!("ArchivoBlack-Regular", 0.95)]),
    theme(ThemeChoice::Raw, "Raw HTML", "Loud", RAW, [face!("Tinos-Regular", 1.12), face!("Tinos-Bold", 1.12), face!("Tinos-Bold", 1.12)]),
    theme(ThemeChoice::Pixel, "8-bit", "Loud", PIXEL, [face!("VT323-Regular", 1.4), face!("VT323-Regular", 1.4), face!("PressStart2P-Regular", 0.62)]),
];

static CURRENT: AtomicUsize = AtomicUsize::new(0);
static FONTS_SET: AtomicBool = AtomicBool::new(false);

pub fn current() -> &'static Theme {
    &THEMES[CURRENT.load(Ordering::Relaxed)]
}

pub fn find(choice: ThemeChoice) -> Option<&'static Theme> {
    THEMES.iter().find(|t| t.choice == choice)
}

pub fn palette(_ctx: &egui::Context) -> Palette {
    current().palette
}

/// The family for names and headings.
pub fn display() -> FontFamily {
    FontFamily::Name("display".into())
}

pub fn semibold() -> FontFamily {
    FontFamily::Name("semibold".into())
}

/// The sizes and spacing every theme shares. Once, at start.
pub fn install(ctx: &egui::Context) {
    ctx.all_styles_mut(|s| {
        s.text_styles = [
            (TextStyle::Small, FontId::new(11.5, FontFamily::Proportional)),
            (TextStyle::Body, FontId::new(13.5, FontFamily::Proportional)),
            (TextStyle::Button, FontId::new(13.5, FontFamily::Proportional)),
            (TextStyle::Monospace, FontId::new(12.5, FontFamily::Monospace)),
            (TextStyle::Heading, FontId::new(24.0, display())),
        ]
        .into();
        s.spacing.item_spacing = egui::vec2(8.0, 6.0);
        s.spacing.button_padding = egui::vec2(12.0, 6.0);
        s.spacing.interact_size.y = 30.0;
        // A scrollbar that takes its own lane, so it never covers slider values or buttons.
        s.spacing.scroll = egui::style::ScrollStyle::solid();
    });
    apply(ctx, ThemeChoice::Washi);
}

/// Makes `choice` the current theme: its fonts and visuals. `System` stands for washi or sumi,
/// whichever matches the system.
pub fn apply(ctx: &egui::Context, choice: ThemeChoice) {
    let choice = match choice {
        ThemeChoice::System => match ctx.system_theme() {
            Some(egui::Theme::Dark) => ThemeChoice::Sumi,
            _ => ThemeChoice::Washi,
        },
        c => c,
    };
    let index = THEMES.iter().position(|t| t.choice == choice).unwrap_or(0);
    let before = &THEMES[CURRENT.swap(index, Ordering::Relaxed)];
    let t = &THEMES[index];
    let fonts_of = |t: &Theme| [t.regular.0, t.semibold.0, t.display.0];
    if fonts_of(before) != fonts_of(t) || !FONTS_SET.swap(true, Ordering::Relaxed) {
        ctx.set_fonts(fonts(t));
    }
    let v = visuals(&t.palette);
    ctx.set_visuals_of(egui::Theme::Light, v.clone());
    ctx.set_visuals_of(egui::Theme::Dark, v);
    ctx.set_theme(if t.palette.dark { egui::Theme::Dark } else { egui::Theme::Light });
}

fn fonts(t: &Theme) -> FontDefinitions {
    let mut fonts = FontDefinitions::default();
    for (key, face) in [("regular", t.regular), ("semibold", t.semibold), ("display", t.display)] {
        let tweak = FontTweak { scale: face.2, ..Default::default() };
        fonts.font_data.insert(key.into(), FontData::from_static(face.1).tweak(tweak));
    }
    // egui's own fonts stay behind ours for symbols the theme's fonts don't have.
    let fallbacks: Vec<String> = fonts.families[&FontFamily::Proportional].clone();
    let with = |first: &str| std::iter::once(first.to_string()).chain(fallbacks.iter().cloned()).collect::<Vec<_>>();
    fonts.families.insert(FontFamily::Proportional, with("regular"));
    fonts.families.insert(semibold(), with("semibold"));
    fonts.families.insert(display(), with("display"));
    fonts
}

fn visuals(p: &Palette) -> egui::Visuals {
    let mut v = if p.dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    let round = Rounding::same(p.radius);
    v.override_text_color = Some(p.text);
    v.panel_fill = p.bg;
    v.window_fill = p.panel;
    v.extreme_bg_color = p.bg;
    v.faint_bg_color = p.panel;
    v.code_bg_color = p.panel;
    v.hyperlink_color = p.accent;
    v.window_rounding = Rounding::same(p.radius.min(12.0));
    v.menu_rounding = Rounding::same(p.radius.min(12.0));
    v.window_stroke = Stroke::new(p.line, p.border);
    if p.shadow > 0.0 {
        v.window_shadow = p.hard_shadow();
        v.popup_shadow = p.hard_shadow();
    }
    v.selection.bg_fill = p.seal.gamma_multiply(if p.dark { 0.45 } else { 0.18 });
    v.selection.stroke = Stroke::new(1.0_f32, p.accent);
    v.slider_trailing_fill = true;
    for (w, fill, stroke) in [
        (&mut v.widgets.noninteractive, p.bg, p.border),
        (&mut v.widgets.inactive, p.bg, p.control),
        (&mut v.widgets.hovered, p.selected, p.text3),
        (&mut v.widgets.active, p.selected, p.accent),
        (&mut v.widgets.open, p.selected, p.control),
    ] {
        w.bg_fill = fill;
        w.weak_bg_fill = fill;
        w.bg_stroke = Stroke::new(p.line, stroke);
        w.rounding = round;
        w.fg_stroke = Stroke::new(1.0_f32, p.text);
    }
    v.widgets.noninteractive.bg_stroke.width = 1.0;
    v.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, p.text);
    v
}
