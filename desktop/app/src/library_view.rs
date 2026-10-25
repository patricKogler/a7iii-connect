//! The library: sources and albums on the left, shots by day in the middle, the selected shot's
//! details on the right.

use crate::app::{App, View};
use crate::images::THUMB;
use crate::state::Filter;
use crate::theme::{display, palette};
use crate::widgets::{self, chip, fact, nav_row, outline_button, paint_badge, paint_seal, seal_button, section_label};
use chrono::{Local, TimeZone};
use eframe::egui::{self, Align2, FontId, Frame, Margin, Pos2, Rect, RichText, Sense, Stroke, Ui, Vec2};
use shootback_core::marks::Mark;

pub fn show(app: &mut App, ctx: &egui::Context) {
    let p = palette(ctx);
    egui::SidePanel::left("sources")
        .exact_width(232.0)
        .resizable(false)
        .frame(Frame::none().fill(p.panel).inner_margin(Margin::symmetric(10.0, 16.0)).stroke(Stroke::new(p.line, p.border)))
        .show(ctx, |ui| sidebar(app, ui));
    egui::SidePanel::right("details")
        .exact_width(300.0)
        .resizable(false)
        .frame(Frame::none().fill(p.panel).inner_margin(Margin::same(16.0)).stroke(Stroke::new(p.line, p.border)))
        .show(ctx, |ui| details(app, ui));
    egui::CentralPanel::default().frame(Frame::none().fill(p.bg)).show(ctx, |ui| grid(app, ui));
}

fn sidebar(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
        ui.spacing_mut().item_spacing.y = 2.0;
        section_label(ui, "Library");
        ui.add_space(6.0);
        for (f, name) in [(Filter::All, "All photos"), (Filter::Review, "To review"), (Filter::Picks, "Picks"), (Filter::Rejected, "Rejected")] {
            let n = app.library.count(&f);
            if nav_row(ui, app.filter == f, name, Some(n)).clicked() {
                app.filter = f;
            }
        }
        ui.add_space(20.0);
        ui.horizontal(|ui| {
            section_label(ui, "Albums");
            widgets::right_to_left(ui, |ui| {
                if ui
                    .add(egui::Label::new(RichText::new("+ New").color(p.accent)).selectable(false).sense(Sense::click()))
                    .on_hover_cursor(egui::CursorIcon::PointingHand)
                    .on_hover_text("New album")
                    .clicked()
                {
                    app.naming = Some((String::new(), String::new()));
                }
            });
        });
        ui.add_space(6.0);
        let albums: Vec<(String, String, usize)> = app.library.alive_albums().map(|a| (a.id.clone(), a.name.clone(), a.len())).collect();
        if albums.is_empty() {
            ui.label(RichText::new("No albums yet").size(12.0).color(p.text3));
        }
        for (id, name, n) in albums {
            let f = Filter::Album(id.clone());
            let r = nav_row(ui, app.filter == f, &name, Some(n));
            if r.clicked() {
                app.filter = f;
            }
            r.context_menu(|ui| {
                if ui.button("Rename…").clicked() {
                    app.naming = Some((id.clone(), name.clone()));
                    ui.close_menu();
                }
                if ui.button("Delete album").clicked() {
                    app.library.delete_album(&id);
                    if app.filter == Filter::Album(id.clone()) {
                        app.filter = Filter::All;
                    }
                    ui.close_menu();
                }
            });
        }
        ui.add_space(20.0);
        people(app, ui);
        ui.add_space(20.0);
        section_label(ui, "Folder");
        ui.add_space(6.0);
        ui.add(egui::Label::new(RichText::new(app.library.root.display().to_string()).monospace().size(12.0).color(p.text)).wrap());
        ui.add_space(4.0);
        ui.label(RichText::new("RAW and JPEG side by side, in a folder per day. Marks and albums in .shootback.").size(12.0).color(p.text3));
        ui.add_space(6.0);
        if outline_button(ui, "Choose another folder…").clicked() {
            if let Some(dir) = rfd::FileDialog::new().set_directory(&app.library.root).pick_folder() {
                app.set_library(ui.ctx(), dir);
            }
        }
    });
    name_album_dialog(app, ui.ctx());
    name_person_dialog(app, ui.ctx());
    crate::merge_view::dialog(app, ui.ctx());
    crate::batch::delete_dialog(app, ui.ctx());
}

