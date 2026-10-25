//! DNG raws as phones write them (Pixel, Vivo, Samsung's Expert RAW): the phone's Dng.kt.
//! Plain or lossless-JPEG Bayer data in strips or tiles, and the lens shading GainMaps.

/// One DNG GainMap: gains on a grid over the image, for one plane, bilinear in between.
#[derive(Clone, Debug, PartialEq)]
pub struct GainMap {
    pub top: usize,
    pub left: usize,
    pub bottom: usize,
    pub right: usize,
    pub row_pitch: usize,
    pub col_pitch: usize,
    pub points_v: usize,
    pub points_h: usize,
    pub spacing_v: f64,
    pub spacing_h: f64,
    pub origin_v: f64,
    pub origin_h: f64,
    pub map_planes: usize,
    /// `points_v` rows of `points_h` points of `map_planes` gains each.
    pub gains: Vec<f32>,
}

impl GainMap {
    /// The gain at (`v`, `h`), fractions of the image's height and width.
    pub fn gain(&self, v: f64, h: f64) -> f32 {
        let axis = |pos: f64, origin: f64, spacing: f64, points: usize| {
            if points == 1 || spacing <= 0.0 {
                0.0
            } else {
                ((pos - origin) / spacing).clamp(0.0, (points - 1) as f64)
            }
        };
        let fy = axis(v, self.origin_v, self.spacing_v, self.points_v);
        let fx = axis(h, self.origin_h, self.spacing_h, self.points_h);
        let (y0, x0) = ((fy.floor() as usize).min(self.points_v - 1), (fx.floor() as usize).min(self.points_h - 1));
        let (y1, x1) = ((y0 + 1).min(self.points_v - 1), (x0 + 1).min(self.points_h - 1));
        let (ty, tx) = ((fy - y0 as f64) as f32, (fx - x0 as f64) as f32);
        let at = |y: usize, x: usize| self.gains[(y * self.points_h + x) * self.map_planes];
        let a = at(y0, x0) + (at(y0, x1) - at(y0, x0)) * tx;
        let b = at(y1, x0) + (at(y1, x1) - at(y1, x0)) * tx;
        a + (b - a) * ty
    }
}

/// The GainMap opcodes of an opcode list (always big-endian); other opcodes are skipped.
pub fn opcodes(b: &[u8]) -> Vec<GainMap> {
    let u32_at = |o: usize| b.get(o..o + 4).map_or(0, |s| u32::from_be_bytes([s[0], s[1], s[2], s[3]]));
    let f64_at = |o: usize| f64::from_bits(((u32_at(o) as u64) << 32) | u32_at(o + 4) as u64);
    let f32_at = |o: usize| f32::from_bits(u32_at(o));
    let mut out = Vec::new();
    let count = u32_at(0) as usize;
    let mut o = 4;
    for _ in 0..count {
        if o + 16 > b.len() {
            break;
        }
        let id = u32_at(o);
        let size = u32_at(o + 12) as usize;
        let p = o + 16;
        if id == 9 && size >= 76 && p + size <= b.len() {
            let (points_v, points_h, map_planes) = (u32_at(p + 32) as usize, u32_at(p + 36) as usize, u32_at(p + 72) as usize);
            let n = points_v * points_h * map_planes;
            if n > 0 && 76 + n * 4 <= size {
                out.push(GainMap {
                    top: u32_at(p) as usize,
                    left: u32_at(p + 4) as usize,
                    bottom: u32_at(p + 8) as usize,
                    right: u32_at(p + 12) as usize,
                    row_pitch: (u32_at(p + 24) as usize).max(1),
                    col_pitch: (u32_at(p + 28) as usize).max(1),
                    points_v,
                    points_h,
                    spacing_v: f64_at(p + 40),
                    spacing_h: f64_at(p + 48),
                    origin_v: f64_at(p + 56),
                    origin_h: f64_at(p + 64),
                    map_planes,
                    gains: (0..n).map(|i| f32_at(p + 76 + i * 4)).collect(),
                });
            }
        }
        o = p + size;
    }
    out
}

