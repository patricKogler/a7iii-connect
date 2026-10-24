//! Import: the three ways photos reach the computer, and what the current import is doing.

use crate::app::App;
use crate::images::THUMB;
use crate::state::{self, Source};
use crate::theme::palette;
use crate::widgets::{heading, hint, outline_button, seal_button, section_label, subheading};
use eframe::egui::{self, Frame, Margin, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use std::sync::atomic::Ordering;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    egui::CentralPanel::default().frame(Frame::none().fill(p.bg).inner_margin(Margin::symmetric(40.0, 28.0))).show(ctx, |ui| {
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
            ui.set_max_width(1120.0);
            heading(ui, "Import");
            ui.add_space(4.0);
            hint(ui, &format!("New photos go into {} in a folder for each day.", app.library.root.display()));
            ui.add_space(22.0);
            ui.columns(3, |c| {
                card(&mut c[0], |ui| camera_card(app, ui));
                card(&mut c[1], |ui| ftp_card(app, ui));
                card(&mut c[2], |ui| folder_card(app, ui));
            });
            ui.add_space(28.0);
            progress(app, ui);
        });
    });
}

fn card(ui: &mut Ui, add: impl FnOnce(&mut Ui)) {
    let p = palette(ui.ctx());
    Frame::none().fill(p.panel).stroke(Stroke::new(1.0_f32, p.border)).rounding(2.0).inner_margin(Margin::same(18.0)).show(ui, |ui| {
        ui.set_width(ui.available_width());
        ui.set_min_height(250.0);
        ui.spacing_mut().item_spacing.y = 8.0;
        ui.with_layout(egui::Layout::top_down(egui::Align::Min), add);
    });
}

fn camera_card(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Wi-Fi from the camera");
    subheading(ui, "Send to Smartphone");
    hint(ui, "On the camera, open Send to Smartphone and join its Wi-Fi from this computer. Shootback copies what isn't here yet.");
    ui.add_space(4.0);
    ui.checkbox(&mut app.settings.full_size, "Full-size originals (slower; off copies the 2 MP version)");
    ui.add_space(6.0);
    if app.importer.running() == Some(Source::Camera) {
        if outline_button(ui, "Stop").clicked() {
            app.importer.cancel();
        }
    } else if seal_button(ui, "Connect and copy").clicked() {
        app.importer.copy_from_camera(ui.ctx(), app.library.root.clone(), app.settings.full_size);
    }
}

fn ftp_card(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    section_label(ui, "FTP inbox");
    subheading(ui, "Every shot as it's taken");
    hint(ui, "Point the camera's FTP Transfer at this computer. Both need to be on the same network.");
    if let Some(s) = &app.ftp.server {
        let host = state::local_ip().unwrap_or_else(|| "this computer's address".into());
        egui::Grid::new("ftp").num_columns(2).spacing([16.0, 4.0]).show(ui, |ui| {
            for (k, v) in [("Host", host), ("Port", s.port.to_string()), ("User", "any".into()), ("Secure Protocol", "Off".into())] {
                ui.label(RichText::new(k).size(12.0).color(p.text3));
                ui.label(RichText::new(v).monospace().color(p.strong));
                ui.end_row();
            }
        });
        let n = app.ftp.received.load(Ordering::Relaxed);
        if n > 0 {
            let last = app.ftp.last.lock().unwrap().clone().unwrap_or_default();
            ui.label(RichText::new(format!("{n} received this session, last {last}")).size(12.0).color(p.good));
        }
        if outline_button(ui, "Turn off").clicked() {
            app.stop_ftp();
        }
    } else {
        ui.horizontal(|ui| {
            ui.label(RichText::new("Port").color(p.text2));
            ui.add(egui::DragValue::new(&mut app.settings.ftp_port).range(1024..=65535));
        });
        if let Some(e) = &app.ftp.error {
            ui.label(RichText::new(e).size(12.0).color(p.accent));
        }
        if seal_button(ui, "Turn on").clicked() {
            app.start_ftp(ui.ctx());
        }
    }
}

fn folder_card(app: &mut App, ui: &mut Ui) {
    section_label(ui, "Card or USB");
    subheading(ui, "From a folder");
    hint(ui, "Choose the card's DCIM folder, or any folder of photos. Files the library already has are skipped.");
    ui.add_space(6.0);
    if app.importer.running() == Some(Source::Folder) {
        if outline_button(ui, "Stop").clicked() {
            app.importer.cancel();
        }
    } else if seal_button(ui, "Choose folder…").clicked() {
        if let Some(dir) = rfd::FileDialog::new().pick_folder() {
            app.importer.copy_from_folder(ui.ctx(), dir, app.library.root.clone(), app.library.file_set());
        }
    }
}

fn progress(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    let status = app.importer.status.lock().unwrap().clone();
    if status.message.is_empty() {
        return;
    }
    section_label(ui, if status.running.is_some() { "Copying" } else { "Last import" });
    ui.add_space(6.0);
    ui.label(RichText::new(&status.message).color(if status.failed { p.accent } else { p.text }));
    let pr = &status.progress;
    if pr.total > 0 {
        ui.add_space(6.0);
        let w = ui.available_width().min(720.0);
        let (rect, _) = ui.allocate_exact_size(Vec2::new(w, 4.0), Sense::hover());
        ui.painter().rect_filled(rect, 0.0, p.border);
        let done = Rect::from_min_size(rect.min, Vec2::new(w * pr.done as f32 / pr.total as f32, 4.0));
        ui.painter().rect_filled(done, 0.0, p.seal);
        if let Some(cur) = &pr.current {
            ui.label(RichText::new(cur).monospace().size(12.0).color(p.text3));
        }
    }
    if !pr.saved.is_empty() {
        ui.add_space(12.0);
        ui.horizontal_wrapped(|ui| {
            for path in pr.saved.iter().rev().take(12) {
                let (rect, _) = ui.allocate_exact_size(Vec2::splat(88.0), Sense::hover());
                ui.painter().rect_filled(rect, 1.0, p.stage);
                if let Some(tex) = app.images.get(path, THUMB, 1) {
                    let [tw, th] = tex.size().map(|v| v as f32);
                    let s = (rect.width() / tw).max(rect.height() / th);
                    let (u, v) = (rect.width() / (tw * s), rect.height() / (th * s));
                    let uv = Rect::from_center_size(Pos2::new(0.5, 0.5), Vec2::new(u, v));
                    ui.painter().image(tex.id(), rect, uv, egui::Color32::WHITE);
                }
            }
        });
    }
}
