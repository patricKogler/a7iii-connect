//! Sony ARW reading (ARW 2.x, as the A7 III writes it) and demosaicing: the phone's Arw.kt,
//! Tiff.kt and the Malvar-He-Cutler demosaic from native/src/demosaic.rs.

use super::image::LinearImage;
use rayon::prelude::*;

/// Sony's embedded lens corrections (tags 0x7032/0x7035/0x7037), as darktable reads them.
#[derive(Clone, Debug, PartialEq)]
pub struct LensData {
    pub distortion: Vec<i32>,
    pub ca_red: Vec<i32>,
    pub ca_blue: Vec<i32>,
    pub vignetting: Vec<i32>,
}

#[derive(Clone, Debug)]
struct RawIfd {
    width: usize,
    height: usize,
    compression: u32,
    bits: u32,
    offset: usize,
    bytes: usize,
    cfa: [usize; 4],
    curve: Option<Vec<u16>>,
    crop: (usize, usize, usize, usize),
}

/// What the editor needs to know about a raw file before decoding its pixels.
#[derive(Clone, Debug)]
pub struct RawInfo {
    pub make: String,
    pub model: String,
    pub orientation: u8,
    /// As-shot white balance multipliers for R, G, B with G = 1.
    pub as_shot: [f32; 3],
    pub black: [i32; 3],
    pub white: i32,
    /// The biggest embedded JPEG preview (offset, length).
    pub preview: Option<(usize, usize)>,
    pub lens: Option<LensData>,
    raw: Option<RawIfd>,
}

/// The sensor data: one value per photosite in a Bayer pattern, with the active area the camera
/// wants shown (the A7 III crops 6048x4024 to 6000x4000).
pub struct RawImage {
    pub info: RawInfo,
    pub raw_width: usize,
    pub data: Vec<u16>,
    pub cfa: [usize; 4],
    pub left: usize,
    pub top: usize,
    pub width: usize,
    pub height: usize,
}

struct Entry {
    tag: u16,
    kind: u16,
    count: usize,
    data: usize,
}

struct Tiff<'a> {
    src: &'a [u8],
    little: bool,
    /// Subtracted from every offset: Sony's decrypted SR2 block keeps the original file's offsets.
    base: usize,
}

fn type_size(kind: u16) -> usize {
    match kind {
        3 | 8 => 2,
        4 | 9 | 11 | 13 => 4,
        5 | 10 | 12 => 8,
        _ => 1,
    }
}

impl<'a> Tiff<'a> {
    fn bytes(&self, offset: usize, len: usize) -> Result<&'a [u8], String> {
        let o = offset.checked_sub(self.base).ok_or("offset before the block")?;
        self.src.get(o..o + len).ok_or_else(|| format!("read {len} at {offset} past the end"))
    }

    fn u16(&self, b: &[u8]) -> u16 {
        if self.little {
            u16::from_le_bytes([b[0], b[1]])
        } else {
            u16::from_be_bytes([b[0], b[1]])
        }
    }

    fn u32(&self, b: &[u8]) -> u32 {
        if self.little {
            u32::from_le_bytes([b[0], b[1], b[2], b[3]])
        } else {
            u32::from_be_bytes([b[0], b[1], b[2], b[3]])
        }
    }

    fn read_ifd(&self, offset: usize) -> Result<(Vec<Entry>, usize), String> {
        let n = self.u16(self.bytes(offset, 2)?) as usize;
        if !(1..=1000).contains(&n) {
            return Err(format!("bad IFD at {offset}"));
        }
        let b = self.bytes(offset + 2, n * 12 + 4)?;
        let mut entries = Vec::with_capacity(n);
        for i in 0..n {
            let o = i * 12;
            let kind = self.u16(&b[o + 2..]);
            let count = self.u32(&b[o + 4..]) as usize;
            let data = if type_size(kind) * count <= 4 { offset + 2 + o + 8 } else { self.u32(&b[o + 8..]) as usize };
            entries.push(Entry { tag: self.u16(&b[o..]), kind, count, data });
        }
        Ok((entries, self.u32(&b[n * 12..]) as usize))
    }

    fn entry_bytes(&self, e: &Entry) -> Result<&'a [u8], String> {
        self.bytes(e.data, type_size(e.kind) * e.count)
    }

    fn ints(&self, e: &Entry) -> Vec<i64> {
        let Ok(b) = self.entry_bytes(e) else { return Vec::new() };
        (0..e.count)
            .map(|i| match e.kind {
                1 | 7 => b[i] as i64,
                6 => b[i] as i8 as i64,
                3 => self.u16(&b[i * 2..]) as i64,
                8 => self.u16(&b[i * 2..]) as i16 as i64,
                4 | 13 => self.u32(&b[i * 4..]) as i64,
                9 => self.u32(&b[i * 4..]) as i32 as i64,
                _ => 0,
            })
            .collect()
    }

    fn rationals(&self, e: &Entry) -> Vec<f64> {
        let Ok(b) = self.entry_bytes(e) else { return Vec::new() };
        if e.kind != 5 {
            return Vec::new();
        }
        (0..e.count).map(|i| self.u32(&b[i * 8..]) as f64 / self.u32(&b[i * 8 + 4..]).max(1) as f64).collect()
    }

    fn int(&self, ifd: &[Entry], tag: u16) -> Option<i64> {
        ifd.iter().find(|e| e.tag == tag).and_then(|e| self.ints(e).first().copied())
    }

    fn string(&self, ifd: &[Entry], tag: u16) -> Option<String> {
        let e = ifd.iter().find(|e| e.tag == tag)?;
        let b = self.entry_bytes(e).ok()?;
        Some(b.iter().map(|&c| c as char).collect::<String>().trim_end_matches(['\0', ' ']).to_string())
    }
}

