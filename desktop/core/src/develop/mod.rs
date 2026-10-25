//! The develop engine, ported from the phone so an edit looks the same on both: the edit format,
//! colour, geometry, raw decoding and the look's maths. The per-pixel pipeline runs on the GPU
//! (the app's shader, a WGSL port of the phone's AGSL one).

pub mod color;
pub mod dng;
pub mod dng_color;
pub mod export;
pub mod geometry;
pub mod image;
pub mod look;
pub mod lut;
pub mod luts;
pub mod maps;
pub mod params;
pub mod presets;
pub mod raw;

use crate::library::{write_atomic, STATE_DIR};
use image::LinearImage;
use look::{FullImage, Source};
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

pub use params::Settings;

/// Long edge of the editing preview, as on the phone.
pub const PREVIEW_EDGE: usize = 2048;

/// A photo opened for editing.
pub struct Opened {
    pub path: PathBuf,
    pub source: Source,
    pub orientation: u8,
    /// The decoded sensor data, kept for a full-size export.
    pub raw: Option<raw::RawImage>,
    /// The raw after AI denoise, once that's been run (see `ai::denoise`).
    pub denoised: OnceLock<Denoised>,
    /// The Remove tool's fills for the preview, by `removal_keys`.
    pub removed: std::sync::Mutex<std::collections::HashMap<String, std::sync::Arc<crate::ai::inpaint::Patch>>>,
}

/// A raw's active area after AI denoise: at full size as `RawImage::demosaic` gives it, and as
/// the preview (like `Source::image`).
pub struct Denoised {
    pub full: Vec<u16>,
    pub preview: LinearImage,
}

impl Denoised {
    /// From the full-size pixels: the preview is made as for the raw.
    pub fn new(full: Vec<u16>, width: usize, height: usize, orientation: u8) -> Denoised {
        // Halved by averaging 2x2 first, as the raw preview is a half-size decode.
        let (hw, hh) = (width / 2, height / 2);
        let mut half = LinearImage::new(hw, hh);
        for y in 0..hh {
            for x in 0..hw {
                let mut sum = [0u32; 3];
                for (dy, dx) in [(0, 0), (0, 1), (1, 0), (1, 1)] {
                    let i = ((2 * y + dy) * width + 2 * x + dx) * 3;
                    for c in 0..3 {
                        sum[c] += full[i + c] as u32;
                    }
                }
                let o = y * hw + x;
                half.r[o] = sum[0] as f32 / (4.0 * 65535.0);
                half.g[o] = sum[1] as f32 / (4.0 * 65535.0);
                half.b[o] = sum[2] as f32 / (4.0 * 65535.0);
            }
        }
        Denoised { full, preview: half.downscaled(PREVIEW_EDGE).oriented(orientation) }
    }
}

