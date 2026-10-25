//! Looking closer on the Edit stage: a pinch or Ctrl+scroll zooms in where the pointer is,
//! scrolling or dragging moves around, and a double-click or Z goes between fitted and 100%. The
//! preview only holds so many pixels, so past that the part in view is developed at full size in
//! the background and drawn over the preview once it's ready.

use crate::gpu::Engine;
use crate::render::{self, MaskMaps};
use eframe::egui::{self, ColorImage, Pos2, Rect, TextureHandle, TextureOptions, Vec2};
use shootback_core::develop::look::FullImage;
use shootback_core::develop::lut::Lut;
use shootback_core::develop::{Opened, Settings};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;

/// The furthest in: screen pixels per photo pixel.
const MOST: f32 = 4.0;
/// How much more is developed around the part in view, on each side (a share of it), so moving
/// around a little stays sharp.
const AROUND: f32 = 0.25;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Zoom {
    /// How many times larger than fitted (1 is fitted).
    pub z: f32,
    /// The point of the photo in the middle of the view, as fractions of its width and height.
    pub center: Vec2,
    /// Whether it was set on the crop's whole frame, which is another picture than the edit.
    cropping: bool,
}

impl Default for Zoom {
    fn default() -> Zoom {
        Zoom { z: 1.0, center: Vec2::splat(0.5), cropping: false }
    }
}

impl Zoom {
    pub fn fitted(&self) -> bool {
        self.z <= 1.0 + 1e-3
    }

    /// Where a picture of `size` lands in `view`.
    pub fn place(&self, view: Rect, size: Vec2) -> Rect {
        let shown = size * fit(view, size) * self.z;
        Rect::from_min_size(view.center() - self.center * shown, shown)
    }

    /// The zoom at which one photo pixel (of a frame `frame_w` wide) is one screen pixel.
    pub fn actual(view: Rect, size: Vec2, frame_w: f32, pixels_per_point: f32) -> f32 {
        frame_w / (pixels_per_point * fit(view, size) * size.x)
    }

    /// Keeps it in bounds after the view changed, and starts over on the other picture when
    /// cropping starts or ends.
    pub fn settle(&mut self, view: Rect, size: Vec2, actual: f32, cropping: bool) {
        if cropping != self.cropping {
            *self = Zoom { cropping, ..Zoom::default() };
        }
        self.z = self.z.clamp(1.0, most(actual));
        self.clamp(view, size);
    }

    /// Zooms by `factor`, keeping the point of the photo under `at` there.
    pub fn zoom_at(&mut self, factor: f32, at: Pos2, view: Rect, size: Vec2, actual: f32) {
        let r = self.place(view, size);
        let under = (at - r.min) / r.size();
        self.z = (self.z * factor).clamp(1.0, most(actual));
        let shown = self.place(view, size).size();
        self.center = (view.center() - (at - under * shown)) / shown;
        self.clamp(view, size);
    }

    /// Moves the photo by `by` screen points.
    pub fn pan(&mut self, by: Vec2, view: Rect, size: Vec2) {
        self.center -= by / self.place(view, size).size();
        self.clamp(view, size);
    }

    /// From fitted to 100% at `at`, or back.
    pub fn toggle(&mut self, at: Pos2, view: Rect, size: Vec2, actual: f32) {
        if self.fitted() {
            self.zoom_at(actual.max(1.0) / self.z, at, view, size, actual);
        } else {
            *self = Zoom { cropping: self.cropping, ..Zoom::default() };
        }
    }

    /// Keeps the photo over the whole view where it's larger than it, and centred where not.
    fn clamp(&mut self, view: Rect, size: Vec2) {
        let shown = self.place(view, size).size();
        for i in 0..2 {
            let half = view.size()[i] / 2.0 / shown[i];
            self.center[i] = if half >= 0.5 { 0.5 } else { self.center[i].clamp(half, 1.0 - half) };
        }
    }
}

fn fit(view: Rect, size: Vec2) -> f32 {
    (view.width() / size.x).min(view.height() / size.y)
}

fn most(actual: f32) -> f32 {
    (actual * MOST).max(1.0)
}

