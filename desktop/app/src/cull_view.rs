//! Cull: the shots dealt one at a time, decided from the keyboard. P picks, X rejects, the up
//! arrow skips; bursts and near-duplicates come as one stack, two shots side by side until the
//! best is left. Space zooms in where the pointer is to check focus (on a single shot, so does a click).

use crate::app::{App, View};
use crate::cull::Verdict;
use crate::images::{FULL, LARGE};
use crate::state::Filter;
use crate::theme::palette;
use crate::widgets::{self, stage_chip};
use eframe::egui::{self, Align2, Color32, FontId, Frame, Key, Margin, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::library::Shot;

/// How far Space zooms in, against the photo fitted.
const ZOOM: f32 = 4.0;
const INFO_H: f32 = 52.0;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    if !app.cull.started() {
        start(app, ctx);
    }
    app.cull.poll();
    app.cull.follow(&mut app.library, &app.insights);
    app.cull.hover = None;
    egui::CentralPanel::default().frame(Frame::none().fill(p.stage).inner_margin(Margin::symmetric(24.0, 14.0))).show(ctx, |ui| {
        header(app, ui);
        ui.add_space(10.0);
        let Some(deck) = app.cull.deck.clone() else {
            ui.vertical_centered(|ui| {
                ui.add_space(ui.available_height() * 0.35);
                ui.add(egui::ProgressBar::new(app.cull.progress()).desired_width(280.0).desired_height(6.0).fill(p.seal));
                ui.add_space(10.0);
                ui.label(RichText::new("Finding similar shots ...").color(Color32::from_gray(170)));
            });
            return;
        };
        if deck.is_empty() {
            nothing(app, ui);
            return;
        }
        let Some(top) = app.cull.top().cloned() else {
            summary(app, ui);
            return;
        };
        // The next cards load while this one is decided.
        for st in deck.iter().skip(app.cull.pos + 1).take(2) {
            let s = st[0].display();
            let _ = app.images.get(&s.path, LARGE, s.orientation);
        }
        let body = ui.available_rect_before_wrap();
        let buttons = Rect::from_min_max(Pos2::new(body.left(), body.bottom() - 40.0), body.max);
        let stage = Rect::from_min_max(body.min, Pos2::new(body.right(), buttons.top() - 12.0));
        if top.len() > 1 {
            contest(app, ui, stage, &top);
        } else {
            card(app, ui, stage, &top[0]);
        }
        let mut bar = ui.new_child(egui::UiBuilder::new().max_rect(buttons).layout(egui::Layout::left_to_right(egui::Align::Center)));
        actions(app, &mut bar, top.len() > 1);
    });
}

/// Deals the shots in the chosen scope.
fn start(app: &mut App, ctx: &egui::Context) {
    if app.library.scanning() && !app.library.scanned_once {
        return;
    }
    let scope = app.cull.scope.clone();
    let shots: Vec<Shot> = app.library.shots.iter().filter(|s| app.library.matches(s, &scope)).cloned().collect();
    let root = app.library.root.clone();
    app.cull.start(ctx, &root, shots);
}

