//! Grey maps over the upright photo that masks select with (the phone's MaskImage): the subject,
//! the sky and the depth found in a photo, and the ones painted or clicked for one mask. They're
//! kept beside the edits, 8 bits a pixel after a width and height, in the phone's format.

use crate::library::{write_atomic, STATE_DIR};
use std::fs;
use std::path::{Path, PathBuf};

/// A 0..1 map over the upright photo (the subject: 1 on it, 0 off it).
#[derive(Clone, Debug, PartialEq)]
pub struct MaskImage {
    pub width: usize,
    pub height: usize,
    pub data: Vec<f32>,
}

impl MaskImage {
    pub fn new(width: usize, height: usize) -> MaskImage {
        MaskImage { width, height, data: vec![0.0; width * height] }
    }

    /// Bilinear value at fractions (u, v) of the photo.
    pub fn at(&self, u: f32, v: f32) -> f32 {
        let x = (u * self.width as f32 - 0.5).clamp(0.0, self.width as f32 - 1.0);
        let y = (v * self.height as f32 - 0.5).clamp(0.0, self.height as f32 - 1.0);
        let (x0, y0) = (x as usize, y as usize);
        let (x1, y1) = ((x0 + 1).min(self.width - 1), (y0 + 1).min(self.height - 1));
        let (fx, fy) = (x - x0 as f32, y - y0 as f32);
        let d = &self.data;
        let w = self.width;
        let a = d[y0 * w + x0] + (d[y0 * w + x1] - d[y0 * w + x0]) * fx;
        let b = d[y1 * w + x0] + (d[y1 * w + x1] - d[y1 * w + x0]) * fx;
        a + (b - a) * fy
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut b = Vec::with_capacity(4 + self.data.len());
        b.extend([(self.width >> 8) as u8, self.width as u8, (self.height >> 8) as u8, self.height as u8]);
        b.extend(self.data.iter().map(|v| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8));
        b
    }

    pub fn decode(b: &[u8]) -> Option<MaskImage> {
        if b.len() < 4 {
            return None;
        }
        let w = (b[0] as usize) << 8 | b[1] as usize;
        let h = (b[2] as usize) << 8 | b[3] as usize;
        if w == 0 || h == 0 || b.len() < 4 + w * h {
            return None;
        }
        Some(MaskImage { width: w, height: h, data: b[4..4 + w * h].iter().map(|&v| v as f32 / 255.0).collect() })
    }
}

/// What a photo's map shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MapKind {
    Subject,
    Sky,
    /// Nearness, 1 close to 0 far.
    Depth,
}

impl MapKind {
    /// The file's extension; the subject's and the sky's are the phone's.
    fn ext(self) -> &'static str {
        match self {
            MapKind::Subject => "mask",
            MapKind::Sky => "sky",
            MapKind::Depth => "depth",
        }
    }
}

/// The maps beside a library (`.shootback/subjects/`, the phone's `develop/subjects/`).
pub struct MapStore {
    dir: PathBuf,
}

impl MapStore {
    pub fn new(root: &Path) -> MapStore {
        MapStore { dir: root.join(STATE_DIR).join("subjects") }
    }

    fn file(&self, key: &str, kind: MapKind) -> PathBuf {
        self.dir.join(format!("{}.{}", super::file_name(key), kind.ext()))
    }

    fn mask_file(&self, key: &str, id: &str) -> PathBuf {
        self.dir.join(format!("{}.{}.paint", super::file_name(key), super::file_name(id)))
    }

    pub fn load(&self, key: &str, kind: MapKind) -> Option<MaskImage> {
        MaskImage::decode(&fs::read(self.file(key, kind)).ok()?)
    }

    pub fn save(&self, key: &str, kind: MapKind, m: &MaskImage) -> std::io::Result<()> {
        write_atomic(&self.file(key, kind), &m.encode())
    }

    /// One mask's own map (painted with the brush, or picked by clicking), by the mask's id.
    pub fn load_mask(&self, key: &str, id: &str) -> Option<MaskImage> {
        MaskImage::decode(&fs::read(self.mask_file(key, id)).ok()?)
    }

    pub fn save_mask(&self, key: &str, id: &str, m: &MaskImage) -> std::io::Result<()> {
        write_atomic(&self.mask_file(key, id), &m.encode())
    }

    /// Takes away the maps of masks that are gone from an edit.
    pub fn keep_masks(&self, key: &str, ids: &[&str]) {
        let prefix = format!("{}.", super::file_name(key));
        let keep: Vec<String> = ids.iter().map(|id| format!("{prefix}{}.paint", super::file_name(id))).collect();
        let Ok(dir) = fs::read_dir(&self.dir) else { return };
        for e in dir.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.starts_with(&prefix) && name.ends_with(".paint") && !keep.contains(&name) {
                let _ = fs::remove_file(e.path());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_round_trip_in_the_phones_format() {
        let m = MaskImage { width: 3, height: 2, data: vec![0.0, 0.5, 1.0, 1.0, 0.25, 0.0] };
        let b = m.encode();
        assert_eq!(&b[..4], &[0, 3, 0, 2]);
        let back = MaskImage::decode(&b).unwrap();
        assert!(back.data.iter().zip(&m.data).all(|(a, b)| (a - b).abs() < 0.003));
    }

    #[test]
    fn mask_maps_go_with_their_masks() {
        let dir = tempfile::tempdir().unwrap();
        let store = MapStore::new(dir.path());
        let m = MaskImage::new(4, 4);
        store.save_mask("A.ARW@1", "x1", &m).unwrap();
        store.save_mask("A.ARW@1", "x2", &m).unwrap();
        store.save("A.ARW@1", MapKind::Sky, &m).unwrap();
        store.keep_masks("A.ARW@1", &["x2"]);
        assert!(store.load_mask("A.ARW@1", "x1").is_none());
        assert!(store.load_mask("A.ARW@1", "x2").is_some());
        assert!(store.load("A.ARW@1", MapKind::Sky).is_some());
    }
}
