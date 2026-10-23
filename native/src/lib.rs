//! JNI entry points for `dev.kogler.a7iiiconnect.develop.engine.Native`. Arrays are pinned with
//! GetPrimitiveArrayCritical (no copies of 24 MP buffers); no other JNI call happens while they
//! are held. A panic is caught and reported as `false`, so Kotlin falls back to its own demosaic.

mod demosaic;

use jni_sys::{jboolean, jclass, jfloatArray, jint, jintArray, jobject, jshortArray, jsize, JNIEnv, JNI_ABORT};
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
