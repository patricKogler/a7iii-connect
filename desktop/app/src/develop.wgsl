// The develop pipeline per pixel: a WGSL port of the phone's AGSL shader (GpuPreview.kt), line
// for line, so an edit renders the same on both. Inputs are the geometry-corrected camera pixels
// (scene) plus lookup textures; every texture is sampled at pixel positions like AGSL's eval().

struct U {
    frame: vec4f,      // frameSize.xy, origin.xy
    pad_guide: vec4f,  // scenePad.xy, guideScale.xy
    row0: vec4f,
    row1: vec4f,
    row2: vec4f,
    flags: vec4f,      // useCurves, hasGuide, mono, hasMixer
    tone1: vec4f,      // ev, shadows, highlights, clarity
    tone2: vec4f,      // dehaze, textureAmt, textureRadius, sat
    air: vec4f,        // air.xyz, vib
    mix_hue: array<vec4f, 2>,
    mix_sat: array<vec4f, 2>,
    mix_lum: array<vec4f, 2>,
    band_c: array<vec4f, 2>,
    band_prev: array<vec4f, 2>,
    band_next: array<vec4f, 2>,
    grade: vec4f,      // hasGrading, blending, balance, maskCount
    grade_off: array<vec4f, 3>,
    grade_lum: vec4f,
    vig: vec4f,        // vignette, vigMid, vigFeather, vigRound
    vig2: vec4f,       // vigHighlights, grain, grainCell, grainRough
    sharp: vec4f,      // sharpen, sharpenRadius, sharpenLimit, sharpenMasking
    misc: vec4f,       // scale, hasSubject, hasSky, lutAmount
    src: vec4f,        // srcSize.xy, coordScale.xy
    subj: vec4f,       // subjSize.xy, lutSize
    masks: array<vec4f, 38>,
}

@group(0) @binding(0) var<uniform> u: U;
@group(0) @binding(1) var samp: sampler;
@group(0) @binding(2) var scene: texture_2d<f32>;
@group(0) @binding(3) var toneLut: texture_2d<f32>;
@group(0) @binding(4) var curves: texture_2d<f32>;
@group(0) @binding(5) var guide: texture_2d<f32>;
@group(0) @binding(6) var coords: texture_2d<f32>;
@group(0) @binding(7) var subject: texture_2d<f32>;
@group(0) @binding(8) var lut: texture_2d<f32>;

@vertex
fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4f {
    let x = f32(i32(i & 1u) * 4 - 1);
    let y = f32(i32(i >> 1u) * 4 - 1);
    return vec4f(x, y, 0.0, 1.0);
}

fn ev(t: texture_2d<f32>, p: vec2f) -> vec4f {
    return textureSampleLevel(t, samp, p / vec2f(textureDimensions(t)), 0.0);
}

// GLSL's mod: x - y * floor(x / y) (WGSL's % truncates).
fn fmod(x: f32, y: f32) -> f32 { return x - y * floor(x / y); }

fn mixHue(k: i32) -> f32 { return u.mix_hue[k / 4][k % 4]; }
fn mixSat(k: i32) -> f32 { return u.mix_sat[k / 4][k % 4]; }
fn mixLum(k: i32) -> f32 { return u.mix_lum[k / 4][k % 4]; }
fn bandC(k: i32) -> f32 { return u.band_c[k / 4][k % 4]; }
fn bandPrev(k: i32) -> f32 { return u.band_prev[k / 4][k % 4]; }
fn bandNext(k: i32) -> f32 { return u.band_next[k / 4][k % 4]; }
fn gradeOff(i: i32) -> f32 { return u.grade_off[i / 4][i % 4]; }
fn maskData(i: i32) -> f32 { return u.masks[i / 4][i % 4]; }

const LUT_SIZE: f32 = 4096.0;
const LUT_MAX: f32 = 64.0;
const MID: f32 = -3.0588937;

fn luma(c: vec3f) -> f32 { return dot(c, vec3f(0.2126, 0.7152, 0.0722)); }
fn logv(v: f32) -> f32 { return log2(max(v, 0.00001)); }

