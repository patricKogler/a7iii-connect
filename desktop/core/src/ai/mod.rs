//! The tools that run a neural network on this computer: subject, object and depth masks, raw
//! denoise, upscaling, faces and search. Every model is an ONNX file run by ONNX Runtime, which
//! is a library of its own. Neither ships with the app: both are downloaded the first time a tool
//! needs them, into the app's data folder, and nothing else ever leaves the computer.
//!
//! [`set_home`] names that folder once at start; [`ensure`] fetches a model (or reports how far a
//! fetch has come) and [`session`] opens one, loading the runtime first.

pub mod catalog;
pub mod clip;

pub mod denoise;
pub mod depth;
pub mod faces;
pub mod inpaint;

pub mod objects;
pub mod sky;
pub mod subject;
pub mod tensor;

use catalog::{Model, Packaging, RUNTIME};
use ort::environment::Environment;
use ort::session::Session;
use std::collections::HashMap;
use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

static HOME: OnceLock<PathBuf> = OnceLock::new();
static ENVIRONMENT: OnceLock<Result<Arc<Environment>, String>> = OnceLock::new();
static PROGRESS: OnceLock<Mutex<HashMap<&'static str, Progress>>> = OnceLock::new();
static SESSIONS: OnceLock<Mutex<HashMap<String, Arc<Mutex<Session>>>>> = OnceLock::new();

/// Where models and the runtime are kept (the app's data folder). Call once at start; later
/// calls are ignored.
pub fn set_home(dir: &Path) {
    let _ = HOME.set(dir.to_path_buf());
}

fn home() -> PathBuf {
    HOME.get().cloned().unwrap_or_else(|| std::env::temp_dir().join("shootback"))
}

/// The folder a model's files are kept in.
pub fn model_dir(m: &Model) -> PathBuf {
    home().join("models").join(m.id)
}

/// How a model's download is going.
#[derive(Clone, Debug, PartialEq)]
pub enum Progress {
    /// Fraction of the bytes received.
    Downloading(f32),
    Failed(String),
}

fn progress() -> &'static Mutex<HashMap<&'static str, Progress>> {
    PROGRESS.get_or_init(Default::default)
}

/// Whether all of a model's files are here.
pub fn installed(m: &Model) -> bool {
    let dir = model_dir(m);
    m.files.iter().all(|f| dir.join(f).is_file())
}

/// The model's download, while one runs or after one failed.
pub fn status(m: &Model) -> Option<Progress> {
    progress().lock().unwrap().get(m.id).cloned()
}

/// Takes a model's files away again (and closes it if it was open).
pub fn remove(m: &Model) -> io::Result<()> {
    if let Some(s) = SESSIONS.get() {
        s.lock().unwrap().retain(|k, _| !k.starts_with(&format!("{}/", m.id)));
    }
    progress().lock().unwrap().remove(m.id);
    match fs::remove_dir_all(model_dir(m)) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

/// A lock for each model's download, by id.
type Locks = Mutex<HashMap<&'static str, Arc<Mutex<()>>>>;

/// Makes sure a model is here, downloading it if not (blocking; call off the UI thread). Only one
/// download per model runs at a time: a second caller waits for the first.
pub fn ensure(m: &'static Model) -> Result<PathBuf, String> {
    static LOCKS: OnceLock<Locks> = OnceLock::new();
    let lock = LOCKS.get_or_init(Default::default).lock().unwrap().entry(m.id).or_default().clone();
    let _held = lock.lock().unwrap();
    let dir = model_dir(m);
    if installed(m) {
        return Ok(dir);
    }
    progress().lock().unwrap().insert(m.id, Progress::Downloading(0.0));
    let result = download(m, &dir);
    let mut p = progress().lock().unwrap();
    match &result {
        Ok(()) => {
            p.remove(m.id);
        }
        Err(e) => {
            p.insert(m.id, Progress::Failed(e.clone()));
        }
    }
    result.map(|_| dir)
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(20)).timeout_read(Duration::from_secs(60)).build()
}

