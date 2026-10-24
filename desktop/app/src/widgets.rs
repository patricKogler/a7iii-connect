//! The few controls the design needs beyond egui's own: seal buttons, outlines, the tab rule,
//! chips and the small section labels. They take their colours and shapes from the theme.

use crate::theme::{display, palette, semibold};
use eframe::egui::{self, text::LayoutJob, Align, Color32, FontId, Rect, Response, RichText, Rounding, Sense, Stroke, TextFormat, Ui, Vec2};

/// A filled vermilion button: the one main action in a place.
pub fn seal_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    let stroke = if p.shadow > 0.0 { Stroke::new(p.line, p.shadow_color) } else { Stroke::NONE };
    shadowed(ui, |ui| {
        ui.add(
            egui::Button::new(RichText::new(text).color(p.on_seal).family(semibold()))
                .fill(p.seal)
                .stroke(stroke)
                .rounding(Rounding::same(p.radius))
                .min_size(Vec2::new(0.0, 34.0)),
        )
    })
}

/// Adds a widget with the theme's hard shadow under it, when the theme has one. The shadow halves
/// while the widget is pressed, so it reads as pushed in.
pub fn shadowed(ui: &mut Ui, add: impl FnOnce(&mut Ui) -> Response) -> Response {
    let p = palette(ui.ctx());
    if p.shadow <= 0.0 {
        return add(ui);
    }
    let under = ui.painter().add(egui::Shape::Noop);
    let resp = add(ui);
    let offset = if resp.is_pointer_button_down_on() { p.shadow * 0.5 } else { p.shadow };
    ui.painter().set(under, egui::Shape::rect_filled(resp.rect.translate(Vec2::splat(offset)), p.radius, p.shadow_color));
    resp
}

/// An outlined button.
pub fn outline_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    let fill = if p.shadow > 0.0 { p.panel } else { Color32::TRANSPARENT };
    shadowed(ui, |ui| {
        ui.add(
            egui::Button::new(RichText::new(text).color(p.text))
                .fill(fill)
                .stroke(Stroke::new(p.line, p.control))
                .rounding(Rounding::same(p.radius))
                .min_size(Vec2::new(0.0, 32.0)),
        )
    })
}

/// A choice among a few: vermilion outline and tint when chosen.
pub fn chip(ui: &mut Ui, selected: bool, text: &str) -> Response {
    chip_on(ui, selected, text, Color32::TRANSPARENT)
}

/// A chip over the dark stage: on a panel-coloured ground, so it reads in light themes too.
pub fn stage_chip(ui: &mut Ui, selected: bool, text: &str) -> Response {
    let p = palette(ui.ctx());
    chip_on(ui, selected, text, p.panel)
}

fn chip_on(ui: &mut Ui, selected: bool, text: &str, ground: Color32) -> Response {
    let p = palette(ui.ctx());
    let (fill, stroke, color) = if selected {
        (if ground == Color32::TRANSPARENT { p.seal.gamma_multiply(0.10) } else { mix(ground, p.seal, 0.10) }, Stroke::new(p.line + 0.5, p.accent), p.strong)
    } else {
        (ground, Stroke::new(p.line, p.control), p.text)
    };
    ui.add(egui::Button::new(RichText::new(text).color(color)).fill(fill).stroke(stroke).rounding(Rounding::same(p.radius)).min_size(Vec2::new(0.0, 28.0)))
}

fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    (egui::Rgba::from(a) * (1.0 - t) + egui::Rgba::from(b) * t).into()
}