/// A person as the sidebar lists them: id, filter key, name, shots, and a face (shot and box).
type PersonRow = (String, String, Option<String>, usize, Option<(String, [f32; 4])>);

/// The people found in the photos: a face, a name and how many shots they're in.
fn people(app: &mut App, ui: &mut Ui) {
    use shootback_core::ai::{self, catalog::FACES, Progress};
    let p = palette(ui.ctx());
    section_label(ui, "People");
    ui.add_space(6.0);
    if !ai::installed(&FACES) {
        match ai::status(&FACES) {
            Some(Progress::Downloading(f)) => {
                ui.label(RichText::new(format!("Getting the face model ... {:.0} %", f * 100.0)).size(12.0).color(p.text3));
                ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
            }
            other => {
                if let Some(Progress::Failed(e)) = other {
                    ui.label(RichText::new(e).size(12.0).color(p.accent));
                }
                ui.add(
                    egui::Label::new(
                        RichText::new("Groups the faces in your photos by who they are, as the phone does. Needs a 10 MB face model, run on this computer.")
                            .size(12.0)
                            .color(p.text3),
                    )
                    .wrap(),
                );
                ui.add_space(4.0);
                if outline_button(ui, "Find people").clicked() {
                    app.insights.fetch_models();
                    ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
                }
            }
        }
        return;
    }
    let shown: Vec<PersonRow> = shootback_core::people::shown(&app.insights.people)
        .into_iter()
        .map(|person| {
            let face = person.faces.first().and_then(|f| app.insights.known.get(&f.shot).and_then(|i| i.faces.get(f.index)).map(|i| (f.shot.clone(), i.b)));
            (person.id.clone(), person.key(), person.name.clone(), person.shots(), face)
        })
        .collect();
    if shown.is_empty() {
        let (known, all) = app.insights.count(app.library.shots.iter().map(|s| s.key()));
        let t = if known < all { format!("Looking at the photos ... {known} of {all}") } else { "No one found in two photos or more yet".to_string() };
        ui.label(RichText::new(t).size(12.0).color(p.text3));
        return;
    }
    for (id, key, name, n, face) in shown {
        let f = Filter::Person(key);
        let r = nav_row(ui, app.filter == f, &format!("       {}", name.as_deref().unwrap_or("Someone")), Some(n));
        // A small square of the face at the row's start.
        let at = Rect::from_min_size(Pos2::new(r.rect.left() + 8.0, r.rect.center().y - 10.0), Vec2::splat(20.0));
        let shot = face.as_ref().and_then(|(k, _)| app.library.shots.iter().find(|s| &s.key() == k)).map(|s| s.display().clone());
        match (shot, face) {
            (Some(show), Some((_, b))) => {
                if let Some(tex) = app.images.get(&show.path, THUMB, show.orientation) {
                    let (cx, cy, half) = ((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0, (b[2] - b[0]).max(b[3] - b[1]) * 0.6);
                    let [tw, th] = tex.size().map(|v| v as f32);
                    let (hx, hy) = (half, half * tw / th);
                    let uv = Rect::from_min_max(Pos2::new(cx - hx, cy - hy), Pos2::new(cx + hx, cy + hy));
                    ui.painter().image(tex.id(), at, uv, egui::Color32::WHITE);
                }
            }
            _ => {
                ui.painter().rect_filled(at, 2.0, p.control);
            }
        }
        if r.clicked() {
            app.filter = f;
        }
        r.context_menu(|ui| {
            if ui.button(if name.is_some() { "Rename…" } else { "Name…" }).clicked() {
                app.naming_person = Some((id.clone(), name.clone().unwrap_or_default()));
                ui.close_menu();
            }
        });
        if r.double_clicked() {
            app.naming_person = Some((id.clone(), name.clone().unwrap_or_default()));
        }
    }
    ui.add_space(4.0);
    let (known, all) = app.insights.count(app.library.shots.iter().map(|s| s.key()));
    if known < all {
        ui.label(RichText::new(format!("Still looking for faces: {known} of {all} photos")).size(11.5).color(p.text3));
    }
    ui.label(RichText::new("Right-click someone to name them. Names travel to and from the phone.").size(11.5).color(p.text3));
}

fn name_person_dialog(app: &mut App, ctx: &egui::Context) {
    let Some((id, mut text)) = app.naming_person.clone() else { return };
    let mut open = true;
    let mut done = false;
    egui::Window::new("Who is this?").collapsible(false).resizable(false).anchor(Align2::CENTER_CENTER, Vec2::ZERO).open(&mut open).show(ctx, |ui| {
        let r = ui.add(egui::TextEdit::singleline(&mut text).hint_text("Name").desired_width(280.0));
        r.request_focus();
        if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
            done = true;
        }
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            if seal_button(ui, "Save").clicked() {
                done = true;
            }
            if outline_button(ui, "Cancel").clicked() {
                app.naming_person = None;
            }
        });
    });
    if !open {
        app.naming_person = None;
    } else if done {
        let was = app.insights.people.iter().find(|p| p.id == id).map(|p| p.key());
        app.insights.rename(std::slice::from_ref(&id), &text);
        app.library.people = app.insights.shots_by_person();
        // Showing that person: keep showing them under the new name.
        if was.is_some_and(|k| app.filter == Filter::Person(k)) {
            if let Some(p) = app.insights.people.iter().find(|p| p.id == id) {
                app.filter = Filter::Person(p.key());
            }
        }
        app.naming_person = None;
    } else if app.naming_person.is_some() {
        app.naming_person = Some((id, text));
    }
}

