//! Finds a photo's sky for Sky masks, without a model: a port of the phone's SkyDetect, so both
//! find the same sky. The sky is the smooth area that reaches the top of the picture. It grows
//! from the smooth parts of the top edge through neighbours that are smooth and close in colour
//! (so it follows a gradient from blue to pale near the horizon but stops at a roofline), then
//! takes in patches of the same colour seen through branches, and finally a guided filter snaps
//! the edges to the photo. Given the photo's depth, the computer also keeps the sky to the
//! farthest parts, so a pale wall reaching the top doesn't pass for an overcast sky.

use super::tensor::{guided_by, Rgb};
use crate::develop::image::box_blur;
use crate::develop::maps::MaskImage;

/// Long edge of the picture looked at, and of the stored map (the phone's).
pub const EDGE: usize = 1024;
/// Share of the picture the sky must cover to count.
const MIN_AREA: f32 = 0.03;
/// Biggest colour change between neighbouring sky pixels (0..1 per channel, summed).
const STEP: f32 = 0.05;

/// Nearness (of a depth map, 0 farthest) below which a pixel can be sky.
const FAR: f32 = 0.06;

/// A 0..1 sky map for `px` (the photo with the default look), or None when there's no sky along
/// the top. `depth`, when known, is the photo's nearness (see `depth::find`).
pub fn find(px: &Rgb, depth: Option<&MaskImage>) -> Option<MaskImage> {
    let (w, h) = (px.width, px.height);
    let n = w * h;
    let far: Vec<bool> = match depth {
        Some(d) => (0..n).map(|i| d.at(((i % w) as f32 + 0.5) / w as f32, ((i / w) as f32 + 0.5) / h as f32) < FAR).collect(),
        None => vec![true; n],
    };
    let (mut r, mut g, mut b) = (vec![0.0f32; n], vec![0.0f32; n], vec![0.0f32; n]);
    for i in 0..n {
        // The phone works on 8-bit pixels.
        let q = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5).floor() / 255.0;
        r[i] = q(px.data[i * 3]);
        g[i] = q(px.data[i * 3 + 1]);
        b[i] = q(px.data[i * 3 + 2]);
    }
    let lum: Vec<f32> = (0..n).map(|i| 0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i]).collect();
    let tex = texture(&lum, w, h);
    // Sky colours: blue (down to dusk), or a pale grey to white (overcast, haze, clouds).
    let blue: Vec<bool> = (0..n).map(|i| far[i] && b[i] > r[i] + 0.02 && b[i] >= g[i] - 0.03 && lum[i] > 0.06).collect();
    let skyish: Vec<bool> = (0..n)
        .map(|i| {
            let mx = r[i].max(g[i]).max(b[i]);
            let mn = r[i].min(g[i]).min(b[i]);
            blue[i] || (far[i] && mx - mn < 0.12 && lum[i] > 0.5)
        })
        .collect();

    // How smooth is smooth: judged from the top tenth of the picture (its far parts).
    let band = 2.max(h / 10);
    let mut top: Vec<f32> = (0..band * w).filter(|&i| far[i]).map(|i| tex[i]).collect();
    if top.len() < band * w / 50 {
        return None;
    }
    top.sort_by(|a, b| a.total_cmp(b));
    let calm = top[(top.len() as f32 * 0.3) as usize];
    if calm > 0.03 {
        return None;
    }
    let limit = (calm * 3.0).clamp(0.01, 0.05);
    let mut mask = if depth.is_some() { by_depth(&far, &skyish, &tex, limit, w, h) } else { by_colour(&r, &g, &b, &tex, &blue, &skyish, limit, w, h) }?;

    fill_holes(&mut mask, w, h, (n as f32 * 0.01) as usize);

    // Snap to the photo's edges; luminance guides the mask.
    let fine = guided_by(&lum, &mask, w, h, 2.max(w.max(h) / 200), 0.0005);
    Some(MaskImage { width: w, height: h, data: fine.into_iter().map(|v| v.clamp(0.0, 1.0)).collect() })
}

