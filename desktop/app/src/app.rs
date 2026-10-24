//! The window: top bar with the three modules, the current screen, and the status line.

use crate::editor::Editor;
use crate::images::Images;
use crate::state::{self, Filter, FtpInbox, Importer, Library, Settings, ThemeChoice};
use crate::theme::{self, palette};
use crate::widgets::{self, status_pill, tab};
use eframe::egui::{self, Frame, Key, Margin, RichText, Stroke, ThemePreference};
use std::sync::atomic::Ordering;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum View {
    Library,
    Edit,
    Import,
    Phone,
}

pub struct App {
    pub settings: Settings,
    pub library: Library,
    pub images: Images,
    pub importer: Importer,
    pub ftp: FtpInbox,
    pub view: View,
    pub filter: Filter,
    pub search: String,
    /// Key of the selected shot.
    pub selected: Option<String>,
    pub tile: f32,
    /// Album being named (new or renamed): its id, or "" for a new one, and the text so far.
    pub naming: Option<(String, String)>,
    pub show_log: bool,
    pub editor: Editor,
}

const SETTINGS_KEY: &str = "settings";

impl App {
    pub fn new(cc: &eframe::CreationContext) -> App {
        theme::install(&cc.egui_ctx);
        let settings: Settings = cc.storage.and_then(|s| eframe::get_value(s, SETTINGS_KEY)).unwrap_or_default();
        let root = settings.library.clone().unwrap_or_else(state::default_library);
        let _ = std::fs::create_dir_all(&root);
        let editor = Editor::new(&cc.egui_ctx, cc.wgpu_render_state.clone(), &root);
        let mut app = App {
            editor,
            library: Library::open(root),
            images: Images::new(&cc.egui_ctx),
            importer: Importer::new(),
            ftp: FtpInbox::new(),
            view: View::Library,
            filter: Filter::All,
            search: String::new(),
            selected: None,
            tile: 168.0,
            naming: None,
            show_log: false,
            settings,
        };
        app.apply_theme(&cc.egui_ctx);
        if app.settings.ftp_on {
            app.start_ftp(&cc.egui_ctx);
        }
        app
    }

    pub fn apply_theme(&self, ctx: &egui::Context) {
        ctx.set_theme(match self.settings.theme {
            ThemeChoice::System => ThemePreference::System,
            ThemeChoice::Washi => ThemePreference::Light,
            ThemeChoice::Sumi => ThemePreference::Dark,
        });
    }

    pub fn start_ftp(&mut self, ctx: &egui::Context) {
        let root = self.library.root.clone();
        self.ftp.start(ctx, self.settings.ftp_port, &root, self.importer.arrived.clone(), self.importer.log.clone());
        self.settings.ftp_on = self.ftp.server.is_some();
    }

    pub fn stop_ftp(&mut self) {
        self.ftp.stop();
        self.settings.ftp_on = false;
    }

    pub fn set_library(&mut self, ctx: &egui::Context, root: std::path::PathBuf) {
        self.library = Library::open(root.clone());
        self.editor.set_root(&root);
        self.settings.library = Some(root);
        self.selected = None;
        self.filter = Filter::All;
        if self.ftp.server.is_some() {
            self.start_ftp(ctx);
        }
    }

    /// The shots the library screen shows, in order.
    pub fn visible(&self) -> Vec<usize> {
        let q = self.search.trim().to_lowercase();
        self.library
            .shots
            .iter()
            .enumerate()
            .filter(|(_, s)| self.library.matches(s, &self.filter))
            .filter(|(_, s)| q.is_empty() || [s.raw.as_ref(), s.jpeg.as_ref()].into_iter().flatten().any(|p| p.name.to_lowercase().contains(&q)))
            .map(|(i, _)| i)
            .collect()
    }

    pub fn selected_index(&self, visible: &[usize]) -> Option<usize> {
        let key = self.selected.as_ref()?;
        visible.iter().position(|&i| &self.library.shots[i].key() == key)
    }

