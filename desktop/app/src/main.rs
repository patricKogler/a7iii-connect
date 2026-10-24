//! Shootback for Windows and Linux.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod develop_view;
mod editor;
mod gpu;
mod images;
mod import_view;
mod library_view;
#[cfg(test)]
mod parity;
mod phone_view;
mod render;
mod state;
mod theme;
mod viewer;
mod widgets;

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title("Shootback")
            .with_app_id("shootback")
            .with_inner_size([1440.0, 900.0])
            .with_min_inner_size([960.0, 600.0])
            .with_icon(app::icon()),
        ..Default::default()
    };
    eframe::run_native("Shootback", options, Box::new(|cc| Ok(Box::new(app::App::new(cc)))))
}
