//! The look from the logo: a hanko seal on washi paper. Washi is the light theme, sumi ink the
//! dark one; vermilion is the only colour, type is Inter with Shippori Mincho for names and
//! headings, and edges are crisp.

use eframe::egui::{self, Color32, FontData, FontDefinitions, FontFamily, FontId, Rounding, Stroke, TextStyle, Theme};

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
    /// Seal fills: buttons, picks, progress.
    pub seal: Color32,
    /// Vermilion for text and outlines (brighter on sumi, to stay readable).
    pub accent: Color32,
    pub on_seal: Color32,
    pub good: Color32,
    /// Behind a photo shown large: always a dark neutral, so colours read true.
    pub stage: Color32,
}

const fn rgb(hex: u32) -> Color32 {
    Color32::from_rgb((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

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
};

pub fn palette(ctx: &egui::Context) -> Palette {
    match ctx.theme() {
        Theme::Light => WASHI,
        Theme::Dark => SUMI,
    }
}

/// The family for names and headings.
pub fn mincho() -> FontFamily {
    FontFamily::Name("mincho".into())
}

pub fn semibold() -> FontFamily {
    FontFamily::Name("semibold".into())
}

pub fn install(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    fonts.font_data.insert("inter".into(), FontData::from_static(include_bytes!("../assets/fonts/Inter-Regular.ttf")));
    fonts.font_data.insert("inter-semibold".into(), FontData::from_static(include_bytes!("../assets/fonts/Inter-SemiBold.ttf")));
    fonts.font_data.insert("mincho".into(), FontData::from_static(include_bytes!("../assets/fonts/ShipporiMincho-SemiBold.ttf")));
    // egui's own fonts stay behind ours for symbols Inter doesn't have.
    let fallbacks: Vec<String> = fonts.families[&FontFamily::Proportional].clone();
    let with = |first: &str| std::iter::once(first.to_string()).chain(fallbacks.iter().cloned()).collect::<Vec<_>>();
    fonts.families.insert(FontFamily::Proportional, with("inter"));
    fonts.families.insert(semibold(), with("inter-semibold"));
    fonts.families.insert(mincho(), with("mincho"));
    ctx.set_fonts(fonts);

    ctx.all_styles_mut(|s| {
        s.text_styles = [
            (TextStyle::Small, FontId::new(11.5, FontFamily::Proportional)),
            (TextStyle::Body, FontId::new(13.5, FontFamily::Proportional)),
            (TextStyle::Button, FontId::new(13.5, FontFamily::Proportional)),
            (TextStyle::Monospace, FontId::new(12.5, FontFamily::Monospace)),
            (TextStyle::Heading, FontId::new(24.0, mincho())),
        ]
        .into();
        s.spacing.item_spacing = egui::vec2(8.0, 6.0);
        s.spacing.button_padding = egui::vec2(12.0, 6.0);
        s.spacing.interact_size.y = 30.0;
    });
    ctx.set_visuals_of(Theme::Light, visuals(&WASHI, false));
    ctx.set_visuals_of(Theme::Dark, visuals(&SUMI, true));
}

fn visuals(p: &Palette, dark: bool) -> egui::Visuals {
    let mut v = if dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    let crisp = Rounding::same(2.0);
    v.override_text_color = Some(p.text);
    v.panel_fill = p.bg;
    v.window_fill = p.panel;
    v.extreme_bg_color = p.bg;
    v.faint_bg_color = p.panel;
    v.code_bg_color = p.panel;
    v.hyperlink_color = p.accent;
    v.window_rounding = crisp;
    v.menu_rounding = crisp;
    v.window_stroke = Stroke::new(1.0_f32, p.border);
    v.selection.bg_fill = p.seal.gamma_multiply(if dark { 0.45 } else { 0.18 });
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
        w.bg_stroke = Stroke::new(1.0_f32, stroke);
        w.rounding = crisp;
        w.fg_stroke = Stroke::new(1.0_f32, p.text);
    }
    v.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, p.text);
    v
}
