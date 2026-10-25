//! Sync with the phone app over the local network. The computer serves its library over HTTP
//! (found by the phone through mDNS, `_shootback._tcp`, or the address in the pairing QR code);
//! the phone drives each sync: it reads the computer's state, merges it with its own and sends
//! back what changed. Every request carries the secret the phone got from the QR code.
//!
//! Endpoints (all under `/v1`, `Authorization: Bearer <secret>`):
//! - `GET hello`: who this computer is. The first call with a new pairing's secret completes it.
//! - `GET state`: photos (key, name, time, size), marks, edits (text and time), albums, presets
//!   (text and time, by name) and the LUTs (id, name, folder). Phones from before presets and
//!   LUTs synced just don't read those.
//! - `GET photo?key=`: a photo's file. `PUT photo?name=&taken=`: a photo for the library.
//! - `POST marks`: `key<TAB>PICK|REJECT|-` lines to apply (`-` clears).
//! - `PUT edit?key=`: an edit's text (empty clears), its time in `X-Modified`.
//! - `POST albums`: the phone's albums; answers the merged ones.
//! - `PUT preset?name=`: a preset's text (empty deletes it), its time in `X-Modified`.
//! - `GET lut?id=`: a LUT in its stored form. `PUT lut?id=&name=&folder=`: one for the library
//!   (empty deletes it).
//! - `GET lut-thumb?id=` / `PUT lut-thumb?id=`: a LUT's preview picture, when it has one.
//! - `POST people`: the phone's remembered people (its people.txt); answers them put together with
//!   this library's. `state` says `people: true` when this endpoint is there.
//! - `POST done`: the sync finished.

use crate::albums::{self, AlbumStore};
use crate::develop::{file_name, luts, presets};
use crate::import::{day_folder, PartFile};
use crate::library::{self, write_atomic, Index, Photo, MATCH_WINDOW_MS, STATE_DIR};
use crate::marks::{self, Mark};
use crate::people;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The port the computer listens on (the next free one above it if taken).
pub const PORT: u16 = 47820;
pub const SERVICE: &str = "_shootback._tcp.local.";
pub const PROTOCOL: u32 = 1;

/// A phone allowed to sync.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Phone {
    pub id: String,
    pub name: String,
    pub secret: String,
    /// When its last sync finished (ms), 0 for never.
    #[serde(default)]
    pub last_sync: i64,
}

