//! What the app knows: the library (shots, marks, albums), settings, and the work running in the
//! background (scans, imports, the FTP server).

use serde::{Deserialize, Serialize};
use shootback_core::albums::{self, Album, AlbumStore};
use shootback_core::camera::{CameraClient, DEFAULT_DESCRIPTION_URL};
use shootback_core::ftp::FtpServer;
use shootback_core::import::{self, CopiedStore, LibrarySink, Progress};
use shootback_core::library::{self, Index, Photo, Shot, STATE_DIR};
use shootback_core::marks::{Mark, Marks};
use shootback_core::meta::PhotoMeta;
use std::collections::{HashMap, HashSet};
use std::net::UdpSocket;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub library: Option<PathBuf>,
    pub ftp_port: u16,
    pub ftp_on: bool,
    /// Take the camera's originals over Wi-Fi (else its 2 MP copies).
    pub full_size: bool,
    pub theme: ThemeChoice,
    /// This computer, as album members know it: made up once, kept.
    pub device_id: String,
    /// Phones paired for sync.
    pub phones: Vec<shootback_core::sync::Phone>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { library: None, ftp_port: 2121, ftp_on: false, full_size: true, theme: ThemeChoice::System, device_id: new_id(), phones: Vec::new() }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThemeChoice {
    /// Washi or sumi, following the system.
    System,
    Washi,
    Sumi,
    Graphite,
    Hairline,
    Daylight,
    SealDark,
    Mono,
    Amber,
    Swiss,
    Bauhaus,
    Industrial,
    Nordic,
    Material,
    Deco,
    Neo,
    Raw,
    Pixel,
}

pub fn new_id() -> String {
    // Random enough for a device or album id; same shape as the phone's UUIDs.
    let mut seed = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_nanos()) ^ (std::process::id() as u128) << 64;
    let mut next = || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        (seed & 0xffff_ffff) as u32
    };
    let (a, b, c, d) = (next(), next(), next(), next());
    format!("{a:08x}-{:04x}-4{:03x}-{:04x}-{:04x}{c:08x}", b >> 16, b & 0xfff, 0x8000 | (d >> 16) & 0x3fff, d & 0xffff)
}

pub fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

