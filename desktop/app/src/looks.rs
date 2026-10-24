//! The Presets and LUTs panels at the top of Edit: the built-in looks and film stocks, the
//! library's own presets (saved here or on the phone), and the LUT browser with its tiles of the
//! open photo, importing and the G'MIC film pack. Hovering any of them shows it on the photo.

use crate::editor::Editor;
use crate::theme::palette;
use crate::widgets::{chip, hint, outline_button};
use eframe::egui::{self, Color32, ColorImage, RichText, Sense, Stroke, TextureHandle, TextureOptions, Ui, Vec2};
use shootback_core::develop::lut::{self, Lut};
use shootback_core::develop::luts::{self, Entry};
use shootback_core::develop::params::{p, Group, Settings};
use shootback_core::develop::presets::{self, Preset, GROUPS};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Looks,
    Film,
    Yours,
}

/// The open photo, small, without its LUT: what the LUT tiles are drawn from.
struct Photo {
    w: usize,
    h: usize,
    rgb: Vec<u8>,
}

struct TileJob {
    root: PathBuf,
    id: String,
    photo: Arc<Photo>,
    generation: u64,
}

/// An import or the pack download, run off the UI thread.
#[derive(Default)]
struct Task {
    running: AtomicBool,
    done: AtomicUsize,
    total: AtomicUsize,
    cancel: AtomicBool,
    label: Mutex<String>,
    result: Mutex<Option<(String, bool)>>,
}

pub struct Looks {
    root: PathBuf,
    built_in: Vec<Preset>,
    user: Vec<Preset>,
    luts: Vec<Entry>,
    listed: Option<Instant>,
    tab: Tab,
    /// The LUT folder shown, or "" for all.
    folder: String,
    /// A preset being saved: its name so far and the panels it takes.
    saving: Option<(String, Vec<Group>)>,
    message: Option<(String, bool)>,
    /// The photo the tiles show, which shot and settings it was taken from, and when.
    photo: Option<Arc<Photo>>,
    photo_of: Option<(String, Settings)>,
    photo_at: Option<Instant>,
    generation: u64,
    tiles: HashMap<String, TextureHandle>,
    asked: HashSet<String>,
    jobs: Sender<TileJob>,
    done: Receiver<(String, u64, ColorImage)>,
    task: Arc<Task>,
}

const TILE_EDGE: usize = 160;

impl Looks {
    pub fn new(ctx: &egui::Context, root: &Path) -> Looks {
        let (jobs, rx) = channel::<TileJob>();
        let (tx, done) = channel();
        let c = ctx.clone();
        std::thread::spawn(move || tile_worker(rx, tx, c));
        Looks {
            root: root.to_path_buf(),
            built_in: presets::built_in(),
            user: Vec::new(),
            luts: Vec::new(),
            listed: None,
            tab: Tab::Looks,
            folder: String::new(),
            saving: None,
            message: None,
            photo: None,
            photo_of: None,
            photo_at: None,
            generation: 0,
            tiles: HashMap::new(),
            asked: HashSet::new(),
            jobs,
            done,
            task: Default::default(),
        }
    }

    /// Reads the presets and LUTs again now and then: sync and imports add to them.
    fn refresh(&mut self, root: &Path, ed: &mut Editor) {
        if root != self.root {
            self.root = root.to_path_buf();
            self.listed = None;
            self.clear_tiles();
        }
        if self.listed.is_some_and(|t| t.elapsed() < Duration::from_secs(2)) {
            return;
        }
        self.listed = Some(Instant::now());
        self.user = presets::user(&self.root);
        let luts = luts::list(&self.root);
        if luts != self.luts {
            self.luts = luts;
            ed.forget_lut();
        }
    }

    fn clear_tiles(&mut self) {
        self.generation += 1;
        self.tiles.clear();
        self.asked.clear();
    }