/// What to develop at full size: the part in view (left, top, width and height in frame pixels
/// of a `frame` sized frame) with some more around it, within the frame and the largest texture.
pub fn around(visible: [usize; 4], frame: (usize, usize), largest: usize) -> [usize; 4] {
    let [x, y, w, h] = visible;
    let grow = |at: usize, len: usize, edge: usize| {
        let more = (len as f32 * AROUND) as usize;
        let start = at.saturating_sub(more);
        let end = (at + len + more).min(edge);
        if end - start <= largest {
            return (start, end - start);
        }
        // Shrunk to fit a texture, it stays around the middle of the part in view.
        let middle = at + len / 2;
        (middle.saturating_sub(largest / 2).min(edge - largest), largest)
    };
    let (x, w) = grow(x, w, frame.0);
    let (y, h) = grow(y, h, frame.1);
    [x, y, w, h]
}

/// Whether `area` holds all of `visible`.
fn holds(area: [usize; 4], visible: [usize; 4]) -> bool {
    area[0] <= visible[0] && area[1] <= visible[1] && area[0] + area[2] >= visible[0] + visible[2] && area[1] + area[3] >= visible[1] + visible[3]
}

/// One full-size render: what it's of (the look's key) and the area.
pub struct Ask {
    pub key: String,
    pub opened: Arc<Opened>,
    pub s: Settings,
    pub whole_frame: bool,
    pub lut: Option<Lut>,
    pub maps: MaskMaps,
    pub area: [usize; 4],
}

struct Done {
    key: String,
    area: [usize; 4],
    image: ColorImage,
}

/// Full-size renders of the part in view, on a worker thread.
pub struct Detail {
    jobs: Option<Sender<Ask>>,
    done: Option<Receiver<Done>>,
    /// Set when a newer ask makes the running one useless.
    stale: Arc<AtomicBool>,
    asked: Option<(String, [usize; 4])>,
    shown: Option<(String, [usize; 4], TextureHandle)>,
}

impl Detail {
    pub fn new(engine: Option<Arc<Engine>>, ctx: &egui::Context) -> Detail {
        let stale = Arc::new(AtomicBool::new(false));
        let (jobs, done) = match engine {
            Some(engine) => {
                let (jobs, rx) = channel();
                let (tx, done) = channel();
                let (c, st) = (ctx.clone(), stale.clone());
                std::thread::spawn(move || worker(rx, tx, st, engine, c));
                (Some(jobs), Some(done))
            }
            None => (None, None),
        };
        Detail { jobs, done, stale, asked: None, shown: None }
    }

    /// The render for `key` that holds `visible`, if there's one; otherwise asks for one (made by
    /// `ask` for an area) unless that's under way.
    pub fn get(
        &mut self,
        ctx: &egui::Context,
        key: &str,
        visible: [usize; 4],
        frame: (usize, usize),
        ask: impl FnOnce([usize; 4]) -> Ask,
    ) -> Option<(egui::TextureId, [usize; 4])> {
        let (Some(jobs), Some(done)) = (&self.jobs, &self.done) else { return None };
        while let Ok(d) = done.try_recv() {
            let tex = ctx.load_texture("detail", d.image, TextureOptions::LINEAR);
            self.shown = Some((d.key, d.area, tex));
        }
        if let Some((k, area, tex)) = &self.shown {
            if k == key && holds(*area, visible) {
                return Some((tex.id(), *area));
            }
        }
        if self.asked.as_ref().is_some_and(|(k, area)| k == key && holds(*area, visible)) {
            return None;
        }
        let largest = ctx.input(|i| i.max_texture_side).min(8192);
        let area = around(visible, frame, largest);
        self.asked = Some((key.to_string(), area));
        if jobs.send(ask(area)).is_ok() {
            self.stale.store(true, Ordering::Relaxed);
        }
        None
    }

    /// Lets go of the photo's renders.
    pub fn close(&mut self) {
        self.stale.store(true, Ordering::Relaxed);
        self.asked = None;
        self.shown = None;
    }
}

