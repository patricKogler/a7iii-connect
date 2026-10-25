//! Photos deleted from this library (`.shootback/deleted.txt`), so a paired phone doesn't send
//! them back. The phone only leaves out photos both sides had at the last sync, so without this a
//! photo deleted here would come back from the phone one sync later.
//!
//! One line per file: `name<TAB>taken<TAB>bytes<TAB>phone`, where `phone` is 1 once a phone tried
//! to send it back. From then on the sync lists it as still here, so the phone keeps counting it
//! as a photo both have and stops sending it.

use crate::library::{write_atomic, Photo, MATCH_WINDOW_MS};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq)]
pub struct Gone {
    pub name: String,
    pub taken: i64,
    pub bytes: u64,
    /// A phone has it and tried to send it back.
    pub phone: bool,
}

impl Gone {
    pub fn of(p: &Photo) -> Gone {
        Gone { name: p.name.clone(), taken: p.taken_millis, bytes: p.bytes, phone: false }
    }

    /// The same file as `name` taken at `taken` (the sync's notion of the same photo).
    pub fn is(&self, name: &str, taken: i64) -> bool {
        self.name.eq_ignore_ascii_case(name) && (self.taken - taken).abs() <= MATCH_WINDOW_MS
    }

    pub fn key(&self) -> String {
        format!("{}@{}", self.name, self.taken)
    }
}

pub fn file(root: &Path) -> PathBuf {
    root.join(crate::library::STATE_DIR).join("deleted.txt")
}

pub fn load(root: &Path) -> Vec<Gone> {
    fs::read_to_string(file(root)).map(|t| decode(&t)).unwrap_or_default()
}

pub fn save(root: &Path, list: &[Gone]) -> io::Result<()> {
    write_atomic(&file(root), encode(list).as_bytes())
}

/// Adds `photos` to the list.
pub fn add(root: &Path, photos: &[Photo]) -> io::Result<()> {
    let mut list = load(root);
    for p in photos {
        if !list.iter().any(|g| g.name == p.name && g.taken == p.taken_millis) {
            list.push(Gone::of(p));
        }
    }
    save(root, &list)
}

/// Takes `photos` off the list again (they were put back).
pub fn remove(root: &Path, photos: &[Photo]) -> io::Result<()> {
    let mut list = load(root);
    list.retain(|g| !photos.iter().any(|p| g.name == p.name && g.taken == p.taken_millis));
    save(root, &list)
}

pub fn decode(text: &str) -> Vec<Gone> {
    text.lines()
        .filter_map(|l| {
            let mut f = l.split('\t');
            let name = f.next()?.to_string();
            let taken = f.next()?.parse().ok()?;
            let bytes = f.next().and_then(|b| b.parse().ok()).unwrap_or(0);
            let phone = f.next() == Some("1");
            (!name.is_empty()).then_some(Gone { name, taken, bytes, phone })
        })
        .collect()
}

pub fn encode(list: &[Gone]) -> String {
    list.iter().map(|g| format!("{}\t{}\t{}\t{}\n", g.name, g.taken, g.bytes, if g.phone { 1 } else { 0 })).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(name: &str, taken: i64) -> Photo {
        Photo { path: PathBuf::from(name), name: name.into(), taken_millis: taken, orientation: 1, bytes: 10 }
    }

    #[test]
    fn round_trips_and_puts_back() {
        let dir = tempfile::tempdir().unwrap();
        let (a, b) = (photo("DSC1.ARW", 1000), photo("DSC1.JPG", 1000));
        add(dir.path(), &[a.clone(), b.clone()]).unwrap();
        add(dir.path(), std::slice::from_ref(&a)).unwrap();
        let list = load(dir.path());
        assert_eq!(list.len(), 2);
        assert!(list[0].is("dsc1.arw", 1000 + 3_600_000));
        assert!(!list[0].is("DSC2.ARW", 1000));
        remove(dir.path(), &[a]).unwrap();
        assert_eq!(load(dir.path()), vec![Gone::of(&b)]);
    }
}
