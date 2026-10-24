//! The maps the open photo's masks select with: the subject, sky and depth found in it (by the
//! models, on worker threads, and kept beside the edit so each photo is looked at once), and the
//! maps of Brush and Object masks, painted or picked here.

use shootback_core::ai::{self, objects, tensor::Rgb};
use shootback_core::develop::look::{map_slots, Source, MASK_MAX};
use shootback_core::develop::maps::{MapKind, MapStore, MaskImage};
use shootback_core::develop::params::{MaskKind, Pick};
use shootback_core::develop::Opened;
use shootback_core::develop::Settings;
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;

/// An Object mask's clicks and its grain: what its map is made from.
type Clicks = (Vec<Pick>, i8);

/// Long edge of every map (the phone's).
pub const EDGE: usize = 1024;

pub const KINDS: [MapKind; 3] = [MapKind::Subject, MapKind::Sky, MapKind::Depth];

/// How finding one of a photo's maps is going.
#[derive(Clone, Debug, PartialEq)]
pub enum Finding {
    Idle,
    Running,
    /// It isn't there (no sky along the top), or the model failed.
    Failed(String),
}

enum Done {
    Found { key: String, kind: MapKind, result: Result<MaskImage, String> },
    Embedded { key: String, result: Result<Arc<objects::Embedding>, String> },
    Picked { key: String, id: String, picks: Clicks, result: Result<Option<MaskImage>, String> },
}

pub struct Maps {
    store: MapStore,
    key: Option<String>,
    opened: Option<Arc<Opened>>,
    pub found: [Option<MaskImage>; 3],
    pub finding: [Finding; 3],
    /// Brush and Object masks' maps, by mask id.
    pub own: HashMap<String, MaskImage>,
    /// Own maps changed since they were saved.
    unsaved: HashSet<String>,
    embedding: Option<Arc<objects::Embedding>>,
    pub embedding_state: Finding,
    /// Picks waiting for the photo to be looked at, by mask id.
    waiting_picks: HashMap<String, Clicks>,
    /// The picks each own map was last made from, so a repeat isn't run again.
    picked: HashMap<String, Clicks>,
    tx: Sender<Done>,
    rx: Receiver<Done>,
    /// Bumped whenever a map changes, so the GPU copies are rebuilt.
    pub version: u64,
}

impl Maps {
    pub fn new(root: &Path) -> Maps {
        let (tx, rx) = channel();
        Maps {
            store: MapStore::new(root),
            key: None,
            opened: None,
            found: [None, None, None],
            finding: [Finding::Idle, Finding::Idle, Finding::Idle],
            own: HashMap::new(),
            unsaved: HashSet::new(),
            embedding: None,
            embedding_state: Finding::Idle,
            waiting_picks: HashMap::new(),
            picked: HashMap::new(),
            tx,
            rx,
            version: 0,
        }
    }

    pub fn set_root(&mut self, root: &Path) {
        self.close();
        self.store = MapStore::new(root);
    }

    /// Lets go of the photo (saving painted maps first).
    pub fn close(&mut self) {
        self.save();
        self.key = None;
        self.opened = None;
        self.found = [None, None, None];
        self.finding = [Finding::Idle, Finding::Idle, Finding::Idle];
        self.own.clear();
        self.unsaved.clear();
        self.embedding = None;
        self.embedding_state = Finding::Idle;
        self.waiting_picks.clear();
        self.picked.clear();
        self.version += 1;
    }

    /// Takes the photo `key`, loading the maps found for it earlier.
    pub fn open(&mut self, key: &str, opened: Arc<Opened>, s: &Settings) {
        if self.key.as_deref() == Some(key) {
            return;
        }
        self.close();
        self.key = Some(key.to_string());
        self.opened = Some(opened);
        for (i, kind) in KINDS.iter().enumerate() {
            self.found[i] = self.store.load(key, *kind);
        }
        for m in &s.masks {
            if m.kind.has_own_map() && !m.id.is_empty() {
                if let Some(map) = self.store.load_mask(key, &m.id) {
                    self.picked.insert(m.id.clone(), (m.picks.clone(), m.grain));
                    self.own.insert(m.id.clone(), map);
                }
            }
        }
        self.version += 1;
    }

    fn index(kind: MapKind) -> usize {
        KINDS.iter().position(|k| *k == kind).unwrap()
    }

    pub fn state(&self, kind: MapKind) -> &Finding {
        &self.finding[Self::index(kind)]
    }

    /// The photo with the default look at the maps' size, as the finders see it.
    fn picture(source: &Source) -> Rgb {
        ai::tensor::display(source, EDGE)
    }

