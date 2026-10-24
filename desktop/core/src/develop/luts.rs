//! The LUTs this library knows (the phone's LutLibrary.kt): each resampled to [`SIZE`]³ and kept
//! in `.shootback/luts/<id>.lut` in the phone's stored form, with `<id>.jpg` as its preview when
//! its pack came with one. `.shootback/luts.txt` lists id, name and the folder it came from (tab
//! separated), which groups them in the Looks panel. Edits name a LUT by id, so the same file on
//! the phone and here renders the same look; sync copies them both ways.

use super::lut::{Lut, SIZE};
use crate::library::{write_atomic, STATE_DIR};
use std::collections::HashSet;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub id: String,
    pub name: String,
    pub folder: String,
}

pub fn dir(root: &Path) -> PathBuf {
    root.join(STATE_DIR).join("luts")
}

fn index(root: &Path) -> PathBuf {
    root.join(STATE_DIR).join("luts.txt")
}

pub fn file(root: &Path, id: &str) -> PathBuf {
    dir(root).join(format!("{}.lut", super::file_name(id)))
}

pub fn thumbnail(root: &Path, id: &str) -> Option<PathBuf> {
    Some(dir(root).join(format!("{}.jpg", super::file_name(id)))).filter(|p| p.exists())
}

/// The listed LUTs whose file is here, then any stored file the list doesn't name.
pub fn list(root: &Path) -> Vec<Entry> {
    let text = fs::read_to_string(index(root)).unwrap_or_default();
    let mut out: Vec<Entry> = text
        .lines()
        .filter_map(|l| {
            let mut it = l.split('\t');
            Some(Entry { id: it.next()?.to_string(), name: it.next()?.to_string(), folder: it.next()?.to_string() })
        })
        .filter(|e| file(root, &e.id).exists())
        .collect();
    let known: HashSet<String> = out.iter().map(|e| super::file_name(&e.id)).collect();
    let mut extra: Vec<Entry> = fs::read_dir(dir(root))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            let id = name.strip_suffix(".lut")?.to_string();
            (!known.contains(&id)).then(|| Entry { name: id.clone(), id, folder: "Other".into() })
        })
        .collect();
    extra.sort_by(|a, b| a.id.cmp(&b.id));
    out.extend(extra);
    out
}

pub fn name(root: &Path, id: &str) -> Option<String> {
    list(root).into_iter().find(|e| e.id == id).map(|e| e.name)
}

static LOCK: Mutex<()> = Mutex::new(());

fn save_index(root: &Path, list: &[Entry]) -> io::Result<()> {
    let text: String = list.iter().map(|e| format!("{}\t{}\t{}\n", e.id, e.name, e.folder)).collect();
    write_atomic(&index(root), text.as_bytes())
}

fn clean(s: &str) -> String {
    s.replace(['\t', '\n', '\r'], " ").trim().to_string()
}

/// An id for a new look: from its folder and name, made unique.
fn new_id(root: &Path, taken: &HashSet<String>, folder: &str, name: &str) -> String {
    let mut base = String::new();
    for ch in format!("{folder}-{name}").to_lowercase().chars() {
        if ch.is_ascii_alphanumeric() {
            base.push(ch);
        } else if !base.ends_with('-') {
            base.push('-');
        }
    }
    let base: String = base.trim_matches('-').chars().take(60).collect();
    let base = if base.is_empty() { "lut".to_string() } else { base };
    let mut id = base.clone();
    let mut n = 2;
    while taken.contains(&id) || file(root, &id).exists() {
        id = format!("{base}-{n}");
        n += 1;
    }
    id
}

/// Adds a look (resampled to [`SIZE`]); `id` fixes its id, else one is made up.
pub fn store(root: &Path, lut: &Lut, name: &str, folder: &str, id: Option<&str>) -> io::Result<Entry> {
    let _guard = LOCK.lock().unwrap();
    let mut all = list(root);
    let taken: HashSet<String> = all.iter().map(|e| e.id.clone()).collect();
    let id = id.map_or_else(|| new_id(root, &taken, folder, name), str::to_string);
    fs::create_dir_all(dir(root))?;
    write_atomic(&file(root, &id), &lut.resized(SIZE).write_sbl())?;
    let e = Entry { id: id.clone(), name: clean(name), folder: clean(folder) };
    all.retain(|x| x.id != id);
    all.push(e.clone());
    save_index(root, &all)?;
    Ok(e)
}

