//! How near each part of a photo is, for Depth masks: Depth Anything V2 (small) estimates relative
//! depth, which is spread over 0..1 (1 nearest) and snapped to the photo's edges.

use super::catalog::DEPTH;
use super::tensor::{self, guided_by, resize_map, Input, Rgb};
use crate::develop::maps::MaskImage;

/// The size the model looks at (a multiple of its 14-pixel patches).
const SIZE: usize = 518;
const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const STD: [f32; 3] = [0.229, 0.224, 0.225];

/// Nearness over `px` (the photo with the default look), 1 for the nearest part, 0 the farthest.
pub fn find(px: &Rgb) -> Result<MaskImage, String> {
    let session = super::session(&DEPTH, "model.onnx")?;
    let input = px.resized(SIZE, SIZE).planar(MEAN, STD);
    let out = tensor::run(&mut session.lock().unwrap(), vec![Input::F32("l_x_", vec![1, 3, SIZE as i64, SIZE as i64], input)])?;
    let (_, disparity) = out.into_iter().next().ok_or("the depth model gave nothing back")?;
    let near = spread(&disparity);
    let (w, h) = (px.width, px.height);
    let near = resize_map(&near, SIZE, SIZE, w, h);
    let fine = guided_by(&px.luminance(), &near, w, h, 2.max(w.max(h) / 256), 0.0001);
    Ok(MaskImage { width: w, height: h, data: fine.into_iter().map(|v| v.clamp(0.0, 1.0)).collect() })
}

/// The model's disparity (larger is nearer, in no particular unit) over 0..1, the farthest and
/// nearest hundredth clipped so a stray pixel doesn't squeeze the rest.
fn spread(d: &[f32]) -> Vec<f32> {
    let mut sorted = d.to_vec();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let lo = sorted[sorted.len() / 100];
    let hi = sorted[sorted.len() - 1 - sorted.len() / 100];
    let span = (hi - lo).max(1e-6);
    d.iter().map(|v| ((v - lo) / span).clamp(0.0, 1.0)).collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn spread_covers_zero_to_one() {
        let d: Vec<f32> = (0..1000).map(|i| i as f32 * 0.01 + 3.0).collect();
        let s = super::spread(&d);
        assert_eq!(s[0], 0.0);
        assert_eq!(s[999], 1.0);
        assert!((s[500] - 0.5).abs() < 0.02);
    }
}
