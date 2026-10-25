package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
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
    /** Lens corrections, crop and rotation: output frame positions -> upright photo positions. */
    val geometry: Geometry
    /**
     * Fills [rows] output rows starting at [y0] (rows outside the image are clamped). With [uv],
     * also each pixel's position in the full-size upright source (x, y pairs), for masks.
     */
    fun fill(y0: Int, rows: Int, out: LinearImage, uv: FloatArray? = null)

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
    override val geometry: Geometry,
    /** Longest output edge, or the natural size when null. */
    maxEdge: Int? = null,
    region: Region? = null,
) : Sampler {
    private val geom get() = geometry
    private val natural = src.width / geom.srcW
    override val scale: Double = region?.scale ?: if (maxEdge == null) natural else minOf(natural, maxEdge / maxOf(geom.outW, geom.outH))
    override val frameWidth = maxOf(1, (geom.outW * scale).toInt())
    override val frameHeight = maxOf(1, (geom.outH * scale).toInt())
    override val width = region?.width ?: frameWidth
    override val height = region?.height ?: frameHeight
    override val originX = region?.x0 ?: 0
    override val originY = region?.y0 ?: 0
    private val direct = region == null && geom.isIdentity && width == src.width && height == src.height

    override fun fill(y0: Int, rows: Int, out: LinearImage, uv: FloatArray?) {
        val toSrc = src.width / geom.srcW
        val vig = geom.hasVignetteCorrection
        parallelRows(rows) { i ->
            val y = (originY + y0 + i).coerceIn(0, frameHeight - 1)
            val o = i * width
            if (uv != null) sourcePositions(geom, scale, originX, y, width, uv, o)
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

/** Source positions (full-size upright pixels) of output row [y], [width] pixels, into [uv] at pixel [o]. */
internal fun sourcePositions(geom: Geometry, scale: Double, originX: Int, y: Int, width: Int, uv: FloatArray, o: Int) {
    val m = DoubleArray(6)
    val oy = (y + 0.5) / scale
    for (x in 0 until width) {
        geom.map((originX + x + 0.5) / scale, oy, m)
        uv[(o + x) * 2] = m[2].toFloat(); uv[(o + x) * 2 + 1] = m[3].toFloat()
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
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * Everything a [Settings] needs at render time, precomputed: colour matrix, tone tables,
 * mixer/grading coefficients. The per-pixel pipeline runs on the GPU (GpuPreview.kt, fed by
 * [shaderParams]); [prepare] does the few tools that need a real neighbourhood beforehand.
 */
class Look(private val source: DevelopSource, private val s: Settings) {

    private val matrix = FloatArray(9)
    /** Camera RGB <-> linear sRGB at the as-shot balance and exposure 0, for [prepare]. */
    private val sceneMatrix = FloatArray(9)
    private val sceneInverse = FloatArray(9)
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
    private val masks: MaskSet? = s.masks.takeIf { it.isNotEmpty() }
        ?.let { MaskSet(it, source.fullWidth.toDouble(), source.fullHeight.toDouble(), source.maps.subject, source.maps.sky) }?.takeIf { it.any }
    private val needsGuide = shadows != 0f || highlights != 0f || clarity != 0f || dehaze != 0f ||
        masks != null && (masks.uses(MaskSet.SHADOWS) || masks.uses(MaskSet.HIGHLIGHTS) || masks.uses(MaskSet.CLARITY) || masks.uses(MaskSet.DEHAZE))

    private val vignette = s[P.VIGNETTE] / 100f
    private val vigMid = s[P.VIGNETTE_MIDPOINT] / 100f
    private val vigFeather = s[P.VIGNETTE_FEATHER] / 100f
    private val vigRound = s[P.VIGNETTE_ROUNDNESS] / 100f
    private val vigHighlights = s[P.VIGNETTE_HIGHLIGHTS] / 100f
    private val grain = s[P.GRAIN] / 100f
    private val grainSize = s[P.GRAIN_SIZE] / 100f
    private val grainRough = s[P.GRAIN_ROUGHNESS] / 100f
    private val lut = s.lut?.let { Luts[it] }
    private val lutAmount = s[P.LUT_AMOUNT] / 100f
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
        val gain = 2.0.pow(s[P.EXPOSURE] + source.baseline)
        val m = Mat3.mul(color.rgbFromCam, Mat3.diag(rel[0] / norm, rel[1] / norm, rel[2] / norm))
        for (i in 0..8) matrix[i] = (m[i] * gain).toFloat()
        val inv = Mat3.invert(color.rgbFromCam)
        for (i in 0..8) { sceneMatrix[i] = color.rgbFromCam[i].toFloat(); sceneInverse[i] = inv[i].toFloat() }
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

    /**
     * How far (in output pixels) the scene must reach past the rendered area: [prepare]'s
     * filters plus the shader's neighbour taps (Texture, Sharpen). The tile and band overlap.
     */
    fun margin(scale: Double): Int {
        var prep = 0.0
        if (noise > 0) prep += noiseRadius(scale) * 2.0
        if (colorNoise > 0) prep += colorNoiseRadius(scale) * 3.0
        if (defringe > 0) prep += 2.0
        var taps = 0.0
        if (texture != 0f) taps = maxOf(taps, textureRadius(scale) * 3.0)
        if (sharpen > 0) taps = maxOf(taps, sharpenRadius(scale) * 3.0 + 1)
        return ceil(prep + taps).toInt() + 2
    }

    /** Whether [prepare] changes anything: noise reduction or defringe is on. */
    val prepares: Boolean get() = noise > 0 || colorNoise > 0 || defringe > 0

    /** What [prepare] depends on besides the pixels and scale, for caching prepared scenes. */
    val prepareKey: List<Float> get() = listOf(noise, noiseDetail, colorNoise, defringe)

    /**
     * Noise reduction and defringe on scene pixels (camera RGB, in place), before they go to the
     * GPU: they need wide neighbourhoods the shader can't afford per pixel. They work in linear
     * sRGB at the as-shot balance and exposure 0, so moving those sliders doesn't redo them.
     * [scale] is output pixels per full-size pixel; rows and columns past the edges are clamped.
     */
    fun prepare(img: LinearImage, scale: Double) {
        if (!prepares) return
        val w = img.width; val h = img.height; val n = w * h
        val r = img.r; val g = img.g; val b = img.b
        convert(img, sceneMatrix, true)
        if (colorNoise > 0) {
            val cr = FloatArray(n); val cb = FloatArray(n)
            for (i in 0 until n) { val l = luma(r[i], g[i], b[i]); cr[i] = r[i] - l; cb[i] = b[i] - l }
            val rad = colorNoiseRadius(scale)
            Filters.blur(cr, cr, w, h, rad); Filters.blur(cb, cb, w, h, rad)
            parallelRows(h) { y ->
                for (i in y * w until (y + 1) * w) {
                    val l = luma(r[i], g[i], b[i])
                    val nr = maxOf(0f, l + cr[i]); val nb = maxOf(0f, l + cb[i])
                    r[i] = nr; b[i] = nb
                    g[i] = maxOf(0f, (l - 0.2126f * nr - 0.0722f * nb) / 0.7152f)
                }
            }
        }
        if (noise > 0) {
            val p = FloatArray(n)
            for (i in 0 until n) p[i] = sqrt(luma(r[i], g[i], b[i]))
            val q = FloatArray(n)
            val eps = (noise * noise * 0.004f * (1.6f - noiseDetail)).coerceAtLeast(1e-6f)
            Filters.guided(p, q, w, h, noiseRadius(scale), eps)
            parallelRows(h) { y ->
                for (i in y * w until (y + 1) * w) {
                    val before = p[i] * p[i]
                    if (before > 1e-6f) {
                        val f = q[i] * q[i] / before
                        r[i] *= f; g[i] *= f; b[i] *= f
                    }
                }
            }
        }
        if (defringe > 0) defringe(r, g, b, w, h)
        convert(img, sceneInverse, false)
    }

    private fun convert(img: LinearImage, m: FloatArray, clamp: Boolean) {
        val w = img.width
        parallelRows(img.height) { y ->
            for (x in y * w until (y + 1) * w) {
                val r = img.r[x]; val g = img.g[x]; val b = img.b[x]
                var nr = m[0] * r + m[1] * g + m[2] * b
                var ng = m[3] * r + m[4] * g + m[5] * b
                var nb = m[6] * r + m[7] * g + m[8] * b
                if (clamp) { nr = maxOf(0f, nr); ng = maxOf(0f, ng); nb = maxOf(0f, nb) }
                img.r[x] = nr; img.g[x] = ng; img.b[x] = nb
            }
        }
    }

    /**
     * Purple and green fringes along high-contrast edges lose their colour. Edges and hues are
     * judged on sRGB-encoded values (close to what's on screen); the colour comes out linearly.
     */
    private fun defringe(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val n = w * h
        val dr = FloatArray(n); val dg = FloatArray(n); val db = FloatArray(n); val l = FloatArray(n)
        parallelRows(h) { y ->
            for (i in y * w until (y + 1) * w) {
                dr[i] = encodeF(r[i]); dg[i] = encodeF(g[i]); db[i] = encodeF(b[i])
                l[i] = luma(dr[i], dg[i], db[i])
            }
        }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val xl = maxOf(0, x - 1); val xr = minOf(w - 1, x + 1)
                val yu = maxOf(0, y - 1); val yd = minOf(h - 1, y + 1)
                val grad = abs(l[y * w + xr] - l[y * w + xl]) + abs(l[yd * w + x] - l[yu * w + x])
                if (grad < 0.08f) continue
                val mx = maxOf(dr[i], maxOf(dg[i], db[i])); val c = mx - minOf(dr[i], minOf(dg[i], db[i]))
                if (c < 0.03f) continue
                val hue = hueOf(dr[i], dg[i], db[i], mx, c)
                val fringe = maxOf(1 - abs(hue - 290f) / 45f, 1 - abs(hue - 110f) / 35f).coerceIn(0f, 1f)
                val k = defringe * fringe * smooth(0.08f, 0.25f, grad)
                if (k <= 0f) continue
                val li = luma(r[i], g[i], b[i])
                r[i] += (li - r[i]) * k; g[i] += (li - g[i]) * k; b[i] += (li - b[i]) * k
            }
        }
    }

    private fun noiseRadius(scale: Double) = maxOf(1, (scale * (1 + noise * 3)).toInt())
    private fun colorNoiseRadius(scale: Double) = maxOf(1f, (scale * (2 + colorNoise * 10)).toFloat())
    private fun textureRadius(scale: Double) = maxOf(0.7f, (scale * 3).toFloat())
    private fun sharpenRadius(scale: Double) = maxOf(0.6f, (sharpenRadius * scale).toFloat())

    /** The subject map, when a mask uses it. */
    internal val subjectMap: MaskImage? get() = if (s.masks.any { it.usesSubject }) masks?.subject else null

    /** The sky map, when a mask uses it. */
    internal val skyMap: MaskImage? get() = if (s.masks.any { it.usesSky }) masks?.sky else null

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
        maskCount = masks?.count ?: 0, masks = (masks?.params ?: FloatArray(0)).copyOf(MaskSet.MAX * MaskSet.STRIDE),
        sourceSize = floatArrayOf(source.fullWidth.toFloat(), source.fullHeight.toFloat()), subject = subjectMap,
        sky = skyMap, lut = lut?.takeIf { lutAmount > 0f }, lutAmount = lutAmount,
    )

    companion object {
        private const val LUT_SIZE = 4096
        private const val LUT_MAX = 64.0
        /** Correctly metered mid grey in raw (~3 stops under clip) and where it lands on screen. */
        const val SCENE_GREY = 0.12
        private const val DISPLAY_GREY = 0.20

        fun encode(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

        private fun encodeF(v: Float): Float {
            val c = v.coerceIn(0f, 1f)
            return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        }

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
    }
}

/** [Look]'s per-pixel parameters, for the GPU shader (see GpuPreview.kt). */
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
    /** [MaskSet.params] padded to [MaskSet.MAX] masks, and the full-size photo they're placed on. */
    val maskCount: Int, val masks: FloatArray, val sourceSize: FloatArray, val subject: MaskImage?,
    val sky: MaskImage? = null,
    /** A film look on top, mixed in by [lutAmount]. */
    val lut: Lut? = null, val lutAmount: Float = 0f,
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
