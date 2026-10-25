//! Filling in what's painted out with the Remove tool, for the open photo: on a thread, one
//! removal after another, into the photo's `Opened::removed` (the preview picks them up as they
//! come; an export fills them again at full size).

use shootback_core::develop::{Opened, Settings};
use std::sync::mpsc::{channel, Receiver};
use std::sync::Arc;

#[derive(Default)]
pub struct Remover {
    rx: Option<Receiver<Result<(), String>>>,
    pub error: Option<String>,
    /// The settings that last failed, so they aren't tried again and again.
    failed: Option<Settings>,
}

impl Remover {
    pub fn close(&mut self) {
        self.rx = None;
        self.error = None;
        self.failed = None;
    }

    pub fn running(&self) -> bool {
        self.rx.is_some()
    }

    /// Fills whatever `s` paints out that isn't filled yet.
    pub fn follow(&mut self, opened: &Arc<Opened>, s: &Settings, ctx: &eframe::egui::Context) {
        if let Some(rx) = &self.rx {
            match rx.try_recv() {
                Ok(Ok(())) => self.rx = None,
                Ok(Err(e)) => {
                    self.error = Some(e);
                    self.failed = Some(s.clone());
                    self.rx = None;
                }
                Err(_) => return,
            }
        }
        if opened.removals_ready(s) >= s.removals.len() || self.failed.as_ref() == Some(s) {
            return;
        }
        self.error = None;
        let (tx, rx) = channel();
        self.rx = Some(rx);
        let (o, s, c) = (opened.clone(), s.clone(), ctx.clone());
        std::thread::spawn(move || {
            let r = (o.removals_ready(&s)..s.removals.len()).try_for_each(|i| {
                let r = o.fill_removal(&s, i);
                c.request_repaint();
                r
            });
            let _ = tx.send(r);
            c.request_repaint();
        });
    }
}
