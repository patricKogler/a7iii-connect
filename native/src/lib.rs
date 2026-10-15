//! JNI entry points for `dev.kogler.a7iiiconnect.develop.engine.Native`. Arrays are pinned with
//! GetPrimitiveArrayCritical (no copies of 24 MP buffers); no other JNI call happens while they
//! are held. A panic is caught and reported as `false`, so Kotlin falls back to its own engine.

mod demosaic;
mod filters;
mod render;

use jni_sys::{jboolean, jclass, jdoubleArray, jfloatArray, jint, jintArray, jobject, jshortArray, jsize, JNIEnv, JNI_ABORT};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

/// A pinned Java primitive array, released when dropped.
struct Pinned<T> {
    env: *mut JNIEnv,
    array: jobject,
    ptr: *mut T,
    len: usize,
    mode: jint,
}

impl<T> Pinned<T> {
    /// Pins `array` (null gives an empty slice). Lengths must be read before pinning anything.
    unsafe fn new(env: *mut JNIEnv, array: jobject, len: usize, write: bool) -> Option<Self> {
        if array.is_null() {
            return Some(Pinned { env, array, ptr: null_mut(), len: 0, mode: 0 });
        }
        let get = (**env).GetPrimitiveArrayCritical?;
        let ptr = get(env, array, null_mut()) as *mut T;
        if ptr.is_null() {
            return None;
        }
        Some(Pinned { env, array, ptr, len, mode: if write { 0 } else { JNI_ABORT } })
    }

    fn slice(&self) -> &[T] {
        if self.ptr.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(self.ptr, self.len) } }
    }

    fn slice_mut(&mut self) -> &mut [T] {
        if self.ptr.is_null() { &mut [] } else { unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) } }
    }
}

impl<T> Drop for Pinned<T> {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            unsafe {
                if let Some(release) = (**self.env).ReleasePrimitiveArrayCritical {
                    release(self.env, self.array, self.ptr as *mut _, self.mode);
                }
            }
        }
    }
}

unsafe fn len(env: *mut JNIEnv, array: jobject) -> usize {
    if array.is_null() {
        return 0;
    }
    match (**env).GetArrayLength {
        Some(f) => f(env, array) as jsize as usize,
        None => 0,
    }
}

fn guarded(f: impl FnOnce() -> Option<()>) -> jboolean {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Some(())) => 1,
        _ => 0,
    }
}

/// MHC demosaic of active-area rows [y0, y1) and columns [x0, x1) into `out` from index
/// `out_offset` (interleaved RGB16).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_kogler_a7iiiconnect_develop_engine_Native_demosaic(
    env: *mut JNIEnv,
    _class: jclass,
    data: jshortArray,
    raw_width: jint,
    cfa: jintArray,
    left: jint,
    top: jint,
    width: jint,
    height: jint,
    black: jintArray,
    scale: jfloatArray,
    x0: jint,
    y0: jint,
    x1: jint,
    y1: jint,
    out: jshortArray,
    out_offset: jint,
) -> jboolean {
    guarded(|| {
        let (ld, lc, lb, ls, lo) = (len(env, data), len(env, cfa), len(env, black), len(env, scale), len(env, out));
        if lc < 4 || lb < 3 || ls < 3 || x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0 || x1 > width || y1 > height {
            return None;
        }
        if out_offset < 0 || lo < out_offset as usize + ((x1 - x0) as usize) * ((y1 - y0) as usize) * 3 {
            return None;
        }
        let (w, h, l, t, rw) = (width as usize, height as usize, left as usize, top as usize, raw_width as usize);
        if ld < (t + h) * rw || l + w > rw {
            return None;
        }
        let d = Pinned::<u16>::new(env, data, ld, false)?;
        let c = Pinned::<i32>::new(env, cfa, lc, false)?;
        let bk = Pinned::<i32>::new(env, black, lb, false)?;
        let sc = Pinned::<f32>::new(env, scale, ls, false)?;
        let mut o = Pinned::<u16>::new(env, out, lo, true)?;
        let raw = demosaic::Raw {
            data: d.slice(),
            raw_width: rw,
            cfa: [c.slice()[0], c.slice()[1], c.slice()[2], c.slice()[3]],
            left: l,
            top: t,
            width: w,
            height: h,
            black: [bk.slice()[0], bk.slice()[1], bk.slice()[2]],
            scale: [sc.slice()[0], sc.slice()[1], sc.slice()[2]],
        };
        demosaic::demosaic(&raw, y0 as usize, y1 as usize, x0 as usize, x1 as usize, &mut o.slice_mut()[out_offset as usize..]);
        Some(())
    })
}