/// `a` and `b` mixed, `t` of the way to `b`.
fn mix(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn decode_jpeg(bytes: &[u8], min_edge: Option<usize>) -> Result<(LinearImage, usize, usize), String> {
    let mut d = jpeg_decoder::Decoder::new(bytes);
    d.read_info().map_err(|e| e.to_string())?;
    let info = d.info().ok_or("no JPEG header")?;
    let (fw, fh) = (info.width as usize, info.height as usize);
    if let Some(edge) = min_edge {
        // DCT scaling to the smallest size at least `edge` on the long side.
        let k = (fw.max(fh) / edge).clamp(1, 8);
        let k = [8, 4, 2, 1].into_iter().find(|&s| s <= k).unwrap_or(1);
        d.scale((fw / k) as u16, (fh / k) as u16).map_err(|e| e.to_string())?;
    }
    let px = d.decode().map_err(|e| e.to_string())?;
    let info = d.info().ok_or("no JPEG header")?;
    let (w, h) = (info.width as usize, info.height as usize);
    let rgb: Vec<u8> = match info.pixel_format {
        jpeg_decoder::PixelFormat::RGB24 => px,
        jpeg_decoder::PixelFormat::L8 => px.iter().flat_map(|&v| [v, v, v]).collect(),
        f => return Err(format!("unsupported JPEG pixels {f:?}")),
    };
    Ok((LinearImage::from_srgb8(&rgb, w, h), fw, fh))
}

/// Decodes a photo for editing. Raw: half-size demosaic; JPEG: DCT-scaled decode.
pub fn open(path: &Path) -> Result<Opened, String> {
    let bytes = fs::read(path).map_err(|e| e.to_string())?;
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    if crate::library::is_raw_name(&name) {
        let info = raw::read_info(&bytes)?;
        let orientation = info.orientation;
        let raw = raw::decode(&bytes, info)?;
        let preview = raw.half_size().downscaled(PREVIEW_EDGE).oriented(orientation);
        let swap = orientation >= 5;
        let source = Source {
            image: preview,
            color: raw.info.color(),
            baked: raw.info.as_shot,
            is_raw: true,
            full_width: if swap { raw.height } else { raw.width },
            full_height: if swap { raw.width } else { raw.height },
            lens: raw.info.lens.clone(),
            baseline: raw.info.baseline_exposure,
        };
        return Ok(Opened { path: path.to_path_buf(), source, orientation, raw: Some(raw), denoised: OnceLock::new(), removed: Default::default() });
    }
    let orientation = crate::meta::read(path).orientation;
    let (img, fw, fh) = decode_jpeg(&bytes, Some(PREVIEW_EDGE))?;
    let swap = orientation >= 5;
    let source = Source {
        image: img.downscaled(PREVIEW_EDGE).oriented(orientation),
        color: color::CameraColor::srgb(),
        baked: [1.0; 3],
        is_raw: false,
        full_width: if swap { fh } else { fw },
        full_height: if swap { fw } else { fh },
        lens: None,
        baseline: 0.0,
    };
    Ok(Opened { path: path.to_path_buf(), source, orientation, raw: None, denoised: OnceLock::new(), removed: Default::default() })
}

impl Opened {
    /// How much of the AI-denoised raw `s` mixes in (0..1): 0 when there's none yet.
    pub fn denoise_amount(&self, s: &Settings) -> f32 {
        if self.denoised.get().is_some() {
            s.get(&params::p::AI_DENOISE) / 100.0
        } else {
            0.0
        }
    }

    /// What each of the removals in `s` is filled for: it and the ones before it (a fill sees
    /// the earlier ones done).
    pub fn removal_keys(s: &Settings) -> Vec<String> {
        let mut key = String::new();
        s.removals
            .iter()
            .map(|r| {
                key.push_str(&r.encode());
                key.push('|');
                key.clone()
            })
            .collect()
    }

    /// How many of the removals in `s` (from the first) the preview has fills for.
    pub fn removals_ready(&self, s: &Settings) -> usize {
        let have = self.removed.lock().unwrap();
        Self::removal_keys(s).iter().take_while(|k| have.contains_key(*k)).count()
    }

    /// Works out the preview's fill for removal `i` of `s` (the ones before it ready).
    pub fn fill_removal(&self, s: &Settings, i: usize) -> Result<(), String> {
        let key = Self::removal_keys(s).swap_remove(i);
        let before = Settings { removals: s.removals[..i].to_vec(), ..s.clone() };
        let img = self.preview(&before);
        let (pts, radius) = crate::ai::inpaint::place(&s.removals[i], img.width, img.height, 1);
        let w = img.width;
        let patch = crate::ai::inpaint::fill(|x, y| [img.r[y * w + x], img.g[y * w + x], img.b[y * w + x]], w, img.height, &pts, radius)?;
        self.removed.lock().unwrap().insert(key, std::sync::Arc::new(patch));
        Ok(())
    }

    /// The preview with AI denoise mixed in and the removals filled, as `s` asks (as far as
    /// their fills are ready).
    pub fn preview(&self, s: &Settings) -> std::borrow::Cow<'_, LinearImage> {
        let img = self.denoised_preview(s);
        if s.removals.is_empty() {
            return img;
        }
        let have = self.removed.lock().unwrap();
        let patches: Vec<_> = Self::removal_keys(s).iter().map_while(|k| have.get(k).cloned()).collect();
        drop(have);
        if patches.is_empty() {
            return img;
        }
        let mut img = img.into_owned();
        let w = img.width;
        for p in patches {
            for (x, y, n, a) in p.pixels() {
                let i = y * w + x;
                img.r[i] = mix(img.r[i], n[0], a);
                img.g[i] = mix(img.g[i], n[1], a);
                img.b[i] = mix(img.b[i], n[2], a);
            }
        }
        std::borrow::Cow::Owned(img)
    }

    fn denoised_preview(&self, s: &Settings) -> std::borrow::Cow<'_, LinearImage> {
        let t = self.denoise_amount(s);
        match self.denoised.get() {
            Some(d) if t > 0.0 && d.preview.width == self.source.image.width && d.preview.height == self.source.image.height => {
                if t >= 1.0 {
                    return std::borrow::Cow::Borrowed(&d.preview);
                }
                let a = &self.source.image;
                let mut img = a.clone();
                for i in 0..img.r.len() {
                    img.r[i] = mix(a.r[i], d.preview.r[i], t);
                    img.g[i] = mix(a.g[i], d.preview.g[i], t);
                    img.b[i] = mix(a.b[i], d.preview.b[i], t);
                }
                std::borrow::Cow::Owned(img)
            }
            _ => std::borrow::Cow::Borrowed(&self.source.image),
        }
    }

    /// The whole photo at full resolution, sensor-oriented, for export, with AI denoise mixed in
    /// as `s` asks.
    pub fn full_image(&self, s: &Settings) -> Result<FullImage, String> {
        if let Some(raw) = &self.raw {
            let t = self.denoise_amount(s);
            let data = match self.denoised.get() {
                Some(d) if t >= 1.0 => d.full.clone(),
                Some(d) if t > 0.0 => {
                    let mut data = raw.demosaic();
                    for (v, &n) in data.iter_mut().zip(&d.full) {
                        *v = mix(*v as f32, n as f32, t).round() as u16;
                    }
                    data
                }
                _ => raw.demosaic(),
            };
            let mut full = FullImage { width: raw.width, height: raw.height, data, orientation: self.orientation };
            remove_in(&mut full, s)?;
            return Ok(full);
        }
        let bytes = fs::read(&self.path).map_err(|e| e.to_string())?;
        let (img, w, h) = decode_jpeg(&bytes, None)?;
        let data = (0..w * h).flat_map(|i| [img.r[i], img.g[i], img.b[i]].map(|v| (v.clamp(0.0, 1.0) * 65535.0 + 0.5) as u16)).collect();
        let mut full = FullImage { width: w, height: h, data, orientation: self.orientation };
        remove_in(&mut full, s)?;
        Ok(full)
    }
}