/// Adds a look that arrived in its stored form (from the phone), with its preview if any.
pub fn store_raw(root: &Path, e: &Entry, bytes: &[u8], thumb: Option<&[u8]>) -> io::Result<()> {
    let lut = Lut::read_sbl(bytes).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "not a stored LUT"))?;
    let _guard = LOCK.lock().unwrap();
    fs::create_dir_all(dir(root))?;
    write_atomic(&file(root, &e.id), &lut.write_sbl())?;
    if let Some(t) = thumb {
        write_atomic(&dir(root).join(format!("{}.jpg", super::file_name(&e.id))), t)?;
    }
    let mut all = list(root);
    all.retain(|x| x.id != e.id);
    all.push(Entry { id: e.id.clone(), name: clean(&e.name), folder: clean(&e.folder) });
    save_index(root, &all)
}

/// A look's preview picture, from the phone.
pub fn put_thumbnail(root: &Path, id: &str, bytes: &[u8]) -> io::Result<()> {
    if !file(root, id).exists() {
        return Err(io::Error::new(io::ErrorKind::NotFound, "no such LUT"));
    }
    write_atomic(&dir(root).join(format!("{}.jpg", super::file_name(id))), bytes)
}

pub fn delete(root: &Path, ids: &[String]) -> io::Result<()> {
    let _guard = LOCK.lock().unwrap();
    for id in ids {
        let _ = fs::remove_file(file(root, id));
        let _ = fs::remove_file(dir(root).join(format!("{}.jpg", super::file_name(id))));
    }
    let all: Vec<Entry> = list(root).into_iter().filter(|e| !ids.contains(&e.id)).collect();
    save_index(root, &all)
}

/// What an import or download did.
#[derive(Debug, Default)]
pub struct Added {
    pub added: Vec<Entry>,
    pub skipped: usize,
}

/// Adds a .cube file's look; its title (or `stem`) names it.
pub fn import_cube(root: &Path, text: &str, stem: &str, folder: &str) -> Result<Entry, String> {
    let lut = Lut::parse_cube(text)?;
    let name = Lut::cube_title(text).unwrap_or_else(|| stem.to_string());
    store(root, &lut, if name.trim().is_empty() { "LUT" } else { &name }, folder, None).map_err(|e| e.to_string())
}

fn is_lut(path: &str) -> bool {
    let l = path.to_lowercase();
    l.ends_with(".cube") || l.ends_with(".png")
}

/// A .cube's or a Hald PNG's table, and the title a .cube gives itself.
fn decode(path: &str, bytes: &[u8]) -> Result<(Lut, Option<String>), String> {
    if path.to_lowercase().ends_with(".png") {
        let mut dec = png::Decoder::new(bytes);
        dec.set_transformations(png::Transformations::normalize_to_color8());
        let mut reader = dec.read_info().map_err(|e| e.to_string())?;
        let mut buf = vec![0; reader.output_buffer_size()];
        let info = reader.next_frame(&mut buf).map_err(|e| e.to_string())?;
        let (w, h) = (info.width as usize, info.height as usize);
        let rgb: Vec<u8> = match info.color_type {
            png::ColorType::Rgb => buf[..w * h * 3].to_vec(),
            png::ColorType::Rgba => buf[..w * h * 4].as_chunks::<4>().0.iter().flat_map(|p| [p[0], p[1], p[2]]).collect(),
            png::ColorType::Grayscale => buf[..w * h].iter().flat_map(|&v| [v, v, v]).collect(),
            png::ColorType::GrayscaleAlpha => buf[..w * h * 2].as_chunks::<2>().0.iter().flat_map(|p| [p[0], p[0], p[0]]).collect(),
            png::ColorType::Indexed => return Err("unexpected palette image".into()),
        };
        Ok((Lut::from_hald(w, h, &rgb)?, None))
    } else {
        let text = String::from_utf8_lossy(bytes);
        Ok((Lut::parse_cube(&text)?, Lut::cube_title(&text)))
    }
}