fn name_album_dialog(app: &mut App, ctx: &egui::Context) {
    let Some((id, mut text)) = app.naming.clone() else { return };
    let mut open = true;
    let mut done = false;
    egui::Window::new(if id.is_empty() { "New album" } else { "Rename album" })
        .collapsible(false)
        .resizable(false)
        .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
        .open(&mut open)
        .show(ctx, |ui| {
            let r = ui.add(egui::TextEdit::singleline(&mut text).hint_text("Iceland 2026").desired_width(280.0));
            r.request_focus();
            if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                done = true;
            }
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if seal_button(ui, if id.is_empty() { "Create" } else { "Rename" }).clicked() {
                    done = true;
                }
                if outline_button(ui, "Cancel").clicked() {
                    app.naming = None;
                }
            });
        });
    if !open {
        app.naming = None;
    } else if done {
        if id.is_empty() {
            let keys = app.targets();
            let new = app.library.create_album(&text, &keys, &app.settings.device_id);
            app.filter = Filter::Album(new);
        } else {
            app.library.rename_album(&id, &text);
        }
        app.naming = None;
    } else if app.naming.is_some() {
        app.naming = Some((id, text));
    }
}

fn title(filter: &Filter, app: &App) -> String {
    match filter {
        Filter::All => "All photos".into(),
        Filter::Review => "To review".into(),
        Filter::Picks => "Picks".into(),
        Filter::Rejected => "Rejected".into(),
        Filter::Album(id) => app.library.albums.iter().find(|a| &a.id == id).map_or("Album".into(), |a| a.name.clone()),
        Filter::Person(key) => app.insights.people.iter().find(|p| &p.key() == key).and_then(|p| p.name.clone()).unwrap_or_else(|| "Someone".into()),
    }
}

fn day_of(millis: i64) -> chrono::NaiveDate {
    Local.timestamp_millis_opt(millis).single().map_or_else(|| chrono::NaiveDate::from_ymd_opt(1970, 1, 1).unwrap(), |t| t.date_naive())
}

