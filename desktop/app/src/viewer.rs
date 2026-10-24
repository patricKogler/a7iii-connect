//! Edit: the selected shot developed live on the stage, the Develop panels beside it and a
//! filmstrip of its neighbours.

use crate::app::{App, View};
use crate::images::{LARGE, THUMB};
use crate::library_view::{details, mark_menu};
use crate::theme::palette;
use crate::widgets::{outline_button, paint_badge, paint_seal, seal_button, stage_chip};
use eframe::egui::{self, Align2, Color32, Frame, Key, Margin, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::marks::Mark;

const STRIP: f32 = 96.0;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    let visible = app.visible();
    if app.selected_index(&visible).is_none() {
        app.step(&visible, 0);
    }
    let shot = app.selected.as_ref().and_then(|k| app.library.shots.iter().find(|s| &s.key() == k)).cloned();
    match &shot {
        Some(shot) => app.editor.open(ctx, shot),
        None => app.editor.close(),
    }
    let preview = app.editor.poll();
    let before = app.editor.s.clone();
    egui::SidePanel::right("edit-panels")
        .default_width(320.0)
        .width_range(280.0..=640.0)
        .resizable(true)
        .frame(Frame::none().fill(p.panel).inner_margin(Margin::symmetric(14.0, 12.0)).stroke(Stroke::new(p.line, p.border)))
        .show(ctx, |ui| panel(app, ui));
    egui::TopBottomPanel::bottom("filmstrip")
        .exact_height(STRIP + 20.0)
        .frame(Frame::none().fill(p.stage).inner_margin(Margin::symmetric(12.0, 10.0)).stroke(Stroke::new(p.line, p.border)))
        .show(ctx, |ui| filmstrip(app, ui, &visible));
    egui::CentralPanel::default().frame(Frame::none().fill(p.stage).inner_margin(Margin::same(24.0))).show(ctx, |ui| stage(app, ui, preview));
    app.editor.end_frame(ctx, &before);
}

fn panel(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    let ed = &mut app.editor;
    ui.horizontal(|ui| {
        if outline_button(ui, "Auto").on_hover_text("Sets the tone from the photo").clicked() {
            ed.auto();
        }
        ui.add_enabled_ui(ed.can_undo(), |ui| {
            if outline_button(ui, "Undo").on_hover_text("Ctrl+Z").clicked() {
                ed.undo();
            }
        });
        ui.add_enabled_ui(ed.can_redo(), |ui| {
            if outline_button(ui, "Redo").on_hover_text("Ctrl+Shift+Z").clicked() {
                ed.redo();
            }
        });
        ui.menu_button(RichText::new("···").color(p.text), |ui| {
            if ui.button("Copy settings  Ctrl+C").clicked() {
                ed.copy();
                ui.ctx().copy_text(ed.s.encode());
                ui.close_menu();
            }
            if ui.add_enabled(ed.can_paste(), egui::Button::new("Paste settings  Ctrl+V")).clicked() {
                ed.paste();
                ui.close_menu();
            }
            ui.separator();
            if ui.button("Reset all").clicked() {
                ed.change(shootback_core::develop::Settings::default());
                ui.close_menu();
            }
        });
    });
    ui.add_space(8.0);
    let export_h = 92.0;
    egui::ScrollArea::vertical().auto_shrink(false).max_height(ui.available_height() - export_h).show(ui, |ui| {
        ui.set_width(ui.available_width());
        let (ed, looks) = (&mut app.editor, &mut app.looks);
        if !ed.has_gpu() {
            crate::widgets::hint(ui, "Editing needs a GPU renderer, and this window was started without one.");
            return;
        }
        if let Some(e) = &ed.error {
            ui.label(RichText::new(e).color(p.accent));
        }
        let mut s = ed.s.clone();
        crate::looks::panels(ui, looks, ed, &app.library.root, &mut s);
        crate::develop_view::panels(ui, ed, &mut s);
        ed.change(s);
        ui.add_space(10.0);
        let id = ui.make_persistent_id("edit-info");
        egui::collapsing_header::CollapsingState::load_with_default_open(ui.ctx(), id, false)
            .show_header(ui, |ui| ui.label(RichText::new("Info").family(crate::theme::display()).size(14.5).color(p.strong)))
            .body(|ui| details(app, ui));
    });
    let ed = &mut app.editor;
    ui.add_space(8.0);
    let status = ed.export.lock().unwrap().clone();
    if status.running {
        ui.add(egui::ProgressBar::new(status.progress).desired_height(6.0).fill(p.seal));
        ui.horizontal(|ui| {
            ui.label(RichText::new("Exporting at full size ...").color(p.text2));
            if ui
                .add(egui::Label::new(RichText::new("Cancel").color(p.text3)).selectable(false).sense(Sense::click()))
                .on_hover_cursor(egui::CursorIcon::PointingHand)
                .clicked()
            {
                ed.cancel_export();
            }
        });
    } else {
        ui.add_enabled_ui(ed.opened.is_some(), |ui| {
            if seal_button(ui, "Export JPEG").on_hover_text(format!("Full size, into {}  (Ctrl+Shift+E)", ed.export_dir().display())).clicked() {
                ed.export(ui.ctx());
            }
        });
        if !status.message.is_empty() {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new(&status.message).size(12.0).color(if status.failed { p.accent } else { p.text3 }));
                if let Some(path) = &status.last {
                    if ui
                        .add(egui::Label::new(RichText::new("Show").size(12.0).color(p.accent)).selectable(false).sense(Sense::click()))
                        .on_hover_cursor(egui::CursorIcon::PointingHand)
                        .clicked()
                    {
                        crate::state::reveal(path);
                    }
                }
            });
        }
    }
}

