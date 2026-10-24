//! Culling, as on the phone: the shots dealt one at a time to pick, reject or skip, bursts and
//! near-duplicates as one stack settled two at a time. Behind it, on a thread, what every shot
//! looks like (for stacking) is worked out once and kept beside the library; faces and focus come
//! from `Insights`, which is asked for the shots coming up next first.

use crate::images::{self, THUMB};
use crate::insights::Insights;
use crate::state::Filter;
use shootback_core::cull::{self, best, CullStore, Duel, Frame, Look};
use shootback_core::library::{self, Shot};
use shootback_core::marks::Mark;
use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Pick,
    Reject,
    Skip,
}

/// One decision, with the marks it replaced so undo can put them back.
struct Step {
    pos: usize,
    previous: Vec<(String, Option<Mark>)>,
}

/// A stack being settled head to head: `order` is the stack with its likely best first, and the
/// duel's indices point into it.
pub struct Contest {
    pub pos: usize,
    pub order: Vec<usize>,
    pub duel: Duel,
}

/// How many stacks ahead of the deck faces and focus are asked for.
const AHEAD: usize = 12;

pub struct Cull {
    /// What's being culled: the shots to review, the picks (to narrow them down) or an album.
    pub scope: Filter,
    /// Stacks in the order they're dealt; None while similar shots are being found.
    pub deck: Option<Vec<Vec<Shot>>>,
    pub pos: usize,
    undo: Vec<Step>,
    pub decided: HashMap<String, Verdict>,
    pub contest: Option<Contest>,
    /// Zoomed in to check focus: the point of the photo in the middle (0..1 each way).
    pub zoom: Option<[f32; 2]>,
    /// The point of the photo under the pointer, to zoom in on.
    pub hover: Option<[f32; 2]>,
    started: bool,
    rx: Option<Receiver<Vec<Vec<Shot>>>>,
    cancel: Arc<AtomicBool>,
    /// Fraction of the shots looked at for stacking, as f32 bits.
    progress: Arc<AtomicU32>,
    /// Where the deck was when faces and focus were last asked for.
    asked: Option<usize>,
}

impl Cull {
    pub fn new() -> Cull {
        Cull {
            scope: Filter::Review,
            deck: None,
            pos: 0,
            undo: Vec::new(),
            decided: HashMap::new(),
            contest: None,
            zoom: None,
            hover: None,
            started: false,
            rx: None,
            cancel: Arc::default(),
            progress: Arc::default(),
            asked: None,
        }
    }

    pub fn started(&self) -> bool {
        self.started
    }

    pub fn progress(&self) -> f32 {
        f32::from_bits(self.progress.load(Ordering::Relaxed))
    }

    /// Stops the worker and forgets the deck (the library changed, or culling starts over).
    pub fn reset(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
        let scope = self.scope.clone();
        *self = Cull::new();
        self.scope = scope;
    }

    /// Deals `shots` (newest day first, each day in the order taken) and starts looking at them.
    pub fn start(&mut self, ctx: &eframe::egui::Context, root: &Path, shots: Vec<Shot>) {
        self.reset();
        self.started = true;
        let (tx, rx) = channel();
        self.rx = Some(rx);
        let (cancel, progress, root, ctx) = (self.cancel.clone(), self.progress.clone(), root.to_path_buf(), ctx.clone());
        std::thread::Builder::new().name("cull".into()).spawn(move || work(&root, shots, &tx, &cancel, &progress, &ctx)).expect("cull thread");
    }

    /// Takes in the deck once the worker has dealt it.
    pub fn poll(&mut self) {
        if let Some(d) = self.rx.as_ref().and_then(|rx| rx.try_recv().ok()) {
            self.deck = Some(d);
            self.rx = None;
        }
    }

    pub fn top(&self) -> Option<&Vec<Shot>> {
        self.deck.as_ref()?.get(self.pos)
    }

    /// Keeps the head to head in step with the top of the deck, and records a stack once it's
    /// settled; asks `insights` for the shots coming up.
    pub fn follow(&mut self, lib: &mut crate::state::Library, insights: &Insights) {
        if let Some(deck) = self.deck.as_ref().filter(|_| self.asked != Some(self.pos)) {
            self.asked = Some(self.pos);
            let next: Vec<Shot> = deck.iter().skip(self.pos).take(AHEAD).flatten().cloned().collect();
            insights.first(&next);
        }
        let Some(top) = self.top().cloned() else {
            self.contest = None;
            return;
        };
        if top.len() < 2 {
            self.contest = None;
            return;
        }
        if self.contest.as_ref().is_none_or(|c| c.pos != self.pos) {
            let order = best::order(&top.iter().map(|s| insights.known.get(&s.key())).collect::<Vec<_>>());
            self.contest = Some(Contest { pos: self.pos, duel: Duel::new(order.len()), order });
            self.zoom = None;
        }
        let c = self.contest.as_ref().unwrap();
        if c.duel.done() {
            let kept: Vec<String> = c.duel.kept().iter().map(|&i| top[c.order[i]].key()).collect();
            let verdicts = top.iter().map(|s| (s.key(), if kept.contains(&s.key()) { Verdict::Pick } else { Verdict::Reject })).collect();
            self.record(lib, verdicts);
        }
    }

    /// Decides the top of the deck: every shot in it gets `v`.
    pub fn decide(&mut self, lib: &mut crate::state::Library, v: Verdict) {
        let Some(top) = self.top() else { return };
        let verdicts = top.iter().map(|s| (s.key(), v)).collect();
        self.record(lib, verdicts);
    }

