//! The phone's golden renders (app/src/test/resources/golden in the Android app), redone here: the
//! same generated scene and settings through this port must give the same pictures, so an edit
//! looks alike on both. The scene is drawn with tiny-skia, a port of the Skia the phone's test
//! draws with, so only anti-aliased edges differ a little.

use crate::gpu::{self, Engine, Inputs, Placement};
use shootback_core::develop::color::CameraColor;
use shootback_core::develop::image::LinearImage;
use shootback_core::develop::look::{sample, Look, Source};
use shootback_core::develop::params::{local, p, Curve, Mask, Profile, Settings};
use std::path::Path;
use std::sync::Arc;
use tiny_skia::{Color, FillRule, GradientStop, LinearGradient, Paint, PathBuilder, Pixmap, Point, RadialGradient, Rect, SpreadMode, Transform};

fn argb(c: u32) -> Color {
    Color::from_rgba8((c >> 16) as u8, (c >> 8) as u8, c as u8, (c >> 24) as u8)
}

fn stops(colors: &[u32], at: &[f32]) -> Vec<GradientStop> {
    colors.iter().zip(at).map(|(c, t)| GradientStop::new(*t, argb(*c))).collect()
}

/// TestScenes.landscape: a lake and mountains at sunset, drawn in a 3000 x 2000 space.
fn landscape(out_w: u32, out_h: u32) -> Pixmap {
    let mut pm = Pixmap::new(out_w, out_h).unwrap();
    let t = Transform::from_scale(out_w as f32 / 3000.0, out_h as f32 / 2000.0);
    let (w, h) = (3000.0f32, 2000.0f32);
    let horizon = h * 0.58;
    let mut paint = Paint { anti_alias: true, ..Default::default() };
    let rect = |l: f32, t: f32, r: f32, b: f32| Rect::from_ltrb(l, t, r, b).unwrap();

    paint.shader = LinearGradient::new(
        Point::from_xy(0.0, 0.0),
        Point::from_xy(0.0, horizon),
        stops(&[0xFF1B3A6B, 0xFF6C7FB0, 0xFFF2A65A, 0xFFFFD39A], &[0.0, 0.45, 0.85, 1.0]),
        SpreadMode::Pad,
        Transform::identity(),
    )
    .unwrap();
    pm.fill_rect(rect(0.0, 0.0, w, horizon), &paint, t, None);
    let sun = Point::from_xy(w * 0.68, horizon - 120.0);
    paint.shader =
        RadialGradient::new(sun, sun, 520.0, stops(&[0xFFFFF6D8, 0x66FFC070, 0x00FFC070], &[0.0, 0.25, 1.0]), SpreadMode::Pad, Transform::identity()).unwrap();
    pm.fill_rect(rect(0.0, 0.0, w, horizon), &paint, t, None);

    let solid = |c: u32| Paint { shader: tiny_skia::Shader::SolidColor(argb(c)), anti_alias: true, ..Default::default() };
    let mut ridge = |base: f32, amp: f32, freq: f32, phase: f32, color: u32| {
        let mut pb = PathBuilder::new();
        pb.move_to(0.0, horizon);
        let mut x = 0.0f32;
        while x <= w {
            let y = base - amp * (0.6 * (x / w * freq + phase).sin() + 0.3 * (x / w * freq * 2.7 + phase * 2.0).sin() + 0.1 * (x / w * freq * 7.1).sin());
            pb.line_to(x, y);
            x += 10.0;
        }
        pb.line_to(w, horizon);
        pb.close();
        pm.fill_path(&pb.finish().unwrap(), &solid(color), FillRule::Winding, t, None);
    };
    ridge(horizon - 330.0, 260.0, 5.0, 0.3, 0xFF5B5F86);
    ridge(horizon - 170.0, 200.0, 7.0, 1.7, 0xFF3C4466);
    ridge(horizon - 40.0, 110.0, 11.0, 2.9, 0xFF223322);

    paint.shader = LinearGradient::new(
        Point::from_xy(0.0, horizon),
        Point::from_xy(0.0, h * 0.86),
        stops(&[0xFFE89A5A, 0xFF4A5A80, 0xFF14233F], &[0.0, 0.4, 1.0]),
        SpreadMode::Pad,
        Transform::identity(),
    )
    .unwrap();
    pm.fill_rect(rect(0.0, horizon, w, h * 0.86), &paint, t, None);
    let glint = solid(0x55FFE0B0);
    for i in 0..14 {
        let (i, y) = (i as f32, horizon + 20.0 + i as f32 * 28.0);
        pm.fill_rect(rect(w * 0.68 - 160.0 + i * 6.0, y, w * 0.68 + 160.0 - i * 6.0, y + 6.0), &glint, t, None);
    }

    let ink = solid(0xFF0F1A0E);
    let mut pb = PathBuilder::new();
    pb.move_to(0.0, h * 0.84);
    pb.cubic_to(w * 0.3, h * 0.80, w * 0.6, h * 0.90, w, h * 0.83);
    pb.line_to(w, h);
    pb.line_to(0.0, h);
    pb.close();
    pm.fill_path(&pb.finish().unwrap(), &ink, FillRule::Winding, t, None);
    for (tx, th) in [(220.0f32, 520.0f32), (330.0, 420.0), (2650.0, 600.0), (2780.0, 470.0)] {
        let mut pb = PathBuilder::new();
        pb.move_to(tx, h * 0.84 - th);
        pb.line_to(tx - th * 0.22, h * 0.86);
        pb.line_to(tx + th * 0.22, h * 0.86);
        pb.close();
        pm.fill_path(&pb.finish().unwrap(), &ink, FillRule::Winding, t, None);
    }
    pm
}

