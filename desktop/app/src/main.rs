//! Shootback for Windows and Linux.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod batch;
mod cull;
mod cull_view;
mod denoise;
mod develop_view;
mod editor;
mod gpu;
mod images;
mod import_view;
mod insights;
mod library_view;
mod looks;
mod mask_view;
mod masks;
mod merge_view;
#[cfg(test)]
mod parity;
mod phone_view;
mod remove;
mod remove_view;
mod render;
mod search;
mod selection;
mod state;
mod theme;
mod viewer;
mod wheel;
mod widgets;
mod zoom;

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
        // Not waiting for the screen to show each frame: NVIDIA's Vulkan on Wayland stops showing
        // them while the screen is off or the window hidden, and a waiting app then hangs for
        // good. Frames are only drawn when something changes, so this costs nothing.
        wgpu_options: eframe::egui_wgpu::WgpuConfiguration { present_mode: eframe::wgpu::PresentMode::AutoNoVsync, ..Default::default() },
        ..Default::default()
    };
    eframe::run_native("Shootback", options, Box::new(|cc| Ok(Box::new(app::App::new(cc)))))
}