/// With the depth: the far parts reaching the top of the picture, past branches and between
/// buildings, where they're smooth and sky-coloured.
fn by_depth(far: &[bool], skyish: &[bool], tex: &[f32], limit: f32, w: usize, h: usize) -> Option<Vec<f32>> {
    let n = w * h;
    // Thin branches in front of the sky break it into pieces; they're stepped over.
    let ones: Vec<f32> = far.iter().map(|&f| if f { 1.0 } else { 0.0 }).collect();
    let mut spread = vec![0.0; n];
    box_blur(&ones, &mut spread, w, h, 2.max(w.max(h) / 100));
    let far: Vec<bool> = (0..n).map(|i| far[i] || spread[i] > 0.3).collect();
    let mut seen = vec![false; n];
    let mut queue: Vec<usize> = (0..w).filter(|&i| far[i]).collect();
    queue.iter().for_each(|&i| seen[i] = true);
    let mut head = 0;
    while head < queue.len() {
        let i = queue[head];
        head += 1;
        let (x, y) = (i % w, i / w);
        for (ok, j) in [(x > 0, i.wrapping_sub(1)), (x < w - 1, i + 1), (y > 0, i.wrapping_sub(w)), (y < h - 1, i + w)] {
            if ok && far[j] && !seen[j] {
                seen[j] = true;
                queue.push(j);
            }
        }
    }
    let mask: Vec<f32> = (0..n).map(|i| if seen[i] && skyish[i] && tex[i] < limit * 1.5 { 1.0 } else { 0.0 }).collect();
    if mask.iter().sum::<f32>() < n as f32 * MIN_AREA {
        return None;
    }
    Some(mask)
}

/// Without it, as the phone does.
#[allow(clippy::too_many_arguments)]
fn by_colour(r: &[f32], g: &[f32], b: &[f32], tex: &[f32], blue: &[bool], skyish: &[bool], limit: f32, w: usize, h: usize) -> Option<Vec<f32>> {
    let n = w * h;

    // Grow from the smooth sky-coloured pixels of the top rows. Blue ones when there are enough,
    // so a white wall reaching the top doesn't pass for an overcast sky.
    let mut sky = vec![false; n];
    let mut queue = vec![0usize; n];
    let (mut head, mut tail) = (0, 0);
    let seed_rows = h.min(2.max(h / 40));
    let blue_seeds = (0..seed_rows * w).filter(|&i| tex[i] < limit && blue[i]).count();
    let seed_ok = if blue_seeds >= seed_rows * w / 10 { blue } else { skyish };
    for i in 0..seed_rows * w {
        if tex[i] < limit && seed_ok[i] && !sky[i] {
            sky[i] = true;
            queue[tail] = i;
            tail += 1;
        }
    }
    let close = |i: usize, j: usize| (r[i] - r[j]).abs() + (g[i] - g[j]).abs() + (b[i] - b[j]).abs() < STEP;
    while head < tail {
        let i = queue[head];
        head += 1;
        let (x, y) = (i % w, i / w);
        let mut visit = |j: usize| {
            if !sky[j] && tex[j] < limit && skyish[j] && close(i, j) {
                sky[j] = true;
                queue[tail] = j;
                tail += 1;
            }
        };
        if x > 0 {
            visit(i - 1);
        }
        if x < w - 1 {
            visit(i + 1);
        }
        if y > 0 {
            visit(i - w);
        }
        if y < h - 1 {
            visit(i + w);
        }
    }
    if (tail as f32) < n as f32 * MIN_AREA {
        return None;
    }

    // Patches of the same sky seen through branches or between buildings (smooth, and close in
    // colour to the sky found at that height).
    const ROWS: usize = 32;
    let mut sums = [[0.0f32; 4]; ROWS];
    let mut lowest = 0;
    for i in 0..n {
        if sky[i] {
            let s = &mut sums[i / w * ROWS / h];
            s[0] += r[i];
            s[1] += g[i];
            s[2] += b[i];
            s[3] += 1.0;
            lowest = lowest.max(i / w);
        }
    }
    // Rows with no sky borrow the nearest row above that has some.
    let mut last: Option<[f32; 4]> = None;
    for s in sums.iter_mut() {
        if s[3] > 0.0 {
            for c in 0..3 {
                s[c] /= s[3];
            }
            last = Some(*s);
        } else if let Some(l) = last {
            *s = l;
        }
    }
    let mut mask = vec![0.0f32; n];
    for i in 0..n {
        if sky[i] {
            mask[i] = 1.0;
            continue;
        }
        let y = i / w;
        if y > lowest || tex[i] >= limit * 1.5 || !skyish[i] {
            continue;
        }
        let s = sums[y * ROWS / h];
        if s[3] <= 0.0 {
            continue;
        }
        let d = ((r[i] - s[0]).powi(2) + (g[i] - s[1]).powi(2) + (b[i] - s[2]).powi(2)).sqrt();
        if d < 0.06 {
            mask[i] = 1.0;
        }
    }

    Some(mask)
}

