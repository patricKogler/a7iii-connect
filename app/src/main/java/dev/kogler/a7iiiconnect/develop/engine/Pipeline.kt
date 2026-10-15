package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Supplies scene pixels (camera RGB, as-shot white balance baked in) for output rows, with
 * geometry already applied. Output is [width] x [height] at this render's resolution.
 */
interface Sampler {
    val width: Int
    val height: Int
    /** Output pixels per full-size source pixel (1 for a full-size export). */
    val scale: Double
    /** Fills [rows] output rows starting at [y0] (rows outside the image are clamped). */
    fun fill(y0: Int, rows: Int, out: LinearImage)

    /**
     * Where this render sits in the whole (cropped) photo at the same scale: a zoomed-in tile
     * covers part of the frame. Vignette, grain and the guide maps are positioned by the frame.
     */
    val originX: Int get() = 0
    val originY: Int get() = 0
    val frameWidth: Int get() = width
    val frameHeight: Int get() = height
}

/** A part of the output frame rendered at [scale] (output pixels per full-size pixel). */
class Region(val scale: Double, val x0: Int, val y0: Int, val width: Int, val height: Int)

/** Samples an in-memory upright preview through a [Geometry]. */
class ImageSampler(
    private val src: LinearImage,
    private val geom: Geometry,
    /** Longest output edge, or the natural size when null. */
    maxEdge: Int? = null,
    region: Region? = null,
) : Sampler {
    private val natural = src.width / geom.srcW
    override val scale: Double = region?.scale ?: if (maxEdge == null) natural else minOf(natural, maxEdge / maxOf(geom.outW, geom.outH))
    override val frameWidth = maxOf(1, (geom.outW * scale).toInt())
    override val frameHeight = maxOf(1, (geom.outH * scale).toInt())
    override val width = region?.width ?: frameWidth
    override val height = region?.height ?: frameHeight
    override val originX = region?.x0 ?: 0
    override val originY = region?.y0 ?: 0
    private val direct = region == null && geom.isIdentity && width == src.width && height == src.height

    override fun fill(y0: Int, rows: Int, out: LinearImage) {
        val toSrc = src.width / geom.srcW
        val vig = geom.hasVignetteCorrection
        parallelRows(rows) { i ->
            val y = (originY + y0 + i).coerceIn(0, frameHeight - 1)
            val o = i * width
            if (direct) {
                System.arraycopy(src.r, y * width, out.r, o, width)
                System.arraycopy(src.g, y * width, out.g, o, width)
                System.arraycopy(src.b, y * width, out.b, o, width)
                return@parallelRows
            }
            val m = DoubleArray(6)
            val oy = (y + 0.5) / scale
            for (x in 0 until width) {
                geom.map((originX + x + 0.5) / scale, oy, m)
                out.r[o + x] = sample(src.r, m[0] * toSrc - 0.5, m[1] * toSrc - 0.5)
                out.g[o + x] = sample(src.g, m[2] * toSrc - 0.5, m[3] * toSrc - 0.5)
                out.b[o + x] = sample(src.b, m[4] * toSrc - 0.5, m[5] * toSrc - 0.5)
                if (vig) {
                    val g = geom.vignetteGain(m[2], m[3])
                    out.r[o + x] *= g; out.g[o + x] *= g; out.b[o + x] *= g
                }
            }
        }
    }

    private fun sample(c: FloatArray, x: Double, y: Double): Float {
        val w = src.width; val h = src.height
        if (x < -1 || y < -1 || x > w || y > h) return 0f
        val xc = x.coerceIn(0.0, w - 1.0); val yc = y.coerceIn(0.0, h - 1.0)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val fx = (xc - x0).toFloat(); val fy = (yc - y0).toFloat()
        val a = c[y0 * w + x0] + (c[y0 * w + x1] - c[y0 * w + x0]) * fx
        val b = c[y1 * w + x0] + (c[y1 * w + x1] - c[y1 * w + x0]) * fx
        return a + (b - a) * fy
    }
}

/**
 * Low-resolution maps for the large-radius adjustments (Shadows/Highlights base layer, Clarity,
 * Dehaze). They come from the preview at a fixed small size for both preview and export, so the
 * exported photo looks like what was on screen.
 */
class Guide(val width: Int, val height: Int, val base: FloatArray, val medium: FloatArray, val dark: FloatArray, val air: FloatArray) {