/// What the server and the app share.
#[derive(Default)]
pub struct Shared {
    pub phones: Mutex<Vec<Phone>>,
    /// The secret in the pairing code on screen, until a phone uses it.
    pub pending: Mutex<Option<String>>,
    /// The library's files changed (marks, albums, edits or photos): the app reloads them.
    pub changed: AtomicBool,
    /// The paired phones changed (a new one, a name, a sync time): the app saves them.
    pub phones_changed: AtomicBool,
    /// What a phone is doing right now and since when, for the status line.
    pub activity: Mutex<Option<(String, std::time::Instant)>>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct PhotoInfo {
    pub key: String,
    pub name: String,
    pub taken: i64,
    pub size: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct EditInfo {
    pub text: String,
    pub modified: i64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct State {
    pub id: String,
    pub name: String,
    pub photos: Vec<PhotoInfo>,
    pub marks: BTreeMap<String, String>,
    pub edits: BTreeMap<String, EditInfo>,
    pub albums: String,
    #[serde(default)]
    pub presets: BTreeMap<String, EditInfo>,
    #[serde(default)]
    pub luts: Vec<LutInfo>,
    #[serde(default)]
    pub people: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct LutInfo {
    pub id: String,
    pub name: String,
    pub folder: String,
    pub thumbnail: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct Hello {
    pub id: String,
    pub name: String,
    pub protocol: u32,
}

/// A new pairing secret: 32 random bytes as hex.
pub fn new_secret() -> String {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b).expect("system randomness");
    b.iter().map(|v| format!("{v:02x}")).collect()
}

/// What the pairing QR code holds.
pub fn pairing_uri(id: &str, name: &str, host: &str, port: u16, secret: &str) -> String {
    format!("shootback://pair?v={PROTOCOL}&id={}&name={}&host={}&port={port}&key={secret}", encode(id), encode(name), encode(host))
}

fn encode(s: &str) -> String {
    s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") }).collect()
}

fn decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => match u8::from_str_radix(std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or(""), 16) {
                Ok(v) => {
                    out.push(v);
                    i += 3;
                    continue;
                }
                Err(_) => out.push(b'%'),
            },
            b'+' => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn query(url: &str) -> (String, BTreeMap<String, String>) {
    let (path, q) = url.split_once('?').unwrap_or((url, ""));
    let params = q.split('&').filter_map(|kv| kv.split_once('=')).map(|(k, v)| (decode(k), decode(v))).collect();
    (path.to_string(), params)
}

fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// Compares secrets without leaking where they differ through timing.
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// One answer: status, content type and body.
pub enum Body {
    Bytes(Vec<u8>),
    File(fs::File, u64),
}

pub struct Reply {
    pub status: u16,
    pub kind: &'static str,
    pub body: Body,
}

impl Reply {
    fn json<T: Serialize>(v: &T) -> Reply {
        Reply { status: 200, kind: "application/json", body: Body::Bytes(serde_json::to_vec(v).unwrap_or_default()) }
    }

    fn text(status: u16, t: impl Into<String>) -> Reply {
        Reply { status, kind: "text/plain; charset=utf-8", body: Body::Bytes(t.into().into_bytes()) }
    }
}

/// The library side of the protocol, separate from HTTP so it can be tested directly.
pub struct Library {
    root: PathBuf,
    id: String,
    name: String,
    shared: Arc<Shared>,
    index: Index,
    photos: Vec<Photo>,
}

impl Library {
    pub fn new(root: &Path, id: &str, name: &str, shared: Arc<Shared>) -> Library {
        let index = Index::load(&root.join(STATE_DIR).join("index.tsv"));
        Library { root: root.to_path_buf(), id: id.into(), name: name.into(), shared, index, photos: Vec::new() }
    }

    fn state_dir(&self) -> PathBuf {
        self.root.join(STATE_DIR)
    }

    fn edit_file(&self, key: &str) -> PathBuf {
        self.state_dir().join("edits").join(file_name(key) + ".txt")
    }

    /// Which phone a request comes from, by its secret; a pending pairing's secret pairs it.
    fn phone(&self, auth: Option<&str>, phone_id: &str, phone_name: &str) -> Option<Phone> {
        let secret = auth?.strip_prefix("Bearer ")?.trim();
        let mut phones = self.shared.phones.lock().unwrap();
        if let Some(p) = phones.iter_mut().find(|p| same(&p.secret, secret)) {
            if !phone_name.is_empty() && p.name != phone_name {
                p.name = phone_name.to_string();
                self.shared.phones_changed.store(true, Ordering::Relaxed);
            }
            return Some(p.clone());
        }
        let mut pending = self.shared.pending.lock().unwrap();
        if pending.as_deref().is_some_and(|s| same(s, secret)) && !phone_id.is_empty() {
            *pending = None;
            phones.retain(|p| p.id != phone_id);
            let p = Phone {
                id: phone_id.into(),
                name: if phone_name.is_empty() { "Phone".into() } else { phone_name.into() },
                secret: secret.into(),
                last_sync: 0,
            };
            phones.push(p.clone());
            self.shared.phones_changed.store(true, Ordering::Relaxed);
            return Some(p);
        }
        None
    }

    fn rescan(&mut self) {
        self.photos = library::scan(&self.root, &mut self.index);
    }

    pub fn state(&mut self) -> State {
        self.rescan();
        let photos: Vec<PhotoInfo> =
            self.photos.iter().map(|p| PhotoInfo { key: p.key(), name: p.name.clone(), taken: p.taken_millis, size: p.bytes }).collect();
        let marks = fs::read_to_string(self.state_dir().join("flags.txt")).map(|t| marks::decode(&t)).unwrap_or_default();
        let mut edits = BTreeMap::new();
        for p in &photos {
            let f = self.edit_file(&p.key);
            if let Ok(text) = fs::read_to_string(&f) {
                edits.insert(p.key.clone(), EditInfo { text, modified: modified(&f) });
            }
        }
        State {
            id: self.id.clone(),
            name: self.name.clone(),
            photos,
            marks: marks.into_iter().map(|(k, v)| (k, v.as_str().to_string())).collect(),
            edits,
            albums: fs::read_to_string(self.state_dir().join("albums.txt")).unwrap_or_default(),
            presets: presets::files(&self.root)
                .into_iter()
                .map(|(name, f)| (name, EditInfo { text: fs::read_to_string(&f).unwrap_or_default(), modified: modified(&f) }))
                .collect(),
            luts: luts::list(&self.root)
                .into_iter()
                .map(|e| LutInfo { thumbnail: luts::thumbnail(&self.root, &e.id).is_some(), id: e.id, name: e.name, folder: e.folder })
                .collect(),
            people: true,
        }
    }

    fn find(&self, key: &str) -> Option<&Photo> {
        self.photos.iter().find(|p| p.key() == key)
    }

    /// Takes in a photo unless the library has it (same name, nearly the same time, same size).
    pub fn receive(&mut self, name: &str, taken: i64, body: &mut dyn Read) -> io::Result<bool> {
        let safe: String = name.chars().filter(|c| !matches!(c, '/' | '\\' | ':' | '\0')).collect();
        if safe.is_empty() || safe.starts_with('.') || !library::is_photo_name(&safe) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "not a photo name"));
        }
        if self.photos.is_empty() {
            self.rescan();
        }
        let day = chrono::DateTime::from_timestamp_millis(taken).map(|t| t.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string());
        let mut part = PartFile::create(&self.root.join(day_folder(day.as_deref())), &safe)?;
        let n = match io::copy(body, &mut part) {
            Ok(n) => n,
            Err(e) => {
                part.abort();
                return Err(e);
            }
        };
        let have = self.photos.iter().any(|p| p.name.eq_ignore_ascii_case(&safe) && (p.taken_millis - taken).abs() <= MATCH_WINDOW_MS && p.bytes == n);
        if have {
            part.abort();
            return Ok(false);
        }
        let path = part.finish()?;
        // Without EXIF the library goes by the file's time: make it the capture time.
        if taken > 0 {
            let t = UNIX_EPOCH + Duration::from_millis(taken as u64);
            let _ = fs::File::options().write(true).open(&path).and_then(|h| h.set_modified(t));
        }
        if let Some(p) = self.index.photo(&path) {
            self.photos.push(p);
        }
        self.shared.changed.store(true, Ordering::Relaxed);
        Ok(true)
    }

    pub fn apply_marks(&mut self, lines: &str) -> io::Result<()> {
        let file = self.state_dir().join("flags.txt");
        let mut map = fs::read_to_string(&file).map(|t| marks::decode(&t)).unwrap_or_default();
        for l in lines.lines() {
            let Some((k, v)) = l.split_once('\t') else { continue };
            match Mark::parse(v) {
                Some(m) => map.insert(k.to_string(), m),
                None => map.remove(k),
            };
        }
        fs::create_dir_all(self.state_dir())?;
        write_atomic(&file, marks::encode(&map).as_bytes())?;
        self.shared.changed.store(true, Ordering::Relaxed);
        Ok(())
    }

    pub fn put_edit(&mut self, key: &str, text: &str, modified_ms: Option<i64>) -> io::Result<()> {
        let f = self.edit_file(key);
        if text.trim().is_empty() {
            match fs::remove_file(&f) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
                _ => {}
            }
        } else {
            fs::create_dir_all(f.parent().unwrap())?;
            write_atomic(&f, text.as_bytes())?;
            if let Some(ms) = modified_ms.filter(|m| *m > 0) {
                let t = UNIX_EPOCH + Duration::from_millis(ms as u64);
                let _ = fs::File::options().write(true).open(&f).and_then(|h| h.set_modified(t));
            }
        }
        self.shared.changed.store(true, Ordering::Relaxed);
        Ok(())
    }