fn find(ifd: &[Entry], tag: u16) -> Option<&Entry> {
    ifd.iter().find(|e| e.tag == tag)
}

const TAG_WIDTH: u16 = 0x100;
const TAG_HEIGHT: u16 = 0x101;
const TAG_BITS: u16 = 0x102;
const TAG_COMPRESSION: u16 = 0x103;
const TAG_PHOTOMETRIC: u16 = 0x106;
const TAG_MAKE: u16 = 0x10f;
const TAG_MODEL: u16 = 0x110;
const TAG_STRIP_OFFSETS: u16 = 0x111;
const TAG_ORIENTATION: u16 = 0x112;
const TAG_STRIP_BYTES: u16 = 0x117;
const TAG_TILE_OFFSETS: u16 = 0x144;
const TAG_TILE_BYTES: u16 = 0x145;
const TAG_SUBIFDS: u16 = 0x14a;
const TAG_JPEG_OFFSET: u16 = 0x201;
const TAG_JPEG_LENGTH: u16 = 0x202;
const TAG_SONY_CURVE: u16 = 0x7010;
const TAG_CFA_PATTERN: u16 = 0x828e;
const TAG_BLACK_LEVEL: u16 = 0xc61a;
const TAG_WHITE_LEVEL: u16 = 0xc61d;
const TAG_AS_SHOT_NEUTRAL: u16 = 0xc628;
const TAG_CROP_ORIGIN: u16 = 0xc61f;
const TAG_CROP_SIZE: u16 = 0xc620;
const TAG_SR2_PRIVATE: u16 = 0xc634;