/// Imports a file the person picked, as the phone does: a .cube, a Hald CLUT .png, or a .zip of
/// either. A zip's looks are grouped by the folder right above each file (a pack's brand), else
/// by the zip's name; a single file goes under "Imported".
pub fn import(root: &Path, file_name: &str, bytes: &[u8]) -> Added {
    let mut out = Added::default();
    let take = |out: &mut Added, path: &str, bytes: &[u8], folder: &str| {
        let stem = path.rsplit('/').next().unwrap_or(path);
        let stem = stem.rsplit_once('.').map_or(stem, |(s, _)| s);
        match decode(path, bytes) {
            Ok((lut, title)) => {
                let name = title.unwrap_or_else(|| stem.to_string());
                let name = if name.trim().is_empty() { "LUT".to_string() } else { name };
                match store(root, &lut, &name, folder, None) {
                    Ok(e) => out.added.push(e),
                    Err(_) => out.skipped += 1,
                }
            }
            Err(_) => out.skipped += 1,
        }
    };
    if file_name.to_lowercase().ends_with(".zip") {
        let pack = file_name.rsplit_once('.').map_or(file_name, |(s, _)| s);
        let Ok(mut zip) = zip::ZipArchive::new(io::Cursor::new(bytes)) else {
            out.skipped += 1;
            return out;
        };
        for i in 0..zip.len() {
            let Ok(mut f) = zip.by_index(i) else { continue };
            let path = f.name().replace('\\', "/");
            let base = path.rsplit('/').next().unwrap_or(&path);
            if f.is_dir() || !is_lut(&path) || base.starts_with('.') || path.contains("__MACOSX") {
                continue;
            }
            let mut data = Vec::new();
            if f.by_ref().take(64 << 20).read_to_end(&mut data).is_err() {
                out.skipped += 1;
                continue;
            }
            let folder = path.rsplit_once('/').map(|(d, _)| d.rsplit('/').next().unwrap_or(d)).filter(|d| !d.is_empty()).unwrap_or(pack).to_string();
            take(&mut out, &path, &data, &folder);
        }
    } else if is_lut(file_name) {
        take(&mut out, file_name, bytes, "Imported");
    } else {
        out.skipped += 1;
    }
    out
}

/// The G'MIC film pack (github.com/YahiaAngelo/Film-Luts, about 300 film stocks), downloaded on
/// request as on the phone. Ids are fixed per file ("gmic-…"), so the phone and this computer name
/// the same look the same way, and running it again only fetches what's missing.
pub mod gmic {
    use super::*;

    pub const BASE: &str = "https://raw.githubusercontent.com/YahiaAngelo/Film-Luts/main/";
    pub const INDEX: &str = "film_luts.json";

    #[derive(Clone, Debug, PartialEq)]
    pub struct Item {
        pub id: String,
        pub name: String,
        pub file: String,
        pub category: String,
        pub thumbnail: Option<String>,
    }

    const CATEGORIES: [(&str, &str); 9] = [
        ("Negative New", "Negative"),
        ("Negative Old", "Negative (classic)"),
        ("Negative Color", "Negative (color)"),
        ("Colorslide", "Slide"),
        ("Bw", "B&W"),
        ("Instant Pro", "Instant (pro)"),
        ("Instant Consumer", "Instant"),
        ("Fujixtransiii", "Fuji X-Trans III"),
        ("Print", "Cinema print"),
    ];

    const WORDS: [(&str, &str); 17] = [
        ("Vc", "VC"),
        ("Nc", "NC"),
        ("Uc", "UC"),
        ("Hc", "HC"),
        ("Bw", "BW"),
        ("Cn", "CN"),
        ("Iii", "III"),
        ("Hie", "HIE"),
        ("Hs", "HS"),
        ("Hp", "HP"),
        ("Xp", "XP"),
        ("Fp", "FP"),
        ("Hps", "HPS"),
        ("Ir", "IR"),
        ("Apx", "APX"),
        ("Gx", "GX"),
        ("Xtrans", "X-Trans"),
    ];

    /// The phone's id for a pack file: push/pull variants ("+" / trailing "-") spelt out.
    pub fn id_of(file: &str) -> String {
        let stem = file.strip_prefix("luts/").unwrap_or(file);
        let stem = stem.rsplit_once('.').map_or(stem, |(s, _)| s).to_lowercase().replace('+', "p");
        let trimmed = stem.trim_end_matches('-');
        let dashes = stem.len() - trimmed.len();
        let stem = if dashes > 0 && trimmed.ends_with('_') { format!("{trimmed}{}", "m".repeat(dashes)) } else { stem.clone() };
        let mut out = String::new();
        for ch in stem.chars() {
            if ch.is_ascii_alphanumeric() {
                out.push(ch);
            } else if !out.ends_with('-') {
                out.push('-');
            }
        }
        format!("gmic-{}", out.trim_matches('-'))
    }

