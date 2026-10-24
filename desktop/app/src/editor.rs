//! The open edit: the photo decoded for editing, its settings with undo, the live GPU preview and
//! exports. Slow work (decoding, sampling through the geometry, noise reduction, guide maps) runs
//! on worker threads; the sliders only re-run the shader, so they stay live.

use crate::gpu::{self, Engine, Inputs, Placement, Tex};
use crate::render;
use eframe::egui;
use eframe::egui_wgpu::{self, wgpu};
use shootback_core::develop::image::LinearImage;
use shootback_core::develop::look::{natural_scale, sample, Guide, Look};
use shootback_core::develop::lut::{self, Lut};
use shootback_core::develop::params::{p, Group};
use shootback_core::develop::{self, EditStore, Opened, Settings, PREVIEW_EDGE};
use shootback_core::library::Shot;
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};

/// A request for the preview's slow inputs.
struct Job {
    opened: Arc<Opened>,
    s: Settings,
    whole_frame: bool,
    key: String,
}

/// The preview's slow inputs for some settings.
struct Prepared {
    key: String,
    scene: LinearImage,
    scale: f64,
    guide: Option<Guide>,
    coords: Option<(usize, usize, Vec<f32>)>,
}

/// The same on the GPU.
struct Scene {
    key: String,
    tex: Tex,
    scale: f64,
    guide: Option<(Tex, [f32; 3])>,
    coords: Option<Tex>,
}

#[derive(Default, Clone)]
pub struct ExportStatus {
    pub running: bool,
    pub progress: f32,
    pub message: String,
    pub failed: bool,
    pub last: Option<PathBuf>,
}

pub struct Editor {
    engine: Option<Arc<Engine>>,
    render_state: Option<egui_wgpu::RenderState>,
    root: PathBuf,
    store: EditStore,
    /// The shot being edited (by key) and its primary file.
    pub key: Option<String>,
    pub file: Option<PathBuf>,
    opening: Option<Receiver<Result<Arc<Opened>, String>>>,
    pub opened: Option<Arc<Opened>>,
    pub error: Option<String>,
    pub s: Settings,
    saved: Settings,
    undo: Vec<Settings>,
    redo: Vec<Settings>,
    /// A change is in progress (a slider held): one undo step for the whole drag.
    gesture: bool,
    clipboard: Option<Settings>,
    jobs: Sender<Job>,
    done: Receiver<Prepared>,
    requested: String,
    scene: Option<Scene>,
    lut: Option<(String, Option<Lut>, Option<Tex>)>,
    target: Option<(Tex, egui::TextureId)>,
    /// The settings the preview shows, or None when it needs drawing.
    drawn: Option<Settings>,
    /// Crop mode: the preview shows the whole frame, with the crop box over it.
    pub cropping: bool,
    /// File names (see [`develop::file_name`]) of the shots with edits.
    pub edited: HashSet<String>,
    pub export: Arc<Mutex<ExportStatus>>,
    cancel: Arc<AtomicBool>,
}

impl Editor {
    pub fn new(ctx: &egui::Context, render_state: Option<egui_wgpu::RenderState>, root: &Path) -> Editor {
        let engine = render_state.as_ref().map(|r| Arc::new(Engine::new(r.device.clone(), r.queue.clone())));
        let (jobs, rx) = channel::<Job>();
        let (tx, done) = channel();
        let c = ctx.clone();
        std::thread::spawn(move || worker(rx, tx, c));
        let store = EditStore::new(root);
        Editor {
            engine,
            render_state,
            root: root.to_path_buf(),
            edited: store.edited(),
            store,
            key: None,
            file: None,
            opening: None,
            opened: None,
            error: None,
            s: Settings::default(),
            saved: Settings::default(),
            undo: Vec::new(),
            redo: Vec::new(),
            gesture: false,
            clipboard: None,
            jobs,
            done,
            requested: String::new(),
            scene: None,
            lut: None,
            target: None,
            drawn: None,
            cropping: false,
            export: Default::default(),
            cancel: Default::default(),
        }
    }

    pub fn has_gpu(&self) -> bool {
        self.engine.is_some()
    }