    fun at(map: FloatArray, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, width - 1f); val yc = y.coerceIn(0f, height - 1f)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = minOf(width - 1, x0 + 1); val y1 = minOf(height - 1, y0 + 1)
        val fx = xc - x0; val fy = yc - y0
        val a = map[y0 * width + x0] + (map[y0 * width + x1] - map[y0 * width + x0]) * fx
        val b = map[y1 * width + x0] + (map[y1 * width + x1] - map[y1 * width + x0]) * fx
        return a + (b - a) * fy
    }

    companion object {
        const val EDGE = 512

        fun build(look: Look, sampler: Sampler): Guide {
            val w = sampler.width; val h = sampler.height
            val img = LinearImage(w, h)
            sampler.fill(0, h, img)
            look.linear(img, 0, h)
            val n = w * h
            val lum = FloatArray(n)
            val dark = FloatArray(n)
            for (i in 0 until n) {
                lum[i] = log2(luma(img.r[i], img.g[i], img.b[i]))
                dark[i] = minOf(img.r[i], minOf(img.g[i], img.b[i]))
            }
            val diag = hypot(w.toDouble(), h.toDouble())
            val base = FloatArray(n)
            Filters.guided(lum, base, w, h, maxOf(2, (diag * 0.035).toInt()), 0.35f)
            val medium = FloatArray(n)
            Filters.guided(lum, medium, w, h, maxOf(2, (diag * 0.012).toInt()), 0.04f)
            // Airlight: the colour of the haziest (brightest dark-channel) pixels.
            val order = dark.indices.sortedByDescending { dark[it] }.take(maxOf(1, n / 500))
            val air = FloatArray(3)
            for (i in order) { air[0] += img.r[i]; air[1] += img.g[i]; air[2] += img.b[i] }
            for (c in 0..2) air[c] = maxOf(0.05f, air[c] / order.size)
            Filters.blur(dark, dark, w, h, (diag * 0.012).toFloat())
            return Guide(w, h, base, medium, dark, air)
        }
    }
}

private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
private const val LN2 = 0.6931472f
private fun log2(v: Float) = ln(maxOf(v, 1e-5f)) / LN2
private fun exp2(v: Float) = exp(v * LN2)
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * Everything a [Settings] needs at render time, precomputed: colour matrix, tone tables,
 * mixer/grading coefficients. [render] runs the whole develop pipeline on a band of rows.
 */
class Look(private val source: DevelopSource, private val s: Settings) {

    private val matrix = FloatArray(9)
    private val tone = FloatArray(LUT_SIZE + 1)
    private val rgbCurves: Array<FloatArray>? = if (s.curves.drop(1).all { it.isIdentity }) null else Array(3) { s.curves[it + 1].table(1024) }
    private val raw = source.isRaw
    private val profile = s.profile
    private val mono = profile == Profile.MONOCHROME
    private val sat = (if (raw || mono) profile.saturation else 1f) * (1f + s[P.SATURATION] / 100f)
    private val vib = s[P.VIBRANCE] / 100f
    private val mixHue = FloatArray(8) { s[P.MIX_HUE[it]] / 100f }
    private val mixSat = FloatArray(8) { s[P.MIX_SAT[it]] / 100f }
    private val mixLum = FloatArray(8) { s[P.MIX_LUM[it]] / 100f }
    private val hasMixer = (mixHue + mixSat + mixLum).any { it != 0f }
    private val bandCenters = FloatArray(8) { P.MIX_BANDS[it].second }
    private val gradeOffset = Array(4) { z -> tintOffset(s[P.GRADE_HUE[z]], s[P.GRADE_SAT[z]] / 100f) }
    private val gradeLum = FloatArray(4) { s[P.GRADE_LUM[it]] / 100f }
    private val hasGrading = gradeOffset.any { o -> o.any { it != 0f } } || gradeLum.any { it != 0f }
    private val blending = s[P.GRADE_BLENDING] / 100f
    private val balance = s[P.GRADE_BALANCE] / 100f

    private val shadows = s[P.SHADOWS] / 100f
    private val highlights = s[P.HIGHLIGHTS] / 100f
    private val clarity = s[P.CLARITY] / 100f
    private val texture = s[P.TEXTURE] / 100f
    private val dehaze = s[P.DEHAZE] / 100f
    private val needsGuide = shadows != 0f || highlights != 0f || clarity != 0f || dehaze != 0f

    private val vignette = s[P.VIGNETTE] / 100f
    private val vigMid = s[P.VIGNETTE_MIDPOINT] / 100f
    private val vigFeather = s[P.VIGNETTE_FEATHER] / 100f
    private val vigRound = s[P.VIGNETTE_ROUNDNESS] / 100f
    private val vigHighlights = s[P.VIGNETTE_HIGHLIGHTS] / 100f
    private val grain = s[P.GRAIN] / 100f
    private val grainSize = s[P.GRAIN_SIZE] / 100f
    private val grainRough = s[P.GRAIN_ROUGHNESS] / 100f
    private val sharpen = s[P.SHARPEN] / 100f + if (raw) 0.25f else 0f
    private val sharpenRadius = s[P.SHARPEN_RADIUS]
    private val sharpenDetail = s[P.SHARPEN_DETAIL] / 100f
    private val sharpenMasking = s[P.SHARPEN_MASKING] / 100f
    private val noise = s[P.NOISE] / 100f
    private val noiseDetail = s[P.NOISE_DETAIL] / 100f
    private val colorNoise = s[P.COLOR_NOISE] / 100f
    private val defringe = s[P.DEFRINGE] / 100f

