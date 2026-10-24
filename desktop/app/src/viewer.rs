//! Edit: the selected shot large on the stage, a filmstrip of its neighbours, and its details.
//! The editing tools themselves come with the engine port.

use crate::app::{App, View};
use crate::images::{LARGE, THUMB};
use crate::library_view::{details, mark_menu};
use crate::theme::palette;
use crate::widgets::{outline_button, paint_seal, section_label};
use eframe::egui::{self, Align2, Color32, Frame, Margin, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::marks::Mark;

const STRIP: f32 = 96.0;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    let visible = app.visible();
    if app.selected_index(&visible).is_none() {
        app.step(&visible, 0);
    }
    egui::SidePanel::right("edit-details")
        .exact_width(300.0)
        .resizable(false)
        .frame(Frame::none().fill(p.panel).inner_margin(Margin::same(16.0)).stroke(Stroke::new(1.0_f32, p.border)))
        .show(ctx, |ui| {
            section_label(ui, "Develop");
            ui.add_space(6.0);
            ui.label(RichText::new("Light, colour and crop arrive with the next update, using the same engine as the phone.").color(p.text3));
            ui.add_space(10.0);
            if outline_button(ui, "Back to library  G").clicked() {
                app.view = View::Library;
            }
            ui.add_space(16.0);
            ui.separator();
            ui.add_space(12.0);
            details(app, ui);
        });
    egui::TopBottomPanel::bottom("filmstrip")
        .exact_height(STRIP + 20.0)
        .frame(Frame::none().fill(p.stage).inner_margin(Margin::symmetric(12.0, 10.0)).stroke(Stroke::new(1.0_f32, p.border)))
        .show(ctx, |ui| filmstrip(app, ui, &visible));
    egui::CentralPanel::default().frame(Frame::none().fill(p.stage).inner_margin(Margin::same(24.0))).show(ctx, |ui| stage(app, ui));
}

fn stage(app: &mut App, ui: &mut Ui) {
    let Some(key) = app.selected.clone() else {
        ui.centered_and_justified(|ui| ui.label(RichText::new("No photo selected").color(Color32::from_gray(150))));
        return;
    };
    let Some(shot) = app.library.shots.iter().find(|s| s.key() == key).cloned() else { return };
    let show = shot.display();
    let rect = ui.available_rect_before_wrap();
    let resp = ui.allocate_rect(rect, Sense::click());
    let tex = app.images.get(&show.path, LARGE, show.orientation).or_else(|| app.images.any(&show.path));
    match tex {
        Some(tex) => {
            let [tw, th] = tex.size().map(|v| v as f32);
            let s = (rect.width() / tw).min(rect.height() / th);
            let r = Rect::from_center_size(rect.center(), Vec2::new(tw * s, th * s));
            ui.painter().image(tex.id(), r, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
            if app.library.mark(&shot) == Some(Mark::Pick) {
                paint_seal(ui.painter(), r.right_top() + Vec2::new(-10.0, 10.0), "Pick", Align2::RIGHT_TOP);
            }
        }
        None if app.images.failed(&show.path, LARGE) => {
            ui.painter().text(rect.center(), Align2::CENTER_CENTER, "This file can't be shown", egui::FontId::proportional(14.0), Color32::from_gray(150));
        }
        None => {}
    }
    resp.context_menu(|ui| mark_menu(app, ui, &key));
}

fn filmstrip(app: &mut App, ui: &mut Ui, visible: &[usize]) {
    let p = palette(ui.ctx());
    let at = app.selected_index(visible);
    let id = ui.id().with("strip-at");
    let moved = ui.memory(|m| m.data.get_temp::<Option<usize>>(id)) != Some(at);
    ui.memory_mut(|m| m.data.insert_temp(id, at));
    egui::ScrollArea::horizontal().auto_shrink(false).show_rows(ui, STRIP, 1, |ui, _| {
        ui.horizontal(|ui| {
            for (n, &i) in visible.iter().enumerate() {
                let shot = &app.library.shots[i];
                let (key, show) = (shot.key(), shot.display().clone());
                let w = STRIP * 1.5;
                let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, STRIP), Sense::click());
                if !ui.is_rect_visible(rect) {
                    continue;
                }
                if moved && Some(n) == at {
                    ui.scroll_to_rect(rect, Some(egui::Align::Center));
                }
                if let Some(tex) = app.images.get(&show.path, THUMB, show.orientation) {
                    let [tw, th] = tex.size().map(|v| v as f32);
                    let s = (w / tw).max(STRIP / th);
                    let uv = Rect::from_center_size(Pos2::new(0.5, 0.5), Vec2::new(w / (tw * s), STRIP / (th * s)));
                    let dim = if Some(n) == at { Color32::WHITE } else { Color32::from_gray(170) };
                    ui.painter().image(tex.id(), rect, uv, dim);
                }
                if Some(n) == at {
                    ui.painter().rect_stroke(rect, 0.0, Stroke::new(2.0_f32, p.accent));
                }
                if resp.clicked() {
                    app.selected = Some(key);
                }
            }
        });
    });
}
