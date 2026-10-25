//! Merging raws into one: HDR from a bracket of exposures, or a focus stack. Started from a
//! shot's menu; the raws shot just before and after it are offered, the quick burst around it
//! already ticked. The result is a new raw beside them, which the library then shows.

use crate::app::App;
use crate::images::THUMB;
use crate::render;
use crate::theme::palette;
use crate::widgets::{chip, hint, outline_button, seal_button};
use eframe::egui::{self, Align2, RichText, Sense, Vec2};
use shootback_core::develop::params::{p, Settings};
use shootback_core::develop::{raw, EditStore};
use shootback_core::library::Shot;
use shootback_core::merge::{self, Kind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};

/// How far apart (ms) the shots offered may be from the one the merge started from.
const AROUND: i64 = 90_000;
/// Shots this close (ms) after each other are taken as one bracket.
const BRACKET: i64 = 2_500;

pub struct Merging {
    kind: Kind,
    offered: Vec<Shot>,
    chosen: Vec<bool>,
    job: Option<Receiver<Result<String, String>>>,
    progress: Arc<Mutex<f32>>,
    cancel: Arc<AtomicBool>,
    error: Option<String>,
}

impl Merging {
    /// For the shot `key`: the raws near it in time, oldest first.
    pub fn new(shots: &[Shot], key: &str) -> Option<Merging> {
        let at = shots.iter().find(|s| s.key() == key)?;
        let t = at.taken_millis();
        let mut offered: Vec<Shot> = shots.iter().filter(|s| s.raw.is_some() && (s.taken_millis() - t).abs() <= AROUND).cloned().collect();
        offered.sort_by_key(|s| s.taken_millis());
        let i = offered.iter().position(|s| s.key() == key)?;
        let mut chosen = vec![false; offered.len()];
        chosen[i] = true;
        let gap = |a: &Shot, b: &Shot| (b.taken_millis() - a.taken_millis()).abs() <= BRACKET;
        let mut j = i;
        while j > 0 && gap(&offered[j - 1], &offered[j]) {
            j -= 1;
            chosen[j] = true;
        }
        let mut j = i;
        while j + 1 < offered.len() && gap(&offered[j], &offered[j + 1]) {
            j += 1;
            chosen[j] = true;
        }
        Some(Merging { kind: Kind::Hdr, offered, chosen, job: None, progress: Default::default(), cancel: Default::default(), error: None })
    }
}

pub fn dialog(app: &mut App, ctx: &egui::Context) {
    let Some(mut m) = app.merging.take() else { return };
    let pal = palette(ctx);
    let mut open = true;
    let mut close = false;
    if let Some(rx) = &m.job {
        if let Ok(r) = rx.try_recv() {
            m.job = None;
            match r {
                Ok(key) => {
                    app.selected = Some(key);
                    // An HDR comes with an edit (its brightness).
                    app.editor.edited = app.editor.store_edited();
                    app.library.rescan();
                    close = true;
                }
                Err(e) if e == "cancelled" => {}
                Err(e) => m.error = Some(e),
            }
        } else {
            ctx.request_repaint_after(std::time::Duration::from_millis(200));
        }
    }
    egui::Window::new("Merge photos").collapsible(false).resizable(false).anchor(Align2::CENTER_CENTER, Vec2::ZERO).open(&mut open).show(ctx, |ui| {
        ui.set_width(520.0);
        ui.horizontal(|ui| {
            if chip(ui, m.kind == Kind::Hdr, "HDR").clicked() {
                m.kind = Kind::Hdr;
            }
            if chip(ui, m.kind == Kind::Focus, "Focus stack").clicked() {
                m.kind = Kind::Focus;
            }
        });
        ui.add_space(4.0);
        hint(
            ui,
            match m.kind {
                Kind::Hdr => "The same scene at different exposures, made into one raw with the shadows of the bright ones and the highlights of the dark one.",
                Kind::Focus => "The same scene focused near to far, made into one raw sharp all the way through.",
            },
        );
        ui.add_space(8.0);
        let running = m.job.is_some();
        egui::ScrollArea::horizontal().show(ui, |ui| {
            ui.horizontal(|ui| {
                for (i, shot) in m.offered.iter().enumerate() {
                    let (rect, resp) = ui.allocate_exact_size(Vec2::new(96.0, 64.0), Sense::click());
                    let show = shot.display();
                    if let Some(tex) = app.images.get(&show.path, THUMB, show.orientation) {
                        let [w, h] = tex.size().map(|v| v as f32);
                        let k = (rect.width() / w).min(rect.height() / h);
                        let r = egui::Rect::from_center_size(rect.center(), Vec2::new(w * k, h * k));
                        ui.painter().image(tex.id(), r, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), egui::Color32::WHITE);
                    } else {
                        ui.painter().rect_filled(rect, 2.0, pal.selected);
                    }
                    if m.chosen[i] {
                        ui.painter().rect_stroke(rect.expand(2.0), 2.0, egui::Stroke::new(2.0_f32, pal.accent));
                    } else {
                        ui.painter().rect_filled(rect, 0.0, pal.bg.gamma_multiply(0.5));
                    }
                    let resp = resp.on_hover_text(shot.primary().name.clone()).on_hover_cursor(egui::CursorIcon::PointingHand);
                    if resp.clicked() && !running {
                        m.chosen[i] = !m.chosen[i];
                    }
                }
            });
        });
        let n = m.chosen.iter().filter(|&&c| c).count();
        ui.add_space(4.0);
        ui.label(RichText::new(format!("{n} of {} picked. Click a photo to add or leave it out.", m.offered.len())).size(12.0).color(pal.text3));
        ui.add_space(8.0);
        if running {
            ui.add(egui::ProgressBar::new(*m.progress.lock().unwrap()).desired_height(6.0).fill(pal.seal));
            ui.horizontal(|ui| {
                ui.label(RichText::new("Merging ...").color(pal.text2));
                if outline_button(ui, "Cancel").clicked() {
                    m.cancel.store(true, Ordering::Relaxed);
                }
            });
        } else {
            if let Some(e) = &m.error {
                ui.label(RichText::new(format!("Couldn't merge: {e}")).color(pal.accent));
            }
            ui.horizontal(|ui| {
                if ui.add_enabled_ui(n >= 2, |ui| seal_button(ui, "Merge")).inner.clicked() {
                    let shots: Vec<Shot> = m.offered.iter().zip(&m.chosen).filter(|(_, &c)| c).map(|(s, _)| s.clone()).collect();
                    start(&mut m, shots, app.library.root.clone(), ctx);
                }
                if outline_button(ui, "Cancel").clicked() {
                    close = true;
                }
            });
        }
    });
    if !open || close {
        m.cancel.store(true, Ordering::Relaxed);
    } else {
        app.merging = Some(m);
    }
}

