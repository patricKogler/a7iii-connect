//! Full-size renders for export (the phone's renderBands): the photo never sits on the GPU whole.
//! Each band of rows is sampled through the geometry, prepared and drawn in tiles, every one
//! carrying the look's margin of scene around it so they meet without seams.

use crate::gpu::{self, Engine, Inputs, Placement};
use shootback_core::develop::look::{frame_size, Look};
use shootback_core::develop::lut::Lut;
use shootback_core::develop::{Opened, Settings};
use std::sync::atomic::{AtomicBool, Ordering};

/// Rows per band.
const BAND: usize = 256;
/// Widest tile drawn at once (with its margin it stays under every GPU's texture limit).
const TILE: usize = 4096;

/// The photo developed at full size: width, height and RGB bytes. `progress` gets the fraction
/// done; `cancel` stops it early.
pub fn full_size(
    engine: &Engine,
    opened: &Opened,
    s: &Settings,
    lut: Option<&Lut>,
    progress: &dyn Fn(f32),
    cancel: &AtomicBool,
) -> Result<(usize, usize, Vec<u8>), String> {
    let source = &opened.source;
    let geom = source.geometry(s, false);
    let look = Look::new(source, s);
    let guide = look.guide(source, &geom);
    let full = opened.full_image()?;
    let (w, h) = frame_size(&geom, 1.0);
    let params = look.shader_params(1.0);
    let pad = look.margin(1.0);
    let band = BAND.min(h);
    let tile_w = TILE.min(w);

    let tone = engine.texture(params.tone.len(), 1, &gpu::tone_data(&params.tone));
    let curves = params.curves.as_ref().map(|c| engine.texture(c[0].len(), 3, &gpu::curves_data(c)));
    let guide_tex = guide.as_ref().map(|g| (engine.texture(g.width, g.height, &gpu::guide_data(g)), g.air));
    let lut_tex = lut.map(|l| {
        let (lw, lh, data) = gpu::lut_data(l);
        engine.texture(lw, lh, &data)
    });
    let target = engine.target(tile_w, band);
    let mut out = vec![0u8; w * h * 3];
    let mut y = 0;
    while y < h {
        if cancel.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        let rows = band.min(h - y);
        let mut img = full.sample_rows(&geom, y as i64 - pad as i64, rows + 2 * pad);
        look.prepare(&mut img, 1.0);
        let mut x = 0;
        while x < w {
            let cols = tile_w.min(w - x);
            // Past the photo's left and right edges the shader clamps, as in the preview.
            let left = pad.min(x);
            let right = pad.min(w - x - cols);
            let scene = engine.texture(cols + left + right, img.height, &gpu::scene_data(&img, x - left, cols + left + right));
            let coords = (params.mask_count > 0).then(|| {
                let (nx, ny, data) = gpu::coords_data(&geom, 1.0, cols, rows, x, y);
                engine.texture(nx, ny, &data)
            });
            let place = Placement { width: cols, height: rows, origin_x: x, origin_y: y, frame_w: w, frame_h: h, pad_x: left, pad_y: pad };
            let inputs = Inputs {
                scene: &scene,
                tone: &tone,
                curves: curves.as_ref(),
                guide: guide_tex.as_ref().map(|(t, a)| (t, *a)),
                coords: coords.as_ref(),
                lut: lut_tex.as_ref(),
                params: &params,
                place,
            };
            engine.draw(&inputs, &target);
            let px = engine.read(&target, cols, rows);
            for r in 0..rows {
                let dst = &mut out[((y + r) * w + x) * 3..((y + r) * w + x + cols) * 3];
                for (d, s) in dst.as_chunks_mut::<3>().0.iter_mut().zip(px[r * cols * 4..(r + 1) * cols * 4].as_chunks::<4>().0) {
                    d.copy_from_slice(&s[..3]);
                }
            }
            x += cols;
        }
        y += rows;
        progress(y as f32 / h as f32);
    }
    Ok((w, h, out))
}

/// Encodes an export: JPEG at high quality with the original's EXIF.
pub fn encode_jpeg(w: usize, h: usize, rgb: &[u8], exif: Option<&[u8]>) -> Result<Vec<u8>, String> {
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 92)
        .encode(rgb, w as u32, h as u32, image::ExtendedColorType::Rgb8)
        .map_err(|e| e.to_string())?;
    Ok(match exif {
        Some(t) => shootback_core::develop::export::with_exif(&jpeg, t),
        None => jpeg,
    })
}
