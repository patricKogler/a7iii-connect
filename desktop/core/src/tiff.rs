//! Just enough TIFF to find what a Sony ARW carries besides the sensor data: the IFD chain and
//! its SubIFDs, where the camera's own JPEG preview and the orientation live.

use std::io::{self, Read, Seek, SeekFrom};

pub const TAG_ORIENTATION: u16 = 0x0112;
pub const TAG_SUBIFDS: u16 = 0x014a;
pub const TAG_JPEG_OFFSET: u16 = 0x0201;
pub const TAG_JPEG_LENGTH: u16 = 0x0202;

#[derive(Clone, Debug)]
pub struct Entry {
    pub tag: u16,
    pub kind: u16,
    pub count: u32,
    /// The value itself when it fits in four bytes, else where it is (as stored).
    pub raw: [u8; 4],
}

#[derive(Clone, Debug, Default)]
pub struct Ifd {
    pub entries: Vec<Entry>,
}

impl Ifd {
    pub fn get(&self, tag: u16) -> Option<&Entry> {
        self.entries.iter().find(|e| e.tag == tag)
    }
}

pub struct Tiff<R> {
    src: R,
    little: bool,
    pub size: u64,
}

impl<R: Read + Seek> Tiff<R> {
    pub fn open(mut src: R) -> io::Result<Self> {
        let size = src.seek(SeekFrom::End(0))?;
        src.seek(SeekFrom::Start(0))?;
        let mut h = [0u8; 4];
        src.read_exact(&mut h)?;
        let little = match &h {
            [b'I', b'I', 42, 0] => true,
            [b'M', b'M', 0, 42] => false,
            _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "not a TIFF file")),
        };
        Ok(Tiff { src, little, size })
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

    pub fn read_at(&mut self, offset: u64, len: usize) -> io::Result<Vec<u8>> {
        if offset + len as u64 > self.size {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "past the end of the file"));
        }
        self.src.seek(SeekFrom::Start(offset))?;
        let mut buf = vec![0u8; len];
        self.src.read_exact(&mut buf)?;
        Ok(buf)
    }

    pub fn read_ifd(&mut self, offset: u64) -> io::Result<(Ifd, u64)> {
        let head = self.read_at(offset, 2)?;
        let n = self.u16(&head) as usize;
        if n > 1000 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "implausible IFD"));
        }
        let body = self.read_at(offset + 2, n * 12 + 4)?;
        let entries = (0..n)
            .map(|i| {
                let e = &body[i * 12..i * 12 + 12];
                Entry { tag: self.u16(&e[0..2]), kind: self.u16(&e[2..4]), count: self.u32(&e[4..8]), raw: [e[8], e[9], e[10], e[11]] }
            })
            .collect();
        let next = self.u32(&body[n * 12..]) as u64;
        Ok((Ifd { entries }, next))
    }

    /// IFD0, IFD1 ... then every SubIFD they point to.
    pub fn all_ifds(&mut self) -> io::Result<Vec<Ifd>> {
        let head = self.read_at(4, 4)?;
        let first = self.u32(&head) as u64;
        let mut out = Vec::new();
        let mut next = first;
        while next != 0 && out.len() < 16 {
            let (ifd, n) = self.read_ifd(next)?;
            out.push(ifd);
            next = n;
        }
        let chain = out.len();
        for i in 0..chain {
            if let Some(e) = out[i].get(TAG_SUBIFDS).cloned() {
                for off in self.ints(&e)? {
                    if let Ok((ifd, _)) = self.read_ifd(off as u64) {
                        out.push(ifd);
                    }
                }
            }
        }
        Ok(out)
    }

    fn type_size(kind: u16) -> usize {
        match kind {
            1 | 2 | 6 | 7 => 1,
            3 | 8 => 2,
            4 | 9 | 11 | 13 => 4,
            5 | 10 | 12 => 8,
            _ => 1,
        }
    }

    /// An entry's values as integers (BYTE, SHORT, LONG and their signed forms).
    pub fn ints(&mut self, e: &Entry) -> io::Result<Vec<u32>> {
        let size = Self::type_size(e.kind);
        let total = size * e.count as usize;
        let bytes = if total <= 4 {
            e.raw[..total].to_vec()
        } else {
            let at = self.u32(&e.raw) as u64;
            self.read_at(at, total)?
        };
        Ok((0..e.count as usize)
            .map(|i| match size {
                1 => bytes[i] as u32,
                2 => self.u16(&bytes[i * 2..]) as u32,
                _ => self.u32(&bytes[i * 4..]),
            })
            .collect())
    }

    pub fn int(&mut self, ifd: &Ifd, tag: u16) -> Option<u32> {
        let e = ifd.get(tag)?.clone();
        self.ints(&e).ok()?.first().copied()
    }
}

