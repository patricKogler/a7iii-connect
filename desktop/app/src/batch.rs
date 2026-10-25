//! Doing something to several shots at once: marks, albums, presets, pasted settings, exports
//! and deleting. Each batch but a permanent delete can be undone (Ctrl+Z, or Undo on the line it
//! leaves at the bottom of the grid).
//!
//! Deleting takes a shot's RAW and JPEG together, into the system trash (Shift+Delete deletes for
//! good). Marks and edits stay in the library's notes, so a shot taken back out of the trash comes
//! back as it was; albums let go of it. `deleted.txt` keeps a paired phone from sending it back.

use crate::app::App;
use crate::state::Filter;
use crate::theme::palette;
use crate::widgets::{self, outline_button, seal_button, section_label};
use eframe::egui::{self, Align2, RichText, Ui, Vec2};
use shootback_core::deleted;
use shootback_core::develop::presets::{self, Preset};
use shootback_core::develop::Settings;
use shootback_core::library::{Photo, Shot};
use shootback_core::marks::Mark;
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// How long the line saying what a batch did stays.
const SHOWN: Duration = Duration::from_secs(8);

/// What undoes a batch.
pub enum Undo {
    Marks(Vec<(String, Option<Mark>)>),
    Edits(Vec<(String, Settings)>),
    /// Put into (true) or taken out of an album.
    Album {
        id: String,
        keys: Vec<String>,
        put_in: bool,
    },
    /// Moved to the trash: the files, and the albums each shot was in.
    Trash {
        photos: Vec<Photo>,
        albums: Vec<(String, Vec<String>)>,
    },
}

/// The last batch: what it did, and how to undo it.
pub struct Done {
    pub text: String,
    pub undo: Option<Undo>,
    pub at: Instant,
    pub failed: bool,
}

/// The delete confirmation, and the delete once it runs.
pub struct Deleting {
    pub keys: Vec<String>,
    pub forever: bool,
    pub error: Option<String>,
    running: Option<Running>,
}

struct Running {
    done: Arc<AtomicUsize>,
    rx: Receiver<Outcome>,
    /// The shot to show after, and the albums the deleted shots leave.
    next: Option<String>,
}

/// What a delete got through: the shots now gone, and why it stopped, if it did.
struct Outcome {
    gone: Vec<Shot>,
    error: Option<String>,
}

fn noun(n: usize) -> String {
    if n == 1 {
        "1 shot".into()
    } else {
        format!("{} shots", widgets::thousands(n))
    }
}

fn size(bytes: u64) -> String {
    let mb = bytes as f64 / 1e6;
    if mb < 1.0 {
        "under 1 MB".into()
    } else if mb >= 1000.0 {
        format!("{:.1} GB", mb / 1000.0)
    } else {
        format!("{mb:.0} MB")
    }
}

fn files(shot: &Shot) -> impl Iterator<Item = &Photo> {
    [shot.raw.as_ref(), shot.jpeg.as_ref()].into_iter().flatten()
}

impl App {
    fn shots_of(&self, keys: &[String]) -> Vec<Shot> {
        let want: HashSet<&String> = keys.iter().collect();
        self.library.shots.iter().filter(|s| want.contains(&s.key())).cloned().collect()
    }

    fn did(&mut self, text: String, undo: Option<Undo>) {
        self.done = Some(Done { text, undo, at: Instant::now(), failed: false });
    }

    fn failed(&mut self, text: String) {
        self.done = Some(Done { text, undo: None, at: Instant::now(), failed: true });
    }

    pub fn batch_mark(&mut self, mark: Option<Mark>) {
        let keys = self.targets();
        if keys.is_empty() {
            return;
        }
        let before = self.library.set_marks(&keys, mark);
        let verb = match mark {
            Some(Mark::Pick) => "Picked",
            Some(Mark::Reject) => "Rejected",
            None => "Cleared the marks of",
        };
        self.did(format!("{verb} {}", noun(keys.len())), Some(Undo::Marks(before)));
    }