    init {
        val color = source.color
        val (k0, t0) = source.asShot
        val kelvin = s.temperature?.toDouble() ?: k0
        val tint = s.tint?.toDouble() ?: t0
        val target = color.multipliers(kelvin, tint)
        val ref = color.multipliers(k0, t0)
        val rel = DoubleArray(3) { target[it] / ref[it] }
        // Keep brightness steady while the balance moves.
        val norm = 0.30 * rel[0] + 0.59 * rel[1] + 0.11 * rel[2]
        val gain = 2.0.pow(s[P.EXPOSURE].toDouble())
        val m = Mat3.mul(color.rgbFromCam, Mat3.diag(rel[0] / norm, rel[1] / norm, rel[2] / norm))
        for (i in 0..8) matrix[i] = (m[i] * gain).toFloat()
        buildTone()
    }

    /** Hue (degrees) and strength -> an RGB offset with no luminance, for color grading. */
    private fun tintOffset(hue: Float, amount: Float): FloatArray {
        if (amount == 0f) return FloatArray(3)
        val rgb = hsvToRgb(hue, 1f, 1f)
        val l = luma(rgb[0], rgb[1], rgb[2])
        return FloatArray(3) { (rgb[it] - l) * amount * 0.3f }
    }

    /**
     * Scene-linear -> display value 0..1 (sRGB encoded): the profile's filmic sigmoid (camera mid
     * grey to display mid grey, clip point to white), Whites/Blacks/Contrast, the parametric
     * curve regions, then the master point curve.
     */
    private fun buildTone() {
        val white = 2.0.pow(-s[P.WHITES] / 100.0 * 1.2)
        val blackPoint = -s[P.BLACKS] / 100.0 * 0.04
        val n = profile.contrast
        val mn = SCENE_GREY.pow(n)
        val cn = white.pow(n)
        val k = mn * cn * (DISPLAY_GREY - 1) / (mn - DISPLAY_GREY * cn)
        val a = (cn + k) / cn
        val contrast = s[P.CONTRAST] / 100.0 * 0.6
        val regions = doubleArrayOf(s[P.CURVE_SHADOWS] / 100.0, s[P.CURVE_DARKS] / 100.0, s[P.CURVE_LIGHTS] / 100.0, s[P.CURVE_HIGHLIGHTS] / 100.0)
        val master = if (s.curves[0].isIdentity) null else s.curves[0].table(1024)
        var prev = 0.0
        for (i in 0..LUT_SIZE) {
            val t = i.toDouble() / LUT_SIZE
            val x = t * t * LUT_MAX
            var y = if (raw) {
                val xn = x.pow(n)
                a * xn / (xn + k)
            } else x / white
            y = (y - blackPoint) / (1 - blackPoint)
            var v = encode(y.coerceIn(0.0, 1.0))
            v += contrast * (0.5 - 0.5 * cos(PI * v) - v)
            for (r in 0..3) {
                if (regions[r] == 0.0) continue
                val c = 0.125 + 0.25 * r
                val d = (v - c) / 0.25
                if (abs(d) < 1) v += regions[r] * 0.1 * 0.5 * (1 + cos(PI * d))
            }
            v = v.coerceIn(0.0, 1.0)
            if (master != null) v = lerpTable(master, v.toFloat()).toDouble()
            if (v < prev) v = prev
            prev = v
            tone[i] = v.toFloat()
        }
    }

    /** White balance, colour matrix and exposure: camera RGB -> linear sRGB (in place). */
    fun linear(img: LinearImage, from: Int, rows: Int) {
        val m = matrix
        val w = img.width
        parallelRows(rows) { i ->
            val o = (from + i) * w
            for (x in o until o + w) {
                val r = img.r[x]; val g = img.g[x]; val b = img.b[x]
                img.r[x] = maxOf(0f, m[0] * r + m[1] * g + m[2] * b)
                img.g[x] = maxOf(0f, m[3] * r + m[4] * g + m[5] * b)
                img.b[x] = maxOf(0f, m[6] * r + m[7] * g + m[8] * b)
            }
        }
    }

    /** Builds the guide this look needs, or null when no large-radius adjustment is active. */
    /**
     * The guide is built at exposure 0, so moving Exposure doesn't rebuild it: in log space the
     * base layers just shift by the exposure, and the haze map scales with it.
     */
    fun guide(preview: LinearImage, geom: Geometry): Guide? =
        if (!needsGuide) null else Guide.build(Look(source, s.with(P.EXPOSURE, 0f)), ImageSampler(preview, geom, Guide.EDGE))