/// Plain samples: 8 or 16 bits in the file's byte order, or packed most significant bit first,
/// each row from a fresh byte.
pub fn unpack(b: &[u8], width: usize, rows: usize, bits: u32, little: bool) -> Vec<u16> {
    let mut out = vec![0u16; width * rows];
    match bits {
        16 => {
            for (o, s) in out.iter_mut().zip(b.as_chunks::<2>().0) {
                *o = if little { u16::from_le_bytes(*s) } else { u16::from_be_bytes(*s) };
            }
        }
        8 => {
            for (o, &s) in out.iter_mut().zip(b) {
                *o = s as u16;
            }
        }
        _ => {
            let row_bytes = (width * bits as usize).div_ceil(8);
            for y in 0..rows {
                let mut bit = y * row_bytes * 8;
                for x in 0..width {
                    let mut v = 0u32;
                    for _ in 0..bits {
                        let Some(&byte) = b.get(bit >> 3) else { break };
                        v = (v << 1) | ((byte >> (7 - (bit & 7))) & 1) as u32;
                        bit += 1;
                    }
                    out[y * width + x] = v as u16;
                }
            }
        }
    }
    out
}

struct Huffman {
    first: [i32; 17],
    last: [i32; 17],
    start: [usize; 17],
    values: Vec<u8>,
}

impl Huffman {
    fn new(counts: &[u8], values: Vec<u8>) -> Huffman {
        let (mut first, mut last, mut start) = ([0; 17], [-1; 17], [0; 17]);
        let (mut code, mut k) = (0i32, 0usize);
        for len in 1..=16 {
            let n = counts[len - 1] as i32;
            first[len] = code;
            start[len] = k;
            if n > 0 {
                last[len] = code + n - 1;
            }
            code = (code + n) << 1;
            k += n as usize;
        }
        Huffman { first, last, start, values }
    }
}

/// Lossless JPEG (ITU T.81 process 14), as DNG stores raw data: Huffman-coded differences from a
/// predicted neighbour, for one to four interleaved components. Returns every sample, row by row,
/// each row `components` samples per pixel.
pub fn lossless_jpeg(b: &[u8]) -> Result<Vec<u16>, String> {
    if b.get(..2) != Some(&[0xff, 0xd8]) {
        return Err("not a JPEG".into());
    }
    let u16_at = |o: usize| b.get(o..o + 2).map_or(0, |s| u16::from_be_bytes([s[0], s[1]])) as usize;
    let (mut width, mut height, mut precision, mut comps) = (0, 0, 16u32, 0);
    let mut ids = [0u8; 4];
    let mut tables: [Option<Huffman>; 4] = Default::default();
    let mut restart = 0;
    let mut o = 2;
    while o + 4 <= b.len() {
        if b[o] != 0xff || b[o + 1] == 0xff {
            o += 1;
            continue;
        }
        let marker = b[o + 1];
        let len = u16_at(o + 2);
        let p = o + 4;
        match marker {
            0xc3 => {
                precision = b[p] as u32;
                height = u16_at(p + 1);
                width = u16_at(p + 3);
                comps = b[p + 5] as usize;
                if !(1..=4).contains(&comps) {
                    return Err(format!("lossless JPEG with {comps} components"));
                }
                for c in 0..comps {
                    ids[c] = b[p + 6 + c * 3];
                }
            }
            0xc4 => {
                let mut q = p;
                while q + 17 <= o + 2 + len {
                    let id = (b[q] & 3) as usize;
                    let counts = &b[q + 1..q + 17];
                    let n: usize = counts.iter().map(|&c| c as usize).sum();
                    tables[id] = Some(Huffman::new(counts, b.get(q + 17..q + 17 + n).ok_or("short Huffman table")?.to_vec()));
                    q += 17 + n;
                }
            }
            0xdd => restart = u16_at(p),
            0xda => {
                let ns = b[p] as usize;
                let mut table_of = [0usize; 4];
                for i in 0..ns {
                    let c = ids[..comps].iter().position(|&id| id == b[p + 1 + i * 2]).unwrap_or(i).min(3);
                    table_of[c] = ((b[p + 2 + i * 2] >> 4) & 3) as usize;
                }
                let predictor = b[p + 1 + ns * 2];
                let transform = (b[p + 3 + ns * 2] & 0x0f) as u32;
                let hs: Vec<&Huffman> =
                    (0..comps).map(|c| tables[table_of[c]].as_ref().or(tables[0].as_ref()).ok_or("missing Huffman table")).collect::<Result<_, _>>()?;
                if restart > 0 && restart % width.max(1) != 0 {
                    return Err(format!("restart interval of {restart} pixels in rows of {width}"));
                }
                let mut bits = Bits { b, pos: o + 2 + len, acc: 0, n: 0 };
                let row_len = width * comps;
                let mut out = vec![0u16; row_len * height];
                let initial = 1i32 << (precision - transform - 1);
                let mut first = 0;
                for y in 0..height {
                    if restart > 0 && y > 0 && (y * width) % restart == 0 {
                        bits.restart();
                        first = y;
                    }
                    for x in 0..width {
                        for (c, h) in hs.iter().enumerate() {
                            let i = y * row_len + x * comps + c;
                            let pred = if y == first {
                                if x == 0 {
                                    initial
                                } else {
                                    out[i - comps] as i32
                                }
                            } else if x == 0 {
                                out[i - row_len] as i32
                            } else {
                                let (ra, rb, rc) = (out[i - comps] as i32, out[i - row_len] as i32, out[i - row_len - comps] as i32);
                                match predictor {
                                    2 => rb,
                                    3 => rc,
                                    4 => ra + rb - rc,
                                    5 => ra + ((rb - rc) >> 1),
                                    6 => rb + ((ra - rc) >> 1),
                                    7 => (ra + rb) >> 1,
                                    _ => ra,
                                }
                            };
                            out[i] = ((pred + bits.diff(h)?) & 0xffff) as u16;
                        }
                    }
                }
                if transform > 0 {
                    out.iter_mut().for_each(|v| *v <<= transform);
                }
                return Ok(out);
            }
            0xd9 => break,
            _ => {}
        }
        o += 2 + len;
    }
    Err("lossless JPEG without a scan".into())
}

