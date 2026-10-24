//! Running AI denoise for the open raw: once per photo, kept beside the library, on a thread so
//! the editor stays free. The result goes into the photo's `Opened::denoised`.

use shootback_core::ai;
use shootback_core::develop::params::p;
use shootback_core::develop::{DenoiseStore, Denoised, Opened, Settings};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::sync::Arc;

/// How denoising the open photo is going.
#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Idle,
    Running,
    Failed(String),
}

pub struct Denoiser {
    store: DenoiseStore,
    pub state: State,
    /// Fraction done, as f32 bits.
    done: Arc<AtomicU32>,
    cancel: Arc<AtomicBool>,
    rx: Option<Receiver<Result<(), String>>>,
    /// Set by the panel's Try again.
    pub retry: AtomicBool,
}

impl Denoiser {
    pub fn new(root: &Path) -> Denoiser {
        Denoiser {
            store: DenoiseStore::new(root),
            state: State::Idle,
            done: Arc::new(AtomicU32::new(0)),
            cancel: Arc::new(AtomicBool::new(false)),
            rx: None,
            retry: AtomicBool::new(false),
        }
    }

    pub fn set_root(&mut self, root: &Path) {
        self.close();
        self.store = DenoiseStore::new(root);
    }

    /// Stops any denoising (the photo was closed).
    pub fn close(&mut self) {
        self.cancel.store(true, Ordering::Relaxed);
        self.rx = None;
        self.state = State::Idle;
    }

    /// Fraction of the denoising done.
    pub fn progress(&self) -> f32 {
        f32::from_bits(self.done.load(Ordering::Relaxed))
    }

    /// Starts denoising `opened` when `s` asks for it and it isn't done or running.
    pub fn follow(&mut self, key: &str, opened: &Arc<Opened>, s: &Settings, ctx: &eframe::egui::Context) {
        if self.retry.swap(false, Ordering::Relaxed) && matches!(self.state, State::Failed(_)) {
            self.state = State::Idle;
        }
        if s.get(&p::AI_DENOISE) <= 0.0 || opened.raw.is_none() || opened.denoised.get().is_some() || self.state != State::Idle {
            return;
        }
        self.state = State::Running;
        self.done.store(0f32.to_bits(), Ordering::Relaxed);
        self.cancel = Arc::new(AtomicBool::new(false));
        let (tx, rx) = channel();
        self.rx = Some(rx);
        let (opened, key, store) = (opened.clone(), key.to_string(), self.store.clone());
        let (done, cancel, c) = (self.done.clone(), self.cancel.clone(), ctx.clone());
        std::thread::spawn(move || {
            let raw = opened.raw.as_ref().unwrap();
            let result = store
                .load(&key, raw.width, raw.height)
                .map(Ok)
                .unwrap_or_else(|| {
                    let full = ai::denoise::raw(
                        raw,
                        &|f| {
                            done.store(f.to_bits(), Ordering::Relaxed);
                            c.request_repaint();
                        },
                        &cancel,
                    )?;
                    let _ = store.save(&key, raw.width, raw.height, &full);
                    Ok(full)
                })
                .map(|full| {
                    let _ = opened.denoised.set(Denoised::new(full, raw.width, raw.height, opened.orientation));
                });
            let _ = tx.send(result);
            c.request_repaint();
        });
    }

    /// Takes in a finished run. True when the photo's denoised version arrived.
    pub fn poll(&mut self) -> bool {
        let Some(Ok(r)) = self.rx.as_ref().map(|rx| rx.try_recv()) else { return false };
        self.rx = None;
        match r {
            Ok(()) => {
                self.state = State::Idle;
                true
            }
            Err(e) => {
                self.state = State::Failed(e);
                false
            }
        }
    }
}