fn engine() -> Option<Engine> {
    let instance = eframe::wgpu::Instance::default();
    let adapter = pollster::block_on(instance.request_adapter(&Default::default()))?;
    let (device, queue) = pollster::block_on(adapter.request_device(&Default::default(), None)).ok()?;
    Some(Engine::new(Arc::new(device), Arc::new(queue)))
}

/// One render of a whole (small) photo, as the phone's renderBands does it in one piece.
fn render(engine: &Engine, src: &Source, s: &Settings) -> (usize, usize, Vec<u8>) {
    let geom = src.geometry(s, false);
    let look = Look::new(src, s);
    let guide = look.guide(src, &geom);
    let mut scene = sample(&src.image, &geom, None, None);
    look.prepare(&mut scene, 1.0);
    let (w, h) = (scene.width, scene.height);
    let params = look.shader_params(1.0);
    let scene_tex = engine.texture(w, h, &gpu::scene_data(&scene, 0, w));
    let tone = engine.texture(params.tone.len(), 1, &gpu::tone_data(&params.tone));
    let curves = params.curves.as_ref().map(|c| engine.texture(c[0].len(), 3, &gpu::curves_data(c)));
    let guide_tex = guide.as_ref().map(|g| (engine.texture(g.width, g.height, &gpu::guide_data(g)), g.air));
    let coords = (params.mask_count > 0).then(|| {
        let (nx, ny, data) = gpu::coords_data(&geom, 1.0, w, h, 0, 0);
        engine.texture(nx, ny, &data)
    });
    let target = engine.target(w, h);
    let inputs = Inputs {
        scene: &scene_tex,
        tone: &tone,
        curves: curves.as_ref(),
        guide: guide_tex.as_ref().map(|(t, a)| (t, *a)),
        coords: coords.as_ref(),
        lut: None,
        found: None,
        maps: None,
        overlay: None,
        params: &params,
        place: Placement::whole(w, h),
    };
    engine.draw(&inputs, &target);
    (w, h, engine.read(&target, w, h))
}