    private val ev = s[P.EXPOSURE]

    /** How far (in output pixels) the small-radius filters reach: the band overlap. */
    fun margin(scale: Double): Int {
        var r = 0.0
        if (noise > 0) r = maxOf(r, noiseRadius(scale) * 2.0)
        if (colorNoise > 0) r = maxOf(r, colorNoiseRadius(scale) * 3.0)
        if (texture != 0f) r = maxOf(r, textureRadius(scale) * 3.0)
        if (sharpen > 0) r = maxOf(r, sharpenRadius(scale) * 3.0)
        if (defringe > 0) r = maxOf(r, 2.0)
        return ceil(r).toInt() + 2
    }

    private fun noiseRadius(scale: Double) = maxOf(1, (scale * (1 + noise * 3)).toInt())
    private fun colorNoiseRadius(scale: Double) = maxOf(1f, (scale * (2 + colorNoise * 10)).toFloat())
    private fun textureRadius(scale: Double) = maxOf(0.7f, (scale * 3).toFloat())
    private fun sharpenRadius(scale: Double) = maxOf(0.6f, (sharpenRadius * scale).toFloat())

    /**
     * Renders output rows [y0, y1) of [sampler] into [out] (ARGB, [y1]-[y0] rows of sampler.width).
     * [guide] must come from [guide] for the same settings when any large-radius tool is on.
     */
    fun render(sampler: Sampler, guide: Guide?, out: IntArray, y0: Int, y1: Int) {
        val w = sampler.width
        val H = sampler.height
        val scale = sampler.scale
        val m = margin(scale)
        val top = maxOf(0, y0 - m)
        val bottom = minOf(H, y1 + m)
        val rows = bottom - top
        val img = LinearImage(w, rows)
        sampler.fill(top, rows, img)
        linear(img, 0, rows)
        val n = w * rows
        val r = img.r; val g = img.g; val b = img.b

        // --- Noise reduction (linear) ---
        if (colorNoise > 0) {
            val cr = FloatArray(n); val cb = FloatArray(n)
            for (i in 0 until n) { val l = luma(r[i], g[i], b[i]); cr[i] = r[i] - l; cb[i] = b[i] - l }
            val rad = colorNoiseRadius(scale)
            Filters.blur(cr, cr, w, rows, rad); Filters.blur(cb, cb, w, rows, rad)
            for (i in 0 until n) {
                val l = luma(r[i], g[i], b[i])
                val nr = maxOf(0f, l + cr[i]); val nb = maxOf(0f, l + cb[i])
                r[i] = nr; b[i] = nb
                g[i] = maxOf(0f, (l - 0.2126f * nr - 0.0722f * nb) / 0.7152f)
            }
        }
        if (noise > 0) {
            val p = FloatArray(n)
            for (i in 0 until n) p[i] = sqrt(luma(r[i], g[i], b[i]))
            val q = FloatArray(n)
            val eps = (noise * noise * 0.004f * (1.6f - noiseDetail)).coerceAtLeast(1e-6f)
            Filters.guided(p, q, w, rows, noiseRadius(scale), eps)
            for (i in 0 until n) {
                val before = p[i] * p[i]
                if (before > 1e-6f) {
                    val f = q[i] * q[i] / before
                    r[i] *= f; g[i] *= f; b[i] *= f
                }
            }
        }

        val gx = if (guide != null) guide.width.toFloat() / sampler.frameWidth else 0f
        val gy = if (guide != null) guide.height.toFloat() / sampler.frameHeight else 0f
        val ox = sampler.originX
        val oy = top + sampler.originY

        // --- Dehaze (dark channel prior, with the haze map from the guide) ---
        if (dehaze != 0f && guide != null) {
            val gain = exp2(ev)
            val air = FloatArray(3) { guide.air[it] * gain }
            val amax = maxOf(guide.air[0], maxOf(guide.air[1], guide.air[2]))
            parallelRows(rows) { j ->
                val yy = (oy + j + 0.5f) * gy - 0.5f
                for (x in 0 until w) {
                    val i = j * w + x
                    val d = guide.at(guide.dark, (ox + x + 0.5f) * gx - 0.5f, yy)
                    if (dehaze > 0) {
                        val t = maxOf(0.15f, 1 - dehaze * 0.9f * d / amax)
                        r[i] = maxOf(0f, (r[i] - air[0]) / t + air[0])
                        g[i] = maxOf(0f, (g[i] - air[1]) / t + air[1])
                        b[i] = maxOf(0f, (b[i] - air[2]) / t + air[2])
                    } else {
                        val k = -dehaze * 0.6f
                        r[i] += (air[0] - r[i]) * k; g[i] += (air[1] - g[i]) * k; b[i] += (air[2] - b[i]) * k
                    }
                }
            }
        }

        // --- Local tone: Shadows/Highlights, Clarity (guide), Texture (band) ---
        if (needsGuide && guide != null && (shadows != 0f || highlights != 0f || clarity != 0f) || texture != 0f) {
            val lum = FloatArray(n)
            for (i in 0 until n) lum[i] = log2(luma(r[i], g[i], b[i]))
            var small: FloatArray? = null
            if (texture != 0f) {
                small = FloatArray(n)
                Filters.blur(lum, small, w, rows, textureRadius(scale))
            }
            val mid = log2(SCENE_GREY.toFloat())
            val texAmt = texture * 0.9f * minOf(1f, (scale * 2.5).toFloat().coerceAtLeast(0.4f))
            parallelRows(rows) { j ->
                val yy = (oy + j + 0.5f) * gy - 0.5f
                for (x in 0 until w) {
                    val i = j * w + x
                    val l = lum[i]
                    var d = 0f
                    if (guide != null && needsGuide) {
                        val gxx = (ox + x + 0.5f) * gx - 0.5f
                        val base = guide.at(guide.base, gxx, yy) + ev
                        if (shadows != 0f) d += shadows * 1.7f / (1 + exp((base - mid + 1.2f) * 1.6f))
                        if (highlights != 0f) d += highlights * 1.7f / (1 + exp(-(base - mid - 0.6f) * 1.6f))
                        if (clarity != 0f) {
                            val med = guide.at(guide.medium, gxx, yy) + ev
                            // Midtone weighting keeps clarity off deep shadows and bright highlights.
                            val wMid = exp(-((l - mid) / 3.5f).let { it * it })
                            d += clarity * 0.7f * (l - med).coerceIn(-2f, 2f) * wMid
                        }
                    }
                    if (small != null) d += texAmt * (l - small[i]).coerceIn(-1.5f, 1.5f)
                    if (d != 0f) {
                        val f = exp2(d)
                        r[i] *= f; g[i] *= f; b[i] *= f
                    }
                }
            }
        }

        // --- Tone, colour, effects in display space ---
        val outRows = y1 - y0
        val off = y0 - top
        val pr = r; val pg = g; val pb = b
        parallelRows(rows) { j ->
            val tmp = FloatArray(3)
            for (x in 0 until w) {
                val i = j * w + x
                var rr = lookup(pr[i]); var gg = lookup(pg[i]); var bb = lookup(pb[i])
                rgbCurves?.let { c ->
                    rr = lerpTable(c[0], rr); gg = lerpTable(c[1], gg); bb = lerpTable(c[2], bb)
                }
                tmp[0] = rr; tmp[1] = gg; tmp[2] = bb
                color(tmp)
                pr[i] = tmp[0]; pg[i] = tmp[1]; pb[i] = tmp[2]
            }
        }

        if (defringe > 0) defringe(pr, pg, pb, w, rows)

        // Vignette (post-crop), in display space.
        if (vignette != 0f) {
            val fw = sampler.frameWidth.toFloat(); val fh = sampler.frameHeight.toFloat()
            val ax = if (vigRound >= 0) 1f + (fw / fh - 1f) * vigRound else 1f
            val ay = if (vigRound >= 0) 1f else 1f - vigRound * 0.6f
            val e0 = 0.3f + vigMid * 0.7f
            val e1 = e0 + 0.05f + vigFeather * 0.9f
            parallelRows(rows) { j ->
                val v = ((oy + j + 0.5f) / fh * 2 - 1) / ay
                for (x in 0 until w) {
                    val i = j * w + x
                    val u = ((ox + x + 0.5f) / fw * 2 - 1) * ax / maxOf(1f, ax)
                    val t = smooth(e0, e1, sqrt(u * u + v * v) / 1.4142f * 1.25f)
                    if (t <= 0f) continue
                    if (vignette < 0) {
                        val l = luma(pr[i], pg[i], pb[i])
                        val f = 1 + vignette * t * (1 - vigHighlights * l * l)
                        pr[i] *= f; pg[i] *= f; pb[i] *= f
                    } else {
                        val k = vignette * t
                        pr[i] += (1 - pr[i]) * k; pg[i] += (1 - pg[i]) * k; pb[i] += (1 - pb[i]) * k
                    }
                }
            }
        }

        // Sharpening: unsharp mask on luminance, with edge masking and halo limit.
        if (sharpen > 0) {
            val l = FloatArray(n)
            for (i in 0 until n) l[i] = luma(pr[i], pg[i], pb[i])
            val bl = FloatArray(n)
            Filters.blur(l, bl, w, rows, sharpenRadius(scale))
            val amt = sharpen * 1.4f * minOf(1f, (scale * 2.5).toFloat().coerceAtLeast(0.3f))
            val limit = 0.02f + 0.25f * sharpenDetail
            val t0 = sharpenMasking * 0.06f
            parallelRows(rows) { j ->
                for (x in 0 until w) {
                    val i = j * w + x
                    var d = (l[i] - bl[i]).coerceIn(-limit, limit) * amt
                    if (t0 > 0f) {
                        val xl = maxOf(0, x - 1); val xr = minOf(w - 1, x + 1)
                        val yu = maxOf(0, j - 1); val yd = minOf(rows - 1, j + 1)
                        val grad = abs(l[j * w + xr] - l[j * w + xl]) + abs(l[yd * w + x] - l[yu * w + x])
                        d *= smooth(t0 * 0.5f, t0 * 1.5f + 1e-4f, grad)
                    }
                    pr[i] += d; pg[i] += d; pb[i] += d
                }
            }
        }

        // Grain: smooth value noise anchored to full-size pixel positions.
        if (grain > 0) {
            val cell = 1.0 + grainSize * 4.0
            parallelRows(rows) { j ->
                val fy = (oy + j + 0.5) / scale / cell
                for (x in 0 until w) {
                    val i = j * w + x
                    val fx = (ox + x + 0.5) / scale / cell
                    var nz = valueNoise(fx, fy) * (1 - grainRough) + hash(floor(fx * 3).toInt(), floor(fy * 3).toInt()) * grainRough
                    val l = luma(pr[i], pg[i], pb[i])
                    nz *= grain * 0.16f * (0.35f + 2.6f * l * (1 - l))
                    pr[i] += nz; pg[i] += nz; pb[i] += nz
                }
            }
        }

        parallelRows(outRows) { j ->
            val si = (j + off) * w
            val o = j * w
            for (x in 0 until w) {
                out[o + x] = (0xff shl 24) or (to8(pr[si + x]) shl 16) or (to8(pg[si + x]) shl 8) or to8(pb[si + x])
            }
        }
    }

