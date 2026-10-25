//! The photos in a library folder: every RAW and JPEG under it, paired into shots the way the
//! phone pairs them, keyed the way the phone keys them ("DSC04217.ARW@1792420927000").

use crate::meta::{self, PhotoMeta};
use std::collections::HashMap;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

/// Where Shootback keeps its own files inside a library folder.
pub const STATE_DIR: &str = ".shootback";

/// How far apart a RAW's and its JPEG's capture times may be and still be one shot (the phone's
/// Albums.MATCH_WINDOW_MS: its media store can guess a file's time zone a day off).
pub const MATCH_WINDOW_MS: i64 = 25 * 60 * 60 * 1000;

#[derive(Clone, Debug, PartialEq)]
pub struct Photo {
    pub path: PathBuf,
    pub name: String,
    pub taken_millis: i64,
    pub orientation: u8,
    pub bytes: u64,
}

impl Photo {
    pub fn is_raw(&self) -> bool {
        is_raw_name(&self.name)
    }

    /// File name without extension: DSC04217 for both DSC04217.ARW and DSC04217.JPG.
    pub fn stem(&self) -> &str {
        self.name.rsplit_once('.').map_or(&self.name, |(s, _)| s)
    }

    /// Stable identity for marks, edits and albums: file name plus capture time, as on the phone.
    pub fn key(&self) -> String {
        format!("{}@{}", self.name, self.taken_millis)
    }
}

pub fn is_raw_name(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".arw")
}

pub fn is_photo_name(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    n.ends_with(".arw") || n.ends_with(".jpg") || n.ends_with(".jpeg")
}

/// One press of the shutter: the RAW and its JPEG twin, or just one of them.
#[derive(Clone, Debug, PartialEq)]
pub struct Shot {
    pub raw: Option<Photo>,
    pub jpeg: Option<Photo>,
}

impl Shot {
    /// What marks and edits hang off: the RAW when there is one.
    pub fn primary(&self) -> &Photo {
        self.raw.as_ref().or(self.jpeg.as_ref()).expect("a shot has a file")
    }

    pub fn key(&self) -> String {
        self.primary().key()
    }

    pub fn taken_millis(&self) -> i64 {
        self.primary().taken_millis
    }

    /// The file to show: the JPEG decodes faster and looks like the RAW's own preview.
    pub fn display(&self) -> &Photo {
        self.jpeg.as_ref().unwrap_or_else(|| self.primary())
    }
}

/// Pairs each RAW with the JPEG of the same name taken at (nearly) the same time; newest first.
/// A RAW and a JPEG left alone that were taken in the same second, with no other RAW or JPEG
/// left alone in it, are one shot too: one of them was renamed (2026050109_0380.ARW beside the
/// camera's DSC03683.JPG, say).
pub fn shots(photos: &[Photo]) -> Vec<Shot> {
    let mut by_stem: HashMap<String, Vec<&Photo>> = HashMap::new();
    for p in photos {
        by_stem.entry(p.stem().to_lowercase()).or_default().push(p);
    }
    let mut out = Vec::new();
    for same in by_stem.values() {
        let mut jpegs: Vec<&Photo> = same.iter().copied().filter(|p| !p.is_raw()).collect();
        for raw in same.iter().filter(|p| p.is_raw()) {
            let twin = jpegs
                .iter()
                .enumerate()
                .filter(|(_, j)| (j.taken_millis - raw.taken_millis).abs() <= MATCH_WINDOW_MS)
                .min_by_key(|(_, j)| (j.taken_millis - raw.taken_millis).abs())
                .map(|(i, _)| i);
            let jpeg = twin.map(|i| jpegs.remove(i).clone());
            out.push(Shot { raw: Some((*raw).clone()), jpeg });
        }
        out.extend(jpegs.into_iter().map(|j| Shot { raw: None, jpeg: Some(j.clone()) }));
    }
    let mut alone: HashMap<i64, Vec<usize>> = HashMap::new();
    for (i, s) in out.iter().enumerate() {
        if s.raw.is_none() || s.jpeg.is_none() {
            alone.entry(s.taken_millis()).or_default().push(i);
        }
    }
    let mut gone = Vec::new();
    for same in alone.values().filter(|v| v.len() == 2) {
        let (a, b) = (same[0], same[1]);
        let (raw, jpeg) = if out[a].raw.is_some() { (a, b) } else { (b, a) };
        if out[raw].raw.is_some() && out[jpeg].jpeg.is_some() {
            out[raw].jpeg = out[jpeg].jpeg.take();
            gone.push(jpeg);
        }
    }
    gone.sort_unstable();
    for i in gone.into_iter().rev() {
        out.remove(i);
    }
    out.sort_by(|a, b| b.taken_millis().cmp(&a.taken_millis()).then_with(|| b.primary().name.cmp(&a.primary().name)));
    out
}

/// Every photo under `root` (Shootback's own folder aside), newest first. Metadata comes from
/// `index`, a cache of what earlier scans read, when a file's size and time haven't changed.
pub fn scan(root: &Path, index: &mut Index) -> Vec<Photo> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else { continue };
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                if !name.starts_with('.') {
                    stack.push(e.path());
                }
            } else if ft.is_file() && is_photo_name(&name) {
                if let Some(p) = index.photo(&e.path()) {
                    out.push(p);
                }
            }
        }
    }
    out.sort_by(|a, b| b.taken_millis.cmp(&a.taken_millis).then_with(|| b.name.cmp(&a.name)).then_with(|| a.is_raw().cmp(&b.is_raw()).reverse()));
    out
}