fn header(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    ui.horizontal(|ui| {
        let mut scopes = vec![(Filter::Review, "To review".to_string()), (Filter::Picks, "Picks".to_string())];
        let album = match (&app.cull.scope, &app.filter) {
            (Filter::Album(id), _) | (_, Filter::Album(id)) => Some(id.clone()),
            _ => None,
        };
        if let Some(a) = album.and_then(|id| app.library.alive_albums().find(|a| a.id == id).map(|a| (a.id.clone(), a.name.clone()))) {
            scopes.push((Filter::Album(a.0), a.1));
        }
        for (f, name) in scopes {
            let n = app.library.count(&f);
            let chosen = app.cull.scope == f;
            if stage_chip(ui, chosen, &format!("{name}  {n}")).on_hover_text("Cull these").clicked() && !chosen {
                app.cull.scope = f;
                start(app, ui.ctx());
            }
        }
        widgets::right_to_left(ui, |ui| {
            ui.add_enabled_ui(app.cull.can_undo(), |ui| {
                if stage_chip(ui, false, "Undo  U").on_hover_text("Also Backspace or Ctrl+Z").clicked() {
                    app.cull.undo(&mut app.library);
                }
            });
            if let Some(deck) = &app.cull.deck {
                if app.cull.pos < deck.len() {
                    ui.label(RichText::new(format!("{} of {}", app.cull.pos + 1, deck.len())).color(Color32::from_gray(170)));
                }
                let (known, all) = app.insights.count(deck.iter().flatten().map(|s| s.key()));
                if let Some(e) = app.insights.faces_error() {
                    ui.label(RichText::new("Faces not looked at").size(12.0).color(p.accent)).on_hover_text(e);
                } else if known < all {
                    ui.label(RichText::new(format!("Looking at faces and focus: {known} of {all}")).size(12.0).color(Color32::from_gray(140)));
                }
            }
        });
    });
}

fn nothing(app: &mut App, ui: &mut Ui) {
    ui.vertical_centered(|ui| {
        ui.add_space(ui.available_height() * 0.3);
        ui.label(RichText::new("Nothing to cull here").size(18.0).color(Color32::from_gray(220)));
        ui.add_space(6.0);
        let why = match app.cull.scope {
            Filter::Review => "Every shot is picked or rejected. Choose Picks to narrow them down.",
            Filter::Picks => "No picks yet.",
            _ => "This album is empty.",
        };
        ui.label(RichText::new(why).color(Color32::from_gray(160)));
    });
}

fn summary(app: &mut App, ui: &mut Ui) {
    let c = &app.cull;
    let (picked, rejected, skipped) = (c.count(Verdict::Pick), c.count(Verdict::Reject), c.count(Verdict::Skip));
    ui.vertical_centered(|ui| {
        ui.add_space(ui.available_height() * 0.3);
        ui.label(RichText::new("All done").size(22.0).family(crate::theme::display()).color(Color32::from_gray(230)));
        ui.add_space(8.0);
        let mut parts = vec![format!("{picked} picked"), format!("{rejected} rejected")];
        if skipped > 0 {
            parts.push(format!("{skipped} skipped"));
        }
        ui.label(RichText::new(parts.join(" · ")).color(Color32::from_gray(170)));
        ui.add_space(20.0);
        ui.horizontal(|ui| {
            centre(ui, &["Show picks", "Show rejected", "Cull again"]);
            if stage_chip(ui, false, "Show picks").clicked() {
                app.filter = Filter::Picks;
                app.view = View::Library;
            }
            if stage_chip(ui, false, "Show rejected").clicked() {
                app.filter = Filter::Rejected;
                app.view = View::Library;
            }
            if stage_chip(ui, false, "Cull again").on_hover_text("Deals what's still in this scope").clicked() {
                start(app, ui.ctx());
            }
        });
        ui.add_space(14.0);
        if app.cull.can_undo() {
            ui.label(RichText::new("U takes back the last decision").size(12.0).color(Color32::from_gray(130)));
        }
    });
}

/// Space before a row of chips so that it sits in the middle.
fn centre(ui: &mut Ui, labels: &[&str]) {
    let pad = ui.spacing().button_padding.x * 2.0 + ui.spacing().item_spacing.x;
    let w: f32 =
        labels.iter().map(|t| ui.painter().layout_no_wrap(t.to_string(), egui::TextStyle::Button.resolve(ui.style()), Color32::WHITE).size().x + pad).sum();
    ui.add_space((ui.available_width() - w).max(0.0) / 2.0);
}

