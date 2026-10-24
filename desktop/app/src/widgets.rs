//! The few controls the design needs beyond egui's own: seal buttons, ink outlines, the tab rule,
//! chips and the small Mincho section labels.

use crate::theme::{mincho, palette, semibold};
use eframe::egui::{self, text::LayoutJob, Align, Color32, FontId, Rect, Response, RichText, Rounding, Sense, Stroke, TextFormat, Ui, Vec2};

/// A filled vermilion button: the one main action in a place.
pub fn seal_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.on_seal).family(semibold()))
            .fill(p.seal)
            .stroke(Stroke::NONE)
            .rounding(Rounding::same(2.0))
            .min_size(Vec2::new(0.0, 34.0)),
    )
}

/// An ink-outlined button.
pub fn outline_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.text))
            .fill(Color32::TRANSPARENT)
            .stroke(Stroke::new(1.0_f32, p.control))
            .rounding(Rounding::same(2.0))
            .min_size(Vec2::new(0.0, 32.0)),
    )
}

/// A choice among a few: vermilion outline and tint when chosen.
pub fn chip(ui: &mut Ui, selected: bool, text: &str) -> Response {
    let p = palette(ui.ctx());
    let (fill, stroke, color) = if selected {
        (p.seal.gamma_multiply(0.10), Stroke::new(1.5_f32, p.accent), p.strong)
    } else {
        (Color32::TRANSPARENT, Stroke::new(1.0_f32, p.control), p.text)
    };
    ui.add(egui::Button::new(RichText::new(text).color(color)).fill(fill).stroke(stroke).rounding(Rounding::same(2.0)).min_size(Vec2::new(0.0, 28.0)))
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

/// A small heading in Mincho with wide tracking, like the design's section labels.
pub fn section_label(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    job.append(text, 0.0, TextFormat { font_id: FontId::new(13.0, mincho()), color: p.text3, extra_letter_spacing: 1.6, ..Default::default() });
    ui.label(job);
}

pub fn heading(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    job.append(text, 0.0, TextFormat { font_id: FontId::new(26.0, mincho()), color: p.strong, extra_letter_spacing: 0.8, ..Default::default() });
    ui.label(job);
}

pub fn subheading(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    let mut job = LayoutJob::default();
    job.append(text, 0.0, TextFormat { font_id: FontId::new(18.0, mincho()), color: p.strong, extra_letter_spacing: 0.6, ..Default::default() });
    ui.label(job);
}

/// A row in the sidebar: name on the left, count on the right, a tint when current.
pub fn nav_row(ui: &mut Ui, selected: bool, text: &str, count: Option<usize>) -> Response {
    let p = palette(ui.ctx());
    let width = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(width, 30.0), Sense::click());
    let resp = resp.on_hover_cursor(egui::CursorIcon::PointingHand);
    if selected {
        ui.painter().rect_filled(rect, 2.0, p.selected);
        ui.painter().rect_filled(Rect::from_min_size(rect.min, Vec2::new(2.0, rect.height())), 0.0, p.seal);
    } else if resp.hovered() {
        ui.painter().rect_filled(rect, 2.0, p.selected.gamma_multiply(0.6));
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
    let stroke = if emphasised { Stroke::new(1.5_f32, dot) } else { Stroke::new(1.0_f32, p.control) };
    ui.painter().rect(rect, 2.0, if resp.hovered() { p.selected } else { Color32::TRANSPARENT }, stroke);
    ui.painter().circle_filled(egui::pos2(rect.left() + 14.0, rect.center().y), 4.0, dot);
    ui.painter().galley(egui::pos2(rect.left() + 26.0, rect.center().y - galley.size().y / 2.0), galley, p.text);
    resp
}

/// A small seal stamped on a tile: vermilion with paper-coloured type.
pub fn paint_seal(painter: &egui::Painter, at: egui::Pos2, text: &str, align: egui::Align2) {
    let galley = painter.layout_no_wrap(text.to_string(), FontId::new(11.5, mincho()), crate::theme::WASHI.on_seal);
    let size = galley.size() + Vec2::new(10.0, 4.0);
    let rect = align.anchor_size(at, size);
    painter.rect_filled(rect, 1.0, crate::theme::WASHI.seal);
    painter.galley(rect.min + Vec2::new(5.0, 2.0), galley, crate::theme::WASHI.on_seal);
}

/// A quiet badge on a tile: ink type on translucent paper (or the reverse on sumi).
pub fn paint_badge(ui: &Ui, painter: &egui::Painter, at: egui::Pos2, text: &str, align: egui::Align2, outlined: bool) {
    let p = palette(ui.ctx());
    let galley = painter.layout_no_wrap(text.to_string(), FontId::proportional(10.5), p.text);
    let size = galley.size() + Vec2::new(10.0, 4.0);
    let rect = align.anchor_size(at, size);
    painter.rect(rect, 1.0, p.bg.gamma_multiply(0.9), if outlined { Stroke::new(1.0_f32, p.text) } else { Stroke::NONE });
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
        ui.label(RichText::new(label).size(11.5).color(p.text3));
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