/// Small bits inside the sky that don't touch the picture's edge (birds, wires, a stray cloud
/// edge) become sky.
fn fill_holes(mask: &mut [f32], w: usize, h: usize, max_area: usize) {
    let mut seen = vec![false; w * h];
    let mut queue = vec![0usize; w * h];
    for start in 0..mask.len() {
        if seen[start] || mask[start] > 0.5 {
            continue;
        }
        let (mut head, mut tail) = (0, 0);
        queue[tail] = start;
        tail += 1;
        seen[start] = true;
        let mut edge = false;
        while head < tail {
            let i = queue[head];
            head += 1;
            let (x, y) = (i % w, i / w);
            if x == 0 || y == 0 || x == w - 1 || y == h - 1 {
                edge = true;
            }
            let mut visit = |j: usize| {
                if !seen[j] && mask[j] <= 0.5 {
                    seen[j] = true;
                    queue[tail] = j;
                    tail += 1;
                }
            };
            if x > 0 {
                visit(i - 1);
            }
            if x < w - 1 {
                visit(i + 1);
            }
            if y > 0 {
                visit(i - w);
            }
            if y < h - 1 {
                visit(i + w);
            }
        }
        if !edge && tail <= max_area {
            for &k in &queue[..tail] {
                mask[k] = 1.0;
            }
        }
    }
}

/// Detail around each pixel: luminance gradient after a light blur, averaged over 5x5.
fn texture(lum: &[f32], w: usize, h: usize) -> Vec<f32> {
    let mut soft = vec![0.0; w * h];
    box_blur(lum, &mut soft, w, h, 1);
    let mut grad = vec![0.0; w * h];
    for y in 0..h {
        for x in 0..w {
            let gx = soft[y * w + (w - 1).min(x + 1)] - soft[y * w + x.saturating_sub(1)];
            let gy = soft[(h - 1).min(y + 1) * w + x] - soft[y.saturating_sub(1) * w + x];
            grad[y * w + x] = gx.abs() + gy.abs();
        }
    }
    let mut out = vec![0.0; w * h];
    box_blur(&grad, &mut out, w, h, 2);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A blue gradient sky over a textured brown ground.
    fn landscape(w: usize, h: usize) -> Rgb {
        let mut data = Vec::with_capacity(w * h * 3);
        for y in 0..h {
            for x in 0..w {
                if y < h / 2 {
                    let t = y as f32 / h as f32;
                    data.extend([0.35 + 0.3 * t, 0.55 + 0.2 * t, 0.9]);
                } else {
                    let n = ((x * 7919 + y * 104729) % 97) as f32 / 97.0;
                    data.extend([0.35 + 0.2 * n, 0.25 + 0.15 * n, 0.1]);
                }
            }
        }
        Rgb { width: w, height: h, data }
    }

    #[test]
    fn finds_the_sky_above_the_horizon() {
        let px = landscape(200, 120);
        let m = find(&px, None).expect("a sky");
        assert!(m.at(0.5, 0.2) > 0.9, "sky {}", m.at(0.5, 0.2));
        assert!(m.at(0.5, 0.8) < 0.1, "ground {}", m.at(0.5, 0.8));
    }

    #[test]
    fn a_pale_wall_near_by_is_not_sky() {
        // The top half is pale grey, like overcast sky or a white wall.
        let mut px = landscape(200, 120);
        for y in 0..60 {
            for x in 0..200 {
                px.data[(y * 200 + x) * 3..(y * 200 + x) * 3 + 3].copy_from_slice(&[0.8, 0.8, 0.8]);
            }
        }
        assert!(find(&px, None).is_some());
        let mut near = MaskImage::new(200, 120);
        near.data.iter_mut().for_each(|v| *v = 0.5);
        assert!(find(&px, Some(&near)).is_none());
    }

    #[test]
    fn no_sky_in_a_picture_of_ground() {
        let mut px = landscape(200, 120);
        let ground = px.data[(110 * 200) * 3..].to_vec();
        for y in 0..120 {
            px.data[y * 200 * 3..(y + 1) * 200 * 3].copy_from_slice(&ground[..200 * 3]);
        }
        assert!(find(&px, None).is_none());
    }
}
