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

#[test]
fn denoise_cleans_a_noisy_raw() {
    use shootback_core::develop::raw::{RawImage, RawInfo};
    use std::sync::atomic::AtomicBool;
    let Some(dir) = setup() else { return };
    // A made-up raw: the truck as photosites in a red-green-green-blue pattern, with shot and
    // read noise like a high ISO.
    let px = photo(&dir, "truck.jpg", 1200);
    let (w, h) = (px.width & !1, px.height & !1);
    let lin = |v: f32| if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) };
    let (black, white) = (512, 16383);
    let mut seed = 12345u64;
    let mut noise = || {
        // Two uniform draws to a rough normal one.
        let mut u = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f32 / (1u64 << 53) as f32
        };
        (u() + u() + u() + u() - 2.0) * 1.73
    };
    let cfa = [0, 1, 1, 2];
    let mut data = vec![0u16; w * h];
    let mut clean = vec![0f32; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            let rgb = px.at(x, y).map(|v| lin(v) * 0.5);
            clean[(y * w + x) * 3..][..3].copy_from_slice(&rgb);
            let c = cfa[((y & 1) << 1) | (x & 1)];
            let e = rgb[c] * (white - black) as f32;
            let v = e + noise() * (e * 40.0 + 2500.0).sqrt();
            data[y * w + x] = (v + black as f32).clamp(0.0, white as f32) as u16;
        }
    }
    let raw = RawImage { info: RawInfo::plain(black, white, [1.0; 3]), raw_width: w, data, cfa, left: 0, top: 0, width: w, height: h };
    // How far off the picture is where it's flat (the truck's panels, the wall), so there's no
    // detail to lose: where the clean picture barely changes over 5x5 pixels.
    let lum = |i: usize| clean[i * 3] + clean[i * 3 + 1] + clean[i * 3 + 2];
    let flat: Vec<usize> = (2..h - 2)
        .flat_map(|y| (2..w - 2).map(move |x| (x, y)))
        .filter(|&(x, y)| {
            let c = lum(y * w + x);
            (0..25).all(|k| (lum((y + k / 5 - 2) * w + x + k % 5 - 2) - c).abs() < 0.01)
        })
        .map(|(x, y)| y * w + x)
        .collect();
    assert!(flat.len() > w * h / 20, "flat {}", flat.len());
    let err = |img: &[u16]| {
        let sum: f64 = flat.iter().flat_map(|&i| (0..3).map(move |c| i * 3 + c)).map(|i| (img[i] as f64 / 65535.0 - clean[i] as f64).powi(2)).sum();
        (sum / (flat.len() * 3) as f64).sqrt()
    };
    let noisy = err(&raw.demosaic());
    let den = ai::denoise::raw(&raw, &|_| {}, &AtomicBool::new(false)).unwrap();
    let cleaned = err(&den);
    assert!(cleaned < noisy / 3.0, "error {noisy} before, {cleaned} after");
}

#[test]
fn faces_are_found_and_told_apart() {
    let Some(dir) = setup() else { return };
    let px = photo(&dir, "messi5.jpg", 1024);
    let faces = ai::faces::find(&px).unwrap();
    eprintln!("{} {}x{} {faces:?}", faces.len(), px.width, px.height);
    assert!(!faces.is_empty());
    let f = &faces[0];
    assert!(f.turned() < 0.6, "turned {}", f.turned());
    // The same face at another size points the same way; the truck has no face.
    let a = ai::faces::embed(&px, f).unwrap();
    let small = photo(&dir, "messi5.jpg", 600);
    let g = ai::faces::find(&small).unwrap().remove(0);
    let b = ai::faces::embed(&small, &g).unwrap();
    let cos: f32 = a.iter().zip(&b).map(|(x, y)| x * y).sum();
    assert_eq!(a.len(), 128);
    assert!(cos > 0.8, "same face {cos}");
    assert!(ai::faces::find(&photo(&dir, "truck.jpg", 1024)).unwrap().is_empty());
}