    /** Everything the GPU preview shader needs for these settings at render [scale]. */
    fun shaderParams(scale: Double): ShaderParams = ShaderParams(
        matrix = matrix.copyOf(), tone = tone, curves = rgbCurves, ev = ev,
        shadows = if (needsGuide) shadows else 0f, highlights = if (needsGuide) highlights else 0f,
        clarity = if (needsGuide) clarity else 0f, dehaze = dehaze,
        texture = texture * 0.9f * minOf(1f, (scale * 2.5).toFloat().coerceAtLeast(0.4f)), textureRadius = textureRadius(scale),
        mono = mono, sat = sat, vib = vib, hasMixer = hasMixer, mixHue = mixHue, mixSat = mixSat, mixLum = mixLum,
        bandCenters = bandCenters, hasGrading = hasGrading, gradeOffset = FloatArray(12) { gradeOffset[it / 3][it % 3] },
        gradeLum = gradeLum, blending = blending, balance = balance,
        vignette = vignette, vigMid = vigMid, vigFeather = vigFeather, vigRound = vigRound, vigHighlights = vigHighlights,
        sharpen = if (sharpen > 0) sharpen * 1.4f * minOf(1f, (scale * 2.5).toFloat().coerceAtLeast(0.3f)) else 0f,
        sharpenRadius = sharpenRadius(scale), sharpenLimit = 0.02f + 0.25f * sharpenDetail, sharpenMasking = sharpenMasking * 0.06f,
        grain = grain, grainCell = (1.0 + grainSize * 4.0).toFloat(), grainRough = grainRough, scale = scale.toFloat(),
    )