/// Bit reader over entropy-coded data: 0xFF 0x00 is a literal 0xFF; a marker ends the data
/// (zeros follow).
struct Bits<'a> {
    b: &'a [u8],
    pos: usize,
    acc: u64,
    n: u32,
}

impl Bits<'_> {
    fn fill(&mut self) {
        while self.n <= 56 {
            let mut v = 0u8;
            if let Some(&byte) = self.b.get(self.pos) {
                v = byte;
                if byte == 0xff {
                    if self.b.get(self.pos + 1) == Some(&0) {
                        self.pos += 2;
                    } else {
                        v = 0; // a marker: stay on it
                    }
                } else {
                    self.pos += 1;
                }
            }
            self.acc = (self.acc << 8) | v as u64;
            self.n += 8;
        }
    }

    fn read(&mut self, n: u32) -> i32 {
        if n == 0 {
            return 0;
        }
        if self.n < n {
            self.fill();
        }
        self.n -= n;
        ((self.acc >> self.n) & ((1u64 << n) - 1)) as i32
    }

    fn symbol(&mut self, h: &Huffman) -> Result<u8, String> {
        let mut code = 0i32;
        for len in 1..=16 {
            code = (code << 1) | self.read(1);
            if h.last[len] >= 0 && code >= h.first[len] && code <= h.last[len] {
                return Ok(h.values[h.start[len] + (code - h.first[len]) as usize]);
            }
        }
        Err("bad Huffman code".into())
    }

    fn diff(&mut self, h: &Huffman) -> Result<i32, String> {
        Ok(match self.symbol(h)? {
            0 => 0,
            16 => 32768,
            s => {
                let s = s as u32;
                let v = self.read(s);
                if v < (1 << (s - 1)) {
                    v - (1 << s) + 1
                } else {
                    v
                }
            }
        })
    }

    /// Moves past a restart marker: the bits left in the byte go, then the marker.
    fn restart(&mut self) {
        (self.acc, self.n) = (0, 0);
        while self.pos + 1 < self.b.len() && !(self.b[self.pos] == 0xff && (0xd0..=0xd7).contains(&self.b[self.pos + 1])) {
            self.pos += 1;
        }
        self.pos += 2;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gain_maps_interpolate_between_points() {
        let m = GainMap {
            top: 0,
            left: 0,
            bottom: 10,
            right: 10,
            row_pitch: 1,
            col_pitch: 1,
            points_v: 2,
            points_h: 2,
            spacing_v: 1.0,
            spacing_h: 1.0,
            origin_v: 0.0,
            origin_h: 0.0,
            map_planes: 1,
            gains: vec![1.0, 2.0, 3.0, 4.0],
        };
        assert_eq!(m.gain(0.0, 0.0), 1.0);
        assert_eq!(m.gain(0.5, 0.5), 2.5);
        assert_eq!(m.gain(2.0, 2.0), 4.0);
    }

    #[test]
    fn unpacks_twelve_bit_samples() {
        // 0xABC and 0x123, most significant bit first.
        assert_eq!(unpack(&[0xab, 0xc1, 0x23], 2, 1, 12, true), vec![0xabc, 0x123]);
        assert_eq!(unpack(&[0x34, 0x12], 1, 1, 16, true), vec![0x1234]);
        assert_eq!(unpack(&[0x12, 0x34], 1, 1, 16, false), vec![0x1234]);
    }
}