    /// Starts finding `kind` unless it's found, being found, or failed.
    pub fn find(&mut self, kind: MapKind, ctx: &eframe::egui::Context) {
        let i = Self::index(kind);
        if self.found[i].is_some() || self.finding[i] != Finding::Idle {
            return;
        }
        let (Some(key), Some(opened)) = (self.key.clone(), self.opened.clone()) else { return };
        self.finding[i] = Finding::Running;
        let tx = self.tx.clone();
        let c = ctx.clone();
        let depth = self.found[Self::index(MapKind::Depth)].clone();
        std::thread::spawn(move || {
            let px = Self::picture(&opened.source);
            let result = match kind {
                MapKind::Subject => ai::subject::find(&px),
                MapKind::Sky => {
                    // The sky is the farthest part: the depth keeps pale walls out of it. Without
                    // the depth model (offline), colour alone does, as on the phone.
                    let depth = depth.or_else(|| {
                        let d = ai::depth::find(&px).ok()?;
                        let _ = tx.send(Done::Found { key: key.clone(), kind: MapKind::Depth, result: Ok(d.clone()) });
                        Some(d)
                    });
                    ai::sky::find(&px, depth.as_ref()).ok_or_else(|| "There's no sky along the top of this photo".to_string())
                }
                MapKind::Depth => ai::depth::find(&px),
            };
            let _ = tx.send(Done::Found { key, kind, result });
            c.request_repaint();
        });
    }

    /// Tries a failed find again.
    pub fn retry(&mut self, kind: MapKind) {
        let i = Self::index(kind);
        if matches!(self.finding[i], Finding::Failed(_)) {
            self.finding[i] = Finding::Idle;
        }
    }

    /// Finds what the edit's masks need, and makes Object masks follow their clicks.
    pub fn follow(&mut self, s: &Settings, ctx: &eframe::egui::Context) {
        for m in s.masks.iter().take(MASK_MAX) {
            if m.uses_subject() {
                self.find(MapKind::Subject, ctx);
            }
            if m.kind == MaskKind::Sky {
                self.find(MapKind::Sky, ctx);
            }
            if m.kind == MaskKind::Depth {
                self.find(MapKind::Depth, ctx);
            }
            if m.kind == MaskKind::Object && self.picked.get(&m.id) != Some(&(m.picks.clone(), m.grain)) && !self.waiting_picks.contains_key(&m.id) {
                self.pick(&m.id, (m.picks.clone(), m.grain), ctx);
            }
        }
    }

    /// Selects what `picks` point at for the Object mask `id` (looking at the photo first if
    /// that's still to do).
    fn pick(&mut self, id: &str, picks: Clicks, ctx: &eframe::egui::Context) {
        let (Some(key), Some(opened)) = (self.key.clone(), self.opened.clone()) else { return };
        if picks.0.is_empty() {
            let blank = self.blank();
            self.own.insert(id.to_string(), blank);
            self.picked.insert(id.to_string(), picks);
            self.version += 1;
            return;
        }
        let Some(e) = self.embedding.clone() else {
            self.waiting_picks.insert(id.to_string(), picks);
            if self.embedding_state == Finding::Idle {
                self.embedding_state = Finding::Running;
                let tx = self.tx.clone();
                let c = ctx.clone();
                std::thread::spawn(move || {
                    let result = objects::embed(&Self::picture(&opened.source)).map(Arc::new);
                    let _ = tx.send(Done::Embedded { key, result });
                    c.request_repaint();
                });
            }
            return;
        };
        self.waiting_picks.insert(id.to_string(), picks.clone());
        let tx = self.tx.clone();
        let c = ctx.clone();
        let id = id.to_string();
        std::thread::spawn(move || {
            let points: Vec<objects::Point> = picks.0.iter().map(|p| objects::Point { x: p.x, y: p.y, add: p.add }).collect();
            let result = objects::select(&e, &points, picks.1);
            let _ = tx.send(Done::Picked { key, id, picks, result });
            c.request_repaint();
        });
    }

    /// Whether an Object mask is waiting for its selection.
    pub fn picking(&self, id: &str) -> bool {
        self.waiting_picks.contains_key(id)
    }