/// Renders output rows [y0, y1) of a band into `out` (ARGB).
///
/// `src_info`: width, height, left, top, sensor width, sensor height, orientation.
/// `grid_info`: x0, y0, nx, ny, step. `frame`: width, height, frame width, frame height,
/// origin x, origin y, scale. `guide_size`: width, height (null when there is no guide).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_kogler_a7iiiconnect_develop_engine_Native_render(
    env: *mut JNIEnv,
    _class: jclass,
    params: jfloatArray,
    tone: jfloatArray,
    curves: jfloatArray,
    guide_size: jintArray,
    base: jfloatArray,
    medium: jfloatArray,
    dark: jfloatArray,
    air: jfloatArray,
    src: jshortArray,
    src_info: jdoubleArray,
    grid: jfloatArray,
    grid_info: jfloatArray,
    vig: jfloatArray,
    frame: jdoubleArray,
    y0: jint,
    y1: jint,
    out: jintArray,
) -> jboolean {
    guarded(|| {
        let lens: Vec<usize> = [params, tone, curves, guide_size, base, medium, dark, air, src, src_info, grid, grid_info, vig, frame, out]
            .iter()
            .map(|&a| len(env, a))
            .collect();
        if lens[0] < render::idx::COUNT || lens[1] < 4097 || lens[9] < 7 || lens[11] < 5 || lens[13] < 7 {
            return None;
        }
        let si = Pinned::<f64>::new(env, src_info, lens[9], false)?;
        let gi = Pinned::<f32>::new(env, grid_info, lens[11], false)?;
        let fi = Pinned::<f64>::new(env, frame, lens[13], false)?;
        let (si, gi, fi) = (si.slice().to_vec(), gi.slice().to_vec(), fi.slice().to_vec());
        let fr = render::Frame {
            width: fi[0] as usize,
            height: fi[1] as usize,
            frame_width: fi[2] as usize,
            frame_height: fi[3] as usize,
            origin_x: fi[4] as usize,
            origin_y: fi[5] as usize,
            scale: fi[6],
        };
        let (sw, sh) = (si[0] as usize, si[1] as usize);
        let (nx, ny) = (gi[2] as usize, gi[3] as usize);
        if y0 < 0 || y1 < y0 || y1 as usize > fr.height || fr.width == 0 || nx < 1 || ny < 1 || sw == 0 || sh == 0 {
            return None;
        }
        if lens[8] < sw * sh * 3 || lens[10] < nx * ny * 6 || (lens[12] != 0 && lens[12] < nx * ny) || lens[14] < fr.width * (y1 - y0) as usize {
            return None;
        }
        if lens[2] != 0 && lens[2] < 3 * 1024 {
            return None;
        }

        let p = Pinned::<f32>::new(env, params, lens[0], false)?;
        let tn = Pinned::<f32>::new(env, tone, lens[1], false)?;
        let cv = Pinned::<f32>::new(env, curves, lens[2], false)?;
        let gs = Pinned::<i32>::new(env, guide_size, lens[3], false)?;
        let gb = Pinned::<f32>::new(env, base, lens[4], false)?;
        let gm = Pinned::<f32>::new(env, medium, lens[5], false)?;
        let gd = Pinned::<f32>::new(env, dark, lens[6], false)?;
        let ga = Pinned::<f32>::new(env, air, lens[7], false)?;
        let s = Pinned::<u16>::new(env, src, lens[8], false)?;
        let g = Pinned::<f32>::new(env, grid, lens[10], false)?;
        let v = Pinned::<f32>::new(env, vig, lens[12], false)?;
        let mut o = Pinned::<i32>::new(env, out, lens[14], true)?;

        let guide = if gs.slice().len() >= 2 {
            let (w, h) = (gs.slice()[0] as usize, gs.slice()[1] as usize);
            let n = w * h;
            if w == 0 || h == 0 || gb.slice().len() < n || gm.slice().len() < n || gd.slice().len() < n || ga.slice().len() < 3 {
                return None;
            }
            Some(render::Guide {
                width: w,
                height: h,
                base: gb.slice(),
                medium: gm.slice(),
                dark: gd.slice(),
                air: [ga.slice()[0], ga.slice()[1], ga.slice()[2]],
            })
        } else {
            None
        };
        let look = render::Look { p: p.slice(), tone: tn.slice(), curves: cv.slice() };
        let source = render::Source {
            data: s.slice(),
            width: sw,
            height: sh,
            left: si[2],
            top: si[3],
            sensor_width: si[4],
            sensor_height: si[5],
            orientation: si[6] as i32,
        };
        let grid = render::Grid { x0: gi[0], y0: gi[1], nx, ny, step: gi[4], map: g.slice(), vig: v.slice() };
        look.render(guide.as_ref(), &source, &grid, &fr, y0 as usize, y1 as usize, o.slice_mut());
        Some(())
    })
}