    pub fn batch_album(&mut self, id: &str, put_in: bool) {
        let keys = self.targets();
        let changed = self.library.set_in_album(id, &keys, put_in);
        let name = self.library.albums.iter().find(|a| a.id == id).map_or(String::new(), |a| a.name.clone());
        let text = if put_in { format!("Put {} in {name}", noun(changed.len())) } else { format!("Took {} out of {name}", noun(changed.len())) };
        self.did(text, Some(Undo::Album { id: id.into(), keys: changed, put_in }));
    }

    /// Changes every target's edit with `f`, as one undoable batch.
    fn batch_edit(&mut self, what: &str, f: impl Fn(&Settings) -> Settings) {
        let keys = self.targets();
        let mut before = Vec::new();
        for k in &keys {
            let old = self.editor.edits_of(k);
            let new = f(&old);
            if new != old {
                self.editor.set_edits(k, &new);
                before.push((k.clone(), old));
            }
        }
        self.did(format!("{what} {}", noun(before.len())), Some(Undo::Edits(before)));
    }

    pub fn batch_preset(&mut self, preset: &Preset) {
        self.batch_edit(&format!("Put {} on", preset.name), |s| preset.apply_to(s));
    }

    pub fn batch_paste(&mut self) {
        let mut before = Vec::new();
        for k in self.targets() {
            let old = self.editor.edits_of(&k);
            if let Some(new) = self.editor.pasted(&old).filter(|n| *n != old) {
                self.editor.set_edits(&k, &new);
                before.push((k, old));
            }
        }
        self.did(format!("Pasted settings onto {}", noun(before.len())), Some(Undo::Edits(before)));
    }

    pub fn batch_reset(&mut self) {
        self.batch_edit("Reset the edits of", |_| Settings::default());
    }

    pub fn batch_export(&mut self, ctx: &egui::Context) {
        let shots: Vec<(String, PathBuf)> = self.shots_of(&self.targets()).iter().map(|s| (s.key(), s.primary().path.clone())).collect();
        self.editor.export_many(ctx, shots);
    }

    /// Copies the active shot's settings, from the library.
    pub fn copy_settings(&mut self, ctx: &egui::Context) {
        if let Some(k) = self.selected.clone() {
            let s = self.editor.edits_of(&k);
            ctx.copy_text(s.encode());
            self.editor.copy_from(s);
        }
    }

    /// Undoes the last batch.
    pub fn undo_batch(&mut self) {
        let Some(done) = self.done.take() else { return };
        let Some(undo) = done.undo else { return };
        match undo {
            Undo::Marks(m) => {
                self.library.restore_marks(&m);
                self.did(format!("Put back the marks of {}", noun(m.len())), None);
            }
            Undo::Edits(e) => {
                for (k, s) in &e {
                    self.editor.set_edits(k, s);
                }
                self.did(format!("Put back the edits of {}", noun(e.len())), None);
            }
            Undo::Album { id, keys, put_in } => {
                self.library.set_in_album(&id, &keys, !put_in);
                self.did(format!("Undid that for {}", noun(keys.len())), None);
            }
            Undo::Trash { photos, albums } => match restore(&photos) {
                Ok(()) => {
                    let _ = deleted::remove(&self.library.root, &photos);
                    for (id, keys) in &albums {
                        self.library.set_in_album(id, keys, true);
                    }
                    self.library.rescan();
                    let shots: HashSet<_> = photos.iter().map(|p| p.path.with_extension("")).collect();
                    self.did(format!("Put {} back", noun(shots.len())), None);
                }
                Err(e) => self.failed(format!("Couldn't take them back out of the trash: {e}")),
            },
        }
    }

    /// Asks before deleting the targets.
    pub fn ask_delete(&mut self, forever: bool) {
        let keys = self.targets();
        if !keys.is_empty() && self.deleting.as_ref().is_none_or(|d| d.running.is_none()) {
            self.deleting = Some(Deleting { keys, forever, error: None, running: None });
        }
    }