    /// Puts the phone's albums together with these (the phone's shots under this library's keys)
    /// and answers the result.
    pub fn merge_albums(&mut self, theirs: &str) -> io::Result<String> {
        if self.photos.is_empty() {
            self.rescan();
        }
        let store = AlbumStore::new(&self.state_dir().join("albums.txt"));
        let local: Vec<String> = self.photos.iter().map(Photo::key).collect();
        let theirs = albums::rekey(&albums::decode(theirs), albums::resolver(&local));
        let mine = store.load();
        let merged = albums::merge(&mine, &theirs);
        if merged != mine {
            fs::create_dir_all(self.state_dir())?;
            store.save(&merged)?;
            self.shared.changed.store(true, Ordering::Relaxed);
        }
        Ok(albums::encode(&merged))
    }

    /// Puts the phone's remembered people together with these and answers the result.
    pub fn merge_people(&mut self, theirs: &str) -> io::Result<String> {
        let f = self.state_dir().join("people.txt");
        let mine = people::decode(&fs::read_to_string(&f).unwrap_or_default());
        let merged = people::merge(&mine, &people::decode(theirs));
        if merged != mine {
            fs::create_dir_all(self.state_dir())?;
            crate::library::write_atomic(&f, people::encode(&merged).as_bytes())?;
            self.shared.changed.store(true, Ordering::Relaxed);
        }
        Ok(people::encode(&merged))
    }