pub fn read_info(src: &[u8]) -> Result<RawInfo, String> {
    let little = match src.get(..2) {
        Some(b"II") => true,
        Some(b"MM") => false,
        _ => return Err("not a TIFF/ARW file".into()),
    };
    let tiff = Tiff { src, little, base: 0 };
    let mut chain = Vec::new();
    let mut next = tiff.u32(tiff.bytes(4, 4)?) as usize;
    while next != 0 && chain.len() < 8 {
        let (ifd, n) = tiff.read_ifd(next)?;
        chain.push(ifd);
        next = n;
    }
    if chain.is_empty() {
        return Err("no IFD".into());
    }
    let mut subs = Vec::new();
    for ifd in &chain {
        if let Some(e) = find(ifd, TAG_SUBIFDS) {
            for off in tiff.ints(e) {
                if let Ok((sub, _)) = tiff.read_ifd(off as usize) {
                    subs.push(sub);
                }
            }
        }
    }
    let all: Vec<&Vec<Entry>> = chain.iter().chain(subs.iter()).collect();

    let mut preview: Option<(usize, usize)> = None;
    for ifd in &all {
        let (Some(off), Some(len)) = (tiff.int(ifd, TAG_JPEG_OFFSET), tiff.int(ifd, TAG_JPEG_LENGTH)) else { continue };
        let (off, len) = (off as usize, len as usize);
        if len > 0 && off + len <= src.len() && preview.is_none_or(|(_, l)| len > l) {
            preview = Some((off, len));
        }
    }

    let raw_ifd = all
        .iter()
        .filter(|i| tiff.int(i, TAG_PHOTOMETRIC) == Some(32803) || tiff.int(i, TAG_COMPRESSION) == Some(32767))
        .max_by_key(|i| tiff.int(i, TAG_WIDTH).unwrap_or(0) * tiff.int(i, TAG_HEIGHT).unwrap_or(0))
        .copied();
    let raw = raw_ifd.and_then(|i| read_raw_ifd(&tiff, i));

    // Defaults for the A7 III, in case the SR2 block is missing.
    let mut black = [512, 512, 512];
    let mut white = 16383;
    let mut as_shot = [2.0, 1.0, 1.6];
    let ifd0 = &chain[0];
    if let Some(e) = find(ifd0, TAG_SR2_PRIVATE) {
        // Sony writes this pointer as four BYTEs; read them as one 32-bit offset.
        let pointer = if type_size(e.kind) == 1 {
            tiff.entry_bytes(e).ok().filter(|b| b.len() >= 4).map(|b| tiff.u32(b) as usize)
        } else {
            tiff.ints(e).first().map(|v| *v as usize)
        };
        if let Some(Some(w)) = pointer.map(|p| read_sr2(&tiff, p, &mut black, &mut as_shot)) {
            white = w;
        }
    }
    if let Some(r) = &raw {
        if r.bits == 12 && r.compression == 1 {
            for b in &mut black {
                *b >>= 2;
            }
            white >>= 2;
        }
    }
    // Raws Shootback writes itself (HDR merges, focus stacks) say their levels and white
    // balance in the DNG tags.
    if let Some(i) = raw_ifd {
        if let Some(v) = find(i, TAG_BLACK_LEVEL).map(|e| tiff.ints(e)).filter(|v| !v.is_empty()) {
            black = [v[0] as i32, v[v.len().min(2) - 1] as i32, v[v.len() - 1] as i32];
        }
        if let Some(w) = tiff.int(i, TAG_WHITE_LEVEL) {
            white = w as i32;
        }
        if let Some(n) = find(i, TAG_AS_SHOT_NEUTRAL).map(|e| tiff.rationals(e)).filter(|n| n.len() == 3 && n.iter().all(|&v| v > 0.0)) {
            as_shot = [(n[1] / n[0]) as f32, 1.0, (n[1] / n[2]) as f32];
        }
    }

    let lens = (|| {
        let look = |tag| raw_ifd.into_iter().chain(all.iter().copied()).find_map(|i| find(i, tag).map(|e| tiff.ints(e)));
        let (d, c, v) = (look(0x7037)?, look(0x7035)?, look(0x7032)?);
        let n = *d.first()? as usize;
        if !(2..=16).contains(&n) || c[0] as usize != 2 * n || v[0] as usize != n || d.len() < n + 1 || c.len() < 2 * n + 1 || v.len() < n + 1 {
            return None;
        }
        let s = |x: i64| x as i16 as i32;
        Some(LensData {
            distortion: (0..n).map(|i| s(d[i + 1])).collect(),
            ca_red: (0..n).map(|i| s(c[i + 1])).collect(),
            ca_blue: (0..n).map(|i| s(c[n + i + 1])).collect(),
            vignetting: (0..n).map(|i| s(v[i + 1])).collect(),
        })
    })();

    Ok(RawInfo {
        make: tiff.string(ifd0, TAG_MAKE).unwrap_or_default(),
        model: tiff.string(ifd0, TAG_MODEL).unwrap_or_default(),
        orientation: tiff.int(ifd0, TAG_ORIENTATION).unwrap_or(1).clamp(1, 8) as u8,
        as_shot,
        black,
        white,
        preview,
        lens,
        raw,
    })
}