    /// Follows the library to another folder.
    pub fn set_root(&mut self, root: &Path) {
        if root != self.root {
            self.close();
            self.root = root.to_path_buf();
            self.store = EditStore::new(root);
            self.edited = self.store.edited();
        }
    }

    pub fn is_edited(&self, shot: &Shot) -> bool {
        self.edited.contains(&develop::file_name(&shot.key()))
    }

    /// Saves and lets go of the open photo.
    pub fn close(&mut self) {
        self.save();
        self.key = None;
        self.file = None;
        self.opening = None;
        self.opened = None;
        self.scene = None;
        self.drawn = None;
        self.cropping = false;
        self.requested.clear();
        self.undo.clear();
        self.redo.clear();
    }

    /// Opens `shot` for editing, unless it already is.
    pub fn open(&mut self, ctx: &egui::Context, shot: &Shot) {
        let key = shot.key();
        if self.key.as_ref() == Some(&key) {
            return;
        }
        self.close();
        self.s = self.store.load(&key);
        self.saved = self.s.clone();
        self.key = Some(key);
        let path = shot.primary().path.clone();
        self.file = Some(path.clone());
        self.error = None;
        let (tx, rx) = channel();
        self.opening = Some(rx);
        let c = ctx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(develop::open(&path).map(Arc::new));
            c.request_repaint();
        });
    }

    pub fn loading(&self) -> bool {
        self.key.is_some() && self.opened.is_none() && self.error.is_none()
    }

    /// Applies a change from the panels or a shortcut, as one undo step (or part of the current
    /// gesture).
    pub fn change(&mut self, s: Settings) {
        if s == self.s {
            return;
        }
        if !self.gesture {
            self.undo.push(self.s.clone());
            self.redo.clear();
        }
        self.s = s;
    }

    /// Call once per frame after the panels: whether a pointer is still holding a control.
    pub fn end_frame(&mut self, ctx: &egui::Context, before: &Settings) {
        let down = ctx.input(|i| i.pointer.any_down());
        if self.s != *before {
            self.gesture = down;
        } else if !down {
            self.gesture = false;
        }
        if !self.gesture && self.s != self.saved {
            self.save();
        }
    }

    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    pub fn undo(&mut self) {
        if let Some(s) = self.undo.pop() {
            self.redo.push(std::mem::replace(&mut self.s, s));
        }
    }

    pub fn redo(&mut self) {
        if let Some(s) = self.redo.pop() {
            self.undo.push(std::mem::replace(&mut self.s, s));
        }
    }

    pub fn copy(&mut self) {
        self.clipboard = Some(self.s.clone());
    }

    /// Settings copied elsewhere (another window, or as text).
    pub fn copy_from(&mut self, s: Settings) {
        self.clipboard = Some(s);
    }

    pub fn can_paste(&self) -> bool {
        self.clipboard.is_some()
    }

    /// Pastes everything copied except the crop and the masks, which belong to one picture.
    pub fn paste(&mut self) {
        if let Some(from) = self.clipboard.clone() {
            let groups =
                [Group::Light, Group::Curve, Group::Color, Group::Mixer, Group::Grading, Group::Effects, Group::Detail, Group::Optics, Group::Geometry];
            self.change(self.s.paste_from(&from, &groups));
        }
    }

    pub fn auto(&mut self) {
        if let Some(o) = &self.opened {
            let s = develop::look::auto_tone(&o.source, &self.s);
            self.change(s);
        }
    }

    fn save(&mut self) {
        let Some(key) = &self.key else { return };
        if self.s == self.saved {
            return;
        }
        match self.store.save(key, &self.s) {
            Ok(()) => {
                self.saved = self.s.clone();
                let name = develop::file_name(key);
                if self.s.is_default() {
                    self.edited.remove(&name);
                } else {
                    self.edited.insert(name);
                }
            }
            Err(e) => self.error = Some(format!("Couldn't save the edit: {e}")),
        }
    }

    /// Loads the LUT the edit names, once.
    fn load_lut(&mut self) {
        let Some(id) = self.s.lut.clone() else { return };
        if self.lut.as_ref().map(|l| &l.0) != Some(&id) {
            let found = lut::find(&self.root, &id);
            let tex = match (&self.engine, &found) {
                (Some(e), Some(l)) => {
                    let (w, h, data) = gpu::lut_data(l);
                    Some(e.texture(w, h, &data))
                }
                _ => None,
            };
            self.lut = Some((id, found, tex));
        }
    }

    /// Whether the edit names a LUT this library doesn't have.
    pub fn lut_missing(&self) -> bool {
        self.s.lut.is_some() && self.lut.as_ref().is_some_and(|l| l.1.is_none())
    }

    /// Takes in finished work and redraws the preview when anything changed. Returns the
    /// preview's texture and size.
    pub fn poll(&mut self) -> Option<(egui::TextureId, egui::Vec2)> {
        if let Some(rx) = &self.opening {
            if let Ok(r) = rx.try_recv() {
                self.opening = None;
                match r {
                    Ok(o) => self.opened = Some(o),
                    Err(e) => self.error = Some(format!("This photo can't be opened: {e}")),
                }
            }
        }
        let opened = self.opened.clone()?;
        let engine = self.engine.clone()?;
        let key = slow_key(&opened, &self.s, self.cropping);
        if key != self.requested {
            self.requested = key.clone();
            let _ = self.jobs.send(Job { opened: opened.clone(), s: self.s.clone(), whole_frame: self.cropping, key });
        }
        let mut fresh = None;
        while let Ok(p) = self.done.try_recv() {
            fresh = Some(p);
        }
        if let Some(p) = fresh {
            let (w, h) = (p.scene.width, p.scene.height);
            self.scene = Some(Scene {
                key: p.key,
                tex: engine.texture(w, h, &gpu::scene_data(&p.scene, 0, w)),
                scale: p.scale,
                guide: p.guide.as_ref().map(|g| (engine.texture(g.width, g.height, &gpu::guide_data(g)), g.air)),
                coords: p.coords.map(|(nx, ny, data)| engine.texture(nx, ny, &data)),
            });
            self.drawn = None;
        }
        if self.drawn.as_ref() != Some(&self.s) && self.scene.is_some() {
            self.draw(&engine, &opened);
        }
        self.target.as_ref().map(|(t, id)| (*id, egui::vec2(t.width as f32, t.height as f32)))
    }

    /// Whether the preview still shows other geometry or tools than the settings ask for.
    pub fn catching_up(&self) -> bool {
        self.scene.as_ref().is_none_or(|s| s.key != self.requested)
    }

    fn draw(&mut self, engine: &Engine, opened: &Opened) {
        self.load_lut();
        let s = self.s.clone();
        let Some(scene) = &self.scene else { return };
        let lut = if s.lut.is_some() { self.lut.as_ref().and_then(|l| l.2.as_ref()) } else { None };
        let look = Look::new(&opened.source, &s);
        let params = look.shader_params(scene.scale);
        let tone = engine.texture(params.tone.len(), 1, &gpu::tone_data(&params.tone));
        let curves = params.curves.as_ref().map(|c| engine.texture(c[0].len(), 3, &gpu::curves_data(c)));
        let (w, h) = (scene.tex.width, scene.tex.height);
        let inputs = Inputs {
            scene: &scene.tex,
            tone: &tone,
            curves: curves.as_ref(),
            guide: scene.guide.as_ref().map(|(t, a)| (t, *a)),
            coords: scene.coords.as_ref(),
            lut,
            params: &params,
            place: Placement::whole(w, h),
        };
        let resized = self.target.as_ref().is_none_or(|(t, _)| t.width != w || t.height != h);
        if resized {
            let tex = engine.target(w, h);
            let rs = self.render_state.as_ref().expect("a GPU engine comes with a render state");
            let mut renderer = rs.renderer.write();
            let id = match &self.target {
                Some((_, id)) => {
                    renderer.update_egui_texture_from_wgpu_texture(&rs.device, &tex.view, wgpu::FilterMode::Linear, *id);
                    *id
                }
                None => renderer.register_native_texture(&rs.device, &tex.view, wgpu::FilterMode::Linear),
            };
            self.target = Some((tex, id));
        }
        engine.draw(&inputs, &self.target.as_ref().unwrap().0);
        self.drawn = Some(s);
    }

    /// Where exports go: a folder beside the library.
    pub fn export_dir(&self) -> PathBuf {
        let name = self.root.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "Shootback".into());
        self.root.parent().unwrap_or(&self.root).join(format!("{name} Exports"))
    }

    /// Exports the open photo at full size, in the background.
    pub fn export(&mut self, ctx: &egui::Context) {
        let (Some(engine), Some(opened), Some(file)) = (self.engine.clone(), self.opened.clone(), self.file.clone()) else { return };
        if self.export.lock().unwrap().running {
            return;
        }
        self.save();
        let s = self.s.clone();
        let lut = self.s.lut.as_ref().and_then(|id| lut::find(&self.root, id));
        let dir = self.export_dir();
        let status = self.export.clone();
        let cancel = self.cancel.clone();
        cancel.store(false, Ordering::Relaxed);
        *status.lock().unwrap() = ExportStatus { running: true, message: "Exporting ...".into(), ..Default::default() };
        let c = ctx.clone();
        std::thread::spawn(move || {
            let stem = file.file_stem().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "photo".into());
            let out = dir.join(format!("{stem}-edit.jpg"));
            let progress = |f: f32| {
                status.lock().unwrap().progress = f;
                c.request_repaint();
            };
            let result = render::full_size(&engine, &opened, &s, lut.as_ref(), &progress, &cancel).and_then(|(w, h, rgb)| {
                let exif = develop::export::exif_for(&file);
                let jpeg = render::encode_jpeg(w, h, &rgb, exif.as_deref())?;
                std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
                shootback_core::library::write_atomic(&out, &jpeg).map_err(|e| e.to_string())?;
                Ok((w, h))
            });
            let mut st = status.lock().unwrap();
            st.running = false;
            match result {
                Ok((w, h)) => {
                    st.message = format!("Saved {} ({w} x {h})", out.file_name().unwrap_or_default().to_string_lossy());
                    st.last = Some(out);
                }
                Err(e) => {
                    st.failed = true;
                    st.message = format!("Export failed: {e}");
                }
            }
            c.request_repaint();
        });
    }

    pub fn cancel_export(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

/// What the slow inputs depend on: the geometry, the noise and defringe tools, what the guide
/// is made from (white balance, not exposure) and whether there are masks.
fn slow_key(o: &Opened, s: &Settings, whole_frame: bool) -> String {
    let geom = o.source.geometry(s, whole_frame);
    let look = Look::new(&o.source, s);
    let flat = Look::new(&o.source, &s.clone().with(&p::EXPOSURE, 0.0));
    format!("{geom:?}|{:?}|{}|{:?}|{}", look.prepare_key(), look.needs_guide(), flat.matrix, s.masks.is_empty())
}

fn worker(jobs: Receiver<Job>, done: Sender<Prepared>, ctx: egui::Context) {
    // The last photo sampled through the last geometry, before preparing.
    let mut sampled: Option<(String, LinearImage, f64)> = None;
    while let Ok(mut job) = jobs.recv() {
        while let Ok(newer) = jobs.try_recv() {
            job = newer;
        }
        let source = &job.opened.source;
        let geom = source.geometry(&job.s, job.whole_frame);
        let geom_key = format!("{:p}|{geom:?}", Arc::as_ptr(&job.opened));
        if sampled.as_ref().is_none_or(|c| c.0 != geom_key) {
            // Output pixels per full-size pixel.
            let scale = natural_scale(&source.image, &geom, Some(PREVIEW_EDGE));
            let img = sample(&source.image, &geom, Some(PREVIEW_EDGE), None);
            sampled = Some((geom_key, img, scale));
        }
        let (_, img, scale) = sampled.as_ref().unwrap();
        let look = Look::new(source, &job.s);
        let mut scene = img.clone();
        look.prepare(&mut scene, *scale);
        let guide = look.guide(source, &geom);
        let coords = (!job.s.masks.is_empty()).then(|| gpu::coords_data(&geom, *scale, scene.width, scene.height, 0, 0));
        if done.send(Prepared { key: job.key, scene, scale: *scale, guide, coords }).is_err() {
            return;
        }
        ctx.request_repaint();
    }
}