/// A top-bar tab: ink type over a vermilion rule when it's the current one.
pub fn tab(ui: &mut Ui, selected: bool, text: &str) -> Response {
    let p = palette(ui.ctx());
    let font = if selected { FontId::new(14.0, semibold()) } else { FontId::proportional(14.0) };
    let color = if selected { p.strong } else { p.text2 };
    let galley = ui.painter().layout_no_wrap(text.to_string(), font, color);
    let size = galley.size() + Vec2::new(24.0, 18.0);
    let (rect, resp) = ui.allocate_exact_size(size, Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    let pos = rect.center() - galley.size() / 2.0;
    ui.painter().galley(pos, galley, color);
    if selected {
        ui.painter().rect_filled(
            Rect::from_min_max(egui::pos2(rect.left() + 8.0, rect.bottom() - 2.0), egui::pos2(rect.right() - 8.0, rect.bottom())),
            0.0,
            p.seal,
        );
    } else if resp.hovered() {
        ui.painter().rect_filled(
            Rect::from_min_max(egui::pos2(rect.left() + 8.0, rect.bottom() - 1.0), egui::pos2(rect.right() - 8.0, rect.bottom())),
            0.0,
            p.control,
        );
    }
    resp
}

/// A small heading in the display face, like the design's section labels.
pub fn section_label(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    let spacing = (1.6 * p.tracking).max(if p.caps { 0.8 } else { 0.0 });
    let text = if p.caps { text.to_uppercase() } else { text.to_string() };
    job.append(&text, 0.0, TextFormat { font_id: FontId::new(13.0, display()), color: p.text3, extra_letter_spacing: spacing, ..Default::default() });
    ui.label(job);
}

pub fn heading(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    job.append(text, 0.0, TextFormat { font_id: FontId::new(26.0, display()), color: p.strong, extra_letter_spacing: 0.8 * p.tracking, ..Default::default() });
    ui.label(job);
}

pub fn subheading(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    job.append(text, 0.0, TextFormat { font_id: FontId::new(18.0, display()), color: p.strong, extra_letter_spacing: 0.6 * p.tracking, ..Default::default() });
    ui.label(job);
}

/// A row in the sidebar: name on the left, count on the right, a tint when current.
pub fn nav_row(ui: &mut Ui, selected: bool, text: &str, count: Option<usize>) -> Response {
    let p = palette(ui.ctx());
    let width = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(width, 30.0), Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    if selected {
        ui.painter().rect_filled(rect, p.radius, p.selected);
        ui.painter().rect_filled(Rect::from_min_size(rect.min, Vec2::new(2.0, rect.height())), 0.0, p.seal);
    } else if resp.hovered() {
        ui.painter().rect_filled(rect, p.radius, p.selected.gamma_multiply(0.6));
    }
    let font = if selected { FontId::new(13.5, semibold()) } else { FontId::proportional(13.5) };
    ui.painter().text(egui::pos2(rect.left() + 10.0, rect.center().y), egui::Align2::LEFT_CENTER, text, font, if selected { p.strong } else { p.text });
    if let Some(n) = count {
        let c = if selected && n > 0 { p.accent } else { p.text3 };
        ui.painter().text(egui::pos2(rect.right() - 10.0, rect.center().y), egui::Align2::RIGHT_CENTER, thousands(n), FontId::proportional(12.5), c);
    }
    resp
}

/// A status pill in the top bar: a coloured dot and a few words.
pub fn status_pill(ui: &mut Ui, dot: Color32, text: &str, emphasised: bool) -> Response {
    let p = palette(ui.ctx());
    let galley = ui.painter().layout_no_wrap(text.to_string(), FontId::proportional(13.0), p.text);
    let size = Vec2::new(galley.size().x + 36.0, 30.0);
    let (rect, resp) = ui.allocate_exact_size(size, Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    let stroke = if emphasised { Stroke::new(p.line + 0.5, dot) } else { Stroke::new(p.line, p.control) };
    ui.painter().rect(rect, p.radius, if resp.hovered() { p.selected } else { Color32::TRANSPARENT }, stroke);
    ui.painter().circle_filled(egui::pos2(rect.left() + 14.0, rect.center().y), 4.0, dot);
    ui.painter().galley(egui::pos2(rect.left() + 26.0, rect.center().y - galley.size().y / 2.0), galley, p.text);
    resp
}

/// A small seal stamped on a tile, in the theme's main colour.
pub fn paint_seal(painter: &egui::Painter, at: egui::Pos2, text: &str, align: egui::Align2) {
    let p = crate::theme::current().palette;
    let galley = painter.layout_no_wrap(text.to_string(), FontId::new(11.5, display()), p.on_seal);
    let size = galley.size() + Vec2::new(10.0, 4.0);
    let rect = align.anchor_size(at, size);
    painter.rect_filled(rect, p.tile_radius, p.seal);
    painter.galley(rect.min + Vec2::new(5.0, 2.0), galley, p.on_seal);
}

/// A quiet badge on a tile: ink type on translucent paper (or the reverse on sumi).
pub fn paint_badge(ui: &Ui, painter: &egui::Painter, at: egui::Pos2, text: &str, align: egui::Align2, outlined: bool) {
    let p = palette(ui.ctx());
    let galley = painter.layout_no_wrap(text.to_string(), FontId::proportional(10.5), p.text);
    let size = galley.size() + Vec2::new(10.0, 4.0);
    let rect = align.anchor_size(at, size);
    painter.rect(rect, p.tile_radius, p.bg.gamma_multiply(0.9), if outlined { Stroke::new(1.0_f32, p.text) } else { Stroke::NONE });
    painter.galley(rect.min + Vec2::new(5.0, 2.0), galley, p.text);
}

/// The seal from the launcher icon, drawn in `color` inside `rect`.
pub fn paint_logo(painter: &egui::Painter, rect: Rect, color: Color32) {
    // In the launcher icon's 108-unit space, the seal spans about 32..76; rotated -4°.
    let k = rect.width() / 52.0;
    let c = rect.center();
    let (s, co) = (-4f32).to_radians().sin_cos();
    let at = |x: f32, y: f32| {
        let (dx, dy) = (x - 54.0, y - 54.0);
        c + Vec2::new(dx * co - dy * s, dx * s + dy * co) * k
    };
    let ink = |w: f32| Stroke::new(w * k, color);
    let frame = [at(32.0, 31.7), at(76.0, 31.7), at(76.0, 75.7), at(32.0, 75.7), at(32.0, 31.7)];
    painter.add(egui::Shape::line(frame.to_vec(), ink(2.6)));
    painter.line_segment([at(35.0, 50.3), at(48.0, 50.3)], ink(2.2));
    painter.line_segment([at(60.0, 58.0), at(73.0, 58.0)], ink(2.2));
    painter.circle_stroke(at(41.0, 49.9), 4.2 * k, ink(2.0));
    painter.circle_stroke(at(67.2, 58.1), 4.2 * k, ink(2.0));
    painter.circle_filled(at(41.0, 50.0), 1.6 * k, color);
    painter.circle_filled(at(67.0, 58.0), 1.6 * k, color);
    let bolt = [at(60.2, 35.1), at(54.4, 44.0), at(49.2, 54.9), at(56.8, 56.1), at(52.3, 67.4), at(48.5, 73.8)];
    painter.add(egui::Shape::line(bolt.to_vec(), ink(2.6)));
}

/// 1,284
pub fn thousands(n: usize) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, ch) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

/// Label and value in two lines, small label above.
pub fn fact(ui: &mut Ui, label: &str, value: &str) {
    let p = palette(ui.ctx());
    ui.vertical(|ui| {
        ui.add(egui::Label::new(RichText::new(label).size(11.5).color(p.text3)).extend());
        ui.add(egui::Label::new(RichText::new(value).color(p.text)).extend());
    });
}

pub fn hint(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    ui.add(egui::Label::new(RichText::new(text).color(p.text2)).wrap());
}

pub fn right_to_left(ui: &mut Ui, add: impl FnOnce(&mut Ui)) {
    ui.with_layout(egui::Layout::right_to_left(Align::Center), add);
}

/// How much slower a slider moves when the pointer is `away` points above or below it.
fn scrub_speed(away: f32) -> f32 {
    match away {
        a if a < 40.0 => 1.0,
        a if a < 120.0 => 0.5,
        a if a < 220.0 => 0.25,
        _ => 0.1,
    }
}

/// A develop slider: label and value over a thin track, filled in the accent from the slider's
/// resting point to its value. Click to set, drag to move: finer as the pointer strays from the
/// slider, and with Shift. Double-click resets.
pub struct DevelopSlider<'a> {
    pub label: &'a str,
    /// The value as shown.
    pub text: String,
    pub value: f32,
    pub min: f32,
    pub max: f32,
    /// Where the fill starts.
    pub origin: f32,
    /// What a double-click restores.
    pub default: f32,
    pub swatch: Option<Color32>,
}