    /// Moves the selection by `by` within `visible`.
    pub fn step(&mut self, visible: &[usize], by: isize) {
        if visible.is_empty() {
            return;
        }
        let at = self.selected_index(visible).map_or(0, |i| (i as isize + by).clamp(0, visible.len() as isize - 1) as usize);
        self.selected = Some(self.library.shots[visible[at]].key());
    }

    fn top_bar(&mut self, ctx: &egui::Context) {
        let p = palette(ctx);
        egui::TopBottomPanel::top("top")
            .exact_height(52.0)
            .frame(Frame::none().fill(p.bg).inner_margin(Margin::symmetric(16.0, 0.0)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    let (rect, _) = ui.allocate_exact_size(egui::vec2(26.0, 26.0), egui::Sense::hover());
                    widgets::paint_logo(ui.painter(), rect, p.accent);
                    ui.add_space(4.0);
                    let mut job = egui::text::LayoutJob::default();
                    job.append(
                        "Shootback",
                        0.0,
                        egui::TextFormat {
                            font_id: egui::FontId::new(18.0, theme::mincho()),
                            color: p.strong,
                            extra_letter_spacing: 1.4,
                            ..Default::default()
                        },
                    );
                    ui.label(job);
                    ui.add_space(20.0);
                    for (v, name) in [(View::Library, "Library"), (View::Edit, "Edit"), (View::Import, "Import")] {
                        if tab(ui, self.view == v, name).clicked() {
                            self.view = v;
                        }
                    }
                    widgets::right_to_left(ui, |ui| {
                        let theme_label = match self.settings.theme {
                            ThemeChoice::System => "Theme: system",
                            ThemeChoice::Washi => "Theme: washi",
                            ThemeChoice::Sumi => "Theme: sumi",
                        };
                        ui.menu_button(RichText::new(theme_label).color(p.text2), |ui| {
                            for (c, label) in
                                [(ThemeChoice::System, "Follow the system"), (ThemeChoice::Washi, "Washi (light)"), (ThemeChoice::Sumi, "Sumi (dark)")]
                            {
                                if ui.radio(self.settings.theme == c, label).clicked() {
                                    self.settings.theme = c;
                                    self.apply_theme(ui.ctx());
                                    ui.close_menu();
                                }
                            }
                        });
                        ui.add_space(8.0);
                        let status = self.importer.status.lock().unwrap().clone();
                        let (dot, text, strong) = if status.running == Some(state::Source::Camera) {
                            (p.accent, format!("{} · copying", status.camera_name.as_deref().unwrap_or("Camera")), true)
                        } else if self.ftp.server.is_some() {
                            (p.good, "FTP inbox on".to_string(), false)
                        } else {
                            (p.control, "Camera not connected".to_string(), false)
                        };
                        if status_pill(ui, dot, &text, strong).clicked() {
                            self.view = View::Import;
                        }
                        if status_pill(ui, p.control, "Phone: not paired", self.view == View::Phone).clicked() {
                            self.view = View::Phone;
                        }
                    });
                });
            });
    }

    fn footer(&mut self, ctx: &egui::Context) {
        let p = palette(ctx);
        egui::TopBottomPanel::bottom("footer")
            .exact_height(28.0)
            .frame(Frame::none().fill(p.bg).inner_margin(Margin::symmetric(16.0, 0.0)).stroke(Stroke::new(1.0_f32, p.border)))
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    let small = |t: String| RichText::new(t).size(11.5).color(p.text3);
                    if self.library.scanning() {
                        ui.label(small("Reading the library ...".into()));
                    } else {
                        ui.label(small(format!("{} shots in {}", widgets::thousands(self.library.shots.len()), self.library.root.display())));
                    }
                    if let Some(s) = &self.ftp.server {
                        let host = state::local_ip().unwrap_or_else(|| "this computer".into());
                        ui.label(small(format!("FTP inbox on {host}:{}", s.port)));
                    }
                    let status = self.importer.status.lock().unwrap().clone();
                    if !status.message.is_empty() {
                        ui.label(RichText::new(status.message).size(11.5).color(if status.failed { p.accent } else { p.text3 }));
                    }
                    widgets::right_to_left(ui, |ui| {
                        if ui.add(egui::Label::new(small(if self.show_log { "Hide log".into() } else { "Log".into() })).sense(egui::Sense::click())).clicked() {
                            self.show_log = !self.show_log;
                        }
                    });
                });
            });
        if self.show_log {
            egui::TopBottomPanel::bottom("log")
                .resizable(true)
                .default_height(180.0)
                .frame(Frame::none().fill(p.panel).inner_margin(Margin::same(12.0)).stroke(Stroke::new(1.0_f32, p.border)))
                .show(ctx, |ui| {
                    egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink(false).show(ui, |ui| {
                        for line in self.importer.log.lock().unwrap().iter() {
                            ui.label(RichText::new(line).monospace().size(11.5).color(p.text2));
                        }
                    });
                });
        }
    }

    fn keys(&mut self, ctx: &egui::Context) {
        if ctx.wants_keyboard_input() || self.naming.is_some() {
            return;
        }
        let visible = self.visible();
        let cols = if self.view == View::Library { library_columns(ctx, self.tile) } else { 1 };
        ctx.input(|i| {
            use shootback_core::marks::Mark;
            let key = self.selected.clone();
            if let Some(key) = &key {
                if i.key_pressed(Key::P) {
                    self.library.set_mark(key, Some(Mark::Pick));
                }
                if i.key_pressed(Key::X) {
                    self.library.set_mark(key, Some(Mark::Reject));
                }
                if i.key_pressed(Key::U) {
                    self.library.set_mark(key, None);
                }
            }
            if i.key_pressed(Key::ArrowRight) {
                self.step(&visible, 1);
            }
            if i.key_pressed(Key::ArrowLeft) {
                self.step(&visible, -1);
            }
            if self.view == View::Library {
                if i.key_pressed(Key::ArrowDown) {
                    self.step(&visible, cols as isize);
                }
                if i.key_pressed(Key::ArrowUp) {
                    self.step(&visible, -(cols as isize));
                }
                if (i.key_pressed(Key::Enter) || i.key_pressed(Key::E)) && self.selected.is_some() {
                    self.view = View::Edit;
                }
            } else if self.view == View::Edit && !self.editor.cropping && (i.key_pressed(Key::Escape) || i.key_pressed(Key::G)) {
                self.view = View::Library;
            }
        });
    }
}