    pub fn parse(json: &str) -> Vec<Item> {
        let Ok(serde_json::Value::Array(items)) = serde_json::from_str::<serde_json::Value>(json) else { return Vec::new() };
        let mut seen = HashSet::new();
        items
            .iter()
            .filter_map(|o| {
                let field = |k: &str| o.get(k).and_then(|v| v.as_str()).map(str::to_string);
                let file = field("lut_file").filter(|f| f.to_lowercase().ends_with(".cube"))?;
                let name = field("name").unwrap_or_else(|| file.rsplit('/').next().unwrap_or(&file).trim_end_matches(".cube").to_string());
                let name = name.split(' ').map(|w| WORDS.iter().find(|(a, _)| *a == w).map_or(w, |(_, b)| b)).collect::<Vec<_>>().join(" ");
                let category = field("category").unwrap_or_else(|| "G'MIC".into());
                let category = CATEGORIES.iter().find(|(a, _)| *a == category).map_or(category.clone(), |(_, b)| b.to_string());
                let id = id_of(&file);
                seen.insert(id.clone()).then(|| Item { id, name, file, category, thumbnail: field("thumbnail") })
            })
            .collect()
    }

    fn fetch(agent: &ureq::Agent, url: &str) -> io::Result<Vec<u8>> {
        let resp = agent.get(url).call().map_err(|e| io::Error::other(e.to_string()))?;
        let mut out = Vec::new();
        resp.into_reader().take(64 << 20).read_to_end(&mut out)?;
        Ok(out)
    }