fn read_raw_ifd(tiff: &Tiff, ifd: &[Entry]) -> Option<RawIfd> {
    let width = tiff.int(ifd, TAG_WIDTH)? as usize;
    let height = tiff.int(ifd, TAG_HEIGHT)? as usize;
    let offset = tiff.int(ifd, TAG_STRIP_OFFSETS).or_else(|| tiff.int(ifd, TAG_TILE_OFFSETS))? as usize;
    let bytes = find(ifd, TAG_STRIP_BYTES).or_else(|| find(ifd, TAG_TILE_BYTES)).map_or(0, |e| tiff.ints(e).iter().sum::<i64>()) as usize;
    let cfa = find(ifd, TAG_CFA_PATTERN)
        .map(|e| tiff.ints(e))
        .filter(|v| v.len() >= 4)
        .map(|v| std::array::from_fn(|i| v[i].clamp(0, 2) as usize))
        .unwrap_or([0, 1, 1, 2]);
    let curve = find(ifd, TAG_SONY_CURVE).map(|e| tone_curve(&tiff.ints(e)));
    let mut crop = (0, 0, width, height);
    if let (Some(o), Some(s)) = (find(ifd, TAG_CROP_ORIGIN).map(|e| tiff.ints(e)), find(ifd, TAG_CROP_SIZE).map(|e| tiff.ints(e))) {
        if o.len() >= 2 && s.len() >= 2 && o[0] >= 0 && o[1] >= 0 && s[0] > 0 && s[1] > 0 && o[0] + s[0] <= width as i64 && o[1] + s[1] <= height as i64 {
            crop = (o[0] as usize, o[1] as usize, s[0] as usize, s[1] as usize);
        }
    }
    Some(RawIfd {
        width,
        height,
        compression: tiff.int(ifd, TAG_COMPRESSION).unwrap_or(1) as u32,
        bits: tiff.int(ifd, TAG_BITS).unwrap_or(14) as u32,
        offset,
        bytes,
        cfa,
        curve,
        crop,
    })
}

/// Sony stores the 11-bit to 14-bit expansion as four knee points; the slope doubles after each.
fn tone_curve(knees: &[i64]) -> Vec<u16> {
    let mut points = [0usize; 6];
    points[5] = 4095;
    for c in 0..knees.len().min(4) {
        points[c + 1] = ((knees[c] >> 2) & 0xfff) as usize;
    }
    let mut curve: Vec<u16> = (0..4096).map(|i| i as u16).collect();
    for i in 0..5 {
        for j in points[i] + 1..=points[i + 1].min(4095) {
            curve[j] = curve[j - 1].wrapping_add(1 << i);
        }
    }
    curve
}

/// Reads the SR2 private directory: an IFD pointing at an encrypted sub-IFD with the camera's
/// white balance, black levels and white level. Returns the white level, if present.
fn read_sr2(tiff: &Tiff, private_ifd: usize, black: &mut [i32; 3], as_shot: &mut [f32; 3]) -> Option<i32> {
    let (ifd, _) = tiff.read_ifd(private_ifd).ok()?;
    let offset = tiff.int(&ifd, 0x7200)? as usize;
    let length = tiff.int(&ifd, 0x7201)? as usize;
    let key = tiff.u32(tiff.entry_bytes(find(&ifd, 0x7221)?).ok()?);
    if length == 0 || length > 10_000_000 {
        return None;
    }
    let mut buf = tiff.bytes(offset, length).ok()?.to_vec();
    sony_decrypt(&mut buf, key);
    let sub = Tiff { src: &buf, little: tiff.little, base: offset };
    let (sr2, _) = sub.read_ifd(offset).ok()?;
    if let Some(v) = find(&sr2, 0x7310).or_else(|| find(&sr2, 0x7300)).map(|e| sub.ints(e)) {
        if v.len() >= 4 {
            *black = [v[0] as i32, ((v[1] + v[2]) / 2) as i32, v[3] as i32];
        }
    }
    let wb = find(&sr2, 0x7313)
        .map(|e| sub.ints(e))
        .filter(|v| v.len() >= 4)
        .map(|v| [v[0] as f32, (v[1] + v[2]) as f32 / 2.0, v[3] as f32]) // RGGB
        .or_else(|| find(&sr2, 0x7303).map(|e| sub.ints(e)).filter(|v| v.len() >= 4).map(|v| [v[1] as f32, (v[0] + v[3]) as f32 / 2.0, v[2] as f32])); // GRBG
    if let Some(wb) = wb.filter(|w| w.iter().all(|&c| c > 0.0)) {
        *as_shot = [wb[0] / wb[1], 1.0, wb[2] / wb[1]];
    }
    find(&sr2, 0x787f).and_then(|e| sub.ints(e).into_iter().min()).map(|v| v as i32).filter(|&v| v > 1000)
}

