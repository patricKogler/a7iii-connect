//! Full-resolution Malvar-He-Cutler demosaic, the same maths as Kotlin's `Demosaic.fullRows`.

use rayon::prelude::*;

pub struct Raw<'a> {
    pub data: &'a [u16],
    pub raw_width: usize,
    pub cfa: [i32; 4],
    pub left: usize,
    pub top: usize,
    pub width: usize,
    pub height: usize,
    pub black: [i32; 3],
    pub scale: [f32; 3],
}

impl Raw<'_> {
    #[inline]
    fn color(&self, row: usize, col: usize) -> usize {
        self.cfa[((row & 1) << 1) | (col & 1)] as usize
    }

    /// Black-subtracted, white-balanced photosite, mirrored at the active area's edges.
    #[inline]
    fn v(&self, row: isize, col: isize) -> f32 {
        let h = self.height as isize;
        let w = self.width as isize;
        let r = if row < 0 { -row } else if row >= h { 2 * h - 2 - row } else { row };
        let c = if col < 0 { -col } else if col >= w { 2 * w - 2 - col } else { col };
        let rr = self.top + r as usize;
        let cc = self.left + c as usize;
        let color = self.color(rr, cc);
        (self.data[rr * self.raw_width + cc] as i32 - self.black[color]) as f32 * self.scale[color]
    }
}

#[inline]
fn clip(v: f32) -> f32 {
    if v < 0.0 { 0.0 } else if v > 1.0 { 1.0 } else { v }
}

#[inline]
fn q(v: f32) -> u16 {
    (clip(v) * 65535.0 + 0.5) as i32 as u16
}

/// Demosaics active-area rows [y0, y1) and columns [x0, x1) into `out`, interleaved RGB16,
/// (x1 - x0) pixels per row.
pub fn demosaic(raw: &Raw, y0: usize, y1: usize, x0: usize, x1: usize, out: &mut [u16]) {
    let ow = x1 - x0;
    out.par_chunks_mut(ow * 3).take(y1 - y0).enumerate().for_each(|(i, row)| {
        let y = (y0 + i) as isize;
        for x in x0..x1 {
            let xi = x as isize;
            let c = raw.color(raw.top + y as usize, raw.left + x);
            let center = raw.v(y, xi);
            let cross = raw.v(y - 1, xi) + raw.v(y + 1, xi) + raw.v(y, xi - 1) + raw.v(y, xi + 1);
            let far = raw.v(y - 2, xi) + raw.v(y + 2, xi) + raw.v(y, xi - 2) + raw.v(y, xi + 2);
            let (r, g, b);
            if c == 1 {
                g = center;
                let horiz = raw.v(y, xi - 1) + raw.v(y, xi + 1);
                let vert = raw.v(y - 1, xi) + raw.v(y + 1, xi);
                let diag = raw.v(y - 1, xi - 1) + raw.v(y - 1, xi + 1) + raw.v(y + 1, xi - 1) + raw.v(y + 1, xi + 1);
                let far_h = raw.v(y, xi - 2) + raw.v(y, xi + 2);
                let far_v = raw.v(y - 2, xi) + raw.v(y + 2, xi);
                let along_row = (5.0 * center + 4.0 * horiz - diag - far_h + 0.5 * far_v) / 8.0;
                let along_col = (5.0 * center + 4.0 * vert - diag - far_v + 0.5 * far_h) / 8.0;
                let row_color = raw.color(raw.top + y as usize, raw.left + x + 1);
                if row_color == 0 { r = along_row; b = along_col } else { b = along_row; r = along_col }
            } else {
                g = (4.0 * center + 2.0 * cross - far) / 8.0;
                let diag = raw.v(y - 1, xi - 1) + raw.v(y - 1, xi + 1) + raw.v(y + 1, xi - 1) + raw.v(y + 1, xi + 1);
                let other = (6.0 * center + 2.0 * diag - 1.5 * far) / 8.0;
                if c == 0 { r = center; b = other } else { b = center; r = other }
            }
            let o = (x - x0) * 3;
            row[o] = q(r);
            row[o + 1] = q(g);
            row[o + 2] = q(b);
        }
    });
}
