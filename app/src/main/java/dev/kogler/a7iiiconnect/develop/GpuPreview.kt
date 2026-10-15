package dev.kogler.a7iiiconnect.develop

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.annotation.RequiresApi
import dev.kogler.a7iiiconnect.develop.engine.Guide
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.ShaderParams
import dev.kogler.a7iiiconnect.develop.engine.parallelRows
import java.nio.ShortBuffer

/**
 * The live preview on the GPU: the photo's geometry-corrected camera pixels go up once as a
 * 16-bit float texture, and an AGSL shader runs the per-pixel develop pipeline every frame, so
 * sliders are live. It mirrors [dev.kogler.a7iiiconnect.develop.engine.Look.render]; noise
 * reduction and defringe are left to the CPU render (sharp zoom and export).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GpuPreview(source: String = AGSL) {
    private val shader = compile(source)
    private val paint = Paint().apply { shader = this@GpuPreview.shader }

    var width = 0
        private set
    var height = 0
        private set

    /** All of one frame's inputs, built off the main thread by [GpuPreview.prepare]. */
    class Frame(
        val scene: Bitmap,
        val guide: Bitmap?,
        val air: FloatArray,
        val tone: Bitmap,
        val curves: Bitmap?,
        val params: ShaderParams,
    )

    /** Applies a prepared frame (main thread). */
    fun set(f: Frame) {
        width = f.scene.width
        height = f.scene.height
        val p = f.params
        shader.setInputShader("scene", linear(f.scene))
        shader.setInputShader("toneLut", linear(f.tone))
        shader.setInputShader("curves", linear(f.curves ?: f.tone))
        shader.setInputShader("guide", linear(f.guide ?: f.tone))
        shader.setFloatUniform("frameSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("guideScale", (f.guide?.width ?: 1).toFloat() / width, (f.guide?.height ?: 1).toFloat() / height)
        val m = p.matrix
        shader.setFloatUniform("row0", m[0], m[1], m[2])
        shader.setFloatUniform("row1", m[3], m[4], m[5])
        shader.setFloatUniform("row2", m[6], m[7], m[8])
        shader.setFloatUniform("useCurves", if (f.curves != null) 1f else 0f)
        shader.setFloatUniform("hasGuide", if (f.guide != null) 1f else 0f)
        shader.setFloatUniform("ev", p.ev)
        shader.setFloatUniform("air", f.air[0], f.air[1], f.air[2])
        shader.setFloatUniform("shadows", p.shadows)
        shader.setFloatUniform("highlights", p.highlights)
        shader.setFloatUniform("clarity", p.clarity)
        shader.setFloatUniform("dehaze", p.dehaze)
        shader.setFloatUniform("textureAmt", p.texture)
        shader.setFloatUniform("textureRadius", p.textureRadius)
        shader.setFloatUniform("mono", if (p.mono) 1f else 0f)
        shader.setFloatUniform("sat", p.sat)
        shader.setFloatUniform("vib", p.vib)
        shader.setFloatUniform("hasMixer", if (p.hasMixer) 1f else 0f)
        shader.setFloatUniform("mixHue", p.mixHue)
        shader.setFloatUniform("mixSat", p.mixSat)
        shader.setFloatUniform("mixLum", p.mixLum)
        val c = p.bandCenters
        shader.setFloatUniform("bandC", c)
        shader.setFloatUniform("bandPrev", FloatArray(8) { (c[it] - c[(it + 7) % 8] + 360f) % 360f })
        shader.setFloatUniform("bandNext", FloatArray(8) { (c[(it + 1) % 8] - c[it] + 360f) % 360f })
        shader.setFloatUniform("hasGrading", if (p.hasGrading) 1f else 0f)
        shader.setFloatUniform("gradeOff", p.gradeOffset)
        shader.setFloatUniform("gradeLum", p.gradeLum)
        shader.setFloatUniform("blending", p.blending)
        shader.setFloatUniform("balance", p.balance)
        shader.setFloatUniform("vignette", p.vignette)
        shader.setFloatUniform("vigMid", p.vigMid)
        shader.setFloatUniform("vigFeather", p.vigFeather)
        shader.setFloatUniform("vigRound", p.vigRound)
        shader.setFloatUniform("vigHighlights", p.vigHighlights)
        shader.setFloatUniform("sharpen", p.sharpen)
        shader.setFloatUniform("sharpenRadius", p.sharpenRadius)
        shader.setFloatUniform("sharpenLimit", p.sharpenLimit)
        shader.setFloatUniform("sharpenMasking", p.sharpenMasking)
        shader.setFloatUniform("grain", p.grain)
        shader.setFloatUniform("grainCell", p.grainCell)
        shader.setFloatUniform("grainRough", p.grainRough)
        shader.setFloatUniform("scale", p.scale)
    }

    /** Draws the developed photo into [canvas] at (left, top), [w] x [h] on screen. */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        if (width == 0) return
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(w / width, h / height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restore()
    }

    companion object {
        /**
         * Child shaders are sampled with `s.eval(p)` on Android 13+; older Skia builds (such as
         * the one Robolectric ships) only know `sample(s, p)`, so fall back to that spelling.
         */
        private fun compile(source: String): RuntimeShader {
            val call = Regex("""EVAL\((\w+), """)
            return try {
                RuntimeShader(call.replace(source) { "${it.groupValues[1]}.eval(" })
            } catch (e: IllegalArgumentException) {
                RuntimeShader(call.replace(source) { "sample(${it.groupValues[1]}, " })
            }
        }

        /** Same transfer function as the window, so Skia passes our values through untouched. */
        private val SPACE: ColorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)

        private fun linear(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

        /** Builds a frame's textures (any thread). [scene] holds camera RGB, as-shot WB. */
        fun prepare(scene: Bitmap, guide: Guide?, guideBitmap: Bitmap?, params: ShaderParams): Frame {
            val tone = floatBitmap(params.tone.size, 1) { _, x, c -> if (c == 3) 1f else params.tone[x] }
            val curves = params.curves?.let { cv -> floatBitmap(cv[0].size, 3) { y, x, c -> if (c == 3) 1f else cv[y][x] } }
            return Frame(scene, guideBitmap, guide?.air ?: FloatArray(3), tone, curves, params)
        }

        /** The geometry-corrected camera pixels as a float texture. */
        fun sceneBitmap(img: LinearImage): Bitmap = floatBitmap(img.width, img.height) { y, x, c ->
            val i = y * img.width + x
            when (c) { 0 -> img.r[i]; 1 -> img.g[i]; 2 -> img.b[i]; else -> 1f }
        }

        /**
         * The guide's maps in one texture: base and medium log-luminance (stored as (v + 20) / 40,
         * to stay positive) and the haze map relative to the airlight.
         */
        fun guideBitmap(g: Guide): Bitmap {
            val amax = maxOf(g.air[0], maxOf(g.air[1], g.air[2]))
            return floatBitmap(g.width, g.height) { y, x, c ->
                val i = y * g.width + x
                when (c) {
                    0 -> (g.base[i] + 20f) / 40f
                    1 -> (g.medium[i] + 20f) / 40f
                    2 -> g.dark[i] / amax / 2f
                    else -> 1f
                }
            }
        }

        private fun floatBitmap(w: Int, h: Int, value: (y: Int, x: Int, c: Int) -> Float): Bitmap {
            val data = ShortArray(w * h * 4)
            parallelRows(h) { y ->
                var o = y * w * 4
                for (x in 0 until w) for (c in 0..3) data[o++] = Half.toHalf(value(y, x, c))
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, SPACE)
            bmp.copyPixelsFromBuffer(ShortBuffer.wrap(data))
            return bmp
        }

        internal const val AGSL = """
uniform shader scene;
uniform shader toneLut;
uniform shader curves;
uniform shader guide;
uniform float2 frameSize;
uniform float2 guideScale;
uniform float3 row0;
uniform float3 row1;
uniform float3 row2;
uniform float useCurves;
uniform float hasGuide;
uniform float ev;
uniform float3 air;
uniform float shadows;
uniform float highlights;
uniform float clarity;
uniform float dehaze;
uniform float textureAmt;
uniform float textureRadius;
uniform float mono;
uniform float sat;
uniform float vib;
uniform float hasMixer;
uniform float mixHue[8];
uniform float mixSat[8];
uniform float mixLum[8];
uniform float bandC[8];
uniform float bandPrev[8];
uniform float bandNext[8];
uniform float hasGrading;
uniform float gradeOff[12];
uniform float gradeLum[4];
uniform float blending;
uniform float balance;
uniform float vignette;
uniform float vigMid;
uniform float vigFeather;
uniform float vigRound;
uniform float vigHighlights;
uniform float sharpen;
uniform float sharpenRadius;
uniform float sharpenLimit;
uniform float sharpenMasking;
uniform float grain;
uniform float grainCell;
uniform float grainRough;
uniform float scale;

const float LUT_SIZE = 4096.0;
const float LUT_MAX = 64.0;
const float MID = -3.0588937;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
float logv(float v) { return log2(max(v, 0.00001)); }

float3 lin(float2 p) {
    float3 c = EVAL(scene, p).rgb;
    return max(float3(dot(row0, c), dot(row1, c), dot(row2, c)), float3(0.0));
}

float tone(float x) {
    if (x <= 0.0) return 0.0;
    float t = min(sqrt(x / LUT_MAX) * LUT_SIZE, LUT_SIZE);
    return EVAL(toneLut, float2(t + 0.5, 0.5)).r;
}

float curve(float v, float row) {
    return EVAL(curves, float2(clamp(v, 0.0, 1.0) * 1023.0 + 0.5, row + 0.5)).r;
}

float hueOf(float3 c, float mx, float ch) {
    float h;
    if (mx == c.r) {
        h = (c.g - c.b) / ch;
        if (h < 0.0) h += 6.0;
    } else if (mx == c.g) {
        h = (c.b - c.r) / ch + 2.0;
    } else {
        h = (c.r - c.g) / ch + 4.0;
    }
    return h * 60.0;
}

float3 hsv(float h, float s, float v) {
    float3 p = abs(fract(float3(h / 360.0) + float3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return v * mix(float3(1.0), clamp(p - 1.0, 0.0, 1.0), s);
}

float3 grade(float3 c, float l) {
    if (hasGrading == 0.0) return c;
    float pivot = clamp(0.5 - balance * 0.3, 0.15, 0.85);
    float width = 0.15 + blending * 0.6;
    float ws = 1.0 - smoothstep(pivot - width, pivot, l);
    float wh = smoothstep(pivot, pivot + width, l);
    float wm = max(0.0, 1.0 - ws - wh);
    c += (float3(gradeOff[0], gradeOff[1], gradeOff[2]) + gradeLum[0] * 0.2) * ws;
    c += (float3(gradeOff[3], gradeOff[4], gradeOff[5]) + gradeLum[1] * 0.2) * wm;
    c += (float3(gradeOff[6], gradeOff[7], gradeOff[8]) + gradeLum[2] * 0.2) * wh;
    c += float3(gradeOff[9], gradeOff[10], gradeOff[11]) + gradeLum[3] * 0.2;
    return c;
}

float3 colorize(float3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float ch = mx - mn;
    if ((hasMixer > 0.0 || mono > 0.0) && ch > 0.0001) {
        float hue = hueOf(c, mx, ch);
        float dh = 0.0;
        float ds = 0.0;
        float dl = 0.0;
        for (int k = 0; k < 8; k++) {
            float d = mod(hue - bandC[k] + 540.0, 360.0) - 180.0;
            float w = d >= 0.0 ? max(0.0, 1.0 - d / bandNext[k]) : max(0.0, 1.0 + d / bandPrev[k]);
            dh += w * mixHue[k];
            ds += w * mixSat[k];
            dl += w * mixLum[k];
        }
        float l = luma(c);
        float satNow = min(ch / max(0.0001, 1.0 - abs(mx + mn - 1.0)), 1.0);
        if (mono > 0.0) {
            float grey = clamp(l + dl * 0.35 * satNow, 0.0, 1.0);
            return grade(float3(grey), grey);
        }
        if (dh != 0.0) c = hsv(mod(hue + dh * 30.0 + 360.0, 360.0), ch / max(mx, 0.0001), mx);
        if (ds != 0.0) {
            float ll = luma(c);
            c = ll + (c - ll) * (1.0 + ds);
        }
        if (dl != 0.0) c += dl * 0.3 * satNow;
    } else if (mono > 0.0) {
        float g = luma(c);
        return grade(float3(g), g);
    }
    if (sat != 1.0 || vib != 0.0) {
        float l = luma(c);
        float cc = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
        float f = sat * (1.0 + vib * (1.0 - cc) * (1.0 - cc));
        c = l + (c - l) * f;
    }
    return grade(c, luma(c));
}

float lumAt(float2 p) { return logv(luma(lin(p))); }
float displayLum(float2 p) { return tone(luma(lin(p))); }

float hash(float2 q) { return fract(sin(dot(q, float2(127.1, 311.7))) * 43758.5453) * 2.0 - 1.0; }

float valueNoise(float2 q) {
    float2 i = floor(q);
    float2 f = q - i;
    float2 s = f * f * (3.0 - 2.0 * f);
    float a = mix(hash(i), hash(i + float2(1.0, 0.0)), s.x);
    float b = mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), s.x);
    return mix(a, b, s.y);
}

half4 main(float2 p) {
    float3 c = lin(p);

    float base = 0.0;
    float med = 0.0;
    float hazeRatio = 0.0;
    if (hasGuide > 0.0) {
        float3 g = EVAL(guide, p * guideScale).rgb;
        base = g.r * 40.0 - 20.0 + ev;
        med = g.g * 40.0 - 20.0 + ev;
        hazeRatio = g.b * 2.0;
    }

    // Dehaze: dark channel prior with the haze map from the guide.
    if (dehaze != 0.0 && hasGuide > 0.0) {
        float3 a = air * exp2(ev);
        if (dehaze > 0.0) {
            float t = max(0.15, 1.0 - dehaze * 0.9 * hazeRatio);
            c = max((c - a) / t + a, float3(0.0));
        } else {
            c += (a - c) * (-dehaze * 0.6);
        }
    }

    // Local tone: Shadows/Highlights and Clarity from the guide, Texture from nearby pixels.
    float l = logv(luma(c));
    float d = 0.0;
    if (hasGuide > 0.0) {
        if (shadows != 0.0) d += shadows * 1.7 / (1.0 + exp((base - MID + 1.2) * 1.6));
        if (highlights != 0.0) d += highlights * 1.7 / (1.0 + exp(-(base - MID - 0.6) * 1.6));
        if (clarity != 0.0) {
            float z = (l - MID) / 3.5;
            d += clarity * 0.7 * clamp(l - med, -2.0, 2.0) * exp(-z * z);
        }
    }
    if (textureAmt != 0.0) {
        float r = textureRadius * 1.4;
        float s = 4.0 * l
            + 2.0 * (lumAt(p + float2(r, 0.0)) + lumAt(p - float2(r, 0.0)) + lumAt(p + float2(0.0, r)) + lumAt(p - float2(0.0, r)))
            + lumAt(p + float2(r, r)) + lumAt(p - float2(r, r)) + lumAt(p + float2(r, -r)) + lumAt(p - float2(r, -r));
        d += textureAmt * clamp(l - s / 16.0, -1.5, 1.5);
    }
    if (d != 0.0) c *= exp2(d);

    // Tone curve and point curves, into display space.
    c = float3(tone(c.r), tone(c.g), tone(c.b));
    if (useCurves > 0.0) c = float3(curve(c.r, 0.0), curve(c.g, 1.0), curve(c.b, 2.0));
    c = colorize(c);

    // Vignette (post-crop).
    if (vignette != 0.0) {
        float ax = vigRound >= 0.0 ? 1.0 + (frameSize.x / frameSize.y - 1.0) * vigRound : 1.0;
        float ay = vigRound >= 0.0 ? 1.0 : 1.0 - vigRound * 0.6;
        float e0 = 0.3 + vigMid * 0.7;
        float e1 = e0 + 0.05 + vigFeather * 0.9;
        float v = (p.y / frameSize.y * 2.0 - 1.0) / ay;
        float u = (p.x / frameSize.x * 2.0 - 1.0) * ax / max(1.0, ax);
        float t = smoothstep(e0, e1, sqrt(u * u + v * v) / 1.4142 * 1.25);
        if (vignette < 0.0) {
            float lv = luma(c);
            c *= 1.0 + vignette * t * (1.0 - vigHighlights * lv * lv);
        } else {
            c += (1.0 - c) * vignette * t;
        }
    }

    // Sharpening: unsharp mask on (approximate) display luminance.
    if (sharpen > 0.0) {
        float r = sharpenRadius * 1.4;
        float l0 = displayLum(p);
        float b = 4.0 * l0
            + 2.0 * (displayLum(p + float2(r, 0.0)) + displayLum(p - float2(r, 0.0)) + displayLum(p + float2(0.0, r)) + displayLum(p - float2(0.0, r)))
            + displayLum(p + float2(r, r)) + displayLum(p - float2(r, r)) + displayLum(p + float2(r, -r)) + displayLum(p - float2(r, -r));
        float sd = clamp(l0 - b / 16.0, -sharpenLimit, sharpenLimit) * sharpen;
        if (sharpenMasking > 0.0) {
            float grad = abs(displayLum(p + float2(1.0, 0.0)) - displayLum(p - float2(1.0, 0.0)))
                + abs(displayLum(p + float2(0.0, 1.0)) - displayLum(p - float2(0.0, 1.0)));
            sd *= smoothstep(sharpenMasking * 0.5, sharpenMasking * 1.5 + 0.0001, grad);
        }
        c += sd;
    }

    // Grain anchored to full-size pixel positions.
    if (grain > 0.0) {
        float2 q = p / scale / grainCell;
        float nz = valueNoise(q) * (1.0 - grainRough) + hash(floor(q * 3.0)) * grainRough;
        float lg = luma(c);
        c += nz * grain * 0.16 * (0.35 + 2.6 * lg * (1.0 - lg));
    }

    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""
    }
}