/// Sony's SR2 cipher: a lagged-Fibonacci keystream XORed over big-endian 32-bit words.
fn sony_decrypt(buf: &mut [u8], start_key: u32) {
    let mut pad = [0u32; 128];
    let mut key = start_key;
    for p in pad.iter_mut().take(4) {
        key = key.wrapping_mul(48828125).wrapping_add(1);
        *p = key;
    }
    pad[3] = (pad[3] << 1) | ((pad[0] ^ pad[2]) >> 31);
    for p in 4..127 {
        pad[p] = ((pad[p - 4] ^ pad[p - 2]) << 1) | ((pad[p - 3] ^ pad[p - 1]) >> 31);
    }
    for (p, chunk) in (127usize..).zip(buf.as_chunks_mut::<4>().0) {
        let v = pad[(p + 1) & 127] ^ pad[(p + 65) & 127];
        pad[p & 127] = v;
        for (b, k) in chunk.iter_mut().zip(v.to_be_bytes()) {
            *b ^= k;
        }
    }
}

pub fn decode(src: &[u8], info: RawInfo) -> Result<RawImage, String> {
    let r = info.raw.clone().ok_or("no raw image in this file")?;
    let (w, h) = (r.width, r.height);
    let mut data = vec![0u16; w * h];
    let slice = |len: usize| src.get(r.offset..r.offset + len).ok_or_else(|| "raw data cut short".to_string());
    if r.compression == 32767 && r.bytes == w * h {
        let curve = r.curve.clone().unwrap_or_else(|| (0..4096).collect());
        decode_arw2(slice(w * h)?, w, &curve, &mut data);
    } else if r.bytes >= w * h * 2 {
        for (o, b) in data.iter_mut().zip(slice(w * h * 2)?.as_chunks::<2>().0) {
            *o = u16::from_le_bytes([b[0], b[1]]);
        }
    } else if r.bytes >= w * h * 3 / 2 && r.bits == 12 {
        for (o, b) in data.as_chunks_mut::<2>().0.iter_mut().zip(slice(w * h * 3 / 2)?.as_chunks::<3>().0) {
            o[0] = b[0] as u16 | ((b[1] as u16 & 0x0f) << 8);
            o[1] = (b[1] as u16 >> 4) | ((b[2] as u16) << 4);
        }
    } else {
        return Err(format!("unsupported raw format (compression {}, {} bytes for {w}x{h})", r.compression, r.bytes));
    }
    let (left, top, width, height) = r.crop;
    Ok(RawImage { info, raw_width: w, data, cfa: r.cfa, left, top, width, height })
}

/// Each 16-byte block holds 16 same-colour pixels from every other column: an 11-bit max and min,
/// the positions of those two, and 14 7-bit deltas from min (scaled up for wide blocks).
fn decode_arw2(src: &[u8], w: usize, curve: &[u16], out: &mut [u16]) {
    out.par_chunks_mut(w).enumerate().for_each(|(row, line)| {
        let mut pix = [0usize; 16];
        let mut dp = row * w;
        let mut col = 0usize;
        while col + 30 < w {
            let v = u32::from_le_bytes([src[dp], src[dp + 1], src[dp + 2], src[dp + 3]]);
            let max = (v & 0x7ff) as usize;
            let min = ((v >> 11) & 0x7ff) as usize;
            let imax = ((v >> 22) & 0x0f) as usize;
            let imin = ((v >> 26) & 0x0f) as usize;
            let mut sh = 0;
            while sh < 4 && (0x80 << sh) <= max as i64 - min as i64 {
                sh += 1;
            }
            let mut bit = 30;
            for (i, px) in pix.iter_mut().enumerate() {
                *px = if i == imax {
                    max
                } else if i == imin {
                    min
                } else {
                    let b = dp + (bit >> 3);
                    let two = src[b] as usize | if b + 1 < src.len() { (src[b + 1] as usize) << 8 } else { 0 };
                    let delta = (two >> (bit & 7)) & 0x7f;
                    bit += 7;
                    ((delta << sh) + min).min(0x7ff)
                };
            }
            for px in pix {
                line[col] = curve[(px << 1).min(curve.len() - 1)];
                col += 2;
            }
            col -= if col & 1 != 0 { 1 } else { 31 };
            dp += 16;
        }
    });
}

impl RawInfo {
    /// Only levels and white balance, for raws made up in tests.
    pub fn plain(black: i32, white: i32, as_shot: [f32; 3]) -> RawInfo {
        RawInfo { make: String::new(), model: String::new(), orientation: 1, as_shot, black: [black; 3], white, preview: None, lens: None, raw: None }
    }
}