/// Under the search box: how searching by what's in the photos is getting on.
fn search_status(app: &mut App, ui: &mut Ui) {
    use shootback_core::ai::{self, catalog::CLIP, Progress};
    let p = palette(ui.ctx());
    let small = |t: String| RichText::new(t).size(12.0).color(p.text3);
    let (done, all) = app.finder.progress(&app.library.shots);
    if ai::installed(&CLIP) && app.finder.error.is_none() && done >= all {
        return;
    }
    ui.horizontal(|ui| {
        widgets::right_to_left(ui, |ui| {
            if !ai::installed(&CLIP) {
                match ai::status(&CLIP) {
                    Some(Progress::Downloading(f)) => {
                        ui.label(small(format!("Getting the search model ... {:.0} %", f * 100.0)));
                        ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
                    }
                    other => {
                        if ui
                            .add(
                                egui::Label::new(RichText::new("Get the search model (600 MB)").size(12.0).color(p.accent))
                                    .selectable(false)
                                    .sense(Sense::click()),
                            )
                            .on_hover_cursor(egui::CursorIcon::PointingHand)
                            .clicked()
                        {
                            app.finder.fetch_model();
                            ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
                        }
                        let why = match other {
                            Some(Progress::Failed(e)) => format!("Couldn't get it: {e}."),
                            _ => "Only file names for now. To find photos by what's in them:".to_string(),
                        };
                        ui.label(small(why));
                    }
                }
                return;
            }
            if let Some(e) = &app.finder.error {
                ui.label(RichText::new(e).size(12.0).color(p.accent));
            }
            if done < all {
                ui.label(small(format!("Looked at {done} of {all} photos for search so far")));
            }
        });
    });
}

enum Row {
    /// A day's heading, and the day's shots.
    Day(String, Vec<usize>),
    Tiles(Vec<usize>),
}