    /// Keeps the tiles' photo in step with the open shot and its edit (once the edit rests).
    fn follow_photo(&mut self, ctx: &egui::Context, ed: &mut Editor) {
        let Some(key) = ed.key.clone() else { return };
        let plain = Settings { lut: None, ..ed.s.clone() };
        let same = self.photo_of.as_ref().is_some_and(|(k, s)| *k == key && *s == plain);
        let new_shot = self.photo_of.as_ref().is_none_or(|(k, _)| *k != key);
        let resting = !ctx.input(|i| i.pointer.any_down()) && self.photo_at.is_none_or(|t| t.elapsed() > Duration::from_millis(600));
        if same || !(new_shot || resting) || ed.catching_up() {
            return;
        }
        if let Some((w, h, rgb)) = ed.snapshot(TILE_EDGE) {
            self.photo = Some(Arc::new(Photo { w, h, rgb }));
            self.photo_of = Some((key, plain));
            self.photo_at = Some(Instant::now());
            // Old tiles stay up until their new ones arrive, so nothing blinks.
            self.generation += 1;
            self.asked.clear();
        }
    }

    fn poll(&mut self, ctx: &egui::Context) {
        while let Ok((id, generation, img)) = self.done.try_recv() {
            if generation == self.generation {
                self.tiles.insert(id.clone(), ctx.load_texture(format!("lut-tile-{id}"), img, TextureOptions::LINEAR));
            }
        }
        if let Some(r) = self.task.result.lock().unwrap().take() {
            self.message = Some(r);
            self.listed = None;
        }
    }
}

/// Draws LUT tiles: the photo through each table.
fn tile_worker(jobs: Receiver<TileJob>, done: Sender<(String, u64, ColorImage)>, ctx: egui::Context) {
    let mut cache: HashMap<(PathBuf, String), Option<Arc<Lut>>> = HashMap::new();
    while let Ok(job) = jobs.recv() {
        if cache.len() > 96 {
            cache.clear();
        }
        let table = cache.entry((job.root.clone(), job.id.clone())).or_insert_with(|| lut::find(&job.root, &job.id).map(Arc::new)).clone();
        let ph = &job.photo;
        let pixels: Vec<Color32> = match &table {
            Some(t) => ph
                .rgb
                .as_chunks::<3>()
                .0
                .iter()
                .map(|c| {
                    let v = t.at(c[0] as f32 / 255.0, c[1] as f32 / 255.0, c[2] as f32 / 255.0);
                    let b = |x: f32| (x.clamp(0.0, 1.0) * 255.0).round() as u8;
                    Color32::from_rgb(b(v[0]), b(v[1]), b(v[2]))
                })
                .collect(),
            None => ph.rgb.as_chunks::<3>().0.iter().map(|c| Color32::from_gray(((c[0] as u32 + c[1] as u32 + c[2] as u32) / 3) as u8)).collect(),
        };
        let img = ColorImage { size: [ph.w, ph.h], pixels };
        if done.send((job.id, job.generation, img)).is_err() {
            return;
        }
        ctx.request_repaint();
    }
}

fn group_label(g: Group) -> &'static str {
    match g {
        Group::Light => "Light",
        Group::Curve => "Curve",
        Group::Color => "Color",
        Group::Mixer => "Color mixer",
        Group::Grading => "Color grading",
        Group::Effects => "Effects",
        Group::Detail => "Detail",
        Group::Optics => "Optics",
        Group::Geometry => "Geometry",
        Group::Crop => "Crop",
    }
}

/// The Presets and LUTs panels, editing `s`. Hovering an entry previews it through `ed.peek`.
pub fn panels(ui: &mut Ui, looks: &mut Looks, ed: &mut Editor, root: &Path, s: &mut Settings) {
    looks.refresh(root, ed);
    looks.poll(ui.ctx());
    let mut peek = None;
    crate::develop_view::section(ui, "Presets", s, None, true, |ui, s| presets_panel(ui, looks, s, &mut peek));
    let lut_open =
        egui::collapsing_header::CollapsingState::load_with_default_open(ui.ctx(), ui.make_persistent_id(("develop-section", "LUTs")), false).is_open();
    if lut_open {
        looks.follow_photo(ui.ctx(), ed);
    }
    crate::develop_view::section(ui, "LUTs", s, None, false, |ui, s| lut_panel(ui, looks, s, &mut peek));
    if peek != ed.peek {
        ed.peek = peek;
        ui.ctx().request_repaint();
    }
}

