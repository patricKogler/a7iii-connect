//! Shootback for Windows and Linux.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod cull;
mod cull_view;
mod denoise;
mod develop_view;
mod editor;
mod gpu;
mod images;
mod import_view;
mod library_view;
mod looks;
mod mask_view;
mod masks;
#[cfg(test)]
mod parity;
mod phone_view;
mod render;
mod state;
mod theme;
mod viewer;
mod wheel;
mod widgets;

fn main() -> eframe::Result {
    // Models and the runtime that runs them live beside the app's settings.
    if let Some(dir) = eframe::storage_dir("shootback") {
        shootback_core::ai::set_home(&dir.join("ai"));
    }
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