    /// Downloads what's missing of the pack into `root`; `done`/`total` follow along and `cancel`
    /// stops it between files.
    pub fn download(root: &Path, done: &AtomicUsize, total: &AtomicUsize, cancel: &AtomicBool) -> io::Result<Added> {
        let agent = ureq::AgentBuilder::new().timeout_connect(std::time::Duration::from_secs(15)).timeout_read(std::time::Duration::from_secs(30)).build();
        let items = parse(&String::from_utf8_lossy(&fetch(&agent, &format!("{BASE}{INDEX}"))?));
        if items.is_empty() {
            return Err(io::Error::other("the pack's list is empty"));
        }
        let have: HashSet<String> = list(root).into_iter().map(|e| e.id).collect();
        let todo: Vec<&Item> = items.iter().filter(|i| !have.contains(&i.id)).collect();
        total.store(todo.len(), Ordering::Relaxed);
        let result = Mutex::new(Added::default());
        let next = AtomicUsize::new(0);
        std::thread::scope(|s| {
            for _ in 0..4 {
                s.spawn(|| loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= todo.len() || cancel.load(Ordering::Relaxed) {
                        break;
                    }
                    let item = todo[i];
                    let got = fetch(&agent, &format!("{BASE}{}", item.file))
                        .and_then(|b| Lut::parse_cube(&String::from_utf8_lossy(&b)).map_err(io::Error::other))
                        .and_then(|lut| {
                            if let Some(t) = &item.thumbnail {
                                if let Ok(bytes) = fetch(&agent, &format!("{BASE}{t}")) {
                                    let _ = fs::create_dir_all(dir(root));
                                    let _ = write_atomic(&dir(root).join(format!("{}.jpg", super::super::file_name(&item.id))), &bytes);
                                }
                            }
                            store(root, &lut, &item.name, &item.category, Some(&item.id))
                        });
                    let mut r = result.lock().unwrap();
                    match got {
                        Ok(e) => r.added.push(e),
                        Err(_) => r.skipped += 1,
                    }
                    done.fetch_add(1, Ordering::Relaxed);
                });
            }
        });
        let r = result.into_inner().unwrap();
        if r.added.is_empty() && r.skipped > 0 && r.skipped == todo.len() {
            return Err(io::Error::other("couldn't download the LUTs"));
        }
        Ok(r)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stores_lists_and_deletes_looks() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let a = store(root, &Lut::identity(17), "Kodak Gold", "Kodak", None).unwrap();
        assert_eq!(a.id, "kodak-kodak-gold");
        let b = store(root, &Lut::identity(17), "Kodak Gold", "Kodak", None).unwrap();
        assert_eq!(b.id, "kodak-kodak-gold-2");
        assert_eq!(list(root).len(), 2);
        assert_eq!(super::super::lut::find(root, &a.id).unwrap().size, SIZE);
        delete(root, std::slice::from_ref(&a.id)).unwrap();
        assert_eq!(list(root), vec![b]);
    }

    #[test]
    fn imports_cube_png_and_zip() {
        use std::io::Write;
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let mut cube = String::from("TITLE \"Warm\"\nLUT_3D_SIZE 2\n");
        for b in 0..2 {
            for g in 0..2 {
                for r in 0..2 {
                    cube += &format!("{r} {g} {b}\n");
                }
            }
        }
        // A level-2 Hald image: 8x8 pixels holding a 4³ identity.
        let mut hald = Vec::new();
        for b in 0..4 {
            for g in 0..4 {
                for r in 0..4 {
                    hald.extend([r * 85u8, g * 85, b * 85]);
                }
            }
        }
        let mut png_bytes = Vec::new();
        {
            let mut enc = png::Encoder::new(&mut png_bytes, 8, 8);
            enc.set_color(png::ColorType::Rgb);
            enc.write_header().unwrap().write_image_data(&hald).unwrap();
        }
        let mut zip_bytes = Vec::new();
        {
            let mut z = zip::ZipWriter::new(io::Cursor::new(&mut zip_bytes));
            let o = zip::write::SimpleFileOptions::default();
            z.start_file("Pack/Kodak/gold.cube", o).unwrap();
            z.write_all(cube.as_bytes()).unwrap();
            z.start_file("Pack/Fuji/hald.png", o).unwrap();
            z.write_all(&png_bytes).unwrap();
            z.start_file("__MACOSX/Pack/._gold.cube", o).unwrap();
            z.write_all(b"junk").unwrap();
            z.start_file("readme.txt", o).unwrap();
            z.finish().unwrap();
        }
        let one = import(root, "warm.cube", cube.as_bytes());
        assert_eq!((one.added.len(), one.added[0].name.as_str(), one.added[0].folder.as_str()), (1, "Warm", "Imported"));
        let pack = import(root, "Pack.zip", &zip_bytes);
        assert_eq!(pack.skipped, 0);
        let got: Vec<(&str, &str)> = pack.added.iter().map(|e| (e.name.as_str(), e.folder.as_str())).collect();
        assert_eq!(got, vec![("Warm", "Kodak"), ("hald", "Fuji")]);
        let l = super::super::lut::find(root, &pack.added[1].id).unwrap();
        let v = l.at(0.5, 0.2, 0.9);
        assert!((v[0] - 0.5).abs() < 0.02 && (v[1] - 0.2).abs() < 0.02 && (v[2] - 0.9).abs() < 0.02, "{v:?}");
        assert_eq!(import(root, "notes.txt", b"x").skipped, 1);
    }

    #[test]
    fn sbl_round_trips() {
        let l = Lut::identity(5);
        let back = Lut::read_sbl(&l.write_sbl()).unwrap();
        assert_eq!(back.size, 5);
        assert!(back.data.iter().zip(&l.data).all(|(a, b)| (a - b).abs() < 1e-4));
    }

    #[test]
    fn gmic_ids_and_names_match_the_phone() {
        assert_eq!(gmic::id_of("luts/Kodak_Portra_400_+.cube"), "gmic-kodak-portra-400-p");
        assert_eq!(gmic::id_of("luts/Fuji_Superia_200_--.cube"), "gmic-fuji-superia-200-mm");
        let items = gmic::parse(
            r#"[{"name":"Kodak Portra 400 Vc","lut_file":"luts/a.cube","category":"Negative New","thumbnail":"t/a.jpg"},{"name":"x","lut_file":"luts/a.png"}]"#,
        );
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].name, "Kodak Portra 400 VC");
        assert_eq!(items[0].category, "Negative");
        assert_eq!(items[0].thumbnail.as_deref(), Some("t/a.jpg"));
    }

    #[test]
    fn hald_images_become_tables() {
        // Level 2: a 8x8 image holds a 4³ table.
        let lut = Lut::identity(4);
        let rgb: Vec<u8> = lut.data.iter().map(|v| (v * 255.0).round() as u8).collect();
        let back = Lut::from_hald(8, 8, &rgb).unwrap();
        assert_eq!(back.size, 4);
        assert!(Lut::from_hald(8, 7, &rgb).is_err());
    }
}