/// What a raw file carries for showing it quickly.
#[derive(Clone, Debug, PartialEq)]
pub struct RawPreview {
    /// The biggest embedded JPEG (offset, length).
    pub jpeg: Option<(u64, usize)>,
    pub orientation: u8,
}

pub fn raw_preview<R: Read + Seek>(src: R) -> io::Result<RawPreview> {
    let mut t = Tiff::open(src)?;
    let ifds = t.all_ifds()?;
    let mut jpeg: Option<(u64, usize)> = None;
    for ifd in &ifds {
        let (Some(off), Some(len)) = (t.int(ifd, TAG_JPEG_OFFSET), t.int(ifd, TAG_JPEG_LENGTH)) else { continue };
        let (off, len) = (off as u64, len as usize);
        if len > 0 && off + len as u64 <= t.size && jpeg.is_none_or(|(_, l)| len > l) {
            jpeg = Some((off, len));
        }
    }
    let orientation = ifds.first().and_then(|i| t.int(i, TAG_ORIENTATION)).unwrap_or(1).clamp(1, 8) as u8;
    Ok(RawPreview { jpeg, orientation })
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::Cursor;

    /// A little-endian TIFF: IFD0 (orientation 6) with a SubIFD that holds a "JPEG" of `jpeg`.
    pub fn tiny_arw(jpeg: &[u8]) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend_from_slice(b"II\x2a\x00");
        b.extend_from_slice(&8u32.to_le_bytes());
        let entry = |b: &mut Vec<u8>, tag: u16, kind: u16, count: u32, value: u32| {
            b.extend_from_slice(&tag.to_le_bytes());
            b.extend_from_slice(&kind.to_le_bytes());
            b.extend_from_slice(&count.to_le_bytes());
            b.extend_from_slice(&value.to_le_bytes());
        };
        // IFD0 at 8: 2 entries -> 2 + 24 + 4 = 30 bytes, SubIFD at 38.
        b.extend_from_slice(&2u16.to_le_bytes());
        entry(&mut b, TAG_ORIENTATION, 3, 1, 6);
        entry(&mut b, TAG_SUBIFDS, 4, 1, 38);
        b.extend_from_slice(&0u32.to_le_bytes());
        // SubIFD at 38: 2 entries, data at 38 + 30 = 68.
        b.extend_from_slice(&2u16.to_le_bytes());
        entry(&mut b, TAG_JPEG_OFFSET, 4, 1, 68);
        entry(&mut b, TAG_JPEG_LENGTH, 4, 1, jpeg.len() as u32);
        b.extend_from_slice(&0u32.to_le_bytes());
        b.extend_from_slice(jpeg);
        b
    }

    #[test]
    fn finds_the_preview_in_a_subifd() {
        let p = raw_preview(Cursor::new(tiny_arw(b"\xff\xd8jpeg"))).unwrap();
        assert_eq!(p, RawPreview { jpeg: Some((68, 6)), orientation: 6 });
    }
}