#[test]
fn renders_match_the_phones_goldens() {
    let Some(engine) = engine() else {
        eprintln!("no GPU adapter; skipped");
        return;
    };
    let goldens = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/test/resources/golden");
    if !goldens.exists() {
        eprintln!("no goldens at {}; skipped", goldens.display());
        return;
    }
    let (w, h) = (600usize, 400usize);
    let pm = landscape(w as u32, h as u32);
    let rgb: Vec<u8> = pm.data().as_chunks::<4>().0.iter().flat_map(|px| [px[0], px[1], px[2]]).collect();
    let jpeg_img = LinearImage::from_srgb8(&rgb, w, h);
    let mut raw_img = jpeg_img.clone();
    for i in 0..w * h {
        raw_img.r[i] *= 0.45;
        raw_img.g[i] *= 0.5;
        raw_img.b[i] *= 0.4;
    }
    let mut seed: i32 = 12345;
    let mut noise = || {
        seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
        (((seed as u32) >> 8) & 0xffff) as f32 / 65536.0 - 0.5
    };
    let mut noisy_img = LinearImage::new(w, h);
    for i in 0..w * h {
        noisy_img.r[i] = (raw_img.r[i] * (1.0 + 0.25 * noise())).max(0.0);
        noisy_img.g[i] = (raw_img.g[i] * (1.0 + 0.15 * noise())).max(0.0);
        noisy_img.b[i] = (raw_img.b[i] * (1.0 + 0.3 * noise())).max(0.0);
    }
    let source = |image: LinearImage, raw: bool| Source {
        image,
        color: if raw { CameraColor::for_camera("ILCE-7M3") } else { CameraColor::srgb() },
        baked: if raw { [2.1, 1.0, 1.55] } else { [1.0; 3] },
        is_raw: raw,
        full_width: w,
        full_height: h,
        lens: None,
        baseline: 0.0,
    };
    let raw = source(raw_img, true);
    let noisy = source(noisy_img, true);
    let jpeg = source(jpeg_img, false);
    let d = Settings::default;
    let with = |pairs: &[(shootback_core::develop::params::Param, f32)]| pairs.iter().fold(d(), |s, (q, v)| s.with(q, *v));
    let mut curves = d();
    curves.curves[0] = Curve { points: vec![(0.0, 0.05), (0.3, 0.22), (0.7, 0.8), (1.0, 1.0)] };
    curves.curves[1] = Curve { points: vec![(0.0, 0.0), (0.5, 0.56), (1.0, 1.0)] };
    let mut mono = with(&[(p::MIX_LUM[5], -50.0), (p::MIX_LUM[1], 40.0), (p::CONTRAST, 20.0)]);
    mono.profile = Profile::Monochrome;
    let mut linear = Mask::linear();
    for (q, v) in [(local::EXPOSURE, -1.0), (local::TEMP, -30.0), (local::DEHAZE, 40.0), (local::CONTRAST, 25.0)] {
        linear.set(&q, v);
    }
    let mut mask_linear = d();
    mask_linear.masks = vec![linear];
    let mut r1 = Mask { x0: 0.68, y0: 0.5, angle: 25.0, ..Mask::radial(1.5) };
    for (q, v) in [(local::EXPOSURE, 0.6), (local::SATURATION, 40.0), (local::TINT, 20.0)] {
        r1.set(&q, v);
    }
    let mut r2 = Mask { invert: true, feather: 80.0, ..Mask::radial(1.5) };
    for (q, v) in [(local::EXPOSURE, -0.8), (local::CLARITY, 30.0)] {
        r2.set(&q, v);
    }
    let mut mask_radial = with(&[(p::CLARITY, 10.0)]);
    mask_radial.masks = vec![r1, r2];

    let cases: Vec<(&str, &Source, Settings)> = vec![
        ("raw-default", &raw, d()),
        (
            "raw-light",
            &raw,
            with(&[(p::EXPOSURE, 0.6), (p::CONTRAST, 30.0), (p::HIGHLIGHTS, -60.0), (p::SHADOWS, 50.0), (p::WHITES, 15.0), (p::BLACKS, -10.0)]),
        ),
        ("raw-local", &raw, with(&[(p::CLARITY, 50.0), (p::TEXTURE, 40.0), (p::DEHAZE, 30.0)])),
        (
            "raw-color",
            &raw,
            with(&[
                (p::TEMPERATURE, 4300.0),
                (p::TINT, 12.0),
                (p::VIBRANCE, 40.0),
                (p::SATURATION, -20.0),
                (p::MIX_HUE[1], 30.0),
                (p::MIX_SAT[5], -60.0),
                (p::MIX_LUM[5], 40.0),
                (p::GRADE_HUE[0], 210.0),
                (p::GRADE_SAT[0], 40.0),
                (p::GRADE_HUE[2], 40.0),
                (p::GRADE_SAT[2], 35.0),
                (p::GRADE_LUM[1], 20.0),
            ]),
        ),
        ("raw-mono", &raw, mono),
        ("raw-curves", &raw, curves),
        ("raw-effects", &raw, with(&[(p::VIGNETTE, -50.0), (p::VIGNETTE_ROUNDNESS, 30.0), (p::SHARPEN, 80.0), (p::SHARPEN_MASKING, 30.0)])),
        ("raw-grain", &raw, with(&[(p::GRAIN, 50.0)])),
        ("raw-noise", &noisy, with(&[(p::NOISE, 60.0), (p::COLOR_NOISE, 50.0), (p::DEFRINGE, 50.0), (p::SHARPEN, 40.0)])),
        ("jpeg-mixed", &jpeg, with(&[(p::EXPOSURE, -0.3), (p::SHADOWS, 40.0), (p::VIBRANCE, 25.0), (p::VIGNETTE, 30.0)])),
        ("mask-linear", &raw, mask_linear),
        ("mask-radial", &raw, mask_radial),
    ];
    let out_dir = std::env::temp_dir().join("shootback-parity");
    let _ = std::fs::create_dir_all(&out_dir);
    let mut report = String::new();
    let mut failures = Vec::new();
    for (name, src, s) in cases {
        let (rw, rh, px) = render(&engine, src, &s);
        let golden = image::open(goldens.join(format!("{name}.png"))).unwrap().to_rgb8();
        assert_eq!((golden.width() as usize, golden.height() as usize), (rw, rh), "{name}: size");
        let mut d: Vec<u8> = Vec::with_capacity(rw * rh * 3);
        let mut side = image::RgbImage::new(rw as u32 * 3, rh as u32);
        for (i, g) in golden.pixels().enumerate() {
            let (x, y) = ((i % rw) as u32, (i / rw) as u32);
            let now = [px[i * 4], px[i * 4 + 1], px[i * 4 + 2]];
            let diff: [u8; 3] = std::array::from_fn(|c| now[c].abs_diff(g.0[c]));
            d.extend(diff);
            side.put_pixel(x, y, *g);
            side.put_pixel(x + rw as u32, y, image::Rgb(now));
            side.put_pixel(x + 2 * rw as u32, y, image::Rgb(diff.map(|v| v.saturating_mul(8))));
        }
        let _ = side.save(out_dir.join(format!("{name}.png")));
        let mean = d.iter().map(|v| *v as f64).sum::<f64>() / d.len() as f64;
        d.sort_unstable();
        let p99 = d[d.len() * 99 / 100];
        report += &format!("{name}\tmean {mean:.2}\tp99 {p99}\tmax {}\n", d.last().unwrap());
        // The phone holds its own renders to mean 0.5 / p99 4; the scene's edges drawn by another
        // rasteriser add a little. Grain's hash rounds differently on another GPU where its cells
        // meet, which moves single grains.
        let (max_mean, max_p99) = if name == "raw-grain" { (1.0, 12) } else { (0.8, 6) };
        if mean > max_mean || p99 > max_p99 {
            failures.push(name);
        }
    }
    eprintln!("{report}(side by side in {})", out_dir.display());
    assert!(failures.is_empty(), "differ from the phone: {failures:?}\n{report}");
}
