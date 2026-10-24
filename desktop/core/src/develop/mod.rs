//! The develop engine, ported from the phone so an edit looks the same on both: the edit format,
//! colour, geometry, raw decoding and the look's maths. The per-pixel pipeline runs on the GPU
//! (the app's shader, a WGSL port of the phone's AGSL one).

pub mod color;
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
            color: color::CameraColor::for_camera(&raw.info.model),
            baked: raw.info.as_shot,
            is_raw: true,
            full_width: if swap { raw.height } else { raw.width },
            full_height: if swap { raw.width } else { raw.height },
            lens: raw.info.lens.clone(),
        };
        return Ok(Opened { path: path.to_path_buf(), source, orientation, raw: Some(raw) });
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
    };
    Ok(Opened { path: path.to_path_buf(), source, orientation, raw: None })
}

impl Opened {
    /// The whole photo at full resolution, sensor-oriented, for export.
    pub fn full_image(&self) -> Result<FullImage, String> {
        if let Some(raw) = &self.raw {
            return Ok(FullImage { width: raw.width, height: raw.height, data: raw.demosaic(), orientation: self.orientation });
        }
        let bytes = fs::read(&self.path).map_err(|e| e.to_string())?;
        let (img, w, h) = decode_jpeg(&bytes, None)?;
        let data = (0..w * h).flat_map(|i| [img.r[i], img.g[i], img.b[i]].map(|v| (v.clamp(0.0, 1.0) * 65535.0 + 0.5) as u16)).collect();
        Ok(FullImage { width: w, height: h, data, orientation: self.orientation })
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
