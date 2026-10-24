//! What a photo file says about itself: when it was taken, which way is up, and how it was shot.

use chrono::{FixedOffset, Local, NaiveDateTime, TimeZone};
use exif::{In, Reader, Tag, Value};
use std::fs::File;
use std::io::BufReader;
use std::path::Path;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct PhotoMeta {
    /// Capture time in ms since 1970, from DateTimeOriginal (and its offset, when the camera wrote one).
    pub taken_millis: Option<i64>,
    /// EXIF orientation, 1 = upright.
    pub orientation: u8,
    pub camera: Option<String>,
    pub lens: Option<String>,
    /// "1/250 s"
    pub shutter: Option<String>,
    /// "f/2.8"
    pub aperture: Option<String>,
    pub iso: Option<u32>,
    /// "35 mm"
    pub focal_length: Option<String>,
}

pub fn read(path: &Path) -> PhotoMeta {
    File::open(path)
        .ok()
        .and_then(|f| Reader::new().read_from_container(&mut BufReader::new(f)).ok())
        .map_or(PhotoMeta { orientation: 1, ..Default::default() }, |e| from_exif(&e))
}

/// The start of a TIFF-based file (a RAW), which is where its EXIF lives.
pub fn read_tiff_bytes(head: Vec<u8>) -> Result<PhotoMeta, exif::Error> {
    Reader::new().read_raw(head).map(|e| from_exif(&e))
}

fn from_exif(exif: &exif::Exif) -> PhotoMeta {
    let field = |tag| exif.get_field(tag, In::PRIMARY);
    let text = |tag| {
        field(tag)
            .and_then(|f| match &f.value {
                Value::Ascii(v) => v.first().map(|s| String::from_utf8_lossy(s).trim().trim_end_matches('\0').to_string()),
                _ => None,
            })
            .filter(|s| !s.is_empty())
    };
    let rational = |tag| {
        field(tag).and_then(|f| match &f.value {
            Value::Rational(v) => v.first().map(|r| (r.num, r.denom)),
            _ => None,
        })
    };
    let taken = text(Tag::DateTimeOriginal).and_then(|d| taken_millis(&d, text(Tag::OffsetTimeOriginal).as_deref()));
    let shutter = rational(Tag::ExposureTime).filter(|(_, d)| *d != 0).map(|(n, d)| {
        if n >= d {
            format!("{} s", trim(n as f64 / d as f64))
        } else {
            format!("1/{} s", (d as f64 / n.max(1) as f64).round())
        }
    });
    let aperture = rational(Tag::FNumber).filter(|(_, d)| *d != 0).map(|(n, d)| format!("f/{}", trim(n as f64 / d as f64)));
    let focal = rational(Tag::FocalLength).filter(|(_, d)| *d != 0).map(|(n, d)| format!("{} mm", trim(n as f64 / d as f64)));
    let iso = field(Tag::PhotographicSensitivity).and_then(|f| f.value.get_uint(0));
    let orientation = field(Tag::Orientation).and_then(|f| f.value.get_uint(0)).unwrap_or(1).clamp(1, 8) as u8;
    PhotoMeta { taken_millis: taken, orientation, camera: text(Tag::Model), lens: text(Tag::LensModel), shutter, aperture, iso, focal_length: focal }
}

/// "2026:10:19 16:42:07" (+ "+02:00") as ms since 1970; without an offset it is local time,
/// which is how the camera's clock is set.
pub fn taken_millis(date: &str, offset: Option<&str>) -> Option<i64> {
    let naive = NaiveDateTime::parse_from_str(date.trim(), "%Y:%m:%d %H:%M:%S").ok()?;
    let parsed = offset.and_then(parse_offset).and_then(|o| o.from_local_datetime(&naive).single().map(|t| t.timestamp_millis()));
    parsed.or_else(|| Local.from_local_datetime(&naive).earliest().map(|t| t.timestamp_millis()))
}

fn parse_offset(s: &str) -> Option<FixedOffset> {
    let s = s.trim();
    let sign = match s.chars().next()? {
        '+' => 1,
        '-' => -1,
        _ => return None,
    };
    let (h, m) = s[1..].split_once(':')?;
    FixedOffset::east_opt(sign * (h.parse::<i32>().ok()? * 3600 + m.parse::<i32>().ok()? * 60))
}

fn trim(v: f64) -> String {
    let s = format!("{v:.1}");
    s.strip_suffix(".0").map(str::to_string).unwrap_or(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capture_time_with_an_offset() {
        assert_eq!(taken_millis("2026:10:19 16:42:07", Some("+02:00")), Some(1_792_420_927_000));
        assert_eq!(taken_millis("not a date", None), None);
    }
}