// p is a position in the drawn area; the scene texture has a margin around it.
fn lin(p: vec2f) -> vec3f {
    let c = ev(scene, p + u.pad_guide.xy).rgb;
    return max(vec3f(dot(u.row0.xyz, c), dot(u.row1.xyz, c), dot(u.row2.xyz, c)), vec3f(0.0));
}

fn tone(x: f32) -> f32 {
    if (x <= 0.0) { return 0.0; }
    let t = min(sqrt(x / LUT_MAX) * LUT_SIZE, LUT_SIZE);
    return ev(toneLut, vec2f(t + 0.5, 0.5)).r;
}

fn curve(v: f32, row: f32) -> f32 {
    return ev(curves, vec2f(clamp(v, 0.0, 1.0) * 1023.0 + 0.5, row + 0.5)).r;
}

fn hueOf(c: vec3f, mx: f32, ch: f32) -> f32 {
    var h: f32;
    if (mx == c.r) {
        h = (c.g - c.b) / ch;
        if (h < 0.0) { h += 6.0; }
    } else if (mx == c.g) {
        h = (c.b - c.r) / ch + 2.0;
    } else {
        h = (c.r - c.g) / ch + 4.0;
    }
    return h * 60.0;
}

fn hsv(h: f32, s: f32, v: f32) -> vec3f {
    let p = abs(fract(vec3f(h / 360.0) + vec3f(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return v * mix(vec3f(1.0), clamp(p - 1.0, vec3f(0.0), vec3f(1.0)), s);
}

fn grade(cin: vec3f, l: f32) -> vec3f {
    if (u.grade.x == 0.0) { return cin; }
    var c = cin;
    let pivot = clamp(0.5 - u.grade.z * 0.3, 0.15, 0.85);
    let width = 0.15 + u.grade.y * 0.6;
    let ws = 1.0 - smoothstep(pivot - width, pivot, l);
    let wh = smoothstep(pivot, pivot + width, l);
    let wm = max(0.0, 1.0 - ws - wh);
    c += (vec3f(gradeOff(0), gradeOff(1), gradeOff(2)) + u.grade_lum.x * 0.2) * ws;
    c += (vec3f(gradeOff(3), gradeOff(4), gradeOff(5)) + u.grade_lum.y * 0.2) * wm;
    c += (vec3f(gradeOff(6), gradeOff(7), gradeOff(8)) + u.grade_lum.z * 0.2) * wh;
    c += vec3f(gradeOff(9), gradeOff(10), gradeOff(11)) + u.grade_lum.w * 0.2;
    return c;
}

fn colorize(cin: vec3f) -> vec3f {
    var c = cin;
    let mono = u.flags.z;
    let hasMixer = u.flags.w;
    let mx = max(c.r, max(c.g, c.b));
    let mn = min(c.r, min(c.g, c.b));
    let ch = mx - mn;
    if ((hasMixer > 0.0 || mono > 0.0) && ch > 0.0001) {
        let hue = hueOf(c, mx, ch);
        var dh = 0.0;
        var ds = 0.0;
        var dl = 0.0;
        for (var k = 0; k < 8; k++) {
            let d = fmod(hue - bandC(k) + 540.0, 360.0) - 180.0;
            var w: f32;
            if (d >= 0.0) { w = max(0.0, 1.0 - d / bandNext(k)); } else { w = max(0.0, 1.0 + d / bandPrev(k)); }
            dh += w * mixHue(k);
            ds += w * mixSat(k);
            dl += w * mixLum(k);
        }
        let l = luma(c);
        let satNow = min(ch / max(0.0001, 1.0 - abs(mx + mn - 1.0)), 1.0);
        if (mono > 0.0) {
            let grey = clamp(l + dl * 0.35 * satNow, 0.0, 1.0);
            return grade(vec3f(grey), grey);
        }
        if (dh != 0.0) { c = hsv(fmod(hue + dh * 30.0 + 360.0, 360.0), ch / max(mx, 0.0001), mx); }
        if (ds != 0.0) {
            let ll = luma(c);
            c = ll + (c - ll) * (1.0 + ds);
        }
        if (dl != 0.0) { c += dl * 0.3 * satNow; }
    } else if (mono > 0.0) {
        let g = luma(c);
        return grade(vec3f(g), g);
    }
    let sat = u.tone2.w;
    let vib = u.air.w;
    if (sat != 1.0 || vib != 0.0) {
        let l = luma(c);
        let cc = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
        let f = sat * (1.0 + vib * (1.0 - cc) * (1.0 - cc));
        c = l + (c - l) * f;
    }
    return grade(c, luma(c));
}

fn decodeSrgb(v: vec3f) -> vec3f {
    return select(pow((v + 0.055) / 1.055, vec3f(2.4)), v / 12.92, v <= vec3f(0.04045));
}

fn lumAt(p: vec2f) -> f32 { return logv(luma(lin(p))); }

fn subjectAt(uv: vec2f) -> f32 {
    if (u.misc.y == 0.0) { return 0.0; }
    return ev(subject, uv * u.subj.xy).r;
}

// The sky map rides in the subject texture's green channel.
fn skyAt(uv: vec2f) -> f32 {
    if (u.misc.z == 0.0) { return 0.0; }
    return ev(subject, uv * u.subj.xy).g;
}

fn displayLum(p: vec2f) -> f32 { return tone(luma(lin(p))); }

// Binomial weights 1 4 6 4 1.
fn bw(i: i32) -> f32 {
    if (i == 2) { return 6.0; }
    if (i == 1 || i == 3) { return 4.0; }
    return 1.0;
}

// Blurs of log luminance (Texture) and display luminance (Sharpen) with sigma about r: nine taps
// for the small radii of the preview, 25 for full-size renders.
fn blurLum(p: vec2f, r: f32) -> f32 {
    if (r > 1.5) {
        var s = 0.0;
        for (var j = 0; j < 5; j++) {
            for (var i = 0; i < 5; i++) { s += bw(i) * bw(j) * lumAt(p + vec2f(f32(i - 2), f32(j - 2)) * r); }
        }
        return s / 256.0;
    }
    let d = r * 1.4;
    return (4.0 * lumAt(p)
        + 2.0 * (lumAt(p + vec2f(d, 0.0)) + lumAt(p - vec2f(d, 0.0)) + lumAt(p + vec2f(0.0, d)) + lumAt(p - vec2f(0.0, d)))
        + lumAt(p + vec2f(d, d)) + lumAt(p - vec2f(d, d)) + lumAt(p + vec2f(d, -d)) + lumAt(p - vec2f(d, -d))) / 16.0;
}

fn blurDisplay(p: vec2f, r: f32) -> f32 {
    if (r > 1.5) {
        var s = 0.0;
        for (var j = 0; j < 5; j++) {
            for (var i = 0; i < 5; i++) { s += bw(i) * bw(j) * displayLum(p + vec2f(f32(i - 2), f32(j - 2)) * r); }
        }
        return s / 256.0;
    }
    let d = r * 1.4;
    return (4.0 * displayLum(p)
        + 2.0 * (displayLum(p + vec2f(d, 0.0)) + displayLum(p - vec2f(d, 0.0)) + displayLum(p + vec2f(0.0, d)) + displayLum(p - vec2f(0.0, d)))
        + displayLum(p + vec2f(d, d)) + displayLum(p - vec2f(d, d)) + displayLum(p + vec2f(d, -d)) + displayLum(p - vec2f(d, -d))) / 16.0;
}

// Sine-free hash on small (wrapped) cell coordinates.
fn hash(q: vec2f) -> f32 {
    let w = q.xyx - 1024.0 * floor(q.xyx / 1024.0);
    var p3 = fract(w * vec3f(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z) * 2.0 - 1.0;
}

fn valueNoise(q: vec2f) -> f32 {
    let i = floor(q);
    let f = q - i;
    let s = f * f * (3.0 - 2.0 * f);
    let a = mix(hash(i), hash(i + vec2f(1.0, 0.0)), s.x);
    let b = mix(hash(i + vec2f(0.0, 1.0)), hash(i + vec2f(1.0, 1.0)), s.x);
    return mix(a, b, s.y);
}

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    let p = pos.xy;
    var c = lin(p);
    let frameSize = u.frame.xy;
    // Position in the whole frame, for what's placed by it (guide maps, vignette, grain).
    let fp = p + u.frame.zw;
    let evv = u.tone1.x;

    // Masks: this pixel's local sliders, summed over the masks by how much each covers it.
    var lEv = 0.0;
    var lTemp = 0.0;
    var lTint = 0.0;
    var lContrast = 0.0;
    var lHighlights = 0.0;
    var lShadows = 0.0;
    var lClarity = 0.0;
    var lDehaze = 0.0;
    var lSat = 0.0;
    let maskCount = u.grade.w;
    if (maskCount > 0.0) {
        let uv = ev(coords, p * u.src.zw + 0.5).rg;
        let q = uv * u.src.xy;
        let subj = subjectAt(uv);
        for (var k = 0; k < 8; k++) {
            if (f32(k) < maskCount) {
                let o = k * 19;
                let kind = maskData(o);
                var w = 0.0;
                if (kind < 1.5) {
                    let a = vec2f(maskData(o + 1), maskData(o + 2));
                    let dd = vec2f(maskData(o + 3), maskData(o + 4)) - a;
                    w = 1.0 - smoothstep(0.0, 1.0, dot(q - a, dd) / max(0.000001, dot(dd, dd)));
                } else if (kind < 2.5) {
                    let off = q - vec2f(maskData(o + 1), maskData(o + 2));
                    let cs = maskData(o + 5);
                    let sn = maskData(o + 6);
                    let rx = (off.x * cs + off.y * sn) / maskData(o + 3);
                    let ry = (-off.x * sn + off.y * cs) / maskData(o + 4);
                    w = 1.0 - smoothstep(1.0 - maskData(o + 7), 1.0, sqrt(rx * rx + ry * ry));
                } else if (kind < 3.5) {
                    w = subj;
                } else {
                    w = skyAt(uv);
                }
                if (maskData(o + 8) > 0.5) { w = 1.0 - w; }
                if (maskData(o + 9) > 0.5) { w *= 1.0 - subj; }
                lEv += w * maskData(o + 10);
                lTemp += w * maskData(o + 11);
                lTint += w * maskData(o + 12);
                lContrast += w * maskData(o + 13);
                lHighlights += w * maskData(o + 14);
                lShadows += w * maskData(o + 15);
                lClarity += w * maskData(o + 16);
                lDehaze += w * maskData(o + 17);
                lSat += w * maskData(o + 18);
            }
        }
        if (lEv != 0.0 || lTemp != 0.0 || lTint != 0.0) {
            let k3 = vec3f(exp2(0.4 * lTemp), exp2(-0.3 * lTint), exp2(-0.4 * lTemp));
            c *= k3 * (exp2(lEv) / luma(k3));
        }
    }

    let hasGuide = u.flags.y;
    var base = 0.0;
    var med = 0.0;
    var hazeRatio = 0.0;
    if (hasGuide > 0.0) {
        let g = ev(guide, fp * u.pad_guide.zw).rgb;
        base = g.r * 40.0 - 20.0 + evv + lEv;
        med = g.g * 40.0 - 20.0 + evv + lEv;
        hazeRatio = g.b * 2.0;
    }

    // Dehaze: dark channel prior with the haze map from the guide.
    let dz = u.tone2.x + lDehaze;
    if (dz != 0.0 && hasGuide > 0.0) {
        let a = u.air.xyz * exp2(evv + lEv);
        if (dz > 0.0) {
            let t = max(0.15, 1.0 - dz * 0.9 * hazeRatio);
            c = max((c - a) / t + a, vec3f(0.0));
        } else {
            c += (a - c) * (-dz * 0.6);
        }
    }

    // Local tone: Shadows/Highlights and Clarity from the guide, Texture from nearby pixels.
    let l = logv(luma(c));
    var d = 0.0;
    if (hasGuide > 0.0) {
        let sh = u.tone1.y + lShadows;
        let hi = u.tone1.z + lHighlights;
        let cl = u.tone1.w + lClarity;
        if (sh != 0.0) { d += sh * 1.7 / (1.0 + exp((base - MID + 1.2) * 1.6)); }
        if (hi != 0.0) { d += hi * 1.7 / (1.0 + exp(-(base - MID - 0.6) * 1.6)); }
        if (cl != 0.0) {
            let z = (l - MID) / 3.5;
            d += cl * 0.7 * clamp(l - med, -2.0, 2.0) * exp(-z * z);
        }
    }
    let textureAmt = u.tone2.y;
    if (textureAmt != 0.0) {
        d += textureAmt * clamp(lumAt(p) - blurLum(p, u.tone2.z), -1.5, 1.5);
    }
    if (d != 0.0) { c *= exp2(d); }

    // Tone curve and point curves, into display space.
    c = vec3f(tone(c.r), tone(c.g), tone(c.b));
    if (u.flags.x > 0.0) { c = vec3f(curve(c.r, 0.0), curve(c.g, 1.0), curve(c.b, 2.0)); }
    if (lContrast != 0.0) {
        let v = clamp(c, vec3f(0.0), vec3f(1.0));
        c = v + lContrast * 0.6 * (0.5 - 0.5 * cos(3.14159265 * v) - v);
    }
    c = colorize(c);
    if (lSat != 0.0) {
        let ls = luma(c);
        c = ls + (c - ls) * (1.0 + lSat);
    }

    // A film look from a LUT, on the finished colour (display sRGB, as LUTs expect).
    let lutAmount = u.misc.w;
    if (lutAmount > 0.0) {
        let lutSize = u.subj.z;
        let v = clamp(c, vec3f(0.0), vec3f(1.0)) * (lutSize - 1.0);
        let z0 = min(floor(v.b), lutSize - 2.0);
        let xy = v.rg + 0.5;
        let a = ev(lut, vec2f(xy.x + z0 * lutSize, xy.y)).rgb;
        let b = ev(lut, vec2f(xy.x + (z0 + 1.0) * lutSize, xy.y)).rgb;
        c = mix(c, mix(a, b, v.b - z0), lutAmount);
    }

    // Vignette (post-crop).
    let vignette = u.vig.x;
    if (vignette != 0.0) {
        let vigRound = u.vig.w;
        var ax = 1.0;
        var ay = 1.0;
        if (vigRound >= 0.0) { ax = 1.0 + (frameSize.x / frameSize.y - 1.0) * vigRound; } else { ay = 1.0 - vigRound * 0.6; }
        let e0 = 0.3 + u.vig.y * 0.7;
        let e1 = e0 + 0.05 + u.vig.z * 0.9;
        let v = (fp.y / frameSize.y * 2.0 - 1.0) / ay;
        let uu = (fp.x / frameSize.x * 2.0 - 1.0) * ax / max(1.0, ax);
        let t = smoothstep(e0, e1, sqrt(uu * uu + v * v) / 1.4142 * 1.25);
        if (vignette < 0.0) {
            let lv = luma(c);
            c *= 1.0 + vignette * t * (1.0 - u.vig2.x * lv * lv);
        } else {
            c += (1.0 - c) * vignette * t;
        }
    }

    // Sharpening: unsharp mask on (approximate) display luminance.
    let sharpen = u.sharp.x;
    if (sharpen > 0.0) {
        let l0 = displayLum(p);
        var sd = clamp(l0 - blurDisplay(p, u.sharp.y), -u.sharp.z, u.sharp.z) * sharpen;
        let masking = u.sharp.w;
        if (masking > 0.0) {
            let grad = abs(displayLum(p + vec2f(1.0, 0.0)) - displayLum(p - vec2f(1.0, 0.0)))
                + abs(displayLum(p + vec2f(0.0, 1.0)) - displayLum(p - vec2f(0.0, 1.0)));
            sd *= smoothstep(masking * 0.5, masking * 1.5 + 0.0001, grad);
        }
        c += sd;
    }

    // Grain anchored to full-size pixel positions.
    let grain = u.vig2.y;
    if (grain > 0.0) {
        let q = fp / u.misc.x / u.vig2.z;
        let rough = u.vig2.w;
        let nz = valueNoise(q) * (1.0 - rough) + hash(floor(q * 3.0)) * rough;
        let lg = luma(c);
        c += nz * grain * 0.16 * (0.35 + 2.6 * lg * (1.0 - lg));
    }

    // The phone writes these display values as they are; here the sRGB target encodes on write,
    // so hand it the linear equivalent.
    return vec4f(decodeSrgb(clamp(c, vec3f(0.0), vec3f(1.0))), 1.0);
}