    /// Starts the delete the dialog asked about, off the UI thread (moving to a trash on another
    /// drive copies).
    fn start_delete(&mut self, ctx: &egui::Context, forever: bool) {
        let Some(keys) = self.deleting.as_ref().map(|d| d.keys.clone()) else { return };
        let shots = self.shots_of(&keys);
        // The shot after the last one deleted (or before, at the end) shows next.
        let visible: Vec<String> = self.visible().iter().map(|&i| self.library.shots[i].key()).collect();
        let gone: HashSet<&String> = keys.iter().collect();
        let last = visible.iter().rposition(|k| gone.contains(k)).unwrap_or(0);
        let next = visible[last..].iter().chain(visible[..last].iter().rev()).find(|k| !gone.contains(k)).cloned();
        let done = Arc::new(AtomicUsize::new(0));
        let (tx, rx) = channel();
        let (count, ctx) = (done.clone(), ctx.clone());
        std::thread::spawn(move || {
            let mut out = Outcome { gone: Vec::new(), error: None };
            for shot in shots {
                let paths: Vec<PathBuf> = files(&shot).map(|p| p.path.clone()).collect();
                let result = if forever {
                    paths.iter().try_for_each(|p| std::fs::remove_file(p).or_else(|e| if p.exists() { Err(e.to_string()) } else { Ok(()) }))
                } else {
                    trash::delete_all(&paths).map_err(|e| e.to_string())
                };
                if paths.iter().all(|p| !p.exists()) {
                    out.gone.push(shot);
                }
                count.fetch_add(1, Ordering::Relaxed);
                ctx.request_repaint();
                if let Err(e) = result {
                    out.error = Some(e);
                    break;
                }
            }
            let _ = tx.send(out);
            ctx.request_repaint();
        });
        let d = self.deleting.as_mut().unwrap();
        d.forever = forever;
        d.error = None;
        d.running = Some(Running { done, rx, next });
    }

    /// Takes in a finished delete.
    pub fn poll_delete(&mut self) {
        let Some(d) = &mut self.deleting else { return };
        let Some(r) = &d.running else { return };
        let Ok(out) = r.rx.try_recv() else { return };
        let (forever, next) = (d.forever, r.next.clone());
        d.running = None;
        let photos: Vec<Photo> = out.gone.iter().flat_map(|s| files(s).cloned()).collect();
        let keys: Vec<String> = out.gone.iter().map(Shot::key).collect();
        if !photos.is_empty() {
            let _ = deleted::add(&self.library.root, &photos);
            if self.editor.key.as_ref().is_some_and(|k| keys.contains(k)) {
                self.editor.close();
            }
            let albums = self.library.take_out_of_albums(&keys);
            self.library.drop_shots(&keys.iter().cloned().collect());
            self.library.rescan();
            self.select_one(next);
            if forever {
                self.did(format!("Deleted {} for good", noun(keys.len())), None);
            } else {
                self.did(format!("Moved {} to the trash", noun(keys.len())), Some(Undo::Trash { photos, albums }));
            }
        }
        match out.error {
            None => self.deleting = None,
            Some(e) => {
                let d = self.deleting.as_mut().unwrap();
                d.keys.retain(|k| !keys.contains(k));
                d.error = Some(if forever { format!("Couldn't delete them: {e}") } else { format!("Couldn't move them to the trash: {e}") });
            }
        }
    }
}

/// Takes `photos` back out of the system trash.
fn restore(photos: &[Photo]) -> Result<(), String> {
    let want: HashSet<PathBuf> = photos.iter().map(|p| p.path.canonicalize().unwrap_or_else(|_| p.path.clone())).collect();
    let mut items = trash::os_limited::list().map_err(|e| e.to_string())?;
    // The newest of each, should one have been deleted more than once.
    items.sort_by_key(|i| -i.time_deleted);
    let mut seen = HashSet::new();
    let items: Vec<_> = items.into_iter().filter(|i| want.contains(&i.original_path()) && seen.insert(i.original_path())).collect();
    if items.is_empty() {
        return Err("they aren't in the trash any more".into());
    }
    trash::os_limited::restore_all(items).map_err(|e| e.to_string())
}

