//! The Remove tool: paint over what should go (a click takes out a dust spot) and AI fills it in
//! from what's around it. Show spots turns the photo into a map of fine detail, where dust on
//! the sensor stands out against a clear sky.

use crate::develop_view::section;
use crate::editor::Editor;
use crate::theme::palette;
use crate::widgets::{chip, hint, outline_button, DevelopSlider};
use eframe::egui::{self, Color32, Key, Pos2, Rect, RichText, Sense, Stroke, Ui};
use shootback_core::ai::{self, catalog, Progress};
use shootback_core::develop::params::{Removal, Settings};
use shootback_core::develop::Opened;

/// The brush's radius, as a share of the photo's longer side.
const MIN: f32 = 0.002;
const MAX: f32 = 0.08;

pub fn panel(ui: &mut Ui, ed: &mut Editor, s: &mut Settings) {
    section(ui, "Remove", s, None, true, |ui, s| body(ui, ed, s));
}

fn body(ui: &mut Ui, ed: &mut Editor, s: &mut Settings) {
    let pal = palette(ui.ctx());
    hint(ui, "Paint over what should go: dust, a sign, a stray branch. A click takes out a spot. AI fills it in from what's around it.");
    ui.add_space(6.0);
    let v = ed.remove_radius;
    let slider =
        DevelopSlider { label: "Size  [ ]", text: format!("{:.1}", v * 100.0), value: v, min: MIN, max: MAX, origin: MIN, default: 0.012, swatch: None };
    if let Some(n) = slider.show(ui) {
        ed.remove_radius = n;
    }
    ui.add_space(4.0);
    ui.horizontal(|ui| {
        if chip(ui, ed.show_spots, "Show spots  A").on_hover_text("Fine detail only, so dust stands out").clicked() {
            ed.show_spots = !ed.show_spots;
        }
    });
    ui.add_space(8.0);
    let state = if let Some(Progress::Downloading(f)) = [&catalog::INPAINT, &catalog::RUNTIME].into_iter().find_map(ai::status) {
        Some(format!("Downloading the remove model {:.0}%", f * 100.0))
    } else if ed.remover.running() {
        Some("Filling in ...".to_string())
    } else {
        None
    };
    match (&state, &ed.remover.error) {
        (Some(t), _) => {
            ui.label(RichText::new(t).color(pal.text2));
            ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
        }
        (None, Some(e)) => {
            ui.label(RichText::new(format!("Couldn't fill it in: {e}")).color(pal.accent));
        }
        _ => {}
    }
    let n = s.removals.len();
    if n > 0 {
        ui.label(RichText::new(if n == 1 { "1 thing removed".to_string() } else { format!("{n} things removed") }).color(pal.text2));
        ui.horizontal(|ui| {
            if outline_button(ui, "Take back the last").clicked() {
                s.removals.pop();
            }
            if outline_button(ui, "Clear all").clicked() {
                s.removals.clear();
            }
        });
    }
}

/// The brush over the photo: a ring where it would paint, the stroke while painting; letting go
/// adds the removal.
pub fn overlay(ed: &mut Editor, ui: &mut Ui, r: Rect) {
    let to_photo = |ed: &Editor, p: Pos2| ed.to_photo((p.x - r.left()) / r.width(), (p.y - r.top()) / r.height());
    let resp = ui.interact(r, ui.id().with("remove-brush"), Sense::click_and_drag());
    let painter = ui.painter_at(r);
    // On screen, the brush's radius: how far one photo-long-side share reaches.
    let aspect = ed.photo_aspect();
    let long_u = if aspect >= 1.0 { 1.0 } else { aspect };
    let screen_radius = |ed: &Editor, at: Pos2| -> f32 {
        let Some((u, v)) = to_photo(ed, at) else { return 10.0 };
        let du = ed.remove_radius / long_u;
        match (ed.to_preview(u, v), ed.to_preview(u + du, v)) {
            (Some(a), Some(b)) => ((b.0 - a.0) * r.width()).abs().max(2.0),
            _ => 10.0,
        }
    };
    let id = ui.id().with("remove-stroke");
    let mut stroke: Vec<Pos2> = ui.data(|d| d.get_temp(id)).unwrap_or_default();
    if resp.drag_started() || (resp.is_pointer_button_down_on() && stroke.is_empty()) {
        stroke.clear();
    }
    if let Some(p) = resp.interact_pointer_pos().filter(|_| resp.is_pointer_button_down_on()) {
        if stroke.last().is_none_or(|l| l.distance(p) > 2.0) {
            stroke.push(p);
        }
    }
    let at = resp.hover_pos().or(stroke.last().copied());
    if let Some(at) = at {
        let rad = screen_radius(ed, at);
        if stroke.len() > 1 {
            painter.add(egui::Shape::line(stroke.clone(), Stroke::new(2.0 * rad, Color32::from_rgba_unmultiplied(230, 60, 50, 110))));
        } else if let Some(&p) = stroke.first() {
            painter.circle_filled(p, rad, Color32::from_rgba_unmultiplied(230, 60, 50, 110));
        }
        painter.circle_stroke(at, rad, Stroke::new(1.5_f32, Color32::WHITE));
        painter.circle_stroke(at, rad + 1.5, Stroke::new(1.0_f32, Color32::from_black_alpha(140)));
        ui.ctx().set_cursor_icon(egui::CursorIcon::Crosshair);
    }
    let released = resp.drag_stopped() || resp.clicked();
    // A quick click can press and let go between two frames.
    if released && stroke.is_empty() {
        stroke.extend(resp.interact_pointer_pos());
    }
    if released && !stroke.is_empty() {
        let points: Vec<[f32; 2]> = stroke.iter().filter_map(|&p| to_photo(ed, p)).map(|(u, v)| [u.clamp(0.0, 1.0), v.clamp(0.0, 1.0)]).collect();
        // Every few pixels on screen is plenty for the fill.
        if !points.is_empty() {
            let mut s = ed.s.clone();
            s.removals.push(Removal { radius: ed.remove_radius, points });
            ed.change(s);
        }
        stroke.clear();
    }
    ui.data_mut(|d| d.insert_temp(id, stroke));
}