fn grid(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    let visible = app.visible();
    Frame::none().inner_margin(Margin { left: 20.0, right: 20.0, top: 14.0, bottom: 12.0 }).show(ui, |ui| {
        ui.horizontal(|ui| {
            widgets::heading(ui, &title(&app.filter, app));
            ui.label(RichText::new(format!("{} shots", widgets::thousands(visible.len()))).color(p.text3));
            if crate::batch::offers_delete_all(app)
                && !visible.is_empty()
                && outline_button(ui, &format!("Delete all {} …", widgets::thousands(visible.len()))).clicked()
            {
                app.select_all(&visible);
                app.ask_delete(false);
            }
            widgets::right_to_left(ui, |ui| {
                for (size, label) in [(232.0, "L"), (168.0, "M"), (128.0, "S")] {
                    if chip(ui, app.tile == size, label).on_hover_text("Tile size").clicked() {
                        app.tile = size;
                    }
                }
                ui.add_space(12.0);
                ui.add(
                    egui::TextEdit::singleline(&mut app.search).hint_text("Search names or what's in them").desired_width(240.0).margin(egui::vec2(8.0, 6.0)),
                );
            });
        });
        if !app.search.trim().is_empty() {
            search_status(app, ui);
        }
    });
    ui.painter().hline(ui.max_rect().x_range(), ui.cursor().top(), Stroke::new(1.0_f32, p.border));

    if app.library.shots.is_empty() {
        ui.add_space(80.0);
        ui.vertical_centered(|ui| {
            if app.library.scanning() && !app.library.scanned_once {
                ui.label(RichText::new("Reading the library ...").color(p.text2));
            } else {
                widgets::subheading(ui, "No photos here yet");
                ui.add_space(6.0);
                ui.label(RichText::new("Bring them in from the camera over Wi-Fi or FTP, or from a card.").color(p.text2));
                ui.add_space(12.0);
                if seal_button(ui, "Get photos from the camera").clicked() {
                    app.view = View::Import;
                }
            }
        });
        return;
    }

    app.tidy_selection(&visible);
    // Lay the shots out by day, a row of tiles at a time, and draw only the rows in view.
    let tile = app.tile;
    let gap = 8.0;
    let label_h = 20.0;
    let width = ui.available_width() - 40.0;
    let cols = ((width + gap) / (tile + gap)).floor().max(1.0) as usize;
    let tile_w = (width - gap * (cols as f32 - 1.0)) / cols as f32;
    let tile_h = tile_w * 2.0 / 3.0;
    let mut rows: Vec<Row> = Vec::new();
    let mut i = 0;
    while i < visible.len() {
        let day = day_of(app.library.shots[visible[i]].taken_millis());
        let mut j = i;
        while j < visible.len() && day_of(app.library.shots[visible[j]].taken_millis()) == day {
            j += 1;
        }
        rows.push(Row::Day(format!("{} · {} shots", day.format("%A, %b %-d %Y"), j - i), visible[i..j].to_vec()));
        for chunk in visible[i..j].chunks(cols) {
            rows.push(Row::Tiles(chunk.to_vec()));
        }
        i = j;
    }
    let heights: Vec<f32> = rows.iter().map(|r| if matches!(r, Row::Day(..)) { 36.0 } else { tile_h + label_h + gap }).collect();
    let total: f32 = heights.iter().sum::<f32>() + 16.0;

    // Keep the selection in view when the keyboard moves it.
    let sel_row = app.selected_index(&visible).and_then(|si| {
        let key = visible[si];
        rows.iter().position(|r| matches!(r, Row::Tiles(t) if t.contains(&key)))
    });
    let mut scroll = egui::ScrollArea::vertical().auto_shrink(false);
    if ui.input(|i| {
        i.key_pressed(egui::Key::ArrowDown) || i.key_pressed(egui::Key::ArrowUp) || i.key_pressed(egui::Key::ArrowLeft) || i.key_pressed(egui::Key::ArrowRight)
    }) {
        if let Some(r) = sel_row {
            let top: f32 = heights[..r].iter().sum();
            let h = ui.available_height();
            let current = ui.ctx().memory(|m| m.data.get_temp::<f32>(egui::Id::new("grid-offset"))).unwrap_or(0.0);
            if top < current || top + heights[r] > current + h {
                scroll = scroll.vertical_scroll_offset((top - h / 3.0).max(0.0));
            }
        }
    }
    let out = scroll.show_viewport(ui, |ui, viewport| {
        ui.set_height(total);
        // Under the tiles: a click between them leaves just the active shot selected.
        let bg = ui.interact(Rect::from_min_size(ui.min_rect().min, Vec2::new(ui.available_width(), total)), ui.id().with("grid-bg"), Sense::click());
        let origin = ui.min_rect().min + Vec2::new(20.0, 8.0);
        let mut y = 0.0;
        for (r, row) in rows.iter().enumerate() {
            let h = heights[r];
            if y + h >= viewport.min.y && y <= viewport.max.y {
                let top = origin.y + y;
                match row {
                    Row::Day(text, day) => day_heading(app, ui, Pos2::new(origin.x, top), width, text, day),
                    Row::Tiles(items) => {
                        for (c, &idx) in items.iter().enumerate() {
                            let rect = Rect::from_min_size(Pos2::new(origin.x + c as f32 * (tile_w + gap), top), Vec2::new(tile_w, tile_h));
                            tile_ui(app, ui, idx, rect, label_h, &visible);
                        }
                    }
                }
            }
            y += h;
        }
        if bg.clicked() {
            app.selection.clear();
        }
    });
    ui.ctx().memory_mut(|m| m.data.insert_temp(egui::Id::new("grid-offset"), out.state.offset.y));
    crate::batch::done_line(app, ui);
}

/// A day's heading, with a tick (on hover, or while gathering) that selects the whole day.
fn day_heading(app: &mut App, ui: &mut Ui, at: Pos2, width: f32, text: &str, day: &[usize]) {
    let p = palette(ui.ctx());
    let r = ui.painter().text(at + Vec2::new(0.0, 22.0), Align2::LEFT_BOTTOM, text, FontId::new(14.0, display()), p.text2);
    let row = Rect::from_min_size(at, Vec2::new(width, 30.0));
    let hovered = ui.rect_contains_pointer(row);
    if !(hovered || app.gathering()) {
        return;
    }
    let all = app.day_selected(day);
    let center = Pos2::new(r.right() + 16.0, r.center().y);
    let hit = Rect::from_center_size(center, Vec2::splat(24.0));
    let resp = ui.interact(hit, ui.id().with(("day", day.first())), Sense::click()).on_hover_text(if all { "Take this day away" } else { "Select this day" });
    paint_tick(ui, center, all, resp.hovered(), false);
    if resp.clicked() {
        app.tick_day(day);
    }
}

