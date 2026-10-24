//! Phone: pairing with the phone app (a QR code to scan from its Computer card) and the phones
//! that sync with this computer. The phone starts each sync; this computer only answers.

use crate::app::App;
use crate::theme::palette;
use crate::widgets::{heading, hint, outline_button, seal_button, section_label, subheading};
use chrono::{Local, TimeZone};
use eframe::egui::{self, Color32, Frame, Margin, Rect, RichText, Sense, Stroke, Vec2};
use shootback_core::sync;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    egui::CentralPanel::default().frame(Frame::none().fill(p.bg).inner_margin(Margin::symmetric(40.0, 28.0))).show(ctx, |ui| {
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
            ui.set_max_width(760.0);
            heading(ui, "Phone");
            ui.add_space(4.0);
            hint(ui, "Pair Shootback on your phone with this computer. On the same Wi-Fi, the phone then syncs photos, picks and rejects, albums and edits both ways.");
            ui.add_space(22.0);
            let card = |ui: &mut egui::Ui, add: &mut dyn FnMut(&mut egui::Ui)| {
                Frame::none().fill(p.panel).stroke(Stroke::new(p.line, p.border)).rounding(p.radius).shadow(p.hard_shadow()).inner_margin(Margin::same(18.0)).show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    add(ui);
                });
            };
            let port = app.phone_sync.port();
            let pending = app.phone_sync.shared.pending.lock().unwrap().clone();
            card(ui, &mut |ui| match (&pending, port) {
                (_, None) => {
                    section_label(ui, "Not available");
                    subheading(ui, "Phone sync couldn't start");
                    hint(ui, app.phone_sync.error.as_deref().unwrap_or("No network port was free."));
                }
                (Some(secret), Some(port)) => {
                    section_label(ui, "Pairing");
                    subheading(ui, "Scan this with your phone");
                    ui.add_space(4.0);
                    hint(ui, "In Shootback on the phone, tap the camera button, then Pair under Computer. Both need to be on the same Wi-Fi.");
                    ui.add_space(12.0);
                    let host = crate::state::local_ip().unwrap_or_else(|| "127.0.0.1".into());
                    let uri = sync::pairing_uri(&app.settings.device_id, &sync::computer_name(), &host, port, secret);
                    paint_qr(ui, &uri, 240.0);
                    ui.add_space(10.0);
                    ui.label(RichText::new(format!("This computer: {} ({host}:{port})", sync::computer_name())).size(12.0).color(p.text3));
                    ui.add_space(8.0);
                    if outline_button(ui, "Cancel").clicked() {
                        *app.phone_sync.shared.pending.lock().unwrap() = None;
                    }
                    ui.ctx().request_repaint_after(std::time::Duration::from_millis(500));
                }
                (None, Some(_)) => {
                    let phones = app.phone_sync.shared.phones.lock().unwrap().clone();
                    section_label(ui, if phones.is_empty() { "Not paired" } else { "Paired" });
                    if phones.is_empty() {
                        subheading(ui, "No phone yet");
                        ui.add_space(6.0);
                        for line in [
                            "Photos copied on either side appear on the other",
                            "Picks and rejects follow you both ways",
                            "Albums stay in step, newest change wins",
                            "Edits made on the phone open here, and the reverse",
                        ] {
                            ui.label(RichText::new(format!("·  {line}")).color(p.text));
                        }
                    }
                    let mut remove = None;
                    for ph in &phones {
                        ui.add_space(8.0);
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.label(RichText::new(&ph.name).size(15.0).color(p.strong));
                                let when = if ph.last_sync == 0 {
                                    "Not synced yet".to_string()
                                } else {
                                    Local.timestamp_millis_opt(ph.last_sync).single().map_or(String::new(), |t| format!("Last sync {}", t.format("%b %-d, %H:%M")))
                                };
                                ui.label(RichText::new(when).size(12.0).color(p.text3));
                            });
                            crate::widgets::right_to_left(ui, |ui| {
                                if ui.add(egui::Label::new(RichText::new("Unpair").size(12.0).color(p.text3)).sense(Sense::click())).clicked() {
                                    remove = Some(ph.id.clone());
                                }
                            });
                        });
                    }
                    if let Some(id) = remove {
                        app.phone_sync.shared.phones.lock().unwrap().retain(|p| p.id != id);
                        app.phone_sync.shared.phones_changed.store(true, std::sync::atomic::Ordering::Relaxed);
                    }
                    ui.add_space(14.0);
                    let label = if phones.is_empty() { "Pair a phone" } else { "Pair another phone" };
                    if seal_button(ui, label).clicked() {
                        *app.phone_sync.shared.pending.lock().unwrap() = Some(sync::new_secret());
                    }
                }
            });
            if let Some(a) = app.phone_sync.activity() {
                ui.add_space(12.0);
                ui.label(RichText::new(a).color(p.accent));
            }
        });
    });
}

/// Draws `text` as a QR code, dark on white with a quiet zone, `size` points wide.
fn paint_qr(ui: &mut egui::Ui, text: &str, size: f32) {
    let Ok(code) = qrcode::QrCode::new(text.as_bytes()) else { return };
    let n = code.width();
    let colors = code.to_colors();
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    let painter = ui.painter();
    painter.rect_filled(rect, 0.0, Color32::WHITE);
    let quiet = 4.0;
    let cell = size / (n as f32 + 2.0 * quiet);
    for y in 0..n {
        for x in 0..n {
            if colors[y * n + x] == qrcode::Color::Dark {
                let min = rect.min + Vec2::new((x as f32 + quiet) * cell, (y as f32 + quiet) * cell);
                painter.rect_filled(Rect::from_min_size(min, Vec2::splat(cell + 0.3)), 0.0, Color32::BLACK);
            }
        }
    }
}
