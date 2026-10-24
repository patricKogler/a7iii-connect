//! Copying new photos into the library: from the camera's Wi-Fi, from its FTP uploads and from a
//! card or folder. Files go to `<library>/<YYYY-MM-DD>/<name>`, never overwrite one already there,
//! and appear only once complete (they're written as `.part` first).

use crate::camera::{CameraClient, CameraItem};
use crate::ftp::{Upload, UploadSink};
use crate::library::{is_photo_name, write_atomic, STATE_DIR};
use chrono::Local;
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

/// The day folder for a camera date ("2026-10-19T16:42:07"), or today's.
pub fn day_folder(date: Option<&str>) -> String {
    date.and_then(|d| d.get(..10))
        .filter(|d| d.len() == 10 && d.as_bytes()[4] == b'-' && d.as_bytes()[7] == b'-')
        .map(str::to_string)
        .unwrap_or_else(|| Local::now().format("%Y-%m-%d").to_string())
}

/// A path for `name` in `dir` that isn't taken: DSC04217.JPG, then DSC04217 (1).JPG ...
pub fn free_path(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = name.rsplit_once('.').map_or((name, String::new()), |(s, e)| (s, format!(".{e}")));
    (1..).map(|i| dir.join(format!("{stem} ({i}){ext}"))).find(|p| !p.exists()).unwrap()
}

/// A file being written next to its final name, moved into place on commit.
pub struct PartFile {
    part: PathBuf,
    dest: PathBuf,
    out: BufWriter<File>,
}

impl PartFile {
    pub fn create(dir: &Path, name: &str) -> io::Result<PartFile> {
        fs::create_dir_all(dir)?;
        let dest = free_path(dir, name);
        let part = dest.with_extension(format!("{}.part", dest.extension().and_then(|e| e.to_str()).unwrap_or("")));
        Ok(PartFile { out: BufWriter::new(File::create(&part)?), part, dest })
    }

    pub fn finish(mut self) -> io::Result<PathBuf> {
        self.out.flush()?;
        drop(self.out);
        fs::rename(&self.part, &self.dest)?;
        Ok(self.dest)
    }
}

impl Write for PartFile {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        self.out.write(b)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

impl Upload for PartFile {
    fn commit(self: Box<Self>) -> io::Result<()> {
        (*self).finish().map(|_| ())
    }
    fn abort(self: Box<Self>) {
        let part = self.part.clone();
        drop(self);
        let _ = fs::remove_file(part);
    }
}

/// FTP uploads land in today's folder of the library.
pub struct LibrarySink {
    pub root: PathBuf,
}

impl UploadSink for LibrarySink {
    fn open(&self, name: &str) -> io::Result<Box<dyn Upload>> {
        let safe: String = name.chars().filter(|c| !matches!(c, '/' | '\\' | ':' | '\0')).collect();
        if safe.is_empty() || safe.starts_with('.') {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "bad file name"));
        }
        Ok(Box::new(PartFile::create(&self.root.join(day_folder(None)), &safe)?))
    }
}

/// Which camera photos were copied before (camera keys, one per line), so a second import only
/// takes what's new even after files were moved or deleted.
pub struct CopiedStore {
    file: PathBuf,
    keys: Mutex<HashSet<String>>,
}

impl CopiedStore {
    pub fn load(root: &Path) -> CopiedStore {
        let file = root.join(STATE_DIR).join("copied.txt");
        let keys = fs::read_to_string(&file).map(|t| t.lines().map(str::to_string).collect()).unwrap_or_default();
        CopiedStore { file, keys: Mutex::new(keys) }
    }

    pub fn contains(&self, key: &str) -> bool {
        self.keys.lock().unwrap().contains(key)
    }