fn actions(app: &mut App, ui: &mut Ui, stack: bool) {
    centre(ui, if stack { &["None of these  X", "Skip  ↑", "Keep both  B", "Not similar  N"] } else { &["Reject  X", "Skip  ↑", "Pick  P"] });
    let lib = &mut app.library;
    let c = &mut app.cull;
    if stack {
        if stage_chip(ui, false, "None of these  X").clicked() {
            c.decide(lib, Verdict::Reject);
        }
        if stage_chip(ui, false, "Skip  ↑").clicked() {
            c.decide(lib, Verdict::Skip);
        }
        if stage_chip(ui, false, "Keep both  B").on_hover_text("The shot on the right is kept too, and the left one meets the next").clicked() {
            if let Some(k) = c.contest.as_mut() {
                k.duel.keep_both();
            }
        }
        if stage_chip(ui, false, "Not similar  N").on_hover_text("Deals these shots one at a time").clicked() {
            c.split();
        }
    } else {
        if stage_chip(ui, false, "Reject  X").clicked() {
            c.decide(lib, Verdict::Reject);
        }
        if stage_chip(ui, false, "Skip  ↑").clicked() {
            c.decide(lib, Verdict::Skip);
        }
        if stage_chip(ui, true, "Pick  P").clicked() {
            c.decide(lib, Verdict::Pick);
        }
    }
}

/// Draws `shot` fitted into `rect` (zoomed when culling is zoomed in); returns where it landed.
fn photo(app: &mut App, ui: &Ui, rect: Rect, shot: &Shot) -> Option<Rect> {
    let show = shot.display().clone();
    let tex = match app.cull.zoom {
        Some(_) => app.images.get(&show.path, FULL, show.orientation).or_else(|| app.images.get(&show.path, LARGE, show.orientation)),
        None => app.images.get(&show.path, LARGE, show.orientation),
    }
    .or_else(|| app.images.any(&show.path))?;
    let size = tex.size_vec2();
    let s = (rect.width() / size.x).min(rect.height() / size.y);
    let r = Rect::from_center_size(rect.center(), size * s);
    let uv = match app.cull.zoom {
        Some([cx, cy]) => {
            let half = 0.5 / ZOOM;
            let (cx, cy) = (cx.clamp(half, 1.0 - half), cy.clamp(half, 1.0 - half));
            Rect::from_min_max(Pos2::new(cx - half, cy - half), Pos2::new(cx + half, cy + half))
        }
        None => Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
    };
    ui.painter().image(tex.id(), r, uv, Color32::WHITE);
    Some(r)
}

/// Clicks and drags on a shown photo: a drag while zoomed in moves around; true for a click.
/// Also notes the point of the photo under the pointer, for Space to zoom in on.
fn photo_input(app: &mut App, ui: &Ui, r: Rect, id: egui::Id) -> bool {
    let resp = ui.interact(r, id, Sense::click_and_drag());
    let resp = resp.on_hover_cursor(if app.cull.zoom.is_some() { egui::CursorIcon::Grab } else { egui::CursorIcon::PointingHand });
    if let Some(at) = resp.hover_pos() {
        app.cull.hover = Some(uv_at(app.cull.zoom, r, at));
    }
    if let Some(z) = app.cull.zoom.as_mut().filter(|_| resp.dragged()) {
        let d = resp.drag_delta();
        let half = 0.5 / ZOOM;
        z[0] = (z[0] - d.x / (r.width() * ZOOM)).clamp(half, 1.0 - half);
        z[1] = (z[1] - d.y / (r.height() * ZOOM)).clamp(half, 1.0 - half);
    }
    resp.clicked()
}

/// The point of the photo (0..1 each way) shown at `at` in `r`.
fn uv_at(zoom: Option<[f32; 2]>, r: Rect, at: Pos2) -> [f32; 2] {
    let (fx, fy) = ((at.x - r.left()) / r.width(), (at.y - r.top()) / r.height());
    match zoom {
        Some([cx, cy]) => {
            let half = 0.5 / ZOOM;
            let (cx, cy) = (cx.clamp(half, 1.0 - half), cy.clamp(half, 1.0 - half));
            [cx - half + fx / ZOOM, cy - half + fy / ZOOM]
        }
        None => [fx, fy],
    }
}

