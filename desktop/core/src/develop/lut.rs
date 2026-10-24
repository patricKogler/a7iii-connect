//! 3D colour lookup tables, the way film looks are shared: display sRGB in, display sRGB out (the
//! phone's Lut.kt). Edits name a LUT by id; one nobody has here renders without it.

use std::path::Path;

#[derive(Clone, Debug, PartialEq)]
pub struct Lut {
    pub size: usize,
    /// size³ RGB triplets, red fastest, then green, then blue (the .cube order).
    pub data: Vec<f32>,
}

/// What every LUT is resampled to: the common .cube size, small enough to be one texture.
pub const SIZE: usize = 33;
const MAGIC: u32 = 0x5342_4c31; // "SBL1"

impl Lut {
    /// The table's colour for (r, g, b) in 0..1, trilinear between its nodes.
    pub fn at(&self, r: f32, g: f32, b: f32) -> [f32; 3] {
        let n = self.size;
        let m = (n - 1) as f32;
        let (x, y, z) = (r.clamp(0.0, 1.0) * m, g.clamp(0.0, 1.0) * m, b.clamp(0.0, 1.0) * m);
        let (x0, y0, z0) = ((x as usize).min(n - 2), (y as usize).min(n - 2), (z as usize).min(n - 2));
        let (fx, fy, fz) = (x - x0 as f32, y - y0 as f32, z - z0 as f32);
        std::array::from_fn(|c| {
            let v = |i: usize, j: usize, k: usize| self.data[(((z0 + k) * n + y0 + j) * n + x0 + i) * 3 + c];
            let a = (v(0, 0, 0) * (1.0 - fx) + v(1, 0, 0) * fx) * (1.0 - fy) + (v(0, 1, 0) * (1.0 - fx) + v(1, 1, 0) * fx) * fy;
            let b = (v(0, 0, 1) * (1.0 - fx) + v(1, 0, 1) * fx) * (1.0 - fy) + (v(0, 1, 1) * (1.0 - fx) + v(1, 1, 1) * fx) * fy;
            a * (1.0 - fz) + b * fz
        })
    }

    /// The same table sampled at n³ nodes.
    pub fn resized(&self, n: usize) -> Lut {
        if n == self.size {
            return self.clone();
        }
        let k = (n - 1) as f32;
        let mut data = Vec::with_capacity(n * n * n * 3);
        for b in 0..n {
            for g in 0..n {
                for r in 0..n {
                    data.extend(self.at(r as f32 / k, g as f32 / k, b as f32 / k));
                }
            }
        }
        Lut { size: n, data }
    }

    /// The phone's compact storage: "SBL1", the size, then 16 bits per value (big-endian).
    pub fn read_sbl(bytes: &[u8]) -> Option<Lut> {
        if bytes.len() < 6 || u32::from_be_bytes(bytes[..4].try_into().ok()?) != MAGIC {
            return None;
        }
        let n = u16::from_be_bytes([bytes[4], bytes[5]]) as usize;
        let count = n * n * n * 3;
        if n < 2 || bytes.len() < 6 + count * 2 {
            return None;
        }
        let data = bytes[6..6 + count * 2].as_chunks::<2>().0.iter().map(|c| u16::from_be_bytes(*c) as f32 / 65535.0).collect();
        Some(Lut { size: n, data })
    }

    /// A .cube file's TITLE, when it has one.
    pub fn cube_title(text: &str) -> Option<String> {
        text.lines().take(50).find_map(|l| {
            let l = l.trim();
            let rest = l.get(..5).filter(|h| h.eq_ignore_ascii_case("TITLE")).map(|_| l[5..].trim())?;
            let t = rest.trim_matches('"').trim();
            (!t.is_empty()).then(|| t.to_string())
        })
    }