fn worker(jobs: Receiver<Ask>, done: Sender<Done>, stale: Arc<AtomicBool>, engine: Arc<Engine>, ctx: egui::Context) {
    // The photo at full size, for which photo, denoise and removals.
    let mut full: Option<(String, FullImage)> = None;
    while let Ok(mut job) = jobs.recv() {
        while let Ok(newer) = jobs.try_recv() {
            job = newer;
        }
        stale.store(false, Ordering::Relaxed);
        let o = &job.opened;
        let full_key = format!("{:p}|{}|{}", Arc::as_ptr(o), o.denoise_amount(&job.s), Opened::removal_keys(&job.s).pop().unwrap_or_default());
        if full.as_ref().is_none_or(|f| f.0 != full_key) {
            full = None;
            match o.full_image(&job.s) {
                Ok(f) => full = Some((full_key, f)),
                Err(_) => continue,
            }
        }
        if stale.load(Ordering::Relaxed) {
            continue;
        }
        let geom = o.source.geometry(&job.s, job.whole_frame);
        let Some((_, image)) = &full else { continue };
        let Ok(rgb) = render::region(&engine, o, image, &job.s, &geom, job.lut.as_ref(), &job.maps, job.area, &|_| {}, &stale) else { continue };
        let [_, _, w, h] = job.area;
        if done.send(Done { key: job.key, area: job.area, image: ColorImage::from_rgb([w, h], &rgb) }).is_err() {
            return;
        }
        ctx.request_repaint();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn view() -> Rect {
        Rect::from_min_size(Pos2::new(100.0, 50.0), Vec2::new(800.0, 600.0))
    }
    const SIZE: Vec2 = Vec2::new(2048.0, 1365.0);

    #[test]
    fn fitted_fills_the_view_centred() {
        let r = Zoom::default().place(view(), SIZE);
        assert!((r.width() - 800.0).abs() < 0.01);
        assert!((r.center() - view().center()).length() < 0.01);
    }

    #[test]
    fn zooming_keeps_the_point_under_the_pointer() {
        let mut z = Zoom::default();
        let at = Pos2::new(300.0, 200.0);
        let before = (at - z.place(view(), SIZE).min) / z.place(view(), SIZE).size();
        z.zoom_at(3.0, at, view(), SIZE, 7.5);
        let r = z.place(view(), SIZE);
        assert!((z.z - 3.0).abs() < 1e-4);
        assert!(((at - r.min) / r.size() - before).length() < 1e-4);
        // Not past 4 screen pixels per photo pixel, and not out past fitted.
        z.zoom_at(100.0, at, view(), SIZE, 7.5);
        assert!((z.z - 30.0).abs() < 1e-3);
        z.zoom_at(0.001, at, view(), SIZE, 7.5);
        assert!(z.fitted());
        assert_eq!(z.center, Vec2::splat(0.5));
    }

    #[test]
    fn moving_around_stops_at_the_edges() {
        let mut z = Zoom::default();
        z.toggle(view().center(), view(), SIZE, 7.5);
        assert!((z.z - 7.5).abs() < 1e-4);
        z.pan(Vec2::new(1e6, -1e6), view(), SIZE);
        let r = z.place(view(), SIZE);
        assert!((r.left() - view().left()).abs() < 0.01, "{r:?}");
        assert!((r.bottom() - view().bottom()).abs() < 0.01, "{r:?}");
        z.toggle(view().center(), view(), SIZE, 7.5);
        assert!(z.fitted());
    }

    #[test]
    fn develops_a_margin_around_the_view_inside_the_frame() {
        assert_eq!(around([1000, 1000, 400, 200], (6000, 4000), 8192), [900, 950, 600, 300]);
        assert_eq!(around([0, 3900, 400, 100], (6000, 4000), 8192), [0, 3875, 500, 125]);
        let [x, _, w, _] = around([0, 0, 6000, 4000], (6000, 4000), 4096);
        assert_eq!((x, w), (952, 4096));
        assert!(holds([900, 950, 600, 300], [1000, 1000, 400, 200]));
        assert!(!holds([900, 950, 600, 300], [1000, 1000, 600, 200]));
    }
}