fn card(app: &mut App, ui: &mut Ui, stage: Rect, shot: &Shot) {
    let area = Rect::from_min_max(stage.min, Pos2::new(stage.right(), stage.bottom() - INFO_H));
    if let Some(r) = photo(app, ui, area, shot) {
        // A click zooms in there, or back out.
        if photo_input(app, ui, r, ui.id().with("cull-card")) {
            app.cull.zoom = match app.cull.zoom {
                None => app.cull.hover,
                Some(_) => None,
            };
        }
        if app.library.marks.get(&shot.key()).is_some() {
            let m = app.library.marks.get(&shot.key());
            let t = if m == Some(shootback_core::marks::Mark::Pick) { "Picked" } else { "Rejected" };
            widgets::paint_badge(ui, ui.painter(), r.left_top() + Vec2::new(10.0, 10.0), t, Align2::LEFT_TOP, false);
        }
        zoom_badge(app, ui, r);
        let notes = notes(app, shot, &[]);
        info(ui, Rect::from_min_size(Pos2::new(r.left(), r.bottom() + 8.0), Vec2::new(r.width(), INFO_H - 8.0)), shot, &notes);
    }
}

fn contest(app: &mut App, ui: &mut Ui, stage: Rect, stack: &[Shot]) {
    let Some(c) = app.cull.contest.as_ref() else { return };
    let (Some(ch), best) = (c.duel.challenger(), c.duel.best()) else { return };
    let (left, right) = (stack[c.order[best]].clone(), stack[c.order[ch]].clone());
    let (round, rounds) = (c.duel.round() + 1, c.duel.rounds());
    let line = Rect::from_min_size(stage.min, Vec2::new(stage.width(), 22.0));
    ui.painter().text(
        line.left_center(),
        Align2::LEFT_CENTER,
        format!("{} similar shots  ·  {round} of {rounds}  ·  ← or → for the better one", stack.len()),
        FontId::proportional(13.0),
        Color32::from_gray(190),
    );
    let gap = 12.0;
    let half = (stage.width() - gap) / 2.0;
    let top = line.bottom() + 8.0;
    for (i, shot) in [left, right].iter().enumerate() {
        let x = stage.left() + i as f32 * (half + gap);
        let side = Rect::from_min_max(Pos2::new(x, top), Pos2::new(x + half, stage.bottom() - INFO_H));
        if let Some(r) = photo(app, ui, side, shot) {
            if photo_input(app, ui, r, ui.id().with(("cull-side", i))) {
                choose(app, i == 0);
                return;
            }
            let hovered = ui.rect_contains_pointer(r);
            if hovered {
                ui.painter().rect_stroke(r, 0.0, Stroke::new(2.0_f32, palette(ui.ctx()).accent));
            }
            widgets::paint_badge(ui, ui.painter(), r.left_top() + Vec2::new(10.0, 10.0), if i == 0 { "←  Best so far" } else { "→" }, Align2::LEFT_TOP, false);
            zoom_badge(app, ui, r);
            let notes = notes(app, shot, stack);
            info(ui, Rect::from_min_size(Pos2::new(r.left(), r.bottom() + 8.0), Vec2::new(r.width(), INFO_H - 8.0)), shot, &notes);
        }
    }
}

/// Short notes on a shot: "Eyes closed", and in a stack "Sharpest" or "Softer".
fn notes(app: &App, shot: &Shot, stack: &[Shot]) -> Vec<String> {
    let known = &app.insights.known;
    let others: Vec<_> = stack.iter().filter(|s| s.key() != shot.key()).map(|s| known.get(&s.key())).collect();
    shootback_core::cull::best::notes(known.get(&shot.key()), &others)
}

fn choose(app: &mut App, best_wins: bool) {
    if let Some(c) = app.cull.contest.as_mut() {
        c.duel.choose(best_wins);
        app.cull.zoom = None;
    }
}

fn zoom_badge(app: &App, ui: &Ui, r: Rect) {
    if app.cull.zoom.is_some() {
        widgets::paint_badge(ui, ui.painter(), r.right_top() + Vec2::new(-10.0, 10.0), "Zoomed in · Space", Align2::RIGHT_TOP, false);
    }
}