/// The right panel while several shots are selected.
pub fn panel(app: &mut App, ui: &mut Ui) {
    let p = palette(ui.ctx());
    let keys = app.targets();
    let shots = app.shots_of(&keys);
    let n = keys.len();
    ui.horizontal(|ui| {
        widgets::subheading(ui, &format!("{} selected", noun(n)));
        widgets::right_to_left(ui, |ui| {
            if ui.add(egui::Label::new(RichText::new("Done  Esc").size(12.0).color(p.accent)).sense(egui::Sense::click())).clicked() {
                app.selection.clear();
            }
        });
    });
    let pairs = shots.iter().filter(|s| s.raw.is_some() && s.jpeg.is_some()).count();
    let picks = shots.iter().filter(|s| app.library.mark(s) == Some(Mark::Pick)).count();
    let rejects = shots.iter().filter(|s| app.library.mark(s) == Some(Mark::Reject)).count();
    let edited = shots.iter().filter(|s| app.editor.is_edited(s)).count();
    let mut facts = Vec::new();
    for (count, what) in [(pairs, "RAW + JPEG"), (picks, "picked"), (rejects, "rejected"), (edited, "edited")] {
        if count > 0 {
            facts.push(format!("{count} {what}"));
        }
    }
    if !facts.is_empty() {
        ui.label(RichText::new(facts.join(" · ")).color(p.text3));
    }
    if app.selection.ticking {
        ui.label(RichText::new("Tap photos to add or take them away.").size(12.0).color(p.text3));
    }
    ui.add_space(14.0);
    ui.horizontal_wrapped(|ui| {
        if outline_button(ui, "Pick  P").clicked() {
            app.batch_mark(Some(Mark::Pick));
        }
        if outline_button(ui, "Reject  X").clicked() {
            app.batch_mark(Some(Mark::Reject));
        }
        if outline_button(ui, "Clear  U").clicked() {
            app.batch_mark(None);
        }
    });

    ui.add_space(16.0);
    section_label(ui, "Albums");
    ui.add_space(6.0);
    ui.horizontal_wrapped(|ui| album_menus(app, ui, &keys));

    ui.add_space(16.0);
    section_label(ui, "Edits");
    ui.add_space(6.0);
    ui.horizontal_wrapped(|ui| {
        ui.menu_button(RichText::new("Apply preset").color(p.text2), |ui| preset_menu(app, ui));
        if ui.add_enabled(app.editor.can_paste(), egui::Button::new(RichText::new("Paste settings  Ctrl+V").color(p.text2))).clicked() {
            app.batch_paste();
        }
        if ui.add_enabled(edited > 0, egui::Button::new(RichText::new("Reset edits").color(p.text2))).clicked() {
            app.batch_reset();
        }
    });
    if !app.editor.can_paste() {
        ui.label(RichText::new("Copy a photo's settings with Ctrl+C to paste them here.").size(12.0).color(p.text3));
    }

    ui.add_space(16.0);
    section_label(ui, "Export");
    ui.add_space(6.0);
    let st = app.editor.export.lock().unwrap().clone();
    if st.running {
        ui.add(egui::ProgressBar::new(st.progress).desired_height(6.0).fill(p.seal));
        ui.horizontal(|ui| {
            ui.label(RichText::new(&st.message).size(12.0).color(p.text3));
            if ui.small_button("Stop").clicked() {
                app.editor.cancel_export();
            }
        });
    } else {
        if outline_button(ui, &format!("Export {} at full size", noun(n))).clicked() {
            app.batch_export(ui.ctx());
        }
        if !st.message.is_empty() {
            ui.horizontal_wrapped(|ui| {
                ui.label(RichText::new(&st.message).size(12.0).color(if st.failed { p.accent } else { p.text3 }));
                if let Some(dir) = &st.last {
                    if ui.small_button("Show").clicked() {
                        crate::state::reveal(dir);
                    }
                }
            });
        }
    }

    ui.add_space(24.0);
    if outline_button(ui, &format!("Delete {} …  Del", noun(n))).clicked() {
        app.ask_delete(ui.input(|i| i.modifiers.shift));
    }
}