fn presets_panel(ui: &mut Ui, looks: &mut Looks, s: &mut Settings, peek: &mut Option<Settings>) {
    let pal = palette(ui.ctx());
    ui.horizontal(|ui| {
        for (t, label) in [(Tab::Looks, "Looks"), (Tab::Film, "Film"), (Tab::Yours, "Yours")] {
            if crate::widgets::tab(ui, looks.tab == t, label).clicked() {
                looks.tab = t;
            }
        }
    });
    ui.add_space(6.0);
    let list: Vec<Preset> = match looks.tab {
        Tab::Looks => looks.built_in.iter().filter(|p| !p.film).cloned().collect(),
        Tab::Film => looks.built_in.iter().filter(|p| p.film).cloned().collect(),
        Tab::Yours => looks.user.clone(),
    };
    if looks.tab == Tab::Yours && list.is_empty() && looks.saving.is_none() {
        hint(ui, "Save the panels of an edit as a preset to use it on other photos. Presets sync with the phone.");
    }
    let mut delete = None;
    ui.horizontal_wrapped(|ui| {
        ui.spacing_mut().item_spacing = egui::vec2(6.0, 6.0);
        for pr in &list {
            let r = chip(ui, pr.is_applied(s), &pr.name);
            if r.hovered() {
                *peek = Some(pr.apply_to(s));
            }
            if r.clicked() {
                *s = pr.apply_to(s);
            }
            if pr.groups.is_some() {
                let names: Vec<&str> = pr.groups.iter().flatten().map(|g| group_label(*g)).collect();
                let r = r.on_hover_text(names.join(", "));
                r.context_menu(|ui| {
                    if ui.button("Delete preset").clicked() {
                        delete = Some(pr.name.clone());
                        ui.close_menu();
                    }
                });
            }
        }
    });
    if let Some(name) = delete {
        looks.message = presets::delete(&looks.root, &name).err().map(|e| (format!("Couldn't delete {name}: {e}"), true));
        looks.listed = None;
    }
    ui.add_space(6.0);
    match &mut looks.saving {
        None => {
            if outline_button(ui, "Save preset ...").clicked() {
                let groups = GROUPS.into_iter().filter(|g| ![Group::Crop, Group::Geometry, Group::Optics].contains(g)).collect();
                looks.saving = Some((String::new(), groups));
                looks.tab = Tab::Yours;
            }
        }
        Some((name, groups)) => {
            let mut done = false;
            let edit = ui.add(egui::TextEdit::singleline(name).hint_text("Name").desired_width(f32::INFINITY));
            if edit.gained_focus() || name.is_empty() {
                edit.request_focus();
            }
            ui.add_space(4.0);
            ui.label(RichText::new("Include").size(11.5).color(pal.text3));
            egui::Grid::new("preset-groups").num_columns(2).spacing([12.0, 2.0]).show(ui, |ui| {
                for (i, g) in GROUPS.into_iter().enumerate() {
                    let mut on = groups.contains(&g);
                    if ui.checkbox(&mut on, group_label(g)).changed() {
                        if on {
                            groups.push(g);
                        } else {
                            groups.retain(|x| *x != g);
                        }
                    }
                    if i % 2 == 1 {
                        ui.end_row();
                    }
                }
            });
            ui.add_space(4.0);
            let enter = edit.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
            ui.horizontal(|ui| {
                let ok = !name.trim().is_empty() && !groups.is_empty();
                if ui.add_enabled(ok, egui::Button::new("Save")).clicked() || (ok && enter) {
                    let order: Vec<Group> = GROUPS.into_iter().filter(|g| groups.contains(g)).collect();
                    looks.message = match presets::save(&looks.root, name, s, &order) {
                        Ok(saved) => Some((format!("Saved \u{201c}{saved}\u{201d}."), false)),
                        Err(e) => Some((format!("Couldn't save the preset: {e}"), true)),
                    };
                    looks.listed = None;
                    done = true;
                }
                if ui.button("Cancel").clicked() || ui.input(|i| i.key_pressed(egui::Key::Escape)) {
                    done = true;
                }
            });
            if done {
                looks.saving = None;
            }
        }
    }
    if let Some((m, failed)) = &looks.message {
        ui.label(RichText::new(m).size(12.0).color(if *failed { pal.accent } else { pal.text3 }));
    }
}