    fn done(&mut self, phone: &Phone) {
        let mut phones = self.shared.phones.lock().unwrap();
        if let Some(p) = phones.iter_mut().find(|p| p.id == phone.id) {
            p.last_sync = now_millis();
            self.shared.phones_changed.store(true, Ordering::Relaxed);
        }
    }

    fn activity(&self, text: String) {
        *self.shared.activity.lock().unwrap() = (!text.is_empty()).then(|| (text, std::time::Instant::now()));
    }

    /// Answers one request. `header` looks up a request header.
    pub fn handle(&mut self, method: &str, url: &str, header: &dyn Fn(&str) -> Option<String>, body: &mut dyn Read) -> Reply {
        let (path, q) = query(url);
        let phone_id = header("X-Shootback-Phone").unwrap_or_default();
        let phone_name = header("X-Shootback-Name").map(|n| decode(&n)).unwrap_or_default();
        let Some(phone) = self.phone(header("Authorization").as_deref(), &phone_id, &phone_name) else {
            return Reply::text(401, "not paired");
        };
        let mut text = || {
            let mut s = String::new();
            body.take(64 << 20).read_to_string(&mut s).map(|_| s)
        };
        let fail = |e: io::Error| Reply::text(500, e.to_string());
        match (method, path.as_str()) {
            ("GET", "/v1/hello") => Reply::json(&Hello { id: self.id.clone(), name: self.name.clone(), protocol: PROTOCOL }),
            ("GET", "/v1/state") => {
                self.activity(format!("Syncing with {}", phone.name));
                Reply::json(&self.state())
            }
            ("GET", "/v1/photo") => {
                let Some(p) = q.get("key").and_then(|k| self.find(k)) else { return Reply::text(404, "no such photo") };
                self.activity(format!("Sending {} to {}", p.name, phone.name));
                match fs::File::open(&p.path) {
                    Ok(f) => {
                        let len = f.metadata().map_or(0, |m| m.len());
                        Reply { status: 200, kind: "application/octet-stream", body: Body::File(f, len) }
                    }
                    Err(e) => fail(e),
                }
            }
            ("PUT", "/v1/photo") => {
                let (Some(name), Some(taken)) = (q.get("name"), q.get("taken").and_then(|t| t.parse().ok())) else {
                    return Reply::text(400, "name and taken needed");
                };
                self.activity(format!("Receiving {name} from {}", phone.name));
                match self.receive(name, taken, body) {
                    Ok(true) => Reply::text(201, "saved"),
                    Ok(false) => Reply::text(200, "already here"),
                    Err(e) => fail(e),
                }
            }
            ("POST", "/v1/marks") => match text().and_then(|t| self.apply_marks(&t)) {
                Ok(()) => Reply::text(200, "ok"),
                Err(e) => fail(e),
            },
            ("PUT", "/v1/edit") => {
                let Some(key) = q.get("key").cloned() else { return Reply::text(400, "key needed") };
                let modified = header("X-Modified").and_then(|m| m.parse().ok());
                match text().and_then(|t| self.put_edit(&key, &t, modified)) {
                    Ok(()) => Reply::text(200, "ok"),
                    Err(e) => fail(e),
                }
            }
            ("PUT", "/v1/preset") => {
                let Some(name) = q.get("name").cloned() else { return Reply::text(400, "name needed") };
                let modified = header("X-Modified").and_then(|m| m.parse().ok());
                match text().and_then(|t| presets::put(&self.root, &name, &t, modified)) {
                    Ok(()) => {
                        self.shared.changed.store(true, Ordering::Relaxed);
                        Reply::text(200, "ok")
                    }
                    Err(e) => fail(e),
                }
            }
            ("GET", "/v1/lut") | ("GET", "/v1/lut-thumb") => {
                let Some(id) = q.get("id") else { return Reply::text(400, "id needed") };
                let f = if path == "/v1/lut" { Some(luts::file(&self.root, id)) } else { luts::thumbnail(&self.root, id) };
                match f.map(fs::read) {
                    Some(Ok(b)) => Reply { status: 200, kind: "application/octet-stream", body: Body::Bytes(b) },
                    _ => Reply::text(404, "no such LUT"),
                }
            }
            ("PUT", "/v1/lut") | ("PUT", "/v1/lut-thumb") => {
                let Some(id) = q.get("id").cloned() else { return Reply::text(400, "id needed") };
                let mut bytes = Vec::new();
                if let Err(e) = body.take(16 << 20).read_to_end(&mut bytes) {
                    return fail(e);
                }
                let r = if path == "/v1/lut" && bytes.is_empty() {
                    luts::delete(&self.root, &[id])
                } else if path == "/v1/lut" {
                    self.activity(format!("Receiving LUTs from {}", phone.name));
                    let e = luts::Entry {
                        name: q.get("name").cloned().unwrap_or_else(|| id.clone()),
                        folder: q.get("folder").cloned().unwrap_or_else(|| "Phone".into()),
                        id,
                    };
                    luts::store_raw(&self.root, &e, &bytes, None)
                } else {
                    luts::put_thumbnail(&self.root, &id, &bytes)
                };
                match r {
                    Ok(()) => {
                        self.shared.changed.store(true, Ordering::Relaxed);
                        Reply::text(200, "ok")
                    }
                    Err(e) => fail(e),
                }
            }
            ("POST", "/v1/albums") => match text().and_then(|t| self.merge_albums(&t)) {
                Ok(merged) => Reply::text(200, merged),
                Err(e) => fail(e),
            },
            ("POST", "/v1/people") => match text().and_then(|t| self.merge_people(&t)) {
                Ok(merged) => Reply::text(200, merged),
                Err(e) => fail(e),
            },
            ("POST", "/v1/done") => {
                self.done(&phone);
                self.activity(String::new());
                Reply::text(200, "ok")
            }
            _ => Reply::text(404, "unknown"),
        }
    }
}

