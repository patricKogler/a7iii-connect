//! Finds a photo's main subject for Subject masks: BiRefNet (lite) marks it, then a guided filter
//! snaps the edges to the photo, as the phone does with ML Kit's mask.

use super::catalog::SUBJECT;
use super::tensor::{self, guided_by, resize_map, Input, Rgb};
use crate::develop::maps::MaskImage;

/// The size the model looks at.
const SIZE: usize = 1024;
const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const STD: [f32; 3] = [0.229, 0.224, 0.225];

/// The subject of `px` (the photo with the default look, about [`super::sky::EDGE`] on the long
/// side), as a map the size of `px`.
pub fn find(px: &Rgb) -> Result<MaskImage, String> {
    let session = super::session(&SUBJECT, "model.onnx")?;
    let input = px.resized(SIZE, SIZE).planar(MEAN, STD);
    let out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("input_image", vec![1, 3, SIZE as i64, SIZE as i64], input)])?;
    let (_, logits) = out.into_iter().next().ok_or("the subject model gave nothing back")?;
    let rough: Vec<f32> = logits.iter().map(|&v| tensor::sigmoid(v)).collect();
    let (w, h) = (px.width, px.height);
    let rough = resize_map(&rough, SIZE, SIZE, w, h);
    let fine = guided_by(&px.luminance(), &rough, w, h, 2.max(w.max(h) / 256), 0.001);
    Ok(MaskImage { width: w, height: h, data: fine.into_iter().map(|v| v.clamp(0.0, 1.0)).collect() })
}