#[test]
fn search_finds_photos_from_words() {
    let Some(dir) = setup() else { return };
    let t = ai::clip::Tokenizer::new(&std::fs::read_to_string(ai::model_dir(&ai::catalog::CLIP).join("vocab.txt")).unwrap());
    assert_eq!(t.encode("A photo of a  dog"), vec![49406, 320, 1125, 539, 320, 1929, 49407]);
    let names = ["dog.jpg", "truck.jpg", "fruits.jpg", "building.jpg", "messi5.jpg"];
    let index: std::collections::HashMap<String, Vec<f32>> =
        names.iter().map(|n| (n.to_string(), ai::clip::embed_image(&photo(&dir, n, 384)).unwrap())).collect();
    for (words, want) in [("a dog", "dog.jpg"), ("white pickup truck", "truck.jpg"), ("fruit", "fruits.jpg"), ("football", "messi5.jpg")] {
        let found = ai::clip::ranked(&ai::clip::embed_query(words).unwrap(), &index);
        eprintln!("{words}: {found:?}");
        assert_eq!(found.first().map(|f| f.0.as_str()), Some(want), "{words}");
    }
}

#[test]
fn remove_fills_a_spot_from_around_it() {
    let Some(dir) = setup() else { return };
    let mut px = photo(&dir, "truck.jpg", 1024);
    let (w, h) = (px.width, px.height);
    // A dark blot on the red wall, top left.
    let (cx, cy, r) = (150.0f32, 90.0f32, 14.0f32);
    let wall = px.at(150, 60);
    for y in 0..h {
        for x in 0..w {
            if ((x as f32 - cx).powi(2) + (y as f32 - cy).powi(2)).sqrt() < r {
                let i = (y * w + x) * 3;
                px.data[i..i + 3].copy_from_slice(&[0.05, 0.05, 0.05]);
            }
        }
    }
    let patch = ai::inpaint::fill(|x, y| px.at(x, y), w, h, &[[cx, cy]], r + 2.0).unwrap();
    let k = (cy as usize - patch.y) * patch.w + (cx as usize - patch.x);
    let got = patch.rgb[k];
    eprintln!("wall {wall:?} filled {got:?} alpha {}", patch.alpha[k]);
    assert_eq!(patch.alpha[k], 1.0);
    assert!((0..3).all(|c| (got[c] - wall[c]).abs() < 0.12), "{got:?} vs {wall:?}");
}

#[test]
fn removals_reach_the_preview_and_the_export() {
    let Some(dir) = setup() else { return };
    let o = shootback_core::develop::open(&dir.join("truck.jpg")).unwrap();
    let mut s = shootback_core::develop::Settings::default();
    s.removals.push(shootback_core::develop::params::Removal { radius: 0.012, points: vec![[0.445, 0.202], [0.52, 0.13]] });
    let before = o.preview(&s).into_owned();
    o.fill_removal(&s, 0).unwrap();
    assert_eq!(o.removals_ready(&s), 1);
    let after = o.preview(&s);
    let changed = before.r.iter().zip(&after.r).filter(|(a, b)| (*a - *b).abs() > 0.01).count();
    eprintln!("changed {changed} of {}", before.r.len());
    assert!(changed > 100);
}

#[test]
fn upscaling_brings_back_detail() {
    let Some(dir) = setup() else { return };
    use std::sync::atomic::AtomicBool;
    let big = photo(&dir, "building.jpg", 1200);
    for factor in [2, 4] {
        let (w, h) = (big.width / factor, big.height / factor);
        let small = big.resized(w, h);
        let up = ai::upscale::upscale(&small.to_bytes(), w, h, factor, &|_| {}, &AtomicBool::new(false)).unwrap();
        let (bw, bh) = (w * factor, h * factor);
        let truth = big.resized(bw, bh).to_bytes();
        let plain = small.resized(bw, bh).to_bytes();
        let err = |a: &[u8]| a.iter().zip(&truth).map(|(&a, &b)| (a as f32 - b as f32).powi(2)).sum::<f32>() / a.len() as f32;
        let (model, resized) = (err(&up), err(&plain));
        eprintln!("{factor}x: model {model:.1}, resized {resized:.1}");
        assert_eq!(up.len(), bw * bh * 3);
        assert!(model < resized * 0.9, "{factor}x: model {model} vs resized {resized}");
    }
}