/// A round tick: filled with a check when on, an empty ring when off. `over_photo` gives the ring
/// a ground so it reads on any picture.
fn paint_tick(ui: &Ui, center: Pos2, on: bool, hovered: bool, over_photo: bool) {
    let p = palette(ui.ctx());
    let painter = ui.painter();
    let radius = 9.0;
    if on {
        painter.circle_filled(center, radius, p.seal);
        let pts = [center + Vec2::new(-4.0, 0.2), center + Vec2::new(-1.2, 3.0), center + Vec2::new(4.2, -3.0)];
        painter.add(egui::Shape::line(pts.to_vec(), Stroke::new(1.8_f32, p.on_seal)));
    } else {
        let ground = if over_photo { p.bg.gamma_multiply(if hovered { 0.9 } else { 0.6 }) } else { egui::Color32::TRANSPARENT };
        painter.circle(center, radius, ground, Stroke::new(1.5_f32, if hovered { p.strong } else { p.text2 }));
    }
}

fn tile_ui(app: &mut App, ui: &mut Ui, idx: usize, rect: Rect, label_h: f32, visible: &[usize]) {
    let p = palette(ui.ctx());
    let shot = app.library.shots[idx].clone();
    let key = shot.key();
    let full = rect.union(Rect::from_min_size(rect.left_bottom(), Vec2::new(rect.width(), label_h)));
    let resp = ui.interact(full, ui.id().with(("tile", &key)), Sense::click());
    let painter = ui.painter_at(full.expand(4.0));
    let show = shot.display();
    painter.rect_filled(rect, p.tile_radius, p.selected);
    if let Some(tex) = app.images.get(&show.path, THUMB, show.orientation) {
        // Fill the 3:2 cell, cropping what doesn't fit.
        let [w, h] = tex.size().map(|v| v as f32);
        let (cw, ch) = (rect.width(), rect.height());
        let scale = (cw / w).max(ch / h);
        let (uw, uh) = (cw / (w * scale), ch / (h * scale));
        let uv = Rect::from_min_size(egui::pos2((1.0 - uw) / 2.0, (1.0 - uh) / 2.0), Vec2::new(uw, uh));
        let mut img = egui::epaint::RectShape::filled(rect, p.tile_radius, egui::Color32::WHITE);
        img.fill_texture_id = tex.id();
        img.uv = uv;
        painter.add(img);
    } else if app.images.failed(&show.path, THUMB) {
        painter.text(rect.center(), Align2::CENTER_CENTER, "No preview", FontId::proportional(11.5), p.text3);
    }
    let mark = app.library.mark(&shot);
    if mark == Some(Mark::Reject) {
        painter.rect_filled(rect, p.tile_radius, p.bg.gamma_multiply(0.45));
    }
    let kind = match (&shot.raw, &shot.jpeg) {
        (Some(_), Some(_)) => "RAW + JPEG",
        (Some(_), None) => "RAW",
        _ => "JPEG",
    };
    // The tick shows on hover and while gathering; the file badge makes room for it.
    let ticks = resp.hovered() || app.gathering();
    let tick_at = rect.left_top() + Vec2::new(16.0, 16.0);
    paint_badge(ui, &painter, rect.left_top() + Vec2::new(if ticks { 30.0 } else { 6.0 }, 6.0), kind, Align2::LEFT_TOP, false);
    match mark {
        Some(Mark::Pick) => paint_seal(&painter, rect.right_top() + Vec2::new(-6.0, 6.0), "Pick", Align2::RIGHT_TOP),
        Some(Mark::Reject) => paint_badge(ui, &painter, rect.right_top() + Vec2::new(-6.0, 6.0), "Reject", Align2::RIGHT_TOP, true),
        None => {}
    }
    if app.editor.is_edited(&shot) {
        paint_badge(ui, &painter, rect.right_bottom() + Vec2::new(-6.0, -6.0), "Edited", Align2::RIGHT_BOTTOM, false);
    }
    let selected = app.is_selected(&key);
    let active = app.selected.as_deref() == Some(key.as_str());
    if selected {
        let w = if app.many() && active { 3.0 } else { 2.0 };
        painter.rect_stroke(rect.expand(w), p.tile_radius + w, Stroke::new(w, p.accent));
    } else if resp.hovered() {
        painter.rect_stroke(rect.expand(1.0), p.tile_radius + 1.0, Stroke::new(p.line, p.control));
    }
    let name = shot.primary().stem().to_string();
    let time = Local.timestamp_millis_opt(shot.taken_millis()).single().map_or(String::new(), |t| t.format("%H:%M").to_string());
    painter.text(rect.left_bottom() + Vec2::new(0.0, 4.0), Align2::LEFT_TOP, name, FontId::monospace(11.5), p.text2);
    painter.text(rect.right_bottom() + Vec2::new(0.0, 4.0), Align2::RIGHT_TOP, time, FontId::proportional(11.5), p.text3);
    let mut ticked = false;
    if ticks {
        // Added after the tile, so it takes the clicks on it.
        let t = ui.interact(Rect::from_center_size(tick_at, Vec2::splat(26.0)), ui.id().with(("tick", &key)), Sense::click());
        paint_tick(ui, tick_at, selected && app.gathering(), t.hovered(), true);
        if t.clicked() {
            app.tick(&key);
            ticked = true;
        }
    }
    if resp.clicked() && !ticked {
        let m = ui.input(|i| i.modifiers);
        app.click(&key, visible, m);
    }
    if resp.double_clicked() && !app.gathering() {
        app.select_one(Some(key.clone()));
        app.view = View::Edit;
    }
    resp.context_menu(|ui| {
        if app.many() && app.selection.keys.contains(&key) {
            crate::batch::context_menu(app, ui);
        } else {
            app.select_one(Some(key.clone()));
            mark_menu(app, ui, &key);
        }
    });
}

