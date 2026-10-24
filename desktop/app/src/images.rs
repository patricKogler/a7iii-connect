//! Photo pictures for the screen: grid thumbnails and the big view. A RAW shows the camera's own
//! JPEG inside it; JPEGs are decoded at a reduced DCT scale, which is fast even at 24 MP. Decoding
//! runs on worker threads, newest request first, so whatever just scrolled into view comes first.

use eframe::egui::{self, ColorImage, TextureHandle, TextureOptions};
use image::{imageops, RgbaImage};
use shootback_core::library::is_raw_name;
use shootback_core::tiff;
use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::io::{BufReader, Cursor};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;

/// Longer edge of a grid thumbnail and of the big view, in pixels.
pub const THUMB: u32 = 384;
pub const LARGE: u32 = 2048;

type Key = (PathBuf, u32);

enum Slot {
    Loading,
    Ready(TextureHandle, u64),
    Failed,
}

struct Job {
    path: PathBuf,
    edge: u32,
    orientation: u8,
}

struct Queue {
    jobs: Mutex<VecDeque<Job>>,
    ready: Condvar,
}

pub struct Images {
    slots: HashMap<Key, Slot>,
    queue: Arc<Queue>,
    done: Receiver<(Key, Option<ColorImage>)>,
    frame: u64,
}

impl Images {
    pub fn new(ctx: &egui::Context) -> Images {
        let queue = Arc::new(Queue { jobs: Mutex::new(VecDeque::new()), ready: Condvar::new() });
        let (tx, done) = channel();
        let workers = thread::available_parallelism().map_or(2, |n| n.get()).clamp(2, 6);
        for i in 0..workers {
            let (queue, tx, ctx): (Arc<Queue>, Sender<_>, _) = (queue.clone(), tx.clone(), ctx.clone());
            thread::Builder::new()
                .name(format!("images-{i}"))
                .spawn(move || loop {
                    let job = {
                        let mut jobs = queue.jobs.lock().unwrap();
                        loop {
                            if let Some(j) = jobs.pop_back() {
                                break j;
                            }
                            jobs = queue.ready.wait(jobs).unwrap();
                        }
                    };
                    let img = decode(&job.path, job.edge, job.orientation);
                    if tx.send(((job.path, job.edge), img)).is_err() {
                        return;
                    }
                    ctx.request_repaint();
                })
                .expect("image worker");
        }
        Images { slots: HashMap::new(), queue, done, frame: 0 }
    }

    /// Picks up finished pictures and drops the least recently shown ones (call once a frame).
    pub fn poll(&mut self, ctx: &egui::Context) {
        self.frame += 1;
        while let Ok((key, img)) = self.done.try_recv() {
            let slot = match img {
                Some(img) => Slot::Ready(ctx.load_texture(key.0.to_string_lossy(), img, TextureOptions::LINEAR), self.frame),
                None => Slot::Failed,
            };
            self.slots.insert(key, slot);
        }
        // Thumbnails for about a thousand tiles; big views for a handful.
        for (edge, cap) in [(THUMB, 1200), (LARGE, 6)] {
            let mut ready: Vec<(u64, Key)> = self
                .slots
                .iter()
                .filter_map(|(k, s)| match s {
                    Slot::Ready(_, used) if k.1 == edge => Some((*used, k.clone())),
                    _ => None,
                })
                .collect();
            if ready.len() > cap {
                ready.sort();
                // Drop down to three quarters, so this doesn't run every frame at the limit.
                let excess = ready.len() - cap * 3 / 4;
                for (_, k) in ready.into_iter().take(excess) {
                    self.slots.remove(&k);
                }
            }
        }
        // Requests nobody looked at again this frame are old: whatever scrolled past.
        let mut jobs = self.queue.jobs.lock().unwrap();
        while jobs.len() > 256 {
            if let Some(j) = jobs.pop_front() {
                self.slots.remove(&(j.path, j.edge));
            }
        }
    }

    /// The picture if it's ready; otherwise asks for it and returns None.
    pub fn get(&mut self, path: &Path, edge: u32, orientation: u8) -> Option<TextureHandle> {
        let key = (path.to_path_buf(), edge);
        match self.slots.get_mut(&key) {
            Some(Slot::Ready(t, used)) => {
                *used = self.frame;
                return Some(t.clone());
            }
            Some(Slot::Loading) | Some(Slot::Failed) => return None,
            None => {}
        }
        self.slots.insert(key, Slot::Loading);
        self.queue.jobs.lock().unwrap().push_back(Job { path: path.to_path_buf(), edge, orientation });
        self.queue.ready.notify_one();
        None
    }

    /// A ready picture at another size, to show while the asked-for one loads.
    pub fn any(&self, path: &Path) -> Option<TextureHandle> {
        [LARGE, THUMB].into_iter().find_map(|e| match self.slots.get(&(path.to_path_buf(), e)) {
            Some(Slot::Ready(t, _)) => Some(t.clone()),
            _ => None,
        })
    }

    pub fn failed(&self, path: &Path, edge: u32) -> bool {
        matches!(self.slots.get(&(path.to_path_buf(), edge)), Some(Slot::Failed))
    }
}

/// Upright picture, longer edge at most `edge`.
pub fn decode(path: &Path, edge: u32, orientation: u8) -> Option<ColorImage> {
    let (bytes, orientation) = if is_raw_name(&path.to_string_lossy()) {
        let preview = tiff::raw_preview(BufReader::new(File::open(path).ok()?)).ok()?;
        let (off, len) = preview.jpeg?;
        let mut t = tiff::Tiff::open(BufReader::new(File::open(path).ok()?)).ok()?;
        (t.read_at(off, len).ok()?, preview.orientation)
    } else {
        (std::fs::read(path).ok()?, orientation)
    };
    let mut d = jpeg_decoder::Decoder::new(Cursor::new(bytes));
    d.read_info().ok()?;
    let info = d.info()?;
    let long = info.width.max(info.height) as u32;
    if long > edge {
        let k = edge as f32 / long as f32;
        d.scale((info.width as f32 * k).ceil() as u16, (info.height as f32 * k).ceil() as u16).ok()?;
    }
    let px = d.decode().ok()?;
    let info = d.info()?;
    let (w, h) = (info.width as u32, info.height as u32);
    let rgba: Vec<u8> = match info.pixel_format {
        jpeg_decoder::PixelFormat::RGB24 => px.as_chunks::<3>().0.iter().flat_map(|c| [c[0], c[1], c[2], 255]).collect(),
        jpeg_decoder::PixelFormat::L8 => px.iter().flat_map(|&l| [l, l, l, 255]).collect(),
        _ => return None,
    };
    let mut img = RgbaImage::from_raw(w, h, rgba)?;
    if w.max(h) > edge {
        let k = edge as f32 / w.max(h) as f32;
        img = imageops::resize(&img, ((w as f32 * k) as u32).max(1), ((h as f32 * k) as u32).max(1), imageops::FilterType::Triangle);
    }
    let img = match orientation {
        2 => imageops::flip_horizontal(&img),
        3 => imageops::rotate180(&img),
        4 => imageops::flip_vertical(&img),
        5 => imageops::flip_horizontal(&imageops::rotate90(&img)),
        6 => imageops::rotate90(&img),
        7 => imageops::flip_horizontal(&imageops::rotate270(&img)),
        8 => imageops::rotate270(&img),
        _ => img,
    };
    let size = [img.width() as usize, img.height() as usize];
    Some(ColorImage::from_rgba_unmultiplied(size, img.as_raw()))
}
