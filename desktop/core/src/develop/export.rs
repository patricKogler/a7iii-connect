//! What goes into an exported JPEG besides its pixels: the original's EXIF (camera, lens,
//! exposure, dates), minus what no longer fits the developed picture.

use exif::{Context, Field, In, Tag, Value};
use std::io::Cursor;
use std::path::Path;

/// The descriptive IFD0 tags worth keeping (the rest describe the original's pixels).
const KEEP_TIFF: [Tag; 6] = [Tag::Make, Tag::Model, Tag::DateTime, Tag::Artist, Tag::Copyright, Tag::ImageDescription];

/// An EXIF block for an export of `original`: its IFD0 description and its Exif and GPS fields,
/// upright (orientation 1) and without the maker notes or a thumbnail. None when it has none.
pub fn exif_for(original: &Path) -> Option<Vec<u8>> {
    let file = std::fs::File::open(original).ok()?;
    let data = exif::Reader::new().read_from_container(&mut std::io::BufReader::new(file)).ok()?;
    let keep = |f: &&Field| {
        f.ifd_num == In::PRIMARY
            && match f.tag.context() {
                Context::Tiff => KEEP_TIFF.contains(&f.tag),
                Context::Exif => f.tag != Tag::MakerNote && f.tag != Tag::PixelXDimension && f.tag != Tag::PixelYDimension,
                Context::Gps => true,
                _ => false,
            }
    };
    let fields: Vec<&Field> = data.fields().filter(keep).collect();
    if fields.is_empty() {
        return None;
    }
    let upright = Field { tag: Tag::Orientation, ifd_num: In::PRIMARY, value: Value::Short(vec![1]) };
    let software = Field { tag: Tag::Software, ifd_num: In::PRIMARY, value: Value::Ascii(vec![b"Shootback".to_vec()]) };
    let mut w = exif::experimental::Writer::new();
    for f in &fields {
        w.push_field(f);
    }
    w.push_field(&upright);
    w.push_field(&software);
    let mut out = Cursor::new(Vec::new());
    w.write(&mut out, data.little_endian()).ok()?;
    Some(out.into_inner())
}

/// `jpeg` with an APP1 EXIF segment holding `tiff` right after the start marker.
pub fn with_exif(jpeg: &[u8], tiff: &[u8]) -> Vec<u8> {
    let len = tiff.len() + 8;
    if jpeg.len() < 2 || len > 0xffff {
        return jpeg.to_vec();
    }
    let mut out = Vec::with_capacity(jpeg.len() + len + 2);
    out.extend_from_slice(&jpeg[..2]);
    out.extend_from_slice(&[0xff, 0xe1]);
    out.extend_from_slice(&(len as u16).to_be_bytes());
    out.extend_from_slice(b"Exif\0\0");
    out.extend_from_slice(tiff);
    out.extend_from_slice(&jpeg[2..]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_camera_and_turns_upright() {
        let fields = [
            Field { tag: Tag::Make, ifd_num: In::PRIMARY, value: Value::Ascii(vec![b"SONY".to_vec()]) },
            Field { tag: Tag::Orientation, ifd_num: In::PRIMARY, value: Value::Short(vec![6]) },
            Field { tag: Tag::PhotographicSensitivity, ifd_num: In::PRIMARY, value: Value::Short(vec![400]) },
        ];
        let mut w = exif::experimental::Writer::new();
        fields.iter().for_each(|f| w.push_field(f));
        let mut tiff = Cursor::new(Vec::new());
        w.write(&mut tiff, false).unwrap();
        // A minimal JPEG container: SOI, APP1 EXIF, EOI.
        let jpeg = with_exif(&[0xff, 0xd8, 0xff, 0xd9], &tiff.into_inner());
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("a.jpg");
        std::fs::write(&path, jpeg).unwrap();

        let out = exif_for(&path).unwrap();
        let back = exif::Reader::new().read_raw(out).unwrap();
        assert_eq!(back.get_field(Tag::Orientation, In::PRIMARY).unwrap().value.get_uint(0), Some(1));
        assert!(back.get_field(Tag::Make, In::PRIMARY).is_some());
        assert_eq!(back.get_field(Tag::PhotographicSensitivity, In::PRIMARY).unwrap().value.get_uint(0), Some(400));
    }
}
