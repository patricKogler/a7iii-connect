//! Phone: what pairing with the phone app will do. Pairing itself comes with the sync update.

use crate::app::App;
use crate::theme::palette;
use crate::widgets::{heading, hint, section_label, subheading};
use eframe::egui::{self, Frame, Margin, RichText, Stroke};

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    egui::CentralPanel::default().frame(Frame::none().fill(p.bg).inner_margin(Margin::symmetric(40.0, 28.0))).show(ctx, |ui| {
        ui.set_max_width(720.0);
        heading(ui, "Phone");
        ui.add_space(4.0);
        hint(ui, "Pair Shootback on your phone with this computer over Wi-Fi, and both keep the same library.");
        ui.add_space(22.0);
        Frame::none().fill(p.panel).stroke(Stroke::new(1.0_f32, p.border)).rounding(2.0).inner_margin(Margin::same(18.0)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            section_label(ui, "Not paired");
            subheading(ui, "Pairing arrives with the sync update");
            ui.add_space(6.0);
            hint(ui, "This screen will show a code to scan from the phone's settings. After that, whenever both are on the same network:");
            ui.add_space(8.0);
            for line in [
                "Photos copied on either side appear on the other",
                "Picks and rejects follow you both ways",
                "Albums stay in step, newest change wins",
                "Edits made on the phone open here, and the reverse",
            ] {
                ui.label(RichText::new(format!("·  {line}")).color(p.text));
            }
            ui.add_space(10.0);
            ui.label(RichText::new(format!("This computer's id: {}", app.settings.device_id)).monospace().size(11.5).color(p.text3));
        });
    });
}