impl DevelopSlider<'_> {
    /// The new value, when it changed.
    pub fn show(self, ui: &mut Ui) -> Option<f32> {
        let p = palette(ui.ctx());
        let w = ui.available_width();
        let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, 36.0), Sense::click_and_drag());
        let resp = resp.on_hover_cursor(egui::CursorIcon::ResizeHorizontal);
        let painter = ui.painter();
        let mut x = rect.left();
        if let Some(c) = self.swatch {
            painter.rect_filled(Rect::from_min_size(egui::pos2(x, rect.top() + 3.0), Vec2::splat(9.0)), 1.0, c);
            x += 15.0;
        }
        let active = resp.hovered() || resp.dragged();
        painter.text(egui::pos2(x, rect.top()), egui::Align2::LEFT_TOP, self.label, FontId::proportional(12.5), if active { p.text } else { p.text2 });
        painter.text(egui::pos2(rect.right(), rect.top()), egui::Align2::RIGHT_TOP, &self.text, FontId::proportional(12.5), p.text);
        let y = rect.bottom() - 8.0;
        let track = Rect::from_min_max(egui::pos2(rect.left() + 4.0, y - 1.0), egui::pos2(rect.right() - 4.0, y + 1.0));
        let span = self.max - self.min;
        let x_of = |v: f32| track.left() + ((v - self.min) / span).clamp(0.0, 1.0) * track.width();
        painter.rect_filled(track, 0.0, p.border);
        let (a, b) = (x_of(self.origin), x_of(self.value));
        painter.rect_filled(Rect::from_x_y_ranges(a.min(b)..=a.max(b), track.y_range()), 0.0, p.accent);
        let knob = Rect::from_center_size(egui::pos2(b, y), Vec2::new(if active { 7.0 } else { 5.0 }, 14.0));
        painter.rect_filled(knob, 1.0, if active { p.strong } else { p.text });
        if resp.double_clicked() {
            return Some(self.default);
        }
        // A press sets the value where it lands; a drag then moves it by how far the pointer
        // travels, slower the further the pointer strays above or below the slider (as on the
        // phone), and ten times slower with Shift. The unrounded value is kept while dragging, so
        // slow drags still add up on whole-number sliders.
        let raw_id = resp.id.with("raw");
        let new = if resp.dragged() && !resp.drag_started() {
            let pointer = resp.interact_pointer_pos()?;
            let away = (rect.top() - pointer.y).max(pointer.y - rect.bottom()).max(0.0);
            let speed = scrub_speed(away) * if ui.input(|i| i.modifiers.shift) { 0.1 } else { 1.0 };
            if speed < 1.0 {
                let label = match speed {
                    s if s >= 0.5 => "Half speed",
                    s if s >= 0.25 => "Fine",
                    _ => "Very fine",
                };
                let at = egui::pos2(rect.center().x, rect.top());
                ui.painter().text(at, egui::Align2::CENTER_TOP, label, FontId::proportional(11.5), p.accent);
            }
            let raw: f32 = ui.data(|d| d.get_temp(raw_id)).unwrap_or(self.value);
            let raw = (raw + resp.drag_delta().x / track.width() * span * speed).clamp(self.min, self.max);
            ui.data_mut(|d| d.insert_temp(raw_id, raw));
            raw
        } else if resp.is_pointer_button_down_on() {
            let px = resp.interact_pointer_pos()?.x;
            let v = (self.min + (px - track.left()) / track.width() * span).clamp(self.min, self.max);
            ui.data_mut(|d| d.insert_temp(raw_id, v));
            v
        } else {
            return None;
        };
        let new = new.clamp(self.min, self.max);
        (new != self.value).then_some(new)
    }
}