    /// Takes in finished work. Returns whether anything changed.
    pub fn poll(&mut self, ctx: &eframe::egui::Context) -> bool {
        let mut changed = false;
        while let Ok(d) = self.rx.try_recv() {
            match d {
                Done::Found { key, kind, result } if Some(&key) == self.key.as_ref() => {
                    let i = Self::index(kind);
                    match result {
                        Ok(m) => {
                            let _ = self.store.save(&key, kind, &m);
                            self.found[i] = Some(m);
                            self.finding[i] = Finding::Idle;
                        }
                        Err(e) => self.finding[i] = Finding::Failed(e),
                    }
                    changed = true;
                }
                Done::Embedded { key, result } if Some(&key) == self.key.as_ref() => match result {
                    Ok(e) => {
                        self.embedding = Some(e);
                        self.embedding_state = Finding::Idle;
                        for (id, picks) in std::mem::take(&mut self.waiting_picks) {
                            self.pick(&id, picks, ctx);
                        }
                    }
                    Err(e) => {
                        self.embedding_state = Finding::Failed(e);
                        self.waiting_picks.clear();
                    }
                },
                Done::Picked { key, id, picks, result } if Some(&key) == self.key.as_ref() => {
                    if self.waiting_picks.get(&id) == Some(&picks) {
                        self.waiting_picks.remove(&id);
                    }
                    match result {
                        Ok(map) => {
                            let map = map.unwrap_or_else(|| self.blank());
                            let _ = self.store.save_mask(&key, &id, &map);
                            self.own.insert(id.clone(), map);
                            self.picked.insert(id, picks);
                            changed = true;
                        }
                        Err(e) => self.embedding_state = Finding::Failed(e),
                    }
                }
                _ => {}
            }
        }
        if changed {
            self.version += 1;
        }
        changed
    }

    /// An empty map at the maps' size for this photo.
    fn blank(&self) -> MaskImage {
        let (w, h) = self.opened.as_ref().map(|o| &o.source).map_or((EDGE, EDGE), |s| {
            let k = EDGE as f64 / s.full_width.max(s.full_height) as f64;
            (((s.full_width as f64 * k).round() as usize).max(1), ((s.full_height as f64 * k).round() as usize).max(1))
        });
        MaskImage::new(w, h)
    }

    /// One dab of the brush on the Brush mask `id`: a soft round spot at (u, v) (fractions of the
    /// upright photo), `radius` a fraction of the long edge, `feather` 0..1 how much of it fades.
    /// Painting adds up to `flow` per dab; erasing takes away.
    #[allow(clippy::too_many_arguments)]
    pub fn dab(&mut self, id: &str, u: f32, v: f32, radius: f32, feather: f32, flow: f32, erase: bool) {
        if !self.own.contains_key(id) {
            let blank = self.blank();
            self.own.insert(id.to_string(), blank);
        }
        let m = self.own.get_mut(id).unwrap();
        let (w, h) = (m.width as f32, m.height as f32);
        let r = radius * w.max(h);
        let (cx, cy) = (u * w, v * h);
        let inner = r * (1.0 - feather.clamp(0.0, 1.0));
        let x0 = (cx - r).floor().max(0.0) as usize;
        let x1 = ((cx + r).ceil() as usize).min(m.width);
        let y0 = (cy - r).floor().max(0.0) as usize;
        let y1 = ((cy + r).ceil() as usize).min(m.height);
        for y in y0..y1 {
            for x in x0..x1 {
                let d = ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt();
                if d >= r {
                    continue;
                }
                let t = if d <= inner { 1.0 } else { 1.0 - (d - inner) / (r - inner).max(1e-3) };
                let a = t * t * (3.0 - 2.0 * t) * flow;
                let px = &mut m.data[y * m.width + x];
                *px = if erase { *px * (1.0 - a) } else { *px + (1.0 - *px) * a };
            }
        }
        self.unsaved.insert(id.to_string());
        self.version += 1;
    }

    /// Gives the mask `to` a copy of the map of `from` (a duplicated mask).
    pub fn copy_map(&mut self, from: &str, to: &str) {
        if let Some(m) = self.own.get(from).cloned() {
            self.own.insert(to.to_string(), m);
            self.unsaved.insert(to.to_string());
            if let Some(p) = self.picked.get(from).cloned() {
                self.picked.insert(to.to_string(), p);
            }
            self.version += 1;
        }
    }

    /// Writes painted maps out.
    pub fn save(&mut self) {
        let Some(key) = &self.key else { return };
        for id in self.unsaved.drain() {
            if let Some(m) = self.own.get(&id) {
                let _ = self.store.save_mask(key, &id, m);
            }
        }
    }

    /// Drops the maps of masks the edit no longer has (after a save of the edit).
    pub fn prune(&mut self, s: &Settings) {
        let Some(key) = &self.key else { return };
        let ids: Vec<&str> = s.masks.iter().filter(|m| m.kind.has_own_map()).map(|m| m.id.as_str()).collect();
        self.own.retain(|id, _| ids.contains(&id.as_str()));
        self.store.keep_masks(key, &ids);
    }

    /// The own maps in the order of their slots, for the GPU.
    pub fn slots(&self, s: &Settings) -> Vec<Option<&MaskImage>> {
        let slots = map_slots(s);
        let mut out = vec![None; slots.iter().flatten().count()];
        for (k, slot) in slots.iter().enumerate() {
            if let (Some(slot), Some(m)) = (slot, s.masks.get(k)) {
                out[*slot] = self.own.get(&m.id);
            }
        }
        out
    }
}