fn lut_panel(ui: &mut Ui, looks: &mut Looks, s: &mut Settings, peek: &mut Option<Settings>) {
    let pal = palette(ui.ctx());
    let mut folders: Vec<String> = Vec::new();
    for e in &looks.luts {
        if !folders.contains(&e.folder) {
            folders.push(e.folder.clone());
        }
    }
    if !looks.folder.is_empty() && !folders.contains(&looks.folder) {
        looks.folder.clear();
    }
    if looks.luts.is_empty() {
        hint(ui, "Import .cube files, Hald PNGs or a .zip of them, or get the G'MIC film pack (about 300 film stocks). LUTs sync with the phone.");
    } else {
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing = egui::vec2(6.0, 6.0);
            if chip(ui, looks.folder.is_empty(), "All").clicked() {
                looks.folder.clear();
            }
            for f in &folders {
                if chip(ui, looks.folder == *f, f).clicked() {
                    looks.folder = f.clone();
                }
            }
        });
        ui.add_space(6.0);
        let shown: Vec<Entry> = looks.luts.iter().filter(|e| looks.folder.is_empty() || e.folder == looks.folder).cloned().collect();
        let gap = 6.0;
        let cols = ((ui.available_width() + gap) / (78.0 + gap)).floor().max(2.0) as usize;
        let w = (ui.available_width() - gap * (cols - 1) as f32) / cols as f32;
        let aspect = looks.photo.as_ref().map_or(0.75, |ph| (ph.h as f32 / ph.w.max(1) as f32).clamp(0.5, 1.5));
        let img_h = (w * aspect).round();
        let mut delete: Option<Vec<String>> = None;
        for row in shown.chunks(cols) {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = gap;
                for e in row {
                    let (rect, r) = ui.allocate_exact_size(Vec2::new(w, img_h + 20.0), Sense::click());
                    if !ui.is_rect_visible(rect) {
                        continue;
                    }
                    let img = egui::Rect::from_min_size(rect.min, Vec2::new(w, img_h));
                    let painter = ui.painter();
                    match looks.tiles.get(&e.id) {
                        Some(t) => {
                            painter.image(t.id(), img, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
                        }
                        None => {
                            painter.rect_filled(img, pal.tile_radius, pal.control);
                        }
                    }
                    if let Some(ph) = &looks.photo {
                        if !looks.asked.contains(&e.id) {
                            looks.asked.insert(e.id.clone());
                            let _ = looks.jobs.send(TileJob { root: looks.root.clone(), id: e.id.clone(), photo: ph.clone(), generation: looks.generation });
                        }
                    }
                    let on = s.lut.as_deref() == Some(e.id.as_str());
                    let stroke = if on {
                        Stroke::new(2.0_f32, pal.accent)
                    } else if r.hovered() {
                        Stroke::new(1.0_f32, pal.text2)
                    } else {
                        Stroke::new(1.0_f32, pal.border)
                    };
                    painter.rect_stroke(img, pal.tile_radius, stroke);
                    let name = egui::Rect::from_min_max(egui::pos2(rect.left(), img.bottom() + 3.0), rect.max);
                    let galley = painter.layout(e.name.clone(), egui::FontId::proportional(11.0), if on { pal.strong } else { pal.text2 }, f32::INFINITY);
                    let clip = painter.with_clip_rect(name);
                    clip.galley(name.left_top(), galley, pal.text2);
                    if r.hovered() {
                        *peek = Some(with_lut(s, Some(&e.id)));
                    }
                    if r.clicked() {
                        *s = with_lut(s, if on { None } else { Some(&e.id) });
                    }
                    let r = r.on_hover_text(format!("{}\n{}", e.name, e.folder));
                    r.context_menu(|ui| {
                        if ui.button("Delete LUT").clicked() {
                            delete = Some(vec![e.id.clone()]);
                            ui.close_menu();
                        }
                        if ui.button(format!("Delete all of \u{201c}{}\u{201d}", e.folder)).clicked() {
                            delete = Some(looks.luts.iter().filter(|x| x.folder == e.folder).map(|x| x.id.clone()).collect());
                            ui.close_menu();
                        }
                    });
                }
            });
            ui.add_space(gap);
        }
        if let Some(ids) = delete {
            looks.message = luts::delete(&looks.root, &ids).err().map(|e| (format!("Couldn't delete: {e}"), true));
            looks.listed = None;
        }
    }
    ui.add_space(4.0);
    let task = looks.task.clone();
    if task.running.load(Ordering::Relaxed) {
        let (done, total) = (task.done.load(Ordering::Relaxed), task.total.load(Ordering::Relaxed));
        let label = task.label.lock().unwrap().clone();
        if total > 0 {
            ui.add(egui::ProgressBar::new(done as f32 / total as f32).desired_height(6.0).fill(pal.seal));
        }
        ui.horizontal(|ui| {
            let count = if total > 0 { format!("  {done} of {total}") } else { String::new() };
            ui.label(RichText::new(format!("{label}{count}")).size(12.0).color(pal.text2));
            if ui
                .add(egui::Label::new(RichText::new("Cancel").size(12.0).color(pal.text3)).selectable(false).sense(Sense::click()))
                .on_hover_cursor(egui::CursorIcon::PointingHand)
                .clicked()
            {
                task.cancel.store(true, Ordering::Relaxed);
            }
        });
        ui.ctx().request_repaint_after(Duration::from_millis(250));
        looks.listed = looks.listed.filter(|t| t.elapsed() < Duration::from_secs(1));
    } else {
        ui.horizontal_wrapped(|ui| {
            if outline_button(ui, "Import ...").on_hover_text(".cube files, Hald CLUT .png images, or a .zip of them").clicked() {
                import(ui.ctx(), &looks.root, &task);
            }
            let has_pack = looks.luts.iter().any(|e| e.id.starts_with("gmic-"));
            let label = if has_pack { "Update film pack" } else { "Get G'MIC film pack" };
            if outline_button(ui, label).on_hover_text("About 300 film stocks from github.com/YahiaAngelo/Film-Luts").clicked() {
                download(ui.ctx(), &looks.root, &task);
            }
        });
    }
}