fn start(m: &mut Merging, shots: Vec<Shot>, root: PathBuf, ctx: &egui::Context) {
    let (tx, rx) = channel();
    m.job = Some(rx);
    m.error = None;
    m.cancel.store(false, Ordering::Relaxed);
    *m.progress.lock().unwrap() = 0.0;
    let (kind, progress, cancel, c) = (m.kind, m.progress.clone(), m.cancel.clone(), ctx.clone());
    std::thread::spawn(move || {
        let r = run(
            kind,
            &shots,
            &root,
            &|f| {
                *progress.lock().unwrap() = f;
                c.request_repaint();
            },
            &cancel,
        );
        let _ = tx.send(r);
        c.request_repaint();
    });
}

/// Reads the raws, merges them, writes the new raw (and, for HDR, an edit as bright as the
/// middle exposure). Gives the new shot's key.
fn run(kind: Kind, shots: &[Shot], root: &Path, progress: &dyn Fn(f32), cancel: &AtomicBool) -> Result<String, String> {
    let mut frames = Vec::new();
    let mut files = Vec::new();
    for (i, s) in shots.iter().enumerate() {
        let photo = s.raw.as_ref().ok_or("only raws can be merged")?;
        let bytes = std::fs::read(&photo.path).map_err(|e| e.to_string())?;
        let info = raw::read_info(&bytes)?;
        frames.push(raw::decode(&bytes, info)?);
        files.push((photo.clone(), bytes));
        if cancel.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        progress(0.25 * (i + 1) as f32 / shots.len() as f32);
    }
    let merged = merge::merge(kind, &frames, &|f| progress(0.25 + 0.65 * f), cancel)?;
    drop(frames);
    let (photo, bytes) = &files[merged.base];
    let (w, h, rgb) = merge::preview_rgb(&merged.raw, merged.exposure, 1616);
    let jpeg = render::encode_jpeg(w, h, &rgb, None)?;
    let file = merge::write_raw(&merged.raw, bytes, Some(&jpeg))?;
    let dir = photo.path.parent().ok_or("no folder")?;
    let tag = if kind == Kind::Hdr { "HDR" } else { "Stack" };
    let name = (1..)
        .map(|n| if n == 1 { format!("{}-{tag}.ARW", photo.stem()) } else { format!("{}-{tag}-{n}.ARW", photo.stem()) })
        .find(|n| !dir.join(n).exists())
        .unwrap();
    shootback_core::library::write_atomic(&dir.join(&name), &file).map_err(|e| e.to_string())?;
    let key = format!("{name}@{}", photo.taken_millis);
    if merged.exposure.abs() > 0.01 {
        let s = Settings::default().with(&p::EXPOSURE, merged.exposure);
        EditStore::new(root).save(&key, &s).map_err(|e| e.to_string())?;
    }
    progress(1.0);
    Ok(key)
}