pub fn default_library() -> PathBuf {
    directories::UserDirs::new()
        .and_then(|u| u.picture_dir().map(Path::to_path_buf))
        .or_else(|| directories::UserDirs::new().map(|u| u.home_dir().join("Pictures")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Shootback")
}

#[derive(Clone, Debug, PartialEq)]
pub enum Filter {
    All,
    Review,
    Picks,
    Rejected,
    Album(String),
    /// Shots with someone in them, by `Person::key`.
    Person(String),
}

pub struct Library {
    pub root: PathBuf,
    pub shots: Vec<Shot>,
    pub marks: Marks,
    pub albums: Vec<Album>,
    album_store: AlbumStore,
    scan: Option<Receiver<Vec<Photo>>>,
    pub scanned_once: bool,
    meta: HashMap<PathBuf, PhotoMeta>,
    /// The shots each person is in, by `Person::key` (kept up to date by `Insights`).
    pub people: HashMap<String, HashSet<String>>,
}

impl Library {
    pub fn open(root: PathBuf) -> Library {
        let state = root.join(STATE_DIR);
        let album_store = AlbumStore::new(&state.join("albums.txt"));
        let mut lib = Library {
            marks: Marks::load(&state.join("flags.txt")),
            albums: album_store.load(),
            album_store,
            root,
            shots: Vec::new(),
            scan: None,
            scanned_once: false,
            meta: HashMap::new(),
            people: HashMap::new(),
        };
        lib.rescan();
        lib
    }

    /// Looks through the folder again in the background.
    pub fn rescan(&mut self) {
        let (tx, rx) = channel();
        let root = self.root.clone();
        thread::Builder::new()
            .name("scan".into())
            .spawn(move || {
                let file = root.join(STATE_DIR).join("index.tsv");
                let mut index = Index::load(&file);
                let photos = library::scan(&root, &mut index);
                let _ = index.save(&file);
                let _ = tx.send(photos);
            })
            .expect("scan thread");
        self.scan = Some(rx);
    }

    pub fn scanning(&self) -> bool {
        self.scan.is_some()
    }

    /// Picks up a finished scan; true when the shots changed.
    pub fn poll(&mut self) -> bool {
        let Some(rx) = &self.scan else { return false };
        match rx.try_recv() {
            Ok(photos) => {
                self.shots = library::shots(&photos);
                // A JPEG marked before its renamed RAW was found to be its twin: the shot keeps it.
                for shot in &self.shots {
                    if let (Some(raw), Some(jpeg)) = (&shot.raw, &shot.jpeg) {
                        if raw.stem() != jpeg.stem() && self.marks.get(&raw.key()).is_none() {
                            if let Some(m) = self.marks.get(&jpeg.key()) {
                                let _ = self.marks.set(&raw.key(), Some(m));
                            }
                        }
                    }
                }
                self.scan = None;
                self.scanned_once = true;
                true
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => false,
            Err(_) => {
                self.scan = None;
                false
            }
        }
    }

    /// Reads marks and albums again and rescans: a phone changed them.
    pub fn reload_files(&mut self) {
        let state = self.root.join(STATE_DIR);
        self.marks = Marks::load(&state.join("flags.txt"));
        self.albums = self.album_store.load();
        if !self.scanning() {
            self.rescan();
        }
    }

    pub fn mark(&self, shot: &Shot) -> Option<Mark> {
        self.marks.get(&shot.key())
    }

    pub fn set_mark(&mut self, key: &str, mark: Option<Mark>) {
        let _ = self.marks.set(key, mark);
    }

    /// Sets marks on several shots at once; answers what they were before.
    pub fn set_marks(&mut self, keys: &[String], mark: Option<Mark>) -> Vec<(String, Option<Mark>)> {
        let before = keys.iter().map(|k| (k.clone(), self.marks.get(k))).collect();
        let _ = self.marks.set_many(&keys.iter().map(|k| (k.clone(), mark)).collect::<Vec<_>>());
        before
    }

    pub fn restore_marks(&mut self, marks: &[(String, Option<Mark>)]) {
        let _ = self.marks.set_many(marks);
    }

    /// Puts `keys` into an album, or takes them out; answers the ones that changed.
    pub fn set_in_album(&mut self, id: &str, keys: &[String], inside: bool) -> Vec<String> {
        let Some(a) = self.albums.iter_mut().find(|a| a.id == id) else { return Vec::new() };
        let changed: Vec<String> = keys.iter().filter(|k| a.contains(k) != inside).cloned().collect();
        if !changed.is_empty() {
            *a = if inside { albums::add(a, &changed, now_millis()) } else { albums::remove(a, &changed, now_millis()) };
            self.save_albums();
        }
        changed
    }

    /// Takes `keys` out of every album; answers which albums had which.
    pub fn take_out_of_albums(&mut self, keys: &[String]) -> Vec<(String, Vec<String>)> {
        let ids: Vec<String> = self.alive_albums().map(|a| a.id.clone()).collect();
        ids.into_iter().map(|id| (id.clone(), self.set_in_album(&id, keys, false))).filter(|(_, k)| !k.is_empty()).collect()
    }

    /// Forgets shots whose files are gone, until the next scan.
    pub fn drop_shots(&mut self, keys: &HashSet<String>) {
        self.shots.retain(|s| !keys.contains(&s.key()));
    }

    pub fn alive_albums(&self) -> impl Iterator<Item = &Album> {
        self.albums.iter().filter(|a| a.alive())
    }

    pub fn create_album(&mut self, name: &str, keys: &[String], owner: &str) -> String {
        let a = albums::create(name, keys, now_millis(), new_id(), owner);
        let id = a.id.clone();
        self.albums.push(a);
        self.save_albums();
        id
    }

    pub fn toggle_in_album(&mut self, id: &str, key: &str) {
        if let Some(a) = self.albums.iter_mut().find(|a| a.id == id) {
            let keys = [key.to_string()];
            *a = if a.contains(key) { albums::remove(a, &keys, now_millis()) } else { albums::add(a, &keys, now_millis()) };
        }
        self.save_albums();
    }

    pub fn rename_album(&mut self, id: &str, name: &str) {
        if let Some(a) = self.albums.iter_mut().find(|a| a.id == id) {
            *a = albums::rename(a, name, now_millis());
        }
        self.save_albums();
    }

    pub fn delete_album(&mut self, id: &str) {
        if let Some(a) = self.albums.iter_mut().find(|a| a.id == id) {
            *a = albums::delete(a, now_millis());
        }
        self.save_albums();
    }

    fn save_albums(&self) {
        let _ = self.album_store.save(&self.albums);
    }

    pub fn matches(&self, shot: &Shot, filter: &Filter) -> bool {
        match filter {
            Filter::All => true,
            Filter::Review => self.mark(shot).is_none(),
            Filter::Picks => self.mark(shot) == Some(Mark::Pick),
            Filter::Rejected => self.mark(shot) == Some(Mark::Reject),
            Filter::Album(id) => self.albums.iter().any(|a| &a.id == id && a.contains(&shot.key())),
            Filter::Person(key) => self.people.get(key).is_some_and(|s| s.contains(&shot.key())),
        }
    }

    pub fn count(&self, filter: &Filter) -> usize {
        self.shots.iter().filter(|s| self.matches(s, filter)).count()
    }

    /// EXIF of a file, read once.
    pub fn meta(&mut self, photo: &Photo) -> &PhotoMeta {
        self.meta.entry(photo.path.clone()).or_insert_with(|| library::read_meta(&photo.path))
    }

    /// Name and size of every file, to tell what a card holds that the library doesn't.
    pub fn file_set(&self) -> HashSet<(String, u64)> {
        self.shots.iter().flat_map(|s| [s.raw.as_ref(), s.jpeg.as_ref()]).flatten().map(|p| (p.name.to_lowercase(), p.bytes)).collect()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Source {
    Camera,
    Folder,
}

#[derive(Clone, Debug, Default)]
pub struct ImportStatus {
    pub running: Option<Source>,
    pub message: String,
    pub progress: Progress,
    pub camera_name: Option<String>,
    /// Ended badly (shown in the accent colour).
    pub failed: bool,
}

/// An import running (or last run) in the background.
pub struct Importer {
    pub status: Arc<Mutex<ImportStatus>>,
    cancel: Arc<AtomicBool>,
    /// Set when files arrived, so the library looks again.
    pub arrived: Arc<AtomicBool>,
    pub log: Arc<Mutex<Vec<String>>>,
}

impl Importer {
    pub fn new() -> Importer {
        Importer { status: Arc::default(), cancel: Arc::default(), arrived: Arc::default(), log: Arc::default() }
    }

    pub fn running(&self) -> Option<Source> {
        self.status.lock().unwrap().running
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    fn begin(&self, source: Source, message: &str) -> bool {
        let mut s = self.status.lock().unwrap();
        if s.running.is_some() {
            return false;
        }
        *s = ImportStatus { running: Some(source), message: message.into(), ..Default::default() };
        self.cancel.store(false, Ordering::Relaxed);
        true
    }

    /// Finds the camera on the network this computer is on (its own Wi-Fi) and copies what's new.
    pub fn copy_from_camera(&self, ctx: &eframe::egui::Context, root: PathBuf, full: bool) {
        if !self.begin(Source::Camera, "Looking for the camera ...") {
            return;
        }
        let (status, cancel, arrived, log, ctx) = (self.status.clone(), self.cancel.clone(), self.arrived.clone(), self.log.clone(), ctx.clone());
        thread::spawn(move || {
            let logger = log.clone();
            let mut client = CameraClient::new(move |m| push_log(&logger, m));
            let set = |f: &dyn Fn(&mut ImportStatus)| {
                f(&mut status.lock().unwrap());
                ctx.request_repaint();
            };
            let location = client.discover(Duration::from_secs(2)).ok().flatten();
            if let Err(e) = client.load_description(location.as_deref().unwrap_or(DEFAULT_DESCRIPTION_URL)) {
                push_log(&log, &format!("Camera not reached: {e}"));
                set(&|s| {
                    s.running = None;
                    s.failed = true;
                    s.message = "Camera not found. Join the camera's Wi-Fi (Send to Smartphone on the camera) and try again.".into();
                });
                return;
            }
            let name = client.name.clone();
            set(&|s| {
                s.camera_name = name.clone();
                s.message = "Reading the camera ...".into();
            });
            let copied = CopiedStore::load(&root);
            let result = import::import_from_camera(&client, &root, &copied, full, &cancel, &|p| {
                if !p.saved.is_empty() {
                    arrived.store(true, Ordering::Relaxed);
                }
                let p = p.clone();
                set(&move |s| {
                    s.message = if p.total == 0 { "Reading the camera ...".into() } else { format!("Copied {} of {} new photos", p.done, p.total) };
                    s.progress = p.clone();
                });
            });
            let cancelled = cancel.load(Ordering::Relaxed);
            set(&|s| {
                s.running = None;
                match &result {
                    Ok((on_camera, p)) => {
                        let left = p.total - p.done;
                        s.failed = left > 0 && !cancelled;
                        s.message = if cancelled {
                            format!("Stopped. Copied {} photos.", p.done)
                        } else if p.total == 0 {
                            format!("All {on_camera} photos on the camera are already here")
                        } else if left == 0 {
                            format!("Copied {} new photos", p.done)
                        } else {
                            format!("Copied {} photos, {left} didn't make it. Try again to get the rest.", p.done)
                        };
                    }
                    Err(e) => {
                        s.failed = true;
                        s.message = format!("Couldn't read the camera: {e}");
                    }
                }
            });
            arrived.store(true, Ordering::Relaxed);
        });
    }

    /// Copies what `from` holds that the library doesn't.
    pub fn copy_from_folder(&self, ctx: &eframe::egui::Context, from: PathBuf, root: PathBuf, have: HashSet<(String, u64)>) {
        if !self.begin(Source::Folder, "Looking through the folder ...") {
            return;
        }
        let (status, cancel, arrived, ctx) = (self.status.clone(), self.cancel.clone(), self.arrived.clone(), ctx.clone());
        thread::spawn(move || {
            let p = import::import_from_folder(&from, &root, &have, &cancel, &|p| {
                if !p.saved.is_empty() {
                    arrived.store(true, Ordering::Relaxed);
                }
                let mut s = status.lock().unwrap();
                s.message = format!("Copied {} of {} new photos", p.done, p.total);
                s.progress = p.clone();
                ctx.request_repaint();
            });
            let mut s = status.lock().unwrap();
            s.running = None;
            s.failed = p.failed > 0;
            s.message = match (p.total, p.failed) {
                (0, _) => "Nothing new in that folder".into(),
                (_, 0) => format!("Copied {} new photos", p.done),
                (_, f) => format!("Copied {} photos, {f} couldn't be copied", p.done),
            };
            arrived.store(true, Ordering::Relaxed);
            ctx.request_repaint();
        });
    }
}

pub fn push_log(log: &Mutex<Vec<String>>, m: &str) {
    let mut l = log.lock().unwrap();
    l.push(m.to_string());
    if l.len() > 500 {
        l.drain(..100);
    }
}

/// The FTP inbox: the camera uploads every shot as it's taken.
pub struct FtpInbox {
    pub server: Option<FtpServer>,
    pub error: Option<String>,
    pub received: Arc<AtomicUsize>,
    pub last: Arc<Mutex<Option<String>>>,
}

impl FtpInbox {
    pub fn new() -> FtpInbox {
        FtpInbox { server: None, error: None, received: Arc::default(), last: Arc::default() }
    }

    pub fn start(&mut self, ctx: &eframe::egui::Context, port: u16, root: &Path, arrived: Arc<AtomicBool>, log: Arc<Mutex<Vec<String>>>) {
        self.stop();
        let (received, last, ctx) = (self.received.clone(), self.last.clone(), ctx.clone());
        let sink = Arc::new(LibrarySink { root: root.to_path_buf() });
        match FtpServer::start(
            port,
            sink,
            Arc::new(move |m| push_log(&log, m)),
            Arc::new(move |name, _| {
                received.fetch_add(1, Ordering::Relaxed);
                *last.lock().unwrap() = Some(name.to_string());
                arrived.store(true, Ordering::Relaxed);
                ctx.request_repaint();
            }),
        ) {
            Ok(s) => {
                self.server = Some(s);
                self.error = None;
            }
            Err(e) => self.error = Some(format!("Can't listen on port {port}: {e}")),
        }
    }

    pub fn stop(&mut self) {
        if let Some(s) = self.server.take() {
            s.stop();
        }
    }
}

/// This computer's address on the network it would use to reach others (no packet is sent).
pub fn local_ip() -> Option<String> {
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("192.168.1.1:9").or_else(|_| s.connect("10.0.0.1:9")).ok()?;
    let ip = s.local_addr().ok()?.ip();
    (!ip.is_unspecified()).then(|| ip.to_string())
}

/// Shows a file in the system's file manager (its folder, on Linux).
pub fn reveal(path: &Path) {
    #[cfg(windows)]
    let r = std::process::Command::new("explorer").arg("/select,").arg(path).spawn();
    #[cfg(not(windows))]
    let r = std::process::Command::new("xdg-open").arg(path.parent().unwrap_or(path)).spawn();
    let _ = r;
}

/// Phone sync: the server phones talk to, and what it shares with the app.
pub struct PhoneSync {
    pub shared: Arc<shootback_core::sync::Shared>,
    server: Option<shootback_core::sync::Server>,
    pub error: Option<String>,
}

impl PhoneSync {
    pub fn new(phones: Vec<shootback_core::sync::Phone>) -> PhoneSync {
        let shared = Arc::new(shootback_core::sync::Shared::default());
        *shared.phones.lock().unwrap() = phones;
        PhoneSync { shared, server: None, error: None }
    }

    /// (Re)starts serving `root`.
    pub fn start(&mut self, root: &Path, device_id: &str) {
        self.server = None;
        match shootback_core::sync::Server::start(root, device_id, &shootback_core::sync::computer_name(), self.shared.clone()) {
            Ok(s) => {
                self.server = Some(s);
                self.error = None;
            }
            Err(e) => self.error = Some(e.to_string()),
        }
    }

    pub fn stop(&mut self) {
        self.server = None;
    }

    pub fn port(&self) -> Option<u16> {
        self.server.as_ref().map(|s| s.port)
    }

    /// What a phone is doing, if it did something in the last few seconds.
    pub fn activity(&self) -> Option<String> {
        let a = self.shared.activity.lock().unwrap();
        a.as_ref().filter(|(_, at)| at.elapsed() < Duration::from_secs(8)).map(|(t, _)| t.clone())
    }
}