/// Fills the removals of `s` into the full-size picture, one after the other.
fn remove_in(full: &mut FullImage, s: &Settings) -> Result<(), String> {
    let (w, h) = (full.width, full.height);
    for r in &s.removals {
        let (pts, radius) = crate::ai::inpaint::place(r, w, h, full.orientation);
        let data = &full.data;
        let patch = crate::ai::inpaint::fill(
            |x, y| {
                let i = (y * w + x) * 3;
                [data[i] as f32 / 65535.0, data[i + 1] as f32 / 65535.0, data[i + 2] as f32 / 65535.0]
            },
            w,
            h,
            &pts,
            radius,
        )?;
        for (x, y, n, a) in patch.pixels() {
            let i = (y * w + x) * 3;
            for (v, n) in full.data[i..i + 3].iter_mut().zip(n) {
                *v = (mix(*v as f32 / 65535.0, n, a).clamp(0.0, 1.0) * 65535.0 + 0.5) as u16;
            }
        }
    }
    Ok(())
}

/// AI-denoised raws beside the library (`.shootback/denoised/`), so denoising runs once per
/// photo: the full-size pixels, deflated, after a width and height.
#[derive(Clone)]
pub struct DenoiseStore {
    dir: PathBuf,
}

impl DenoiseStore {
    pub fn new(root: &Path) -> DenoiseStore {
        DenoiseStore { dir: root.join(STATE_DIR).join("denoised") }
    }