pub fn mark_menu(app: &mut App, ui: &mut Ui, key: &str) {
    if ui.button("Pick   P").clicked() {
        app.library.set_mark(key, Some(Mark::Pick));
        ui.close_menu();
    }
    if ui.button("Reject   X").clicked() {
        app.library.set_mark(key, Some(Mark::Reject));
        ui.close_menu();
    }
    if ui.button("Clear mark   U").clicked() {
        app.library.set_mark(key, None);
        ui.close_menu();
    }
    if app.library.shots.iter().any(|s| s.key() == key && s.raw.is_some()) && ui.button("Merge HDR or focus stack …").clicked() {
        app.merging = crate::merge_view::Merging::new(&app.library.shots, key);
        ui.close_menu();
    }
    ui.separator();
    let albums: Vec<(String, String, bool)> = app.library.alive_albums().map(|a| (a.id.clone(), a.name.clone(), a.contains(key))).collect();
    ui.menu_button("Albums", |ui| {
        for (id, name, inside) in albums {
            if ui.checkbox(&mut inside.clone(), name).clicked() {
                app.library.toggle_in_album(&id, key);
            }
        }
        if ui.button("New album…").clicked() {
            app.naming = Some((String::new(), String::new()));
            ui.close_menu();
        }
    });
}

/// The selected shot: preview, marks, how it was shot, albums.
pub fn details(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    if app.view == View::Library && app.many() {
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, |ui| crate::batch::panel(app, ui));
        return;
    }
    let Some(key) = app.selected.clone() else {
        ui.label(RichText::new("Select a photo to see its details.").color(p.text3));
        return;
    };
    let Some(shot) = app.library.shots.iter().find(|s| s.key() == key).cloned() else { return };
    // The edit screen already shows the shot large, and its panel scrolls on its own.
    let in_edit = app.view == View::Edit;
    let mut body = |ui: &mut Ui| {
        if !in_edit {
            let show = shot.display();
            let w = ui.available_width();
            let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, w * 2.0 / 3.0), Sense::click());
            ui.painter().rect_filled(rect, 1.0, p.stage);
            if let Some(tex) = app.images.get(&show.path, THUMB, show.orientation) {
                let [tw, th] = tex.size().map(|v| v as f32);
                let s = (rect.width() / tw).min(rect.height() / th);
                ui.painter().image(
                    tex.id(),
                    Rect::from_center_size(rect.center(), Vec2::new(tw * s, th * s)),
                    Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
                    egui::Color32::WHITE,
                );
            }
            if resp.double_clicked() {
                app.view = View::Edit;
            }
            ui.add_space(10.0);
        }
        ui.label(RichText::new(&shot.primary().name).monospace().size(13.5).color(p.strong));
        let when = Local.timestamp_millis_opt(shot.taken_millis()).single().map_or(String::new(), |t| t.format("%b %-d %Y, %H:%M").to_string());
        let twin = shot.raw.as_ref().and(shot.jpeg.as_ref()).map(|j| format!("+ {} · ", j.name)).unwrap_or_default();
        ui.label(RichText::new(format!("{twin}{when}")).color(p.text3));
        ui.add_space(12.0);
        let mark = app.library.mark(&shot);
        ui.columns(2, |c| {
            let pick = if mark == Some(Mark::Pick) { seal_button(&mut c[0], "Picked  P") } else { outline_button(&mut c[0], "Pick  P") };
            if pick.clicked() {
                app.library.set_mark(&key, if mark == Some(Mark::Pick) { None } else { Some(Mark::Pick) });
            }
            let label = if mark == Some(Mark::Reject) { "Rejected  X" } else { "Reject  X" };
            if outline_button(&mut c[1], label).clicked() {
                app.library.set_mark(&key, if mark == Some(Mark::Reject) { None } else { Some(Mark::Reject) });
            }
        });
        ui.add_space(14.0);
        let meta = app.library.meta(shot.primary()).clone();
        Frame::none().stroke(Stroke::new(p.line, p.border)).rounding(p.radius).inner_margin(Margin::same(12.0)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            egui::Grid::new("facts").num_columns(2).spacing([24.0, 10.0]).show(ui, |ui| {
                fact(ui, "Shutter", meta.shutter.as_deref().unwrap_or("–"));
                fact(ui, "Aperture", meta.aperture.as_deref().unwrap_or("–"));
                ui.end_row();
                fact(ui, "ISO", &meta.iso.map_or("–".into(), |v| v.to_string()));
                fact(ui, "Focal length", meta.focal_length.as_deref().unwrap_or("–"));
                ui.end_row();
            });
            ui.add_space(6.0);
            fact(ui, "Lens", meta.lens.as_deref().unwrap_or("–"));
            if let Some(cam) = &meta.camera {
                ui.add_space(6.0);
                fact(ui, "Camera", cam);
            }
        });
        ui.add_space(16.0);
        section_label(ui, "Albums");
        ui.add_space(6.0);
        ui.horizontal_wrapped(|ui| {
            let albums: Vec<(String, String, bool)> = app.library.alive_albums().map(|a| (a.id.clone(), a.name.clone(), a.contains(&key))).collect();
            for (id, name, inside) in &albums {
                if *inside && chip(ui, true, name).on_hover_text("Click to take it out").clicked() {
                    app.library.toggle_in_album(id, &key);
                }
            }
            ui.menu_button(RichText::new("Add to album").color(p.text2), |ui| {
                for (id, name, inside) in &albums {
                    if !inside && ui.button(name).clicked() {
                        app.library.toggle_in_album(id, &key);
                        ui.close_menu();
                    }
                }
                if ui.button("New album…").clicked() {
                    app.naming = Some((String::new(), String::new()));
                    ui.close_menu();
                }
            });
        });
        ui.add_space(18.0);
        if !in_edit && seal_button(ui, "Open large  E").clicked() {
            app.view = View::Edit;
        }
    };
    if in_edit {
        body(ui);
    } else {
        egui::ScrollArea::vertical().auto_shrink(false).show(ui, body);
    }
}