/// Reads a photo's metadata. A RAW is a TIFF with the EXIF near its start; only that part is read.
pub fn read_meta(path: &Path) -> PhotoMeta {
    if is_raw_name(&path.to_string_lossy()) {
        let mut head = Vec::new();
        if fs::File::open(path).and_then(|f| f.take(1 << 20).read_to_end(&mut head)).is_ok() {
            if let Ok(m) = meta::read_tiff_bytes(head) {
                return m;
            }
        }
    }
    meta::read(path)
}

/// What earlier scans read from each file, so opening a big library doesn't read every file again.
#[derive(Default)]
pub struct Index {
    entries: HashMap<PathBuf, (u64, u64, i64, u8)>,
    dirty: bool,
}

impl Index {
    pub fn photo(&mut self, path: &Path) -> Option<Photo> {
        let md = fs::metadata(path).ok()?;
        let mtime = md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
        let (taken, orientation) = match self.entries.get(path) {
            Some(&(size, m, t, o)) if size == md.len() && m == mtime => (t, o),
            _ => {
                let meta = read_meta(path);
                let t = meta.taken_millis.unwrap_or(mtime as i64 * 1000);
                self.entries.insert(path.to_path_buf(), (md.len(), mtime, t, meta.orientation));
                self.dirty = true;
                (t, meta.orientation)
            }
        };
        Some(Photo { path: path.to_path_buf(), name: path.file_name()?.to_string_lossy().to_string(), taken_millis: taken, orientation, bytes: md.len() })
    }

    /// Tab-separated: path, size, mtime, capture time, orientation.
    pub fn load(file: &Path) -> Index {
        let mut index = Index::default();
        if let Ok(text) = fs::read_to_string(file) {
            for line in text.lines() {
                let p: Vec<&str> = line.split('\t').collect();
                if p.len() < 5 {
                    continue;
                }
                if let (Ok(s), Ok(m), Ok(t), Ok(o)) = (p[1].parse(), p[2].parse(), p[3].parse(), p[4].parse()) {
                    index.entries.insert(PathBuf::from(p[0]), (s, m, t, o));
                }
            }
        }
        index
    }

    pub fn save(&mut self, file: &Path) -> io::Result<()> {
        if !self.dirty {
            return Ok(());
        }
        let mut text = String::new();
        for (path, (s, m, t, o)) in &self.entries {
            if path.exists() {
                text.push_str(&format!("{}\t{s}\t{m}\t{t}\t{o}\n", path.display()));
            }
        }
        write_atomic(file, text.as_bytes())?;
        self.dirty = false;
        Ok(())
    }
}

/// Written beside and then moved over, so a crash mid-write can't lose the file.
pub fn write_atomic(file: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(dir) = file.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = file.with_extension("tmp");
    fs::write(&tmp, bytes)?;
    fs::rename(&tmp, file)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(name: &str, t: i64) -> Photo {
        Photo { path: PathBuf::from(name), name: name.into(), taken_millis: t, orientation: 1, bytes: 1 }
    }

    #[test]
    fn pairs_raw_and_jpeg_by_name_and_time() {
        let day = 24 * 3600 * 1000;
        let photos = vec![
            photo("DSC00001.ARW", 1000),
            photo("DSC00001.JPG", 1000),
            // The counter came round: same name, months later.
            photo("DSC00001.JPG", 1000 + 90 * day),
            photo("DSC00002.JPG", 5000),
        ];
        let s = shots(&photos);
        assert_eq!(s.len(), 3);
        assert_eq!(s[0].key(), format!("DSC00001.JPG@{}", 1000 + 90 * day));
        assert!(s[0].raw.is_none());
        assert_eq!(s[1].key(), "DSC00002.JPG@5000");
        assert_eq!(s[2].key(), "DSC00001.ARW@1000");
        assert_eq!(s[2].jpeg.as_ref().unwrap().name, "DSC00001.JPG");
        assert_eq!(s[2].display().name, "DSC00001.JPG");
    }

    #[test]
    fn a_renamed_raw_finds_its_jpeg_by_time() {
        let photos = vec![
            photo("2026050109_0380.ARW", 7000),
            photo("DSC03683.JPG", 7000),
            // A burst in one second: which JPEG is whose can't be told, so they stay apart.
            photo("2026050109_0381.ARW", 9000),
            photo("2026050109_0382.ARW", 9000),
            photo("DSC03684.JPG", 9000),
            photo("DSC03685.JPG", 9000),
        ];
        let s = shots(&photos);
        assert_eq!(s.len(), 5);
        let one = s.iter().find(|s| s.taken_millis() == 7000).unwrap();
        assert_eq!((one.key().as_str(), one.display().name.as_str()), ("2026050109_0380.ARW@7000", "DSC03683.JPG"));
        assert!(s.iter().filter(|s| s.taken_millis() == 9000).all(|s| s.raw.is_none() || s.jpeg.is_none()));
    }

    #[test]
    fn index_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let jpg = dir.path().join("a.jpg");
        fs::write(&jpg, b"not really a jpeg").unwrap();
        let mut index = Index::default();
        let p = index.photo(&jpg).unwrap();
        let file = dir.path().join(STATE_DIR).join("index.tsv");
        index.save(&file).unwrap();
        let mut again = Index::load(&file);
        assert_eq!(again.photo(&jpg).unwrap(), p);
        assert!(!again.dirty);
    }
}