/// Show spots: fine detail of the photo, white on black, over where the photo is shown.
pub fn spots(ed: &mut Editor, ui: &mut Ui, r: Rect) {
    let Some(o) = ed.opened.clone() else { return };
    // Of the photo as filled in so far, so what's left to take out shows.
    let ready = o.removals_ready(&ed.s);
    let key = format!("{:p}|{}", std::sync::Arc::as_ptr(&o), Opened::removal_keys(&ed.s).get(ready.wrapping_sub(1)).cloned().unwrap_or_default());
    if ed.spots_tex.as_ref().is_none_or(|t| t.0 != key) {
        let img = o.preview(&ed.s);
        let (w, h) = (img.width, img.height);
        let lum: Vec<f32> = (0..w * h).map(|i| (0.2126 * img.r[i] + 0.7152 * img.g[i] + 0.0722 * img.b[i]).max(0.0).powf(1.0 / 2.2)).collect();
        // Smoothed a little (so grain fades) against smoothed a lot: what's left is blobs the
        // size of dust.
        let (mut fine, mut soft) = (vec![0.0; w * h], vec![0.0; w * h]);
        shootback_core::develop::image::box_blur(&lum, &mut fine, w, h, 1);
        shootback_core::develop::image::box_blur(&lum, &mut soft, w, h, (w.max(h) / 150).max(4));
        let px: Vec<Color32> = fine
            .iter()
            .zip(&soft)
            .map(|(a, b)| Color32::from_gray((((a - b).abs() - 0.01).max(0.0) * 8.0).clamp(0.0, 1.0).sqrt().mul_add(255.0, 0.0) as u8))
            .collect();
        let tex = ui.ctx().load_texture("spots", egui::ColorImage { size: [w, h], pixels: px }, egui::TextureOptions::LINEAR);
        ed.spots_tex = Some((key, tex));
    }
    let Some((_, tex)) = &ed.spots_tex else { return };
    let (Some(a), Some(b)) = (ed.to_preview(0.0, 0.0), ed.to_preview(1.0, 1.0)) else { return };
    let full = Rect::from_two_pos(
        Pos2::new(r.left() + a.0 * r.width(), r.top() + a.1 * r.height()),
        Pos2::new(r.left() + b.0 * r.width(), r.top() + b.1 * r.height()),
    );
    ui.painter_at(r).image(tex.id(), full, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
}

/// Remove's keys: [ and ] size the brush, A shows spots, Escape leaves the tool.
pub fn keys(ed: &mut Editor, ctx: &egui::Context) {
    if ctx.wants_keyboard_input() || !ed.removing {
        return;
    }
    let (smaller, bigger, spots) =
        ctx.input(|i| (i.key_pressed(Key::OpenBracket), i.key_pressed(Key::CloseBracket), !i.modifiers.any() && i.key_pressed(Key::A)));
    if smaller {
        ed.remove_radius = (ed.remove_radius / 1.2).max(MIN);
    }
    if bigger {
        ed.remove_radius = (ed.remove_radius * 1.2).min(MAX);
    }
    if spots {
        ed.show_spots = !ed.show_spots;
    }
}