    /** HSL mixer (or B&W mixer), color grading, vibrance and saturation on one display pixel. */
    private fun color(p: FloatArray) {
        var r = p[0]; var g = p[1]; var b = p[2]
        val mx = maxOf(r, maxOf(g, b)); val mn = minOf(r, minOf(g, b))
        val chroma = mx - mn
        if ((hasMixer || mono) && chroma > 1e-4f) {
            val hue = hueOf(r, g, b, mx, chroma)
            var dh = 0f; var ds = 0f; var dl = 0f
            for (k in 0 until 8) {
                val wgt = bandWeight(k, hue)
                if (wgt == 0f) continue
                dh += wgt * mixHue[k]; ds += wgt * mixSat[k]; dl += wgt * mixLum[k]
            }
            val l = luma(r, g, b)
            val satNow = chroma / maxOf(1e-4f, 1 - abs(mx + mn - 1))
            if (mono) {
                val grey = (l + dl * 0.35f * satNow.coerceAtMost(1f)).coerceIn(0f, 1f)
                p[0] = grey; p[1] = grey; p[2] = grey
                gradeAndOutput(p, grey)
                return
            }
            if (dh != 0f) {
                val rgb = hsvToRgb((hue + dh * 30f + 360f) % 360f, chroma / maxOf(mx, 1e-4f), mx)
                r = rgb[0]; g = rgb[1]; b = rgb[2]
            }
            if (ds != 0f) {
                val ll = luma(r, g, b)
                val f = 1 + ds
                r = ll + (r - ll) * f; g = ll + (g - ll) * f; b = ll + (b - ll) * f
            }
            if (dl != 0f) {
                val shift = dl * 0.3f * satNow.coerceAtMost(1f)
                r += shift; g += shift; b += shift
            }
        } else if (mono) {
            val grey = luma(r, g, b)
            p[0] = grey; p[1] = grey; p[2] = grey
            gradeAndOutput(p, grey)
            return
        }
        // Vibrance boosts muted colours more than vivid ones; saturation scales all.
        if (sat != 1f || vib != 0f) {
            val l = luma(r, g, b)
            val c = maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
            val f = sat * (1f + vib * (1f - c).let { it * it })
            r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
        }
        p[0] = r; p[1] = g; p[2] = b
        gradeAndOutput(p, luma(r, g, b))
    }