impl RawImage {
    /// Which colour (0 red, 1 green, 2 blue) the photosite at `row`, `col` of the whole sensor
    /// sees.
    #[inline]
    pub fn color(&self, row: usize, col: usize) -> usize {
        self.cfa[((row & 1) << 1) | (col & 1)]
    }

    /// A photosite of the active area from black (0) to white (1), without white balance.
    #[inline]
    pub fn level(&self, row: usize, col: usize) -> f32 {
        let (rr, cc) = (self.top + row, self.left + col);
        let c = self.color(rr, cc);
        let black = self.info.black[c];
        ((self.data[rr * self.raw_width + cc] as i32 - black) as f32 / (self.info.white - black).max(1) as f32).clamp(0.0, 1.0)
    }

    /// Black subtraction and as-shot white balance in one multiply per photosite.
    fn levels(&self) -> [f32; 3] {
        std::array::from_fn(|c| self.info.as_shot[c] / (self.info.white - self.info.black[c]) as f32)
    }

    /// One RGB pixel per 2x2 Bayer cell (half width and height): fast and clean, for previews.
    pub fn half_size(&self) -> LinearImage {
        let (w, h) = (self.width / 2, self.height / 2);
        let scale = self.levels();
        let black = self.info.black;
        let mut img = LinearImage::new(w, h);
        let rows: Vec<Vec<[f32; 3]>> = (0..h)
            .into_par_iter()
            .map(|y| {
                (0..w)
                    .map(|x| {
                        let mut sum = [0f32; 3];
                        let mut cnt = [0u32; 3];
                        for dy in 0..2 {
                            for dx in 0..2 {
                                let (row, col) = (self.top + 2 * y + dy, self.left + 2 * x + dx);
                                let c = self.color(row, col);
                                sum[c] += (self.data[row * self.raw_width + col] as i32 - black[c]) as f32 * scale[c];
                                cnt[c] += 1;
                            }
                        }
                        std::array::from_fn(|c| clip(sum[c] / cnt[c].max(1) as f32))
                    })
                    .collect()
            })
            .collect();
        for (y, row) in rows.into_iter().enumerate() {
            for (x, px) in row.into_iter().enumerate() {
                let o = y * w + x;
                img.r[o] = px[0];
                img.g[o] = px[1];
                img.b[o] = px[2];
            }
        }
        img
    }

    /// Black-subtracted, white-balanced photosite, mirrored at the active area's edges.
    #[inline]
    fn v(&self, scale: &[f32; 3], row: isize, col: isize) -> f32 {
        let (h, w) = (self.height as isize, self.width as isize);
        let r = if row < 0 {
            -row
        } else if row >= h {
            2 * h - 2 - row
        } else {
            row
        };
        let c = if col < 0 {
            -col
        } else if col >= w {
            2 * w - 2 - col
        } else {
            col
        };
        let (rr, cc) = (self.top + r as usize, self.left + c as usize);
        let color = self.color(rr, cc);
        (self.data[rr * self.raw_width + cc] as i32 - self.info.black[color]) as f32 * scale[color]
    }

    /// Full-resolution Malvar-He-Cutler demosaic of the active area, interleaved RGB16.
    pub fn demosaic(&self) -> Vec<u16> {
        let scale = self.levels();
        let w = self.width;
        let mut out = vec![0u16; w * self.height * 3];
        out.par_chunks_mut(w * 3).enumerate().for_each(|(y, row)| {
            let y = y as isize;
            let v = |r: isize, c: isize| self.v(&scale, r, c);
            for x in 0..w {
                let xi = x as isize;
                let c = self.color(self.top + y as usize, self.left + x);
                let center = v(y, xi);
                let cross = v(y - 1, xi) + v(y + 1, xi) + v(y, xi - 1) + v(y, xi + 1);
                let far = v(y - 2, xi) + v(y + 2, xi) + v(y, xi - 2) + v(y, xi + 2);
                let (r, g, b);
                if c == 1 {
                    g = center;
                    let horiz = v(y, xi - 1) + v(y, xi + 1);
                    let vert = v(y - 1, xi) + v(y + 1, xi);
                    let diag = v(y - 1, xi - 1) + v(y - 1, xi + 1) + v(y + 1, xi - 1) + v(y + 1, xi + 1);
                    let far_h = v(y, xi - 2) + v(y, xi + 2);
                    let far_v = v(y - 2, xi) + v(y + 2, xi);
                    let along_row = (5.0 * center + 4.0 * horiz - diag - far_h + 0.5 * far_v) / 8.0;
                    let along_col = (5.0 * center + 4.0 * vert - diag - far_v + 0.5 * far_h) / 8.0;
                    if self.color(self.top + y as usize, self.left + x + 1) == 0 {
                        r = along_row;
                        b = along_col;
                    } else {
                        b = along_row;
                        r = along_col;
                    }
                } else {
                    g = (4.0 * center + 2.0 * cross - far) / 8.0;
                    let diag = v(y - 1, xi - 1) + v(y - 1, xi + 1) + v(y + 1, xi - 1) + v(y + 1, xi + 1);
                    let other = (6.0 * center + 2.0 * diag - 1.5 * far) / 8.0;
                    if c == 0 {
                        r = center;
                        b = other;
                    } else {
                        b = center;
                        r = other;
                    }
                }
                row[x * 3] = q(r);
                row[x * 3 + 1] = q(g);
                row[x * 3 + 2] = q(b);
            }
        });
        out
    }
}