    /// An Adobe/Resolve .cube file (3D only).
    pub fn parse_cube(text: &str) -> Result<Lut, String> {
        let mut size = 0;
        let (mut min, mut max) = ([0f32; 3], [1f32; 3]);
        let mut values: Vec<f32> = Vec::new();
        for raw in text.lines() {
            let line = raw.split('#').next().unwrap_or("").trim();
            if line.is_empty() {
                continue;
            }
            let parts: Vec<&str> = line.split_whitespace().collect();
            match parts[0].to_ascii_uppercase().as_str() {
                "TITLE" => {}
                "LUT_3D_SIZE" => {
                    size = parts.get(1).and_then(|v| v.parse().ok()).filter(|s| (2..=256).contains(s)).ok_or("bad LUT_3D_SIZE")?;
                    values.reserve(size * size * size * 3);
                }
                "LUT_1D_SIZE" => return Err("a 1D LUT, not a colour look".into()),
                "DOMAIN_MIN" => min = std::array::from_fn(|c| parts.get(c + 1).and_then(|v| v.parse().ok()).unwrap_or(0.0)),
                "DOMAIN_MAX" => max = std::array::from_fn(|c| parts.get(c + 1).and_then(|v| v.parse().ok()).unwrap_or(1.0)),
                "LUT_3D_INPUT_RANGE" => {
                    let lo = parts.get(1).and_then(|v| v.parse().ok()).unwrap_or(0.0);
                    let hi = parts.get(2).and_then(|v| v.parse().ok()).unwrap_or(1.0);
                    min = [lo; 3];
                    max = [hi; 3];
                }
                _ => {
                    if size == 0 || parts.len() < 3 || values.len() >= size * size * size * 3 {
                        continue;
                    }
                    for p in &parts[..3] {
                        values.push(p.parse().map_err(|_| format!("bad colour line: {line}"))?);
                    }
                }
            }
        }
        if size == 0 {
            return Err("no LUT_3D_SIZE".into());
        }
        if values.len() != size * size * size * 3 {
            return Err(format!("expected {} colours, found {}", size * size * size, values.len() / 3));
        }
        let lut = Lut { size, data: values };
        if min == [0.0; 3] && max == [1.0; 3] {
            return Ok(lut);
        }
        // Inputs outside 0..1 (a log domain): sample the table back onto 0..1.
        let k = (size - 1) as f32;
        let pos = |i: usize, c: usize| (i as f32 / k - min[c]) / (max[c] - min[c]);
        let mut data = Vec::with_capacity(lut.data.len());
        for b in 0..size {
            for g in 0..size {
                for r in 0..size {
                    data.extend(lut.at(pos(r, 0), pos(g, 1), pos(b, 2)));
                }
            }
        }
        Ok(Lut { size, data })
    }

    /// The phone's compact storage, the other way.
    pub fn write_sbl(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(6 + self.data.len() * 2);
        out.extend(MAGIC.to_be_bytes());
        out.extend((self.size as u16).to_be_bytes());
        for v in &self.data {
            out.extend(((v.clamp(0.0, 1.0) * 65535.0).round() as u16).to_be_bytes());
        }
        out
    }

    /// A Hald CLUT image (square, level³ pixels a side) from its 8-bit RGB pixels, row by row.
    pub fn from_hald(width: usize, height: usize, rgb: &[u8]) -> Result<Lut, String> {
        if width != height {
            return Err("a Hald image is square".into());
        }
        let level = (2..64).find(|l| l * l * l >= width).ok_or("too big for a Hald image")?;
        if level * level * level != width || rgb.len() < width * height * 3 {
            return Err(format!("{width}x{height} isn't a Hald image size"));
        }
        let n = level * level;
        Ok(Lut { size: n, data: rgb[..n * n * n * 3].iter().map(|&v| v as f32 / 255.0).collect() })
    }

    pub fn identity(n: usize) -> Lut {
        let k = (n - 1) as f32;
        let mut data = Vec::with_capacity(n * n * n * 3);
        for b in 0..n {
            for g in 0..n {
                for r in 0..n {
                    data.extend([r as f32 / k, g as f32 / k, b as f32 / k]);
                }
            }
        }
        Lut { size: n, data }
    }
}

/// The LUT an edit names, from the library's `.shootback/luts/` (the phone's stored form, or a
/// .cube), at [`SIZE`]³.
pub fn find(root: &Path, id: &str) -> Option<Lut> {
    let dir = root.join(crate::library::STATE_DIR).join("luts");
    let safe = super::file_name(id);
    let lut = std::fs::read(dir.join(format!("{safe}.lut")))
        .ok()
        .and_then(|b| Lut::read_sbl(&b))
        .or_else(|| std::fs::read_to_string(dir.join(format!("{safe}.cube"))).ok().and_then(|t| Lut::parse_cube(&t).ok()))?;
    Some(lut.resized(SIZE))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_cube_changes_nothing() {
        let mut text = String::from("TITLE \"id\"\nLUT_3D_SIZE 2\n");
        for b in 0..2 {
            for g in 0..2 {
                for r in 0..2 {
                    text += &format!("{r} {g} {b}\n");
                }
            }
        }
        let lut = Lut::parse_cube(&text).unwrap();
        assert_eq!(lut, Lut::identity(2));
        let c = lut.resized(5).at(0.3, 0.6, 0.9);
        assert!((c[0] - 0.3).abs() < 1e-5 && (c[2] - 0.9).abs() < 1e-5);
    }

    #[test]
    fn reads_the_phones_stored_form() {
        let mut b = MAGIC.to_be_bytes().to_vec();
        b.extend(2u16.to_be_bytes());
        for v in Lut::identity(2).data {
            b.extend(((v * 65535.0) as u16).to_be_bytes());
        }
        assert_eq!(Lut::read_sbl(&b).unwrap(), Lut::identity(2));
    }
}