    private fun gradeAndOutput(p: FloatArray, l: Float) {
        if (!hasGrading) return
        val pivot = (0.5f - balance * 0.3f).coerceIn(0.15f, 0.85f)
        val width = 0.15f + blending * 0.6f
        val ws = 1 - smooth(pivot - width, pivot, l)
        val wh = smooth(pivot, pivot + width, l)
        val wm = maxOf(0f, 1 - ws - wh)
        val ws4 = floatArrayOf(ws, wm, wh, 1f)
        for (z in 0..3) {
            val wz = ws4[z]
            if (wz == 0f) continue
            val o = gradeOffset[z]
            val dl = gradeLum[z] * 0.2f * wz
            p[0] += (o[0] * wz + dl); p[1] += (o[1] * wz + dl); p[2] += (o[2] * wz + dl)
        }
    }

    /** Weight of mixer band [k] at [hue]: 1 at its centre, falling to 0 at the neighbours. */
    private fun bandWeight(k: Int, hue: Float): Float {
        val c = bandCenters[k]
        val prev = bandCenters[(k + 7) % 8]
        val next = bandCenters[(k + 1) % 8]
        val dPrev = ((c - prev + 360f) % 360f)
        val dNext = ((next - c + 360f) % 360f)
        val d = ((hue - c + 540f) % 360f) - 180f
        return if (d >= 0) maxOf(0f, 1 - d / dNext) else maxOf(0f, 1 + d / dPrev)
    }

    /** Purple and green fringes along high-contrast edges lose their colour. */
    private fun defringe(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val l = FloatArray(w * h)
        for (i in l.indices) l[i] = luma(r[i], g[i], b[i])
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val xl = maxOf(0, x - 1); val xr = minOf(w - 1, x + 1)
                val yu = maxOf(0, y - 1); val yd = minOf(h - 1, y + 1)
                val grad = abs(l[y * w + xr] - l[y * w + xl]) + abs(l[yd * w + x] - l[yu * w + x])
                if (grad < 0.08f) continue
                val mx = maxOf(r[i], maxOf(g[i], b[i])); val c = mx - minOf(r[i], minOf(g[i], b[i]))
                if (c < 0.03f) continue
                val hue = hueOf(r[i], g[i], b[i], mx, c)
                val fringe = maxOf(1 - abs(hue - 290f) / 45f, 1 - abs(hue - 110f) / 35f).coerceIn(0f, 1f)
                val k = defringe * fringe * smooth(0.08f, 0.25f, grad)
                if (k <= 0f) continue
                val li = l[i]
                r[i] += (li - r[i]) * k; g[i] += (li - g[i]) * k; b[i] += (li - b[i]) * k
            }
        }
    }

    private fun lookup(x: Float): Float {
        if (x <= 0f) return 0f
        val t = sqrt(x / LUT_MAX_F) * LUT_SIZE
        if (t >= LUT_SIZE) return tone[LUT_SIZE]
        val i = t.toInt()
        val f = t - i
        return tone[i] + (tone[i + 1] - tone[i]) * f
    }

    companion object {
        private const val LUT_SIZE = 4096
        private const val LUT_MAX = 64.0
        private const val LUT_MAX_F = 64f
        /** Correctly metered mid grey in raw (~3 stops under clip) and where it lands on screen. */
        const val SCENE_GREY = 0.12
        private const val DISPLAY_GREY = 0.20

        fun encode(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

        private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun lerpTable(t: FloatArray, v: Float): Float {
            val x = v.coerceIn(0f, 1f) * (t.size - 1)
            val i = minOf(t.size - 2, x.toInt())
            return t[i] + (t[i + 1] - t[i]) * (x - i)
        }

        fun hueOf(r: Float, g: Float, b: Float, mx: Float, c: Float): Float {
            val h = when (mx) {
                r -> ((g - b) / c).let { if (it < 0) it + 6 else it }
                g -> (b - r) / c + 2
                else -> (r - g) / c + 4
            }
            return h * 60f
        }

        fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
            val hh = ((h % 360f) + 360f) % 360f / 60f
            val i = hh.toInt()
            val f = hh - i
            val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
            return when (i) {
                0 -> floatArrayOf(v, t, p)
                1 -> floatArrayOf(q, v, p)
                2 -> floatArrayOf(p, v, t)
                3 -> floatArrayOf(p, q, v)
                4 -> floatArrayOf(t, p, v)
                else -> floatArrayOf(v, p, q)
            }
        }

        private fun hash(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xffff) / 32768f - 1f
        }

        private fun valueNoise(x: Double, y: Double): Float {
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
            val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
            val a = hash(x0, y0) + (hash(x0 + 1, y0) - hash(x0, y0)) * sx
            val b = hash(x0, y0 + 1) + (hash(x0 + 1, y0 + 1) - hash(x0, y0 + 1)) * sx
            return a + (b - a) * sy
        }
    }
}