// After white balance a clipped photosite reads above 1 in red or blue; capping every channel at
// 1 keeps blown highlights white instead of pink.
#[inline]
fn clip(v: f32) -> f32 {
    v.clamp(0.0, 1.0)
}

#[inline]
fn q(v: f32) -> u16 {
    (clip(v) * 65535.0 + 0.5) as u16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decrypt_is_its_own_inverse() {
        let mut b: Vec<u8> = (0..64).collect();
        sony_decrypt(&mut b, 0x1234_5678);
        assert_ne!(b, (0..64).collect::<Vec<u8>>());
        sony_decrypt(&mut b, 0x1234_5678);
        assert_eq!(b, (0..64).collect::<Vec<u8>>());
    }

    #[test]
    fn tone_curve_doubles_its_slope_at_each_knee() {
        let c = tone_curve(&[1000 << 2, 1600 << 2, 2200 << 2, 2800 << 2]);
        assert_eq!(c[1000], 1000);
        assert_eq!(c[1001], 1002);
        assert_eq!(c[1601], 1000 + 1200 + 4);
    }

    #[test]
    fn uncompressed_raw_demosaics_flat_grey() {
        // An 8x8 16-bit raw with every photosite at the same level comes out grey.
        let (w, h) = (8usize, 8usize);
        let mut file = vec![0u8; 8];
        file[..4].copy_from_slice(b"II*\0");
        file[4..8].copy_from_slice(&8u32.to_le_bytes());
        let data_off = 8 + 2 + 7 * 12 + 4;
        let entries: [(u16, u16, u32, u32); 7] = [
            (TAG_WIDTH, 3, 1, w as u32),
            (TAG_HEIGHT, 3, 1, h as u32),
            (TAG_BITS, 3, 1, 14),
            (TAG_COMPRESSION, 3, 1, 1),
            (TAG_PHOTOMETRIC, 3, 1, 32803),
            (TAG_STRIP_OFFSETS, 4, 1, data_off as u32),
            (TAG_STRIP_BYTES, 4, 1, (w * h * 2) as u32),
        ];
        file.extend_from_slice(&(entries.len() as u16).to_le_bytes());
        for (tag, kind, count, value) in entries {
            file.extend_from_slice(&tag.to_le_bytes());
            file.extend_from_slice(&kind.to_le_bytes());
            file.extend_from_slice(&count.to_le_bytes());
            file.extend_from_slice(&value.to_le_bytes());
        }
        file.extend_from_slice(&0u32.to_le_bytes());
        for _ in 0..w * h {
            file.extend_from_slice(&4000u16.to_le_bytes());
        }
        let info = read_info(&file).unwrap();
        let raw = decode(&file, info).unwrap();
        assert_eq!((raw.width, raw.height), (8, 8));
        let half = raw.half_size();
        assert_eq!((half.width, half.height), (4, 4));
        let full = raw.demosaic();
        let expect = (4000.0 - 512.0) / (16383.0 - 512.0);
        assert!((full[0] as f32 / 65535.0 * 1.0 - (expect * 2.0f32).min(1.0)).abs() < 0.01);
        assert!((full[1] as f32 / 65535.0 - expect).abs() < 0.01);
    }
}