    pub fn add(&self, key: &str) -> io::Result<()> {
        let mut keys = self.keys.lock().unwrap();
        keys.insert(key.to_string());
        let mut all: Vec<&String> = keys.iter().collect();
        all.sort();
        write_atomic(&self.file, all.iter().map(|k| format!("{k}\n")).collect::<String>().as_bytes())
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Progress {
    pub done: usize,
    pub total: usize,
    pub failed: usize,
    pub current: Option<String>,
    /// Files written so far, newest last.
    pub saved: Vec<PathBuf>,
}

/// Copies the camera photos not copied before. `full` takes the original files (RAW and JPEG);
/// otherwise the camera's 2 MP copies. Returns how many were on the camera.
pub fn import_from_camera(
    client: &CameraClient,
    root: &Path,
    copied: &CopiedStore,
    full: bool,
    cancel: &AtomicBool,
    progress: &dyn Fn(&Progress),
) -> Result<(usize, Progress), String> {
    let items = client.list_all_items(6)?;
    let mut todo: Vec<&CameraItem> = items.iter().filter(|i| !copied.contains(&i.key())).collect();
    todo.sort_by(|a, b| b.date.cmp(&a.date));
    let mut p = Progress { total: todo.len(), ..Default::default() };
    progress(&p);
    for item in todo {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let Some(res) = (if full { item.best_resource() } else { item.small_resource() }) else { continue };
        p.current = Some(item.title.clone());
        progress(&p);
        let name = if item.title.trim().is_empty() { format!("A7III_{}.JPG", Local::now().timestamp_millis()) } else { item.title.clone() };
        let dir = root.join(day_folder(item.date.as_deref()));
        let mut result = Err(String::new());
        for attempt in 0..3 {
            result = download(client, &res.url, &dir, &name, cancel);
            if result.is_ok() || cancel.load(Ordering::Relaxed) {
                break;
            }
            std::thread::sleep(std::time::Duration::from_secs(attempt + 1));
        }
        match result {
            Ok(path) => {
                let _ = copied.add(&item.key());
                p.done += 1;
                p.saved.push(path);
            }
            Err(_) => p.failed += 1,
        }
        progress(&p);
    }
    p.current = None;
    progress(&p);
    Ok((items.len(), p))
}

fn download(client: &CameraClient, url: &str, dir: &Path, name: &str, cancel: &AtomicBool) -> Result<PathBuf, String> {
    let mut input = client.open(url)?;
    let mut out = PartFile::create(dir, name).map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; 64 * 1024];
    let copied: io::Result<()> = (|| loop {
        if cancel.load(Ordering::Relaxed) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
        }
        let n = input.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        out.write_all(&buf[..n])?;
    })();
    match copied {
        Ok(()) => out.finish().map_err(|e| e.to_string()),
        Err(e) => {
            Box::new(out).abort();
            Err(e.to_string())
        }
    }
}

/// Copies the photos under `from` (a card's DCIM, a folder) that the library doesn't have yet,
/// judged by name and size, into day folders by file time.
pub fn import_from_folder(from: &Path, root: &Path, have: &HashSet<(String, u64)>, cancel: &AtomicBool, progress: &dyn Fn(&Progress)) -> Progress {
    let mut files = Vec::new();
    let mut stack = vec![from.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else { continue };
        for e in entries.flatten() {
            let path = e.path();
            if path.is_dir() {
                if path != root {
                    stack.push(path);
                }
            } else if let (Some(name), Ok(md)) = (path.file_name().map(|n| n.to_string_lossy().to_string()), e.metadata()) {
                if is_photo_name(&name) && !have.contains(&(name.to_lowercase(), md.len())) {
                    files.push((path, name, md));
                }
            }
        }
    }
    let mut p = Progress { total: files.len(), ..Default::default() };
    progress(&p);
    for (path, name, md) in files {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        p.current = Some(name.clone());
        progress(&p);
        let day = md.modified().ok().map(|t| chrono::DateTime::<Local>::from(t).format("%Y-%m-%d").to_string());
        let dir = root.join(day.unwrap_or_else(|| day_folder(None)));
        let result = PartFile::create(&dir, &name).and_then(|mut out| {
            io::copy(&mut File::open(&path)?, &mut out)?;
            out.finish()
        });
        match result {
            Ok(dest) => {
                p.done += 1;
                p.saved.push(dest);
            }
            Err(_) => p.failed += 1,
        }
        progress(&p);
    }
    p.current = None;
    progress(&p);
    p
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn day_folders_and_free_names() {
        assert_eq!(day_folder(Some("2026-10-19T16:42:07")), "2026-10-19");
        assert_eq!(day_folder(Some("junk")).len(), 10);
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("DSC1.JPG"), b"x").unwrap();
        assert_eq!(free_path(dir.path(), "DSC1.JPG"), dir.path().join("DSC1 (1).JPG"));
        assert_eq!(free_path(dir.path(), "DSC2.JPG"), dir.path().join("DSC2.JPG"));
    }

    #[test]
    fn a_part_file_appears_only_when_finished() {
        let dir = tempfile::tempdir().unwrap();
        let mut f = PartFile::create(dir.path(), "DSC1.ARW").unwrap();
        f.write_all(b"data").unwrap();
        assert!(!dir.path().join("DSC1.ARW").exists());
        assert_eq!(f.finish().unwrap(), dir.path().join("DSC1.ARW"));
        assert_eq!(fs::read(dir.path().join("DSC1.ARW")).unwrap(), b"data");
    }

    #[test]
    fn folder_import_skips_what_the_library_has() {
        let card = tempfile::tempdir().unwrap();
        let lib = tempfile::tempdir().unwrap();
        fs::create_dir_all(card.path().join("DCIM/100MSDCF")).unwrap();
        fs::write(card.path().join("DCIM/100MSDCF/DSC1.ARW"), b"raw").unwrap();
        fs::write(card.path().join("DCIM/100MSDCF/DSC2.JPG"), b"jpeg").unwrap();
        fs::write(card.path().join("DCIM/100MSDCF/notes.txt"), b"skip").unwrap();
        let have: HashSet<(String, u64)> = [("dsc1.arw".to_string(), 3)].into_iter().collect();
        let p = import_from_folder(card.path(), lib.path(), &have, &AtomicBool::new(false), &|_| {});
        assert_eq!((p.done, p.total, p.failed), (1, 1, 0));
        assert_eq!(p.saved[0].file_name().unwrap(), "DSC2.JPG");
    }
}