/** [Look]'s per-pixel parameters, for the GPU preview (see GpuPreview.kt). */
class ShaderParams(
    val matrix: FloatArray, val tone: FloatArray, val curves: Array<FloatArray>?, val ev: Float,
    val shadows: Float, val highlights: Float, val clarity: Float, val dehaze: Float,
    val texture: Float, val textureRadius: Float,
    val mono: Boolean, val sat: Float, val vib: Float, val hasMixer: Boolean,
    val mixHue: FloatArray, val mixSat: FloatArray, val mixLum: FloatArray, val bandCenters: FloatArray,
    val hasGrading: Boolean, val gradeOffset: FloatArray, val gradeLum: FloatArray, val blending: Float, val balance: Float,
    val vignette: Float, val vigMid: Float, val vigFeather: Float, val vigRound: Float, val vigHighlights: Float,
    val sharpen: Float, val sharpenRadius: Float, val sharpenLimit: Float, val sharpenMasking: Float,
    val grain: Float, val grainCell: Float, val grainRough: Float, val scale: Float,
) {
    companion object {
        const val LUT_SIZE = 4096
        const val LUT_MAX = 64f
    }
}

/**
 * Lightroom's Auto: sets Exposure, Contrast, Highlights, Shadows, Whites, Blacks, Vibrance and
 * Saturation from the photo's brightness distribution (white balance and the rest are kept).
 */
fun autoTone(source: DevelopSource, current: Settings): Settings {
    val base = current.with(
        P.EXPOSURE to 0f, P.CONTRAST to 0f, P.HIGHLIGHTS to 0f, P.SHADOWS to 0f, P.WHITES to 0f, P.BLACKS to 0f,
    )
    val geom = Geometry(base, source.fullWidth.toDouble(), source.fullHeight.toDouble(), source.lens)
    val sampler = ImageSampler(source.image, geom, 400)
    val img = LinearImage(sampler.width, sampler.height)
    sampler.fill(0, sampler.height, img)
    Look(source, base).linear(img, 0, img.height)
    val logs = FloatArray(img.r.size) { log2(luma(img.r[it], img.g[it], img.b[it])) }.sorted()
    fun pct(p: Double) = logs[((logs.size - 1) * p).toInt()]
    val mid = log2(Look.SCENE_GREY.toFloat())
    // Put the median a little under mid grey (Lightroom's auto tends bright-ish but safe).
    val exposure = (mid + 0.3f - pct(0.5)).coerceIn(-3f, 3f)
    val hi = pct(0.995) + exposure
    val lo = pct(0.005) + exposure
    val spread = pct(0.95) - pct(0.05)
    val highlights = if (hi > mid + 2.6f) -((hi - mid - 2.6f) * 35f).coerceAtMost(70f) else 0f
    val shadows = if (lo < mid - 5f) ((mid - 5f - lo) * 12f).coerceAtMost(50f) else 0f
    val whites = ((mid + 2.8f - hi) * 18f).coerceIn(-40f, 40f)
    val blacks = ((mid - 6f - lo) * -6f).coerceIn(-30f, 20f)
    val contrast = ((6f - spread) * 6f).coerceIn(-20f, 30f)
    return current.with(
        P.EXPOSURE to (exposure * 100).toInt() / 100f, P.CONTRAST to contrast.toInt().toFloat(),
        P.HIGHLIGHTS to highlights.toInt().toFloat(), P.SHADOWS to shadows.toInt().toFloat(),
        P.WHITES to whites.toInt().toFloat(), P.BLACKS to blacks.toInt().toFloat(),
        P.VIBRANCE to maxOf(current[P.VIBRANCE], 10f),
    )
}