    fn record(&mut self, lib: &mut crate::state::Library, verdicts: Vec<(String, Verdict)>) {
        self.undo.push(Step { pos: self.pos, previous: verdicts.iter().map(|(k, _)| (k.clone(), lib.marks.get(k))).collect() });
        for (k, v) in verdicts {
            match v {
                Verdict::Pick => lib.set_mark(&k, Some(Mark::Pick)),
                Verdict::Reject => lib.set_mark(&k, Some(Mark::Reject)),
                Verdict::Skip => {}
            }
            self.decided.insert(k, v);
        }
        self.contest = None;
        self.zoom = None;
        self.pos += 1;
    }

    pub fn can_undo(&self) -> bool {
        self.contest.as_ref().is_some_and(|c| c.duel.can_undo()) || !self.undo.is_empty()
    }

    /// Takes back the last choice in the head to head, or else the last decision.
    pub fn undo(&mut self, lib: &mut crate::state::Library) {
        if let Some(c) = self.contest.as_mut().filter(|c| c.duel.can_undo()) {
            c.duel.undo();
            return;
        }
        let Some(step) = self.undo.pop() else { return };
        for (k, m) in step.previous {
            lib.set_mark(&k, m);
            self.decided.remove(&k);
        }
        self.contest = None;
        self.zoom = None;
        self.pos = step.pos;
    }

    /// The top stack wasn't really similar: its shots come one at a time.
    pub fn split(&mut self) {
        let pos = self.pos;
        if let Some(deck) = self.deck.as_mut() {
            if deck.get(pos).is_some_and(|s| s.len() > 1) {
                let stack = deck.remove(pos);
                for (i, s) in stack.into_iter().enumerate() {
                    deck.insert(pos + i, vec![s]);
                }
            }
        }
        self.contest = None;
    }

    pub fn count(&self, v: Verdict) -> usize {
        self.decided.values().filter(|&&d| d == v).count()
    }
}

fn work(root: &Path, shots: Vec<Shot>, tx: &Sender<Vec<Vec<Shot>>>, cancel: &AtomicBool, progress: &AtomicU32, ctx: &eframe::egui::Context) {
    let store = CullStore::new(root);
    let mut shots = shots;
    shots.sort_by(|a, b| a.taken_millis().cmp(&b.taken_millis()).then_with(|| a.primary().name.cmp(&b.primary().name)));

    // What each shot looks like, a few at a time.
    let looks = store.looks();
    let done = AtomicUsize::new(0);
    let threads = std::thread::available_parallelism().map_or(2, |n| n.get()).clamp(1, 4);
    let mut seen: Vec<(Option<Look>, Option<f32>)> = vec![(None, None); shots.len()];
    std::thread::scope(|scope| {
        for (t, part) in seen.chunks_mut(shots.len().div_ceil(threads).max(1)).enumerate() {
            let (shots, looks, store, done) = (&shots, &looks, &store, &done);
            let first = t * shots.len().div_ceil(threads).max(1);
            scope.spawn(move || {
                for (j, slot) in part.iter_mut().enumerate() {
                    if cancel.load(Ordering::Relaxed) {
                        return;
                    }
                    let shot = &shots[first + j];
                    let key = shot.key();
                    let look = looks.get(&key).cloned().or_else(|| {
                        let l = look_of(shot)?;
                        let _ = store.add_look(&key, &l);
                        Some(l)
                    });
                    *slot = (look, focal(shot));
                    let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                    progress.store((n as f32 / shots.len() as f32).to_bits(), Ordering::Relaxed);
                    ctx.request_repaint();
                }
            });
        }
    });
    if cancel.load(Ordering::Relaxed) {
        return;
    }
    let frames: Vec<Frame> = shots
        .iter()
        .zip(&seen)
        .map(|(s, (look, focal))| Frame {
            taken_millis: s.taken_millis(),
            look: look.as_ref(),
            portrait: Some(matches!(s.display().orientation, 5..=8)),
            focal: *focal,
        })
        .collect();
    let mut deck: Vec<Vec<Shot>> = cull::stacks(&frames).into_iter().map(|st| st.into_iter().map(|i| shots[i].clone()).collect()).collect();
    // Newest day first; within a day, in the order taken.
    deck.sort_by_key(|st| std::cmp::Reverse(day(st[0].taken_millis())));
    let _ = tx.send(deck);
    ctx.request_repaint();
}

fn day(millis: i64) -> i64 {
    millis.div_euclid(86_400_000)
}

/// The shot as a 17x17 thumbnail's look.
fn look_of(shot: &Shot) -> Option<Look> {
    let show = shot.display();
    let img = images::decode(&show.path, THUMB, show.orientation)?;
    let [w, h] = img.size;
    let s = Look::SIDE;
    let mut rgb = vec![0u8; s * s * 3];
    for y in 0..s {
        for x in 0..s {
            let (x0, x1) = (x * w / s, ((x + 1) * w / s).max(x * w / s + 1));
            let (y0, y1) = (y * h / s, ((y + 1) * h / s).max(y * h / s + 1));
            let mut sum = [0u32; 3];
            for yy in y0..y1 {
                for xx in x0..x1 {
                    let c = img.pixels[yy * w + xx];
                    sum[0] += c.r() as u32;
                    sum[1] += c.g() as u32;
                    sum[2] += c.b() as u32;
                }
            }
            let n = ((x1 - x0) * (y1 - y0)) as u32;
            for c in 0..3 {
                rgb[(y * s + x) * 3 + c] = (sum[c] / n) as u8;
            }
        }
    }
    Some(Look::of(&rgb))
}

/// Focal length in mm, from EXIF.
fn focal(shot: &Shot) -> Option<f32> {
    let m = library::read_meta(&shot.display().path);
    m.focal_length?.split_whitespace().next()?.parse().ok()
}