/// Fetches `m` into a scratch folder beside `dir`, then moves it into place, so a cut-off
/// download never looks installed.
fn download(m: &'static Model, dir: &Path) -> Result<(), String> {
    let parent = dir.parent().ok_or("no models folder")?;
    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let part = parent.join(format!("{}.part", m.id));
    let _ = fs::remove_dir_all(&part);
    fs::create_dir_all(&part).map_err(|e| e.to_string())?;
    let total: u64 = m.sources.iter().map(|s| s.bytes).sum::<u64>().max(1);
    let mut before = 0u64;
    for src in m.sources {
        let file = part.join("download");
        fetch(src.url, &file, |got| {
            let f = (before + got) as f32 / total as f32;
            progress().lock().unwrap().insert(m.id, Progress::Downloading(f.min(1.0)));
        })
        .map_err(|e| format!("couldn't download {}: {e}", m.label))?;
        before += src.bytes;
        unpack(&src.packaging, &file, &part).map_err(|e| format!("couldn't unpack {}: {e}", m.label))?;
        let _ = fs::remove_file(&file);
    }
    for f in m.files {
        if !part.join(f).is_file() {
            return Err(format!("the download of {} has no {f}", m.label));
        }
    }
    let _ = fs::remove_dir_all(dir);
    fs::rename(&part, dir).map_err(|e| e.to_string())
}

fn fetch(url: &str, to: &Path, on: impl Fn(u64)) -> io::Result<()> {
    let resp = agent().get(url).call().map_err(|e| io::Error::other(e.to_string()))?;
    let mut reader = resp.into_reader();
    let mut out = io::BufWriter::new(fs::File::create(to)?);
    let mut buf = vec![0u8; 1 << 16];
    let mut got = 0u64;
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        got += n as u64;
        on(got);
    }
    out.flush()
}

/// Puts a downloaded file's contents into `dir` under the names the catalog expects.
fn unpack(how: &Packaging, file: &Path, dir: &Path) -> io::Result<()> {
    match how {
        Packaging::File(name) => fs::rename(file, dir.join(name)),
        Packaging::Gzip(name) => {
            let mut d = flate2::read::GzDecoder::new(fs::File::open(file)?);
            let mut out = fs::File::create(dir.join(name))?;
            io::copy(&mut d, &mut out).map(|_| ())
        }
        Packaging::Zip(entries) => {
            let mut z = zip::ZipArchive::new(fs::File::open(file)?).map_err(io::Error::other)?;
            for (inside, name) in *entries {
                let mut e = z.by_name(inside).map_err(|e| io::Error::other(format!("{inside}: {e}")))?;
                let mut out = fs::File::create(dir.join(name))?;
                io::copy(&mut e, &mut out)?;
            }
            Ok(())
        }
        Packaging::Tgz(entries) => {
            let mut t = tar::Archive::new(flate2::read::GzDecoder::new(fs::File::open(file)?));
            let mut left: Vec<&(&str, &str)> = entries.iter().collect();
            for e in t.entries()? {
                let mut e = e?;
                let path = e.path()?.to_string_lossy().to_string();
                if let Some(i) = left.iter().position(|(inside, _)| *inside == path) {
                    let mut out = fs::File::create(dir.join(left[i].1))?;
                    io::copy(&mut e, &mut out)?;
                    left.remove(i);
                }
            }
            match left.first() {
                Some((inside, _)) => Err(io::Error::other(format!("no {inside} in the archive"))),
                None => Ok(()),
            }
        }
    }
}

/// Loads ONNX Runtime once (downloading it the first time). `SHOOTBACK_ORT` can name a copy to
/// use instead.
pub fn runtime() -> Result<Arc<Environment>, String> {
    ENVIRONMENT
        .get_or_init(|| {
            let lib = match std::env::var_os("SHOOTBACK_ORT") {
                Some(p) => PathBuf::from(p),
                None => ensure(&RUNTIME)?.join(RUNTIME.files[0]),
            };
            ort::init_from(&lib)
                .map_err(|e| format!("ONNX Runtime didn't load: {e}"))?
                .with_name("shootback")
                .with_telemetry(false)
                .build()
                .map(Arc::new)
                .map_err(|e| format!("ONNX Runtime didn't start: {e}"))
        })
        .clone()
}

/// One of a model's networks, opened once and kept: `file` is one of [`Model::files`].
pub fn session(m: &'static Model, file: &str) -> Result<Arc<Mutex<Session>>, String> {
    let key = format!("{}/{file}", m.id);
    let sessions = SESSIONS.get_or_init(Default::default);
    if let Some(s) = sessions.lock().unwrap().get(&key) {
        return Ok(s.clone());
    }
    let dir = ensure(m)?;
    let env = runtime()?;
    let threads = std::thread::available_parallelism().map_or(4, |n| n.get());
    let s = Session::builder(&env)
        .map_err(|e| e.to_string())?
        .with_intra_threads(threads)
        .map_err(|e| e.to_string())?
        .commit_from_file(dir.join(file))
        .map_err(|e| format!("{} didn't open: {e}", m.label))?;
    let s = Arc::new(Mutex::new(s));
    sessions.lock().unwrap().insert(key, s.clone());
    Ok(s)
}