fn modified(f: &Path) -> i64 {
    fs::metadata(f).and_then(|m| m.modified()).ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_millis() as i64)
}

/// The HTTP server and its mDNS announcement, running until dropped.
pub struct Server {
    pub port: u16,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    mdns: Option<mdns_sd::ServiceDaemon>,
}

impl Server {
    pub fn start(root: &Path, id: &str, name: &str, shared: Arc<Shared>) -> io::Result<Server> {
        let (http, port) = (PORT..PORT + 20)
            .find_map(|p| tiny_http::Server::http(("0.0.0.0", p)).ok().map(|s| (s, p)))
            .ok_or_else(|| io::Error::new(io::ErrorKind::AddrInUse, "no free port for phone sync"))?;
        let stop = Arc::new(AtomicBool::new(false));
        let mut lib = Library::new(root, id, name, shared);
        let s = stop.clone();
        let thread = thread::Builder::new().name("phone-sync".into()).spawn(move || {
            while !s.load(Ordering::Relaxed) {
                let Ok(Some(mut req)) = http.recv_timeout(Duration::from_millis(250)) else { continue };
                let headers: Vec<(String, String)> = req.headers().iter().map(|h| (h.field.to_string(), h.value.to_string())).collect();
                let header = |n: &str| headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(n)).map(|(_, v)| v.clone());
                let (method, url) = (req.method().to_string(), req.url().to_string());
                let reply = lib.handle(&method, &url, &header, req.as_reader());
                let ct = tiny_http::Header::from_bytes("Content-Type", reply.kind).unwrap();
                let _ = match reply.body {
                    Body::Bytes(b) => req.respond(tiny_http::Response::from_data(b).with_status_code(reply.status).with_header(ct)),
                    Body::File(f, len) => req.respond(tiny_http::Response::new(reply.status.into(), vec![ct], f, Some(len as usize), None)),
                };
            }
        })?;
        let mdns = announce(id, name, port).ok();
        Ok(Server { port, stop, thread: Some(thread), mdns })
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(m) = self.mdns.take() {
            let _ = m.shutdown();
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

fn announce(id: &str, name: &str, port: u16) -> Result<mdns_sd::ServiceDaemon, mdns_sd::Error> {
    let daemon = mdns_sd::ServiceDaemon::new()?;
    let host: String = name.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
    let host = if host.is_empty() { "shootback".to_string() } else { host };
    let info = mdns_sd::ServiceInfo::new(SERVICE, name, &format!("{host}.local."), "", port, &[("id", id), ("v", "1")][..])?.enable_addr_auto();
    daemon.register(info)?;
    Ok(daemon)
}

/// This computer's name, as the phone shows it.
pub fn computer_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .or_else(|| fs::read_to_string("/etc/hostname").ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Computer".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lib() -> (tempfile::TempDir, Library, Arc<Shared>) {
        let dir = tempfile::tempdir().unwrap();
        let shared = Arc::new(Shared::default());
        *shared.pending.lock().unwrap() = Some("s3cret".into());
        let lib = Library::new(dir.path(), "pc-1", "Laptop", shared.clone());
        (dir, lib, shared)
    }

    fn call(lib: &mut Library, method: &str, url: &str, secret: &str, body: &[u8]) -> (u16, Vec<u8>) {
        let auth = format!("Bearer {secret}");
        let header = |n: &str| match n {
            "Authorization" => Some(auth.clone()),
            "X-Shootback-Phone" => Some("phone-1".into()),
            "X-Shootback-Name" => Some("Pixel%208a".into()),
            "X-Modified" => Some("1700000000000".into()),
            _ => None,
        };
        let r = lib.handle(method, url, &header, &mut &body[..]);
        let b = match r.body {
            Body::Bytes(b) => b,
            Body::File(mut f, _) => {
                let mut v = Vec::new();
                f.read_to_end(&mut v).unwrap();
                v
            }
        };
        (r.status, b)
    }

    #[test]
    fn pairing_takes_the_code_once() {
        let (_d, mut lib, shared) = lib();
        assert_eq!(call(&mut lib, "GET", "/v1/hello", "wrong", b"").0, 401);
        assert_eq!(call(&mut lib, "GET", "/v1/hello", "s3cret", b"").0, 200);
        let phones = shared.phones.lock().unwrap().clone();
        assert_eq!(phones.len(), 1);
        assert_eq!((phones[0].id.as_str(), phones[0].name.as_str()), ("phone-1", "Pixel 8a"));
        assert!(shared.pending.lock().unwrap().is_none());
        // Still works as the paired phone's secret.
        assert_eq!(call(&mut lib, "GET", "/v1/hello", "s3cret", b"").0, 200);
    }

    #[test]
    fn photos_marks_edits_and_albums_round_trip() {
        let (dir, mut lib, shared) = lib();
        let (s, _) = call(&mut lib, "PUT", "/v1/photo?name=DSC00001.JPG&taken=1700000000000", "s3cret", b"jpegbytes");
        assert_eq!(s, 201);
        assert!(shared.changed.swap(false, Ordering::Relaxed));
        // The same again is recognised.
        assert_eq!(call(&mut lib, "PUT", "/v1/photo?name=DSC00001.JPG&taken=1700000000000", "s3cret", b"jpegbytes").0, 200);

        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        let st: State = serde_json::from_slice(&body).unwrap();
        assert_eq!(st.photos.len(), 1);
        let key = st.photos[0].key.clone();
        assert_eq!(call(&mut lib, "GET", &format!("/v1/photo?key={}", encode(&key)), "s3cret", b"").1, b"jpegbytes");

        call(&mut lib, "POST", "/v1/marks", "s3cret", format!("{key}\tPICK\n").as_bytes());
        call(&mut lib, "PUT", &format!("/v1/edit?key={}", encode(&key)), "s3cret", b"v=2\nprofile=COLOR\nexposure=0.5\n");
        let albums = format!("A\tal-1\t1\t1\t0\tTrip\t0\tphone-1\nS\tal-1\t5\t+\t{key}\n");
        let (_, merged) = call(&mut lib, "POST", "/v1/albums", "s3cret", albums.as_bytes());
        assert!(String::from_utf8(merged).unwrap().contains("Trip"));

        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        let st: State = serde_json::from_slice(&body).unwrap();
        assert_eq!(st.marks.get(&key).map(String::as_str), Some("PICK"));
        let e = &st.edits[&key];
        assert!(e.text.contains("exposure=0.5"));
        assert_eq!(e.modified, 1_700_000_000_000);
        assert!(st.albums.contains("al-1"));
        assert!(dir.path().join(STATE_DIR).join("albums.txt").exists());

        call(&mut lib, "POST", "/v1/marks", "s3cret", format!("{key}\t-\n").as_bytes());
        call(&mut lib, "PUT", &format!("/v1/edit?key={}", encode(&key)), "s3cret", b"");
        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        let st: State = serde_json::from_slice(&body).unwrap();
        assert!(st.marks.is_empty() && st.edits.is_empty());

        call(&mut lib, "POST", "/v1/done", "s3cret", b"");
        assert!(shared.phones.lock().unwrap()[0].last_sync > 0);
    }

    #[test]
    fn people_come_together_with_the_phones() {
        let (d, mut lib, _) = call_ready();
        let look = people::encode(&people::Remembered {
            people: vec![people::Anchor { id: "p1".into(), name: Some("Ann".into()), centroid: vec![1.0, 0.0, 0.0] }],
            next: 2,
        });
        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        assert!(serde_json::from_slice::<State>(&body).unwrap().people);
        let (s, merged) = call(&mut lib, "POST", "/v1/people", "s3cret", look.as_bytes());
        assert_eq!(s, 200);
        assert_eq!(people::decode(std::str::from_utf8(&merged).unwrap()).people[0].name.as_deref(), Some("Ann"));
        assert!(d.path().join(STATE_DIR).join("people.txt").exists());
    }

    #[test]
    fn presets_and_luts_round_trip() {
        let (_d, mut lib, _) = call_ready();
        let preset = "groups=LIGHT\nv=2\nprofile=COLOR\ncontrast=20\n";
        assert_eq!(call(&mut lib, "PUT", "/v1/preset?name=Punchy%2Fmine", "s3cret", preset.as_bytes()).0, 200);
        let lut = crate::develop::lut::Lut::identity(3).write_sbl();
        assert_eq!(call(&mut lib, "PUT", "/v1/lut?id=kodak-gold&name=Kodak%20Gold&folder=Kodak", "s3cret", &lut).0, 200);
        assert_eq!(call(&mut lib, "PUT", "/v1/lut-thumb?id=kodak-gold", "s3cret", b"jpg").0, 200);
        assert_eq!(call(&mut lib, "PUT", "/v1/lut-thumb?id=nope", "s3cret", b"jpg").0, 500);
        assert_eq!(call(&mut lib, "PUT", "/v1/lut?id=bad", "s3cret", b"not a lut").0, 500);
        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        let st: State = serde_json::from_slice(&body).unwrap();
        assert_eq!(st.presets.keys().collect::<Vec<_>>(), vec!["Punchymine"]);
        assert_eq!(st.presets["Punchymine"], EditInfo { text: preset.into(), modified: 1700000000000 });
        assert_eq!(st.luts, vec![LutInfo { id: "kodak-gold".into(), name: "Kodak Gold".into(), folder: "Kodak".into(), thumbnail: true }]);
        assert_eq!(call(&mut lib, "GET", "/v1/lut?id=kodak-gold", "s3cret", b"").1, lut);
        assert_eq!(call(&mut lib, "GET", "/v1/lut-thumb?id=kodak-gold", "s3cret", b"").1, b"jpg");
        assert_eq!(call(&mut lib, "GET", "/v1/lut?id=other", "s3cret", b"").0, 404);
        call(&mut lib, "PUT", "/v1/lut?id=kodak-gold", "s3cret", b"");
        assert_eq!(call(&mut lib, "GET", "/v1/lut?id=kodak-gold", "s3cret", b"").0, 404);
        call(&mut lib, "PUT", "/v1/preset?name=Punchymine", "s3cret", b"");
        let (_, body) = call(&mut lib, "GET", "/v1/state", "s3cret", b"");
        assert!(serde_json::from_slice::<State>(&body).unwrap().presets.is_empty());
        // A phone from before reads the state without the new keys, and an old state still parses.
        let old = r#"{"id":"a","name":"b","photos":[],"marks":{},"edits":{},"albums":""}"#;
        assert!(serde_json::from_str::<State>(old).unwrap().luts.is_empty());
    }

    fn call_ready() -> (tempfile::TempDir, Library, Arc<Shared>) {
        let (d, mut lib, shared) = lib();
        assert_eq!(call(&mut lib, "GET", "/v1/hello", "s3cret", b"").0, 200);
        (d, lib, shared)
    }

    #[test]
    fn uploads_cannot_leave_the_library() {
        let (_d, mut lib, _) = lib();
        assert_eq!(call(&mut lib, "PUT", "/v1/photo?name=..%2F..%2Fx.sh&taken=1", "s3cret", b"x").0, 500);
    }

    #[test]
    fn the_qr_code_survives_odd_names() {
        let uri = pairing_uri("id 1", "Patrick's PC", "192.168.1.5", 47820, "ab");
        let (_, q) = query(&uri["shootback://pair".len()..]);
        assert_eq!(q["name"], "Patrick's PC");
        assert_eq!(q["host"], "192.168.1.5");
    }

    #[test]
    fn the_server_answers_over_http() {
        let dir = tempfile::tempdir().unwrap();
        let shared = Arc::new(Shared::default());
        *shared.pending.lock().unwrap() = Some("k".into());
        let server = Server::start(dir.path(), "pc", "Laptop", shared.clone()).unwrap();
        let r = ureq::get(&format!("http://127.0.0.1:{}/v1/hello", server.port)).set("Authorization", "Bearer k").set("X-Shootback-Phone", "p").call().unwrap();
        let hello: Hello = serde_json::from_str(&r.into_string().unwrap()).unwrap();
        assert_eq!(hello.name, "Laptop");
        drop(server);
    }
}
