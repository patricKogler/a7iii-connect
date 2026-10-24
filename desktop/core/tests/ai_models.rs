//! The models on real pictures. They need the downloads, so they only run when
//! `SHOOTBACK_AI_HOME` names a folder to keep them in (and `SHOOTBACK_SAMPLES` one with sample
//! photos: truck.jpg from Segment Anything's notebooks); otherwise they pass without doing anything.

use shootback_core::ai::{self, tensor::Rgb};
use std::path::{Path, PathBuf};

fn setup() -> Option<PathBuf> {
    let home = std::env::var_os("SHOOTBACK_AI_HOME")?;
    ai::set_home(&PathBuf::from(home));
    std::env::var_os("SHOOTBACK_SAMPLES").map(PathBuf::from)
}

fn photo(dir: &Path, name: &str, edge: usize) -> Rgb {
    let bytes = std::fs::read(dir.join(name)).unwrap();
    let mut d = jpeg_decoder::Decoder::new(&bytes[..]);
    let px = d.decode().unwrap();
    let info = d.info().unwrap();
    let rgb = Rgb::from_bytes(&px, info.width as usize, info.height as usize);
    let k = edge as f32 / rgb.width.max(rgb.height) as f32;
    rgb.resized((rgb.width as f32 * k) as usize, (rgb.height as f32 * k) as usize)
}

#[test]
fn the_runtime_downloads_and_starts() {
    if setup().is_none() {
        return;
    }
    ai::runtime().unwrap();
}

#[test]
fn subject_finds_the_truck() {
    let Some(dir) = setup() else { return };
    let px = photo(&dir, "truck.jpg", 1024);
    let m = ai::subject::find(&px).unwrap();
    assert!(m.at(0.5, 0.45) > 0.9, "truck {}", m.at(0.5, 0.45));
    assert!(m.at(0.05, 0.1) < 0.1, "wall {}", m.at(0.05, 0.1));
    assert!(m.at(0.5, 0.95) < 0.1, "road {}", m.at(0.5, 0.95));
}

#[test]
fn a_click_selects_the_front_wheel() {
    let Some(dir) = setup() else { return };
    let px = photo(&dir, "truck.jpg", 1024);
    let e = ai::objects::embed(&px).unwrap();
    let wheel = ai::objects::Point { x: 0.31, y: 0.65, add: true };
    let m = ai::objects::select(&e, &[wheel], 0).unwrap().unwrap();
    assert!(m.at(0.31, 0.65) > 0.8, "wheel {}", m.at(0.31, 0.65));
    assert!(m.at(0.6, 0.35) < 0.2, "door {}", m.at(0.6, 0.35));
    // A click on the other hubcap takes the whole wheel, a step smaller only the hubcap.
    let hub = ai::objects::Point { x: 0.846, y: 0.576, add: true };
    let m = ai::objects::select(&e, &[hub], 0).unwrap().unwrap();
    assert!(m.at(0.84, 0.64) > 0.8, "tyre {}", m.at(0.84, 0.64));
    let m = ai::objects::select(&e, &[hub], -1).unwrap().unwrap();
    assert!(m.at(0.84, 0.64) < 0.2, "tyre {}", m.at(0.84, 0.64));
    assert!(ai::objects::select(&e, &[ai::objects::Point { add: false, ..wheel }], 0).unwrap().is_none());
}

#[test]
fn the_road_is_nearer_than_the_wall() {
    let Some(dir) = setup() else { return };
    let px = photo(&dir, "truck.jpg", 1024);
    let m = ai::depth::find(&px).unwrap();
    assert!(m.at(0.5, 0.97) > m.at(0.5, 0.45) && m.at(0.5, 0.45) > m.at(0.5, 0.05), "{} {} {}", m.at(0.5, 0.97), m.at(0.5, 0.45), m.at(0.5, 0.05));
}
