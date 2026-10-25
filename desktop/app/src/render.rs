//! Full-size renders, for export and for a close look at part of the photo (the phone's
//! renderBands): the photo never sits on the GPU whole. Each band of rows is sampled through the
//! geometry, prepared and drawn in tiles, every one carrying the look's margin of scene around it
//! so they meet without seams.

use crate::gpu::{self, Engine, Inputs, Placement};
use shootback_core::develop::geometry::Geometry;
use shootback_core::develop::look::{frame_size, FullImage, Look};
use shootback_core::develop::lut::Lut;
use shootback_core::develop::maps::MaskImage;
use shootback_core::develop::{Opened, Settings};
use std::sync::atomic::{AtomicBool, Ordering};

/// Rows per band.
const BAND: usize = 256;
/// Widest tile drawn at once (with its margin it stays under every GPU's texture limit).
const TILE: usize = 4096;

/// The maps an export's masks select with: the found ones (subject, sky, depth) and the masks'
/// own, by slot.
#[derive(Default, Clone)]
pub struct MaskMaps {
    pub found: [Option<MaskImage>; 3],
    pub own: Vec<Option<MaskImage>>,
}

/// The photo developed at full size: width, height and RGB bytes. `progress` gets the fraction
/// done; `cancel` stops it early.
#[allow(clippy::too_many_arguments)]
pub fn full_size(
    engine: &Engine,
    opened: &Opened,
    s: &Settings,
    lut: Option<&Lut>,
    maps: &MaskMaps,
    progress: &dyn Fn(f32),
    cancel: &AtomicBool,
) -> Result<(usize, usize, Vec<u8>), String> {
    let geom = opened.source.geometry(s, false);
    let full = opened.full_image(s)?;
    let (w, h) = frame_size(&geom, 1.0);
    let rgb = region(engine, opened, &full, s, &geom, lut, maps, [0, 0, w, h], progress, cancel)?;
    Ok((w, h, rgb))
}

/// Part of the frame developed at full size: `area` is its left, top, width and height in
/// full-size pixels of the frame `geom` makes. Answers its RGB bytes.
#[allow(clippy::too_many_arguments)]
pub fn region(
    engine: &Engine,
    opened: &Opened,
    full: &FullImage,
    s: &Settings,
    geom: &Geometry,
    lut: Option<&Lut>,
    maps: &MaskMaps,
    area: [usize; 4],
    progress: &dyn Fn(f32),
    cancel: &AtomicBool,
) -> Result<Vec<u8>, String> {
    let source = &opened.source;
    let look = Look::new(source, s);
    let guide = look.guide(source, geom);
    let (w, h) = frame_size(geom, 1.0);
    let [x0, y0, aw, ah] = area;
    let params = look.shader_params(1.0);
    let pad = look.margin(1.0);
    let band = BAND.min(ah);
    let tile_w = TILE.min(aw);

    let tone = engine.texture(params.tone.len(), 1, &gpu::tone_data(&params.tone));
    let curves = params.curves.as_ref().map(|c| engine.texture(c[0].len(), 3, &gpu::curves_data(c)));
    let guide_tex = guide.as_ref().map(|g| (engine.texture(g.width, g.height, &gpu::guide_data(g)), g.air));
    let lut_tex = lut.map(|l| {
        let (lw, lh, data) = gpu::lut_data(l);
        engine.texture(lw, lh, &data)
    });
    let found =
        gpu::found_data([maps.found[0].as_ref(), maps.found[1].as_ref(), maps.found[2].as_ref()]).map(|(fw, fh, data, f)| (engine.texture(fw, fh, &data), f));
    let own = gpu::maps_data(&maps.own.iter().map(|m| m.as_ref()).collect::<Vec<_>>()).map(|(mw, mh, data)| engine.texture(mw, mh, &data));
    let target = engine.target(tile_w, band);
    let mut out = vec![0u8; aw * ah * 3];
    let mut y = y0;
    while y < y0 + ah {
        if cancel.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        let rows = band.min(y0 + ah - y);
        let mut img = full.sample_rows(geom, y as i64 - pad as i64, rows + 2 * pad);
        look.prepare(&mut img, 1.0);
        let mut x = x0;
        while x < x0 + aw {
            let cols = tile_w.min(x0 + aw - x);
            // Past the photo's left and right edges the shader clamps, as in the preview.
            let left = pad.min(x);
            let right = pad.min(w - x - cols);
            let scene = engine.texture(cols + left + right, img.height, &gpu::scene_data(&img, x - left, cols + left + right));
            let coords = (params.mask_count > 0).then(|| {
                let (nx, ny, data) = gpu::coords_data(geom, 1.0, cols, rows, x, y);
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
                found: found.as_ref().map(|(t, f)| (t, *f)),
                maps: own.as_ref(),
                overlay: None,
                params: &params,
                place,
            };
            engine.draw(&inputs, &target);
            let px = engine.read(&target, cols, rows);
            for r in 0..rows {
                let at = ((y - y0 + r) * aw + x - x0) * 3;
                let dst = &mut out[at..at + cols * 3];
                for (d, s) in dst.as_chunks_mut::<3>().0.iter_mut().zip(px[r * cols * 4..(r + 1) * cols * 4].as_chunks::<4>().0) {
                    d.copy_from_slice(&s[..3]);
                }
            }
            x += cols;
        }
        y += rows;
        progress((y - y0) as f32 / ah as f32);
    }
    Ok(out)
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
