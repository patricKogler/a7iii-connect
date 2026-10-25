//! Object masks: click on something and Segment Anything 2.1 selects it. The photo is looked at
//! once (the encoder, a few seconds); each click after that only runs the small decoder, so
//! adding or taking away parts feels immediate.

use super::catalog::OBJECTS;
use super::tensor::{self, guided_by, resize_map, Input, Rgb};
use crate::develop::maps::MaskImage;

const SIZE: usize = 1024;
const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const STD: [f32; 3] = [0.229, 0.224, 0.225];

/// What the encoder made of one photo: everything the decoder needs to answer clicks.
pub struct Embedding {
    embed: Vec<f32>,
    feats0: Vec<f32>,
    feats1: Vec<f32>,
    /// The picture it was made from, for snapping masks to its edges.
    lum: Vec<f32>,
    width: usize,
    height: usize,
}

/// A click: where (fractions of the upright photo) and whether it adds or takes away.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
    pub add: bool,
}

/// Looks at the photo (`px`, the default look) once.
pub fn embed(px: &Rgb) -> Result<Embedding, String> {
    let session = super::session(&OBJECTS, "encoder.onnx")?;
    let input = px.resized(SIZE, SIZE).planar(MEAN, STD);
    let out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("image", vec![1, 3, SIZE as i64, SIZE as i64], input)])?;
    let mut by_shape = out.into_iter();
    // Outputs: high_res_feats_0 [1,32,256,256], high_res_feats_1 [1,64,128,128], image_embed [1,256,64,64].
    let (Some(f0), Some(f1), Some(e)) = (by_shape.next(), by_shape.next(), by_shape.next()) else {
        return Err("the object model gave too little back".into());
    };
    Ok(Embedding { embed: e.1, feats0: f0.1, feats1: f1.1, lum: px.luminance(), width: px.width, height: px.height })
}

/// The object the clicks point at, as a map the size of the embedded picture. None without a
/// click that adds. The model offers three readings, from a part to the whole thing; `grain`
/// steps from the first guess to a smaller (below 0) or larger one.
pub fn select(e: &Embedding, points: &[Point], grain: i8) -> Result<Option<MaskImage>, String> {
    if !points.iter().any(|p| p.add) {
        return Ok(None);
    }
    let session = super::session(&OBJECTS, "decoder.onnx")?;
    let n = points.len() as i64;
    let coords: Vec<f32> = points.iter().flat_map(|p| [p.x * SIZE as f32, p.y * SIZE as f32]).collect();
    let labels: Vec<f32> = points.iter().map(|p| if p.add { 1.0 } else { 0.0 }).collect();
    let out = tensor::run(
        &mut session.lock().unwrap(),
        vec![
            Input::F32("image_embed", vec![1, 256, 64, 64], e.embed.clone()),
            Input::F32("high_res_feats_0", vec![1, 32, 256, 256], e.feats0.clone()),
            Input::F32("high_res_feats_1", vec![1, 64, 128, 128], e.feats1.clone()),
            Input::F32("point_coords", vec![1, n, 2], coords),
            Input::F32("point_labels", vec![1, n], labels),
            Input::F32("mask_input", vec![1, 1, 256, 256], vec![0.0; 256 * 256]),
            Input::F32("has_mask_input", vec![1], vec![0.0]),
        ],
    )?;
    let mut it = out.into_iter();
    let (Some((shape, masks)), Some((_, iou))) = (it.next(), it.next()) else {
        return Err("the object model gave too little back".into());
    };
    let count = (shape.get(1).copied().unwrap_or(1).max(1) as usize).min(iou.len());
    let side = 256 * 256;
    let area = |k: usize| masks[k * side..(k + 1) * side].iter().filter(|&&v| v > 0.0).count();
    let mut by_size: Vec<usize> = (0..count).collect();
    by_size.sort_by_key(|&k| area(k));
    let first = guess(&by_size, &iou, points.len());
    let at = (first as i64 + grain as i64).clamp(0, count as i64 - 1) as usize;
    let best = by_size[at];
    let logits = &masks[best * side..(best + 1) * side];
    // Logits to a soft edge about a pixel of the small mask wide. Not a sigmoid: its long tails
    // would leave a faint haze over everything near the object.
    let soft: Vec<f32> = logits
        .iter()
        .map(|&v| {
            let t = ((v + 1.0) / 2.0).clamp(0.0, 1.0);
            t * t * (3.0 - 2.0 * t)
        })
        .collect();
    let (w, h) = (e.width, e.height);
    let rough = resize_map(&soft, 256, 256, w, h);
    let fine = guided_by(&e.lum, &rough, w, h, 2.max(w.max(h) / 256), 0.001);
    Ok(Some(MaskImage { width: w, height: h, data: fine.into_iter().map(|v| v.clamp(0.0, 1.0)).collect() }))
}

/// Which of the readings (`by_size`, smallest first) to start from. After one click the model
/// rates the small parts highest (a wheel's hubcap over the wheel), while people mostly mean the
/// thing they clicked on, so it takes the largest reading the model is fairly sure of. More
/// clicks settle what's meant, so then its own best rating wins.
fn guess(by_size: &[usize], iou: &[f32], clicks: usize) -> usize {
    let best = (0..by_size.len()).max_by(|&a, &b| iou[by_size[a]].total_cmp(&iou[by_size[b]])).unwrap_or(0);
    if clicks > 1 {
        return best;
    }
    (0..by_size.len()).rev().find(|&i| iou[by_size[i]] >= SURE).unwrap_or(best)
}

/// How sure the model must be of a reading for a single click to take it.
const SURE: f32 = 0.15;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_click_takes_the_largest_reading_the_model_is_fairly_sure_of() {
        // A click on a hubcap: the truck (0.01), the hubcap (0.70), the wheel (0.23).
        let iou = [0.01, 0.70, 0.23];
        let by_size = [1, 2, 0];
        assert_eq!(by_size[guess(&by_size, &iou, 1)], 2);
        assert_eq!(by_size[guess(&by_size, &iou, 2)], 1);
    }
}