    fn file(&self, key: &str) -> PathBuf {
        self.dir.join(file_name(key) + ".rgb16")
    }

    pub fn load(&self, key: &str, width: usize, height: usize) -> Option<Vec<u16>> {
        use std::io::Read;
        let f = fs::File::open(self.file(key)).ok()?;
        let mut r = std::io::BufReader::new(f);
        let mut head = [0u8; 8];
        r.read_exact(&mut head).ok()?;
        let (w, h) = (u32::from_le_bytes(head[..4].try_into().ok()?) as usize, u32::from_le_bytes(head[4..].try_into().ok()?) as usize);
        if (w, h) != (width, height) {
            return None;
        }
        let mut bytes = Vec::with_capacity(w * h * 6);
        flate2::read::DeflateDecoder::new(r).read_to_end(&mut bytes).ok()?;
        if bytes.len() != w * h * 6 {
            return None;
        }
        Some(bytes.as_chunks::<2>().0.iter().map(|&b| u16::from_le_bytes(b)).collect())
    }

    pub fn save(&self, key: &str, width: usize, height: usize, full: &[u16]) -> std::io::Result<()> {
        use std::io::Write;
        fs::create_dir_all(&self.dir)?;
        let mut out = Vec::with_capacity(full.len());
        out.extend((width as u32).to_le_bytes());
        out.extend((height as u32).to_le_bytes());
        let mut e = flate2::write::DeflateEncoder::new(out, flate2::Compression::fast());
        let bytes: Vec<u8> = full.iter().flat_map(|v| v.to_le_bytes()).collect();
        e.write_all(&bytes)?;
        write_atomic(&self.file(key), &e.finish()?)
    }

    pub fn remove(&self, key: &str) {
        let _ = fs::remove_file(self.file(key));
    }
}

/// A key as a file name, the phone's EditStore.fileName.
pub fn file_name(key: &str) -> String {
    key.chars().map(|c| if c.is_ascii_alphanumeric() || "._@-".contains(c) { c } else { '_' }).collect()
}

/// Edits beside the library, one small text file per photo named after its key, as on the phone
/// (`develop/edits/` there, `.shootback/edits/` here). The originals are never touched.
pub struct EditStore {
    dir: PathBuf,
}

impl EditStore {
    pub fn new(root: &Path) -> EditStore {
        EditStore { dir: root.join(STATE_DIR).join("edits") }
    }

    fn file(&self, key: &str) -> PathBuf {
        self.dir.join(file_name(key) + ".txt")
    }

    pub fn load(&self, key: &str) -> Settings {
        fs::read_to_string(self.file(key)).map(|t| Settings::decode(&t)).unwrap_or_default()
    }

    pub fn save(&self, key: &str, s: &Settings) -> std::io::Result<()> {
        let f = self.file(key);
        if s.is_default() {
            match fs::remove_file(&f) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
                _ => Ok(()),
            }
        } else {
            fs::create_dir_all(&self.dir)?;
            write_atomic(&f, s.encode().as_bytes())
        }
    }

    /// File names (without .txt) of the photos with edits.
    pub fn edited(&self) -> HashSet<String> {
        fs::read_dir(&self.dir)
            .map(|d| d.flatten().filter_map(|e| e.file_name().to_str()?.strip_suffix(".txt").map(String::from)).collect())
            .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edits_round_trip_and_defaults_delete() {
        let dir = tempfile::tempdir().unwrap();
        let store = EditStore::new(dir.path());
        let key = "DSC01234.ARW@1792420927000";
        assert!(store.load(key).is_default());
        let s = Settings::default().with(&params::p::EXPOSURE, 0.5);
        store.save(key, &s).unwrap();
        assert_eq!(store.load(key), s);
        assert!(store.edited().contains(&file_name(key)));
        store.save(key, &Settings::default()).unwrap();
        assert!(store.edited().is_empty());
    }

    #[test]
    fn file_names_match_the_phone() {
        assert_eq!(file_name("IMG 1.JPG@5|x"), "IMG_1.JPG@5_x");
    }
}
