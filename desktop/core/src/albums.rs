//! Albums, as the phone keeps them (develop/albums.txt) and merges them between devices: shots
//! are referenced by key, a shot taken out is kept as a dated entry and a deleted album as a
//! marker, so two copies can be put together with the newer change winning. A port of the phone
//! app's Albums.

use crate::library::{write_atomic, MATCH_WINDOW_MS};
use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq)]
pub struct AlbumEntry {
    pub key: String,
    pub at: i64,
    pub in_album: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Album {
    pub id: String,
    pub name: String,
    pub created_at: i64,
    pub renamed_at: i64,
    pub deleted_at: Option<i64>,
    pub entries: HashMap<String, AlbumEntry>,
    pub shared: bool,
    /// The device that made the album ("" for one made before albums had owners).
    pub owner: String,
    /// Members by device id, in the order they joined.
    pub members: Vec<(String, String)>,
}

impl Album {
    pub fn alive(&self) -> bool {
        self.deleted_at.is_none()
    }

    pub fn contains(&self, key: &str) -> bool {
        self.entries.get(key).is_some_and(|e| e.in_album)
    }

    pub fn len(&self) -> usize {
        self.entries.values().filter(|e| e.in_album).count()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The shots in the album, in the order they were added.
    pub fn keys(&self) -> Vec<&str> {
        let mut e: Vec<&AlbumEntry> = self.entries.values().filter(|e| e.in_album).collect();
        e.sort_by_key(|e| e.at);
        e.into_iter().map(|e| e.key.as_str()).collect()
    }
}

/// Names are one line of text; an empty one becomes "Album".
pub fn clean(name: &str) -> String {
    let one_line: String = name.split(['\t', '\r', '\n']).filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" ");
    let t = one_line.trim();
    if t.is_empty() {
        "Album".into()
    } else {
        t.into()
    }
}

pub fn create(name: &str, keys: &[String], now: i64, id: String, owner: &str) -> Album {
    let a = Album {
        id,
        name: clean(name),
        created_at: now,
        renamed_at: now,
        deleted_at: None,
        entries: HashMap::new(),
        shared: false,
        owner: owner.into(),
        members: Vec::new(),
    };
    add(&a, keys, now)
}

pub fn add(album: &Album, keys: &[String], now: i64) -> Album {
    let mut a = album.clone();
    let mut i = 0;
    let mut seen = std::collections::HashSet::new();
    for k in keys {
        if !seen.insert(k) || album.contains(k) {
            continue;
        }
        // One millisecond apart, so the album keeps the order they were added in.
        a.entries.insert(k.clone(), AlbumEntry { key: k.clone(), at: now + i, in_album: true });
        i += 1;
    }
    a
}

pub fn remove(album: &Album, keys: &[String], now: i64) -> Album {
    let mut a = album.clone();
    for k in keys {
        if album.contains(k) {
            a.entries.insert(k.clone(), AlbumEntry { key: k.clone(), at: now, in_album: false });
        }
    }
    a
}

pub fn rename(album: &Album, name: &str, now: i64) -> Album {
    Album { name: clean(name), renamed_at: now, ..album.clone() }
}

pub fn delete(album: &Album, now: i64) -> Album {
    Album { deleted_at: Some(now), ..album.clone() }
}

/// Puts two copies of the albums together: the same album (by id) takes the newer name, stays
/// deleted if either side deleted it, and for each shot the newer add or take-out wins (a tie
/// takes it out). Whether it's shared stays as `mine` had it.
pub fn merge(mine: &[Album], theirs: &[Album]) -> Vec<Album> {
    let mut out: Vec<Album> = mine.to_vec();
    for b in theirs {
        match out.iter_mut().find(|a| a.id == b.id) {
            Some(a) => *a = merge_one(a, b),
            None => out.push(b.clone()),
        }
    }
    out
}

fn merge_one(a: &Album, b: &Album) -> Album {
    let named = if b.renamed_at > a.renamed_at { b } else { a };
    let deleted = a.deleted_at.max(b.deleted_at);
    let mut entries = a.entries.clone();
    for (k, y) in &b.entries {
        let e = match entries.get(k) {
            Some(x) => newer(x, y).clone(),
            None => y.clone(),
        };
        entries.insert(k.clone(), e);
    }
    let mut members = b.members.clone();
    for (id, name) in &a.members {
        match members.iter_mut().find(|(m, _)| m == id) {
            Some(m) => m.1 = name.clone(),
            None => members.push((id.clone(), name.clone())),
        }
    }
    Album {
        id: a.id.clone(),
        name: named.name.clone(),
        created_at: a.created_at.min(b.created_at),
        renamed_at: named.renamed_at,
        deleted_at: deleted,
        entries,
        shared: a.shared,
        owner: if a.owner.is_empty() { b.owner.clone() } else { a.owner.clone() },
        members,
    }
}

/// The later change to a shot; on a tie, taking it out.
fn newer<'a>(x: &'a AlbumEntry, y: &'a AlbumEntry) -> &'a AlbumEntry {
    if x.at != y.at {
        if x.at > y.at {
            x
        } else {
            y
        }
    } else if !x.in_album {
        x
    } else {
        y
    }
}

/// "DSC01234.ARW@1760000000000" as its lower-case stem and capture time; " (1)" copies count.
fn parse_key(key: &str) -> Option<(String, i64)> {
    let (name, time) = key.rsplit_once('@')?;
    let time = time.parse().ok()?;
    let stem = name.rsplit_once('.').map_or(name, |(s, _)| s);
    let stem = match stem.rfind(" (") {
        Some(i) if stem.ends_with(')') && stem[i + 2..stem.len() - 1].chars().all(|c| c.is_ascii_digit()) => &stem[..i],
        _ => stem,
    };
    Some((stem.to_lowercase(), time))
}

/// Finds this library's own key for a shot named by another device: the same key, or else the
/// same file name stem taken at (nearly) the same time (her DSC01234.JPG is my DSC01234.ARW).
pub fn resolver(local: &[String]) -> impl Fn(&str) -> Option<String> {
    let known: std::collections::HashSet<String> = local.iter().cloned().collect();
    let mut by_stem: HashMap<String, Vec<(i64, String)>> = HashMap::new();
    for k in local {
        if let Some((stem, t)) = parse_key(k) {
            by_stem.entry(stem).or_default().push((t, k.clone()));
        }
    }
    move |key| {
        if known.contains(key) {
            return Some(key.to_string());
        }
        let (stem, t) = parse_key(key)?;
        by_stem.get(&stem)?.iter().filter(|(lt, _)| (lt - t).abs() <= MATCH_WINDOW_MS).min_by_key(|(lt, _)| (lt - t).abs()).map(|(_, k)| k.clone())
    }
}

/// Tab-separated lines, like the flags file: `A id created renamed deleted(0 = no) name
/// shared(1 = yes) owner` for each album, then `M id device name` per member and `S id at +/- key`
/// per shot in it.
pub fn encode(albums: &[Album]) -> String {
    let mut s = String::new();
    for a in albums {
        s.push_str(&format!(
            "A\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            a.id,
            a.created_at,
            a.renamed_at,
            a.deleted_at.unwrap_or(0),
            a.name,
            if a.shared { 1 } else { 0 },
            a.owner
        ));
        for (device, name) in &a.members {
            s.push_str(&format!("M\t{}\t{device}\t{}\n", a.id, clean(name)));
        }
        let mut e: Vec<&AlbumEntry> = a.entries.values().collect();
        e.sort_by(|x, y| x.at.cmp(&y.at).then_with(|| x.key.cmp(&y.key)));
        for e in e {
            s.push_str(&format!("S\t{}\t{}\t{}\t{}\n", a.id, e.at, if e.in_album { "+" } else { "-" }, e.key));
        }
    }
    s
}

pub fn decode(text: &str) -> Vec<Album> {
    let mut order = Vec::new();
    let mut albums: HashMap<String, Album> = HashMap::new();
    let mut entries: HashMap<String, HashMap<String, AlbumEntry>> = HashMap::new();
    let mut members: BTreeMap<String, Vec<(String, String)>> = BTreeMap::new();
    for line in text.lines() {
        let p: Vec<&str> = line.split('\t').collect();
        match p.first() {
            Some(&"A") if p.len() >= 6 => {
                let (Ok(created), Ok(renamed), Ok(deleted)) = (p[2].parse::<i64>(), p[3].parse::<i64>(), p[4].parse::<i64>()) else { continue };
                if !albums.contains_key(p[1]) {
                    order.push(p[1].to_string());
                }
                albums.insert(
                    p[1].into(),
                    Album {
                        id: p[1].into(),
                        name: p[5].into(),
                        created_at: created,
                        renamed_at: renamed,
                        deleted_at: (deleted != 0).then_some(deleted),
                        entries: HashMap::new(),
                        shared: p.get(6) == Some(&"1"),
                        owner: p.get(7).unwrap_or(&"").to_string(),
                        members: Vec::new(),
                    },
                );
            }
            Some(&"M") if p.len() >= 4 => {
                let list = members.entry(p[1].into()).or_default();
                list.retain(|(d, _)| d != p[2]);
                list.push((p[2].into(), p[3].into()));
            }
            Some(&"S") if p.len() >= 5 => {
                let Ok(at) = p[2].parse() else { continue };
                entries.entry(p[1].into()).or_default().insert(p[4].into(), AlbumEntry { key: p[4].into(), at, in_album: p[3] == "+" });
            }
            _ => {}
        }
    }
    order
        .into_iter()
        .filter_map(|id| {
            let mut a = albums.remove(&id)?;
            a.entries = entries.remove(&id).unwrap_or_default();
            a.members = members.remove(&id).unwrap_or_default();
            Some(a)
        })
        .collect()
}

/// The albums of a library, in its Shootback folder.
pub struct AlbumStore {
    file: PathBuf,
}

impl AlbumStore {
    pub fn new(file: &Path) -> AlbumStore {
        AlbumStore { file: file.to_path_buf() }
    }

