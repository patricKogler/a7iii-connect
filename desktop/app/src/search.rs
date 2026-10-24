//! Searching the library in words: once the search model is there, every photo is looked at on a
//! thread (newest first) and its CLIP vector kept beside the library; a search then runs the
//! words through the text half and keeps the shots close to them.

use crate::images::{self, THUMB};
use shootback_core::ai::{self, catalog::CLIP, clip, tensor::Rgb};
use shootback_core::library::Shot;
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};

struct Queue {
    todo: Mutex<VecDeque<Shot>>,
    ready: Condvar,
    stop: AtomicBool,
}

pub struct Search {
    index: HashMap<String, Vec<f32>>,
    queue: Arc<Queue>,
    rx: Receiver<(String, Option<Vec<f32>>)>,
    /// Shots that couldn't be looked at, counted as done.
    skipped: HashSet<String>,
    /// The words searched for and where they sit.
    words: Option<(String, Vec<f32>)>,
    asking: Option<(String, Receiver<Result<Vec<f32>, String>>)>,
    /// Shots that match `words` by what's in them.
    found: HashSet<String>,
    pub error: Option<String>,
    all: bool,
    ctx: eframe::egui::Context,
}

impl Search {
    pub fn new(ctx: &eframe::egui::Context, root: &Path) -> Search {
        let queue = Arc::new(Queue { todo: Mutex::default(), ready: Condvar::new(), stop: AtomicBool::new(false) });
        let (tx, rx) = channel();
        let store = clip::SearchStore::new(root);
        let index = store.load();
        let done: HashSet<String> = index.keys().cloned().collect();
        let (q, c) = (queue.clone(), ctx.clone());
        std::thread::Builder::new().name("search".into()).spawn(move || work(&store, done, &q, &tx, &c)).expect("search thread");
        Search { index, queue, rx, skipped: HashSet::new(), words: None, asking: None, found: HashSet::new(), error: None, all: false, ctx: ctx.clone() }
    }

    pub fn stop(&self) {
        self.queue.stop.store(true, Ordering::Relaxed);
        self.queue.ready.notify_all();
    }

    pub fn set_root(&mut self, root: &Path) {
        self.stop();
        *self = Search::new(&self.ctx.clone(), root);
    }

    pub fn fetch_model(&self) {
        std::thread::spawn(|| ai::ensure(&CLIP));
    }

    /// Shots looked at for search, out of `shots`.
    pub fn progress(&self, shots: &[Shot]) -> (usize, usize) {
        let done = shots.iter().filter(|s| self.index.contains_key(&s.key()) || self.skipped.contains(&s.key())).count();
        (done, shots.len())
    }

    /// Whether `key` matches the words searched for by what's in it.
    pub fn found(&self, query: &str, key: &str) -> bool {
        self.words.as_ref().is_some_and(|(w, _)| w == query.trim()) && self.found.contains(key)
    }

    /// Takes in new vectors, looks at the library once the model is there (again when
    /// `rescanned`), and works out where `query` sits when it changed.
    pub fn follow(&mut self, query: &str, shots: &[Shot], rescanned: bool) {
        let mut grew = false;
        while let Ok((k, v)) = self.rx.try_recv() {
            match v {
                Some(v) => {
                    self.index.insert(k, v);
                    grew = true;
                }
                None => {
                    self.skipped.insert(k);
                }
            }
        }
        if !ai::installed(&CLIP) {
            return;
        }
        if !self.all || rescanned {
            self.all = true;
            let mut q = self.queue.todo.lock().unwrap();
            q.extend(shots.iter().filter(|s| !self.index.contains_key(&s.key())).cloned());
            self.queue.ready.notify_one();
        }
        if let Some((w, rx)) = &self.asking {
            if let Ok(r) = rx.try_recv() {
                match r {
                    Ok(v) => {
                        self.words = Some((w.clone(), v));
                        self.error = None;
                        grew = true;
                    }
                    Err(e) => self.error = Some(e),
                }
                self.asking = None;
            }
        }
        let q = query.trim().to_string();
        let have = self.asking.as_ref().map(|a| &a.0).or(self.words.as_ref().map(|w| &w.0));
        if !q.is_empty() && have != Some(&q) && self.asking.is_none() {
            let (tx, rx) = channel();
            let (words, ctx) = (q.clone(), self.ctx.clone());
            std::thread::spawn(move || {
                let _ = tx.send(clip::embed_query(&words));
                ctx.request_repaint();
            });
            self.asking = Some((q, rx));
        }
        if grew {
            if let Some((_, v)) = &self.words {
                self.found = clip::ranked(v, &self.index).into_iter().map(|(k, _)| k).collect();
            }
        }
    }
}

fn work(store: &clip::SearchStore, mut done: HashSet<String>, q: &Queue, tx: &Sender<(String, Option<Vec<f32>>)>, ctx: &eframe::egui::Context) {
    loop {
        let shot = {
            let mut todo = q.todo.lock().unwrap();
            loop {
                if q.stop.load(Ordering::Relaxed) {
                    return;
                }
                match todo.pop_front() {
                    Some(s) if done.contains(&s.key()) => continue,
                    Some(s) => break s,
                    None => todo = q.ready.wait(todo).unwrap(),
                }
            }
        };
        let key = shot.key();
        done.insert(key.clone());
        let show = shot.display();
        let v = images::decode(&show.path, THUMB, show.orientation).and_then(|img| {
            let rgb: Vec<u8> = img.pixels.iter().flat_map(|c| [c.r(), c.g(), c.b()]).collect();
            clip::embed_image(&Rgb::from_bytes(&rgb, img.size[0], img.size[1])).ok()
        });
        if let Some(v) = &v {
            let _ = store.add(&key, v);
        }
        if tx.send((key, v)).is_err() {
            return;
        }
        ctx.request_repaint();
    }
}
