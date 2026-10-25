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

/// What to do when the window gives no picture to draw into. After the screen was off, NVIDIA's
/// driver can leave the window's pictures stuck for good, each try timing out (the app looked
/// frozen while waiting a second at a time): they are made anew then, as when they're lost.
fn surface_error(err: eframe::wgpu::SurfaceError) -> eframe::egui_wgpu::SurfaceErrorAction {
    use eframe::{egui_wgpu::SurfaceErrorAction, wgpu::SurfaceError};
    match err {
        SurfaceError::Timeout | SurfaceError::Lost => SurfaceErrorAction::RecreateSurface,
        // A minimized window on Windows: nothing to draw until it's back.
        SurfaceError::Outdated if cfg!(windows) => SurfaceErrorAction::SkipFrame,
        SurfaceError::Outdated => SurfaceErrorAction::RecreateSurface,
        _ => SurfaceErrorAction::SkipFrame,
    }
}

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
        // them while the screen is off or the window hidden. Frames are only drawn when something
        // changes, so this costs nothing.
        wgpu_options: eframe::egui_wgpu::WgpuConfiguration {
            present_mode: eframe::wgpu::PresentMode::AutoNoVsync,
            on_surface_error: std::sync::Arc::new(surface_error),
            // The graphics built into the processor, not a separate card: on laptops with both
            // (Patrick's: AMD and NVIDIA) the screen hangs off the built-in one, and NVIDIA's
            // driver drawing for it on Wayland left the window frozen after the screen had been
            // off. The editor's drawing is light enough for either. WGPU_POWER_PREF=high still
            // picks the card.
            power_preference: eframe::wgpu::util::power_preference_from_env().unwrap_or(eframe::wgpu::PowerPreference::LowPower),
            ..Default::default()
        },
        ..Default::default()
    };
    eframe::run_native("Shootback", options, Box::new(|cc| Ok(Box::new(app::App::new(cc)))))
}