/// Under a photo: its notes, name, kind and time.
fn info(ui: &mut Ui, rect: Rect, shot: &Shot, notes: &[String]) {
    let p = palette(ui.ctx());
    let painter = ui.painter_at(rect);
    let mut x = rect.left();
    for n in notes {
        let good = n == "Sharpest";
        let (fill, ink) = if good { (p.good, Color32::from_rgb(12, 40, 22)) } else { (Color32::from_rgb(255, 201, 77), Color32::from_rgb(61, 42, 0)) };
        let g = painter.layout_no_wrap(n.clone(), FontId::proportional(11.5), ink);
        let r = Rect::from_min_size(Pos2::new(x, rect.top()), g.size() + Vec2::new(14.0, 4.0));
        painter.rect_filled(r, r.height() / 2.0, fill);
        painter.galley(r.min + Vec2::new(7.0, 2.0), g, ink);
        x = r.right() + 6.0;
    }
    let kind = match (&shot.raw, &shot.jpeg) {
        (Some(_), Some(_)) => "RAW+JPEG",
        (Some(_), None) => "RAW",
        _ => "JPEG",
    };
    let time = chrono::DateTime::from_timestamp_millis(shot.taken_millis()).map_or(String::new(), |t| t.format("%-d %b, %H:%M:%S").to_string());
    let y = rect.top() + if notes.is_empty() { 0.0 } else { 22.0 };
    let name = painter.text(Pos2::new(rect.left(), y), Align2::LEFT_TOP, shot.primary().stem(), FontId::proportional(13.0), Color32::from_gray(225));
    painter.text(Pos2::new(name.right() + 12.0, y + 1.0), Align2::LEFT_TOP, format!("{kind} · {time}"), FontId::proportional(12.0), Color32::from_gray(150));
}

/// Cull's keys: P or → picks, X or ← rejects, ↑ skips, U undoes, Space zooms; in a stack ← and →
/// choose a side, B keeps both, N splits the stack. Escape goes back to the library.
pub fn keys(app: &mut App, ctx: &egui::Context) {
    if app.view != View::Cull || ctx.wants_keyboard_input() {
        return;
    }
    let pressed = |k: Key| ctx.input(|i| i.key_pressed(k) && !i.modifiers.alt);
    let ctrl_z = ctx.input(|i| i.modifiers.command && i.key_pressed(Key::Z));
    if pressed(Key::U) || pressed(Key::Backspace) || ctrl_z {
        app.cull.undo(&mut app.library);
        return;
    }
    if pressed(Key::Escape) {
        if app.cull.zoom.is_some() {
            app.cull.zoom = None;
        } else {
            app.view = View::Library;
        }
        return;
    }
    let Some(top) = app.cull.top().cloned() else { return };
    if pressed(Key::Space) {
        app.cull.zoom = if app.cull.zoom.is_some() { None } else { Some(app.cull.hover.unwrap_or([0.5, 0.5])) };
    }
    let (lib, c) = (&mut app.library, &mut app.cull);
    if pressed(Key::ArrowUp) || pressed(Key::S) {
        c.decide(lib, Verdict::Skip);
    } else if top.len() > 1 {
        if pressed(Key::ArrowLeft) || pressed(Key::Num1) {
            choose(app, true);
        } else if pressed(Key::ArrowRight) || pressed(Key::Num2) {
            choose(app, false);
        } else if pressed(Key::B) {
            if let Some(k) = c.contest.as_mut() {
                k.duel.keep_both();
            }
        } else if pressed(Key::X) {
            c.decide(lib, Verdict::Reject);
        } else if pressed(Key::N) {
            c.split();
        }
    } else if pressed(Key::P) || pressed(Key::ArrowRight) {
        c.decide(lib, Verdict::Pick);
    } else if pressed(Key::X) || pressed(Key::ArrowLeft) {
        c.decide(lib, Verdict::Reject);
    }
}