/// How many tiles fit across the library grid (the window less both side panels).
pub fn library_columns(ctx: &egui::Context, tile: f32) -> usize {
    let w = ctx.screen_rect().width() - 232.0 - 300.0 - 40.0;
    ((w + 8.0) / (tile + 8.0)).floor().max(1.0) as usize
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.images.poll(ctx);
        if self.library.poll() && self.selected.is_none() {
            self.selected = self.library.shots.first().map(|s| s.key());
        }
        if self.importer.arrived.swap(false, Ordering::Relaxed) && !self.library.scanning() {
            self.library.rescan();
        }
        if self.library.scanning() || self.importer.running().is_some() {
            ctx.request_repaint_after(std::time::Duration::from_millis(250));
        }
        self.keys(ctx);
        crate::viewer::keys(self, ctx);
        self.top_bar(ctx);
        self.footer(ctx);
        match self.view {
            View::Library => crate::library_view::show(self, ctx),
            View::Edit => crate::viewer::show(self, ctx),
            View::Import => crate::import_view::show(self, ctx),
            View::Phone => crate::phone_view::show(self, ctx),
        }
    }

    fn save(&mut self, storage: &mut dyn eframe::Storage) {
        eframe::set_value(storage, SETTINGS_KEY, &self.settings);
    }

    fn on_exit(&mut self) {
        self.editor.close();
        self.importer.cancel();
        self.ftp.stop();
    }
}

/// The window icon: the seal in vermilion on cream, like the phone's launcher icon.
pub fn icon() -> egui::IconData {
    let png = include_bytes!("../assets/icon.png");
    let img = image::load_from_memory_with_format(png, image::ImageFormat::Png).map(|i| i.to_rgba8());
    match img {
        Ok(i) => egui::IconData { width: i.width(), height: i.height(), rgba: i.into_raw() },
        Err(_) => egui::IconData { width: 1, height: 1, rgba: vec![0xF3, 0xEE, 0xE3, 0xFF] },
    }
}