fn with_lut(s: &Settings, id: Option<&str>) -> Settings {
    let mut n = Settings { lut: id.map(str::to_string), ..s.clone() };
    if id.is_some() && s.lut.is_none() && n.get(&p::LUT_AMOUNT) <= 0.0 {
        n.set(&p::LUT_AMOUNT, p::LUT_AMOUNT.default);
    }
    n
}

fn start(task: &Arc<Task>, label: &str) -> bool {
    if task.running.swap(true, Ordering::Relaxed) {
        return false;
    }
    task.done.store(0, Ordering::Relaxed);
    task.total.store(0, Ordering::Relaxed);
    task.cancel.store(false, Ordering::Relaxed);
    *task.label.lock().unwrap() = label.to_string();
    true
}

fn summary(added: usize, skipped: usize) -> String {
    let n = |k: usize| if k == 1 { "1 LUT".to_string() } else { format!("{k} LUTs") };
    match (added, skipped) {
        (0, 0) => "Nothing new.".into(),
        (a, 0) => format!("Added {}.", n(a)),
        (a, s) => format!("Added {}; {} couldn't be read.", n(a), s),
    }
}

fn import(ctx: &egui::Context, root: &Path, task: &Arc<Task>) {
    if !start(task, "Importing") {
        return;
    }
    let (root, task, ctx) = (root.to_path_buf(), task.clone(), ctx.clone());
    std::thread::spawn(move || {
        let files = rfd::FileDialog::new().add_filter("LUTs", &["cube", "CUBE", "png", "PNG", "zip", "ZIP"]).pick_files().unwrap_or_default();
        task.total.store(files.len(), Ordering::Relaxed);
        let (mut added, mut skipped) = (0, 0);
        for f in &files {
            if task.cancel.load(Ordering::Relaxed) {
                break;
            }
            let name = f.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            match std::fs::read(f) {
                Ok(bytes) => {
                    let r = luts::import(&root, &name, &bytes);
                    added += r.added.len();
                    skipped += r.skipped;
                }
                Err(_) => skipped += 1,
            }
            task.done.fetch_add(1, Ordering::Relaxed);
            ctx.request_repaint();
        }
        if !files.is_empty() {
            *task.result.lock().unwrap() = Some((summary(added, skipped), added == 0 && skipped > 0));
        }
        task.running.store(false, Ordering::Relaxed);
        ctx.request_repaint();
    });
}

fn download(ctx: &egui::Context, root: &Path, task: &Arc<Task>) {
    if !start(task, "Downloading the film pack") {
        return;
    }
    let (root, task, ctx) = (root.to_path_buf(), task.clone(), ctx.clone());
    std::thread::spawn(move || {
        let r = luts::gmic::download(&root, &task.done, &task.total, &task.cancel);
        *task.result.lock().unwrap() = Some(match r {
            Ok(a) => (summary(a.added.len(), a.skipped), false),
            Err(e) => (format!("Couldn't get the pack: {e}"), true),
        });
        task.running.store(false, Ordering::Relaxed);
        ctx.request_repaint();
    });
}