/// Add to and take out of albums, for `keys`.
pub fn album_menus(app: &mut App, ui: &mut Ui, keys: &[String]) {
    let p = palette(ui.ctx());
    let albums: Vec<(String, String, usize)> =
        app.library.alive_albums().map(|a| (a.id.clone(), a.name.clone(), keys.iter().filter(|k| a.contains(k)).count())).collect();
    ui.menu_button(RichText::new("Add to album").color(p.text2), |ui| {
        for (id, name, inside) in &albums {
            if *inside < keys.len() && ui.button(name).clicked() {
                app.batch_album(id, true);
                ui.close_menu();
            }
        }
        if ui.button("New album…").clicked() {
            app.naming = Some((String::new(), String::new()));
            ui.close_menu();
        }
    });
    if albums.iter().any(|a| a.2 > 0) {
        ui.menu_button(RichText::new("Take out of album").color(p.text2), |ui| {
            for (id, name, inside) in &albums {
                if *inside > 0 && ui.button(format!("{name}  ({inside})")).clicked() {
                    app.batch_album(id, false);
                    ui.close_menu();
                }
            }
        });
    }
}

fn preset_menu(app: &mut App, ui: &mut Ui) {
    let built_in = presets::built_in();
    let user = presets::user(&app.library.root);
    let groups: [(&str, Vec<&Preset>); 3] =
        [("Looks", built_in.iter().filter(|p| !p.film).collect()), ("Film", built_in.iter().filter(|p| p.film).collect()), ("Yours", user.iter().collect())];
    for (label, list) in groups {
        if list.is_empty() {
            continue;
        }
        ui.menu_button(label, |ui| {
            egui::ScrollArea::vertical().max_height(360.0).show(ui, |ui| {
                for pr in list {
                    if ui.button(&pr.name).clicked() {
                        app.batch_preset(pr);
                        ui.close_menu();
                    }
                }
            });
        });
    }
}

/// The right-click menu on a tile that is part of a selection.
pub fn context_menu(app: &mut App, ui: &mut Ui) {
    let n = app.targets().len();
    if ui.button("Pick   P").clicked() {
        app.batch_mark(Some(Mark::Pick));
        ui.close_menu();
    }
    if ui.button("Reject   X").clicked() {
        app.batch_mark(Some(Mark::Reject));
        ui.close_menu();
    }
    if ui.button("Clear marks   U").clicked() {
        app.batch_mark(None);
        ui.close_menu();
    }
    ui.separator();
    let keys = app.targets();
    album_menus(app, ui, &keys);
    ui.menu_button("Apply preset", |ui| preset_menu(app, ui));
    if ui.add_enabled(app.editor.can_paste(), egui::Button::new("Paste settings   Ctrl+V")).clicked() {
        app.batch_paste();
        ui.close_menu();
    }
    ui.separator();
    if ui.button(format!("Delete {} …   Del", noun(n))).clicked() {
        app.ask_delete(false);
        ui.close_menu();
    }
}