fn stage(app: &mut App, ui: &mut Ui, preview: Option<(egui::TextureId, Vec2)>) {
    let Some(key) = app.selected.clone() else {
        ui.centered_and_justified(|ui| ui.label(RichText::new("No photo selected").color(Color32::from_gray(150))));
        return;
    };
    let Some(shot) = app.library.shots.iter().find(|s| s.key() == key).cloned() else { return };
    let show = shot.display();
    let full = ui.available_rect_before_wrap();
    // A strip above the photo for the before/after switch and what's going on.
    let bar = Rect::from_min_size(full.min, Vec2::new(full.width(), 30.0));
    let rect = Rect::from_min_max(Pos2::new(full.left(), bar.bottom() + 8.0), full.max);
    let resp = ui.allocate_rect(full, Sense::click());
    let held = ui.input(|i| i.key_down(Key::Backslash));
    let id = ui.id().with("show-before");
    let mut before: bool = ui.memory(|m| m.data.get_temp(id).unwrap_or(false));
    let mut bar_ui = ui.new_child(egui::UiBuilder::new().max_rect(bar).layout(egui::Layout::left_to_right(egui::Align::Center)));
    if stage_chip(&mut bar_ui, before || held, "Before  \\").on_hover_text("The photo as the camera made it; hold \\ to peek").clicked() {
        before = !before;
    }
    ui.memory_mut(|m| m.data.insert_temp(id, before));
    if stage_chip(&mut bar_ui, app.editor.cropping, "Crop  R").on_hover_text("Drag the edges, corners or the middle of the box").clicked() {
        app.editor.cropping = !app.editor.cropping;
    }
    let ed = &app.editor;
    let state = if ed.error.is_some() {
        None
    } else if ed.loading() {
        Some("Opening ...")
    } else if ed.catching_up() {
        Some("Developing ...")
    } else {
        None
    };
    if let Some(t) = state {
        bar_ui.label(RichText::new(t).color(Color32::from_gray(160)));
    }
    if app.editor.is_edited(&shot) {
        crate::widgets::right_to_left(&mut bar_ui, |ui| {
            ui.label(RichText::new("Edited").size(12.0).color(Color32::from_gray(160)));
        });
    }
    let tex = match preview {
        Some((id, size)) if !(before || held) => Some((id, size)),
        _ => app.images.get(&show.path, LARGE, show.orientation).or_else(|| app.images.any(&show.path)).map(|t| (t.id(), t.size_vec2())),
    };
    match tex {
        Some((id, size)) => {
            let s = (rect.width() / size.x).min(rect.height() / size.y);
            let r = Rect::from_center_size(rect.center(), size * s);
            ui.painter().image(id, r, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
            if app.library.mark(&shot) == Some(Mark::Pick) {
                paint_seal(ui.painter(), r.right_top() + Vec2::new(-10.0, 10.0), "Pick", Align2::RIGHT_TOP);
            }
            if before || held {
                paint_badge(ui, ui.painter(), r.left_top() + Vec2::new(10.0, 10.0), "Before", Align2::LEFT_TOP, false);
            } else if app.editor.cropping && preview.is_some() {
                crop_overlay(app, ui, r);
            }
        }
        None if app.images.failed(&show.path, LARGE) => {
            ui.painter().text(rect.center(), Align2::CENTER_CENTER, "This file can't be shown", egui::FontId::proportional(14.0), Color32::from_gray(150));
        }
        None => {}
    }
    resp.context_menu(|ui| mark_menu(app, ui, &key));
}

/// Which part of the crop box a drag holds: edges as left, top, right, bottom flags.
#[derive(Clone, Copy, Debug, PartialEq)]
struct CropGrip([bool; 4]);

/// The crop box over the whole frame: the rest dimmed, thirds drawn, edges and corners to drag,
/// the middle to move it.
fn crop_overlay(app: &mut App, ui: &mut Ui, r: Rect) {
    use crate::develop_view::{crop_box, set_crop_box};
    let p = palette(ui.ctx());
    let c = crop_box(&app.editor.s);
    let to_screen = |x: f32, y: f32| Pos2::new(r.left() + x * r.width(), r.top() + y * r.height());
    let b = Rect::from_min_max(to_screen(c[0], c[1]), to_screen(c[2], c[3]));
    let painter = ui.painter_at(r.expand(6.0));
    let dim = Color32::from_black_alpha(150);
    painter.rect_filled(Rect::from_min_max(r.min, Pos2::new(r.right(), b.top())), 0.0, dim);
    painter.rect_filled(Rect::from_min_max(Pos2::new(r.left(), b.bottom()), r.max), 0.0, dim);
    painter.rect_filled(Rect::from_min_max(Pos2::new(r.left(), b.top()), Pos2::new(b.left(), b.bottom())), 0.0, dim);
    painter.rect_filled(Rect::from_min_max(Pos2::new(b.right(), b.top()), Pos2::new(r.right(), b.bottom())), 0.0, dim);
    let thin = Stroke::new(1.0_f32, Color32::from_white_alpha(50));
    for k in 1..3 {
        let f = k as f32 / 3.0;
        painter.vline(b.left() + f * b.width(), b.y_range(), thin);
        painter.hline(b.x_range(), b.top() + f * b.height(), thin);
    }
    painter.rect_stroke(b, 0.0, Stroke::new(1.0_f32, Color32::WHITE));
    for corner in [b.left_top(), b.right_top(), b.left_bottom(), b.right_bottom()] {
        painter.rect_filled(Rect::from_center_size(corner, Vec2::splat(9.0)), 0.0, p.seal);
    }

    let resp = ui.interact(r.expand(12.0), ui.id().with("crop-box"), Sense::drag());
    let id = ui.id().with("crop-grip");
    let grip_at = |at: Pos2| {
        let near = |a: f32, b: f32| (a - b).abs() < 10.0;
        let inside_y = at.y > b.top() - 10.0 && at.y < b.bottom() + 10.0;
        let inside_x = at.x > b.left() - 10.0 && at.x < b.right() + 10.0;
        let g = [near(at.x, b.left()) && inside_y, near(at.y, b.top()) && inside_x, near(at.x, b.right()) && inside_y, near(at.y, b.bottom()) && inside_x];
        if g.iter().any(|v| *v) {
            Some(CropGrip(g))
        } else if b.contains(at) {
            Some(CropGrip([true; 4]))
        } else {
            None
        }
    };
    if let Some(h) = resp.hover_pos().and_then(grip_at) {
        let icon = match h.0 {
            [true, true, true, true] => egui::CursorIcon::Grab,
            [true, true, false, false] | [false, false, true, true] => egui::CursorIcon::ResizeNwSe,
            [false, true, true, false] | [true, false, false, true] => egui::CursorIcon::ResizeNeSw,
            [true, false, false, false] | [false, false, true, false] => egui::CursorIcon::ResizeHorizontal,
            _ => egui::CursorIcon::ResizeVertical,
        };
        ui.ctx().set_cursor_icon(icon);
    }
    if resp.drag_started() {
        let g = resp.interact_pointer_pos().and_then(grip_at);
        ui.memory_mut(|m| m.data.insert_temp(id, g));
    }
    let grip: Option<CropGrip> = ui.memory(|m| m.data.get_temp(id)).flatten();
    if let (Some(CropGrip(g)), true) = (grip, resp.dragged()) {
        let d = resp.drag_delta();
        let (dx, dy) = (d.x / r.width(), d.y / r.height());
        let mut n = c;
        if g == [true; 4] {
            let dx = dx.clamp(-n[0], 1.0 - n[2]);
            let dy = dy.clamp(-n[1], 1.0 - n[3]);
            n = [n[0] + dx, n[1] + dy, n[2] + dx, n[3] + dy];
        } else {
            const MIN: f32 = 0.05;
            if g[0] {
                n[0] = (n[0] + dx).clamp(0.0, n[2] - MIN);
            }
            if g[1] {
                n[1] = (n[1] + dy).clamp(0.0, n[3] - MIN);
            }
            if g[2] {
                n[2] = (n[2] + dx).clamp(n[0] + MIN, 1.0);
            }
            if g[3] {
                n[3] = (n[3] + dy).clamp(n[1] + MIN, 1.0);
            }
        }
        let mut s = app.editor.s.clone();
        set_crop_box(&mut s, n);
        app.editor.change(s);
    }
    if resp.drag_stopped() {
        ui.memory_mut(|m| m.data.remove::<Option<CropGrip>>(id));
    }
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
                let edited = app.editor.is_edited(shot);
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
                if edited {
                    paint_badge(ui, ui.painter(), rect.right_bottom() + Vec2::new(-4.0, -4.0), "Edited", Align2::RIGHT_BOTTOM, false);
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

/// Edit's own shortcuts: undo and redo, copy and paste settings, export.
pub fn keys(app: &mut App, ctx: &egui::Context) {
    if app.view != View::Edit {
        return;
    }
    let ed = &mut app.editor;
    let (undo, redo, export, copy, paste) = ctx.input(|i| {
        let cmd = i.modifiers.command;
        let shift = i.modifiers.shift;
        let paste = i.events.iter().find_map(|e| match e {
            egui::Event::Paste(t) if t.starts_with("v=2\n") => Some(t.clone()),
            _ => None,
        });
        (
            cmd && !shift && i.key_pressed(Key::Z),
            cmd && ((shift && i.key_pressed(Key::Z)) || i.key_pressed(Key::Y)),
            cmd && shift && i.key_pressed(Key::E),
            i.events.iter().any(|e| matches!(e, egui::Event::Copy)),
            paste,
        )
    });
    if undo {
        ed.undo();
    }
    if redo {
        ed.redo();
    }
    if copy && ed.opened.is_some() {
        ed.copy();
        ctx.copy_text(ed.s.encode());
    }
    if let Some(text) = paste {
        ed.copy_from(shootback_core::develop::Settings::decode(&text));
        ed.paste();
    }
    if export {
        ed.export(ctx);
    }
    let (crop, done) = ctx.input(|i| (!i.modifiers.any() && i.key_pressed(Key::R), i.key_pressed(Key::Enter) || i.key_pressed(Key::Escape)));
    if crop && !ctx.wants_keyboard_input() {
        ed.cropping = !ed.cropping;
    } else if done && ed.cropping {
        ed.cropping = false;
    }
}