    /// Every album, deleted ones included (they matter when devices compare albums).
    pub fn load(&self) -> Vec<Album> {
        fs::read_to_string(&self.file).map(|t| decode(&t)).unwrap_or_default()
    }

    pub fn save(&self, albums: &[Album]) -> io::Result<()> {
        write_atomic(&self.file, encode(albums).as_bytes())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keys(k: &[&str]) -> Vec<String> {
        k.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn round_trips_the_phones_format() {
        let text = "A\tid1\t100\t150\t0\tIceland\t1\tphoneA\nM\tid1\tphoneA\tPatrick\nS\tid1\t101\t+\tDSC1.ARW@1\nS\tid1\t120\t-\tDSC2.ARW@2\n";
        let albums = decode(text);
        assert_eq!(albums.len(), 1);
        let a = &albums[0];
        assert_eq!((a.name.as_str(), a.shared, a.owner.as_str(), a.len()), ("Iceland", true, "phoneA", 1));
        assert_eq!(encode(&albums), text);
    }

    #[test]
    fn merging_keeps_the_newer_change() {
        let a = create("Trip", &keys(&["x@1", "y@2"]), 1000, "id".into(), "");
        let mut b = remove(&a, &keys(&["x@1"]), 2000);
        b = rename(&b, "Trip 2026", 2000);
        let a = add(&a, &keys(&["z@3"]), 1500);
        let m = merge(&[a], &[b]);
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].name, "Trip 2026");
        assert_eq!(m[0].keys(), vec!["y@2", "z@3"]);
    }

    #[test]
    fn resolves_another_devices_key() {
        let resolve = resolver(&keys(&["DSC01234.ARW@1000", "DSC05555.JPG@9"]));
        assert_eq!(resolve("DSC01234.ARW@1000").as_deref(), Some("DSC01234.ARW@1000"));
        assert_eq!(resolve("DSC01234 (1).JPG@3601000").as_deref(), Some("DSC01234.ARW@1000"));
        assert_eq!(resolve("DSC01234.JPG@999999999999"), None);
    }
}