/// The delete confirmation, and its progress once it runs.
pub fn delete_dialog(app: &mut App, ctx: &egui::Context) {
    app.poll_delete();
    let Some(d) = &app.deleting else { return };
    let p = palette(ctx);
    let shots = app.shots_of(&d.keys);
    let forever = d.forever;
    let error = d.error.clone();
    let running = d.running.as_ref().map(|r| r.done.load(Ordering::Relaxed));
    if shots.is_empty() && running.is_none() {
        app.deleting = None;
        return;
    }
    let n = shots.len();
    let file_count: usize = shots.iter().map(|s| files(s).count()).sum();
    let bytes: u64 = shots.iter().flat_map(files).map(|f| f.bytes).sum();
    let picks = shots.iter().filter(|s| app.library.mark(s) == Some(Mark::Pick)).count();
    let kept = shots.iter().filter(|s| app.library.mark(s) != Some(Mark::Reject)).count();
    let mut open = true;
    let (mut go, mut go_forever, mut cancel) = (false, false, false);
    egui::Window::new(if forever { format!("Delete {} for good?", noun(n)) } else { format!("Delete {}?", noun(n)) })
        .collapsible(false)
        .resizable(false)
        .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
        .open(&mut open)
        .show(ctx, |ui| {
            ui.set_width(340.0);
            if let Some(done) = running {
                ui.label(RichText::new(if forever { "Deleting ..." } else { "Moving to the trash ..." }).color(p.text2));
                ui.add_space(6.0);
                ui.add(egui::ProgressBar::new(done as f32 / n.max(1) as f32).desired_height(6.0).fill(p.seal));
                return;
            }
            let what = format!("{} files, {}", file_count, size(bytes));
            let text = if forever {
                format!("{what}, are deleted for good. This can't be undone.")
            } else {
                format!("{what}, go to the trash. You can take them back out from there.")
            };
            ui.label(RichText::new(text).color(p.text));
            ui.label(RichText::new("A RAW and its JPEG go together. Copies on your phone stay.").color(p.text3));
            if kept > 0 {
                ui.add_space(6.0);
                let warn = if picks > 0 && picks == kept {
                    format!("{} of them {} picked.", picks, if picks == 1 { "is" } else { "are" })
                } else if picks > 0 {
                    format!("{kept} of them aren't rejected ({picks} picked).")
                } else {
                    format!("{kept} of them {} rejected.", if kept == 1 { "isn't" } else { "aren't" })
                };
                ui.label(RichText::new(warn).color(p.accent));
            }
            if let Some(e) = &error {
                ui.add_space(6.0);
                ui.label(RichText::new(e).color(p.accent));
            }
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if forever {
                    go_forever = seal_button(ui, "Delete for good").clicked();
                } else if error.is_some() {
                    go_forever = seal_button(ui, "Delete for good instead").clicked();
                } else {
                    go = seal_button(ui, "Move to the trash").clicked();
                }
                cancel = outline_button(ui, "Cancel").clicked();
            });
            if !forever && error.is_none() {
                ui.input(|i| {
                    if i.key_pressed(egui::Key::Enter) {
                        go = true;
                    }
                });
            }
        });
    if running.is_some() {
        return;
    }
    if !open || cancel || ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
        app.deleting = None;
    } else if go {
        app.start_delete(ctx, false);
    } else if go_forever {
        app.start_delete(ctx, true);
    }
}

/// The line saying what the last batch did, with Undo, along the bottom of the grid.
pub fn done_line(app: &mut App, ui: &Ui) {
    let Some(done) = &app.done else { return };
    if done.at.elapsed() > SHOWN {
        return;
    }
    ui.ctx().request_repaint_after(SHOWN.saturating_sub(done.at.elapsed()));
    let p = palette(ui.ctx());
    let (text, can_undo, failed) = (done.text.clone(), done.undo.is_some(), done.failed);
    let rect = ui.max_rect();
    let mut undo = false;
    egui::Area::new(egui::Id::new("batch-done"))
        .order(egui::Order::Foreground)
        .fixed_pos(rect.center_bottom() + Vec2::new(0.0, -16.0))
        .pivot(Align2::CENTER_BOTTOM)
        .show(ui.ctx(), |ui| {
            egui::Frame::none()
                .fill(p.panel)
                .stroke(egui::Stroke::new(p.line, p.border))
                .rounding(p.radius)
                .shadow(p.hard_shadow())
                .inner_margin(egui::Margin::symmetric(14.0, 8.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(&text).color(if failed { p.accent } else { p.text }));
                        if can_undo {
                            ui.add_space(8.0);
                            undo = ui
                                .add(egui::Label::new(RichText::new("Undo  Ctrl+Z").color(p.accent)).sense(egui::Sense::click()))
                                .on_hover_cursor(egui::CursorIcon::PointingHand)
                                .clicked();
                        }
                    });
                });
        });
    if undo {
        app.undo_batch();
    }
}

/// Whether the library heading should offer to delete every rejected shot.
pub fn offers_delete_all(app: &App) -> bool {
    app.filter == Filter::Rejected
}
