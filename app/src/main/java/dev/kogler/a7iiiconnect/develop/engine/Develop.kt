package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One photo's edit, Lightroom style. Sliders run -100..100 unless noted; 0 is "no change".
 * [temperature]/[tint] null means "As Shot".
 */
data class Settings(
    val exposure: Float = 0f, // stops, -5..5
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float? = null, // Kelvin
    val tint: Float? = null, // -150..150
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
) {
    val isDefault get() = this == Settings()

    fun encode(): String = buildString {
        append("v=1\n")
        append("exposure=$exposure\ncontrast=$contrast\nhighlights=$highlights\nshadows=$shadows\n")
        append("whites=$whites\nblacks=$blacks\nvibrance=$vibrance\nsaturation=$saturation\n")
        temperature?.let { append("temperature=$it\n") }
        tint?.let { append("tint=$it\n") }
    }

    companion object {
        fun decode(text: String): Settings {
            val m = text.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().toFloatOrNull()
            }.filter { it.second != null }.associate { it.first to it.second!! }
            fun f(k: String) = m[k] ?: 0f
            return Settings(
                exposure = f("exposure"), contrast = f("contrast"), highlights = f("highlights"),
                shadows = f("shadows"), whites = f("whites"), blacks = f("blacks"),
                temperature = m["temperature"], tint = m["tint"],
                vibrance = f("vibrance"), saturation = f("saturation"),
            )
        }
    }
}

/** Lightroom's white balance presets (Kelvin, tint). */
enum class WhiteBalance(val label: String, val kelvin: Float, val tint: Float) {
    DAYLIGHT("Daylight", 5500f, 10f),
    CLOUDY("Cloudy", 6500f, 10f),
    SHADE("Shade", 7500f, 10f),
    TUNGSTEN("Tungsten", 2850f, 0f),
    FLUORESCENT("Fluorescent", 3800f, 21f),
    FLASH("Flash", 5500f, 0f),
}

/** A source photo ready to render: its pixels plus what's needed to colour them. */
class DevelopSource(
    val image: LinearImage,
    val color: CameraColor,
    /** Multipliers already applied to [image] (as-shot WB for raws, 1,1,1 for JPEGs). */
    val baked: FloatArray,
    val isRaw: Boolean,
) {
    /** The as-shot Kelvin and tint, shown when the photo uses "As Shot". */
    val asShot: Pair<Double, Double> by lazy {
        if (isRaw) color.temperatureOf(baked) else 6500.0 to 0.0
    }
}

/**
 * Renders [Settings] to 8-bit sRGB. Everything except saturation folds into a 3x3 matrix and a
 * per-channel tone table, so a slider change re-renders a 2-megapixel preview quickly.
 */
class Renderer(private val source: DevelopSource, settings: Settings) {

    private val matrix = FloatArray(9)
    private val tone = FloatArray(LUT_SIZE + 1)
    private val sat: Float
    private val vib: Float

    init {
        val color = source.color
        // White balance relative to what's baked into the pixels.
        val (k0, t0) = source.asShot
        val kelvin = (settings.temperature?.toDouble() ?: k0)
        val tint = (settings.tint?.toDouble() ?: t0)
        val target = color.multipliers(kelvin, tint)
        val ref = color.multipliers(k0, t0)
        val rel = DoubleArray(3) { target[it] / ref[it] }
        // Keep brightness steady while the balance moves: normalise by the luminance-weighted gain.
        val norm = 0.30 * rel[0] + 0.59 * rel[1] + 0.11 * rel[2]
        val gain = 2.0.pow(settings.exposure.toDouble()) * (if (source.isRaw) RAW_BASELINE else 1.0)
        val m = Mat3.mul(color.rgbFromCam, Mat3.diag(rel[0] / norm, rel[1] / norm, rel[2] / norm))
        for (i in 0..8) matrix[i] = (m[i] * gain).toFloat()

        buildTone(settings, if (source.isRaw) RAW_BASELINE else 1.0, source.isRaw)
        sat = 1f + settings.saturation / 100f
        vib = settings.vibrance / 100f
    }

    /**
     * Scene-linear value -> display value 0..1 (sRGB encoded). The base is a highlight roll-off
     * that reaches white exactly at the sensor's clip point, then Lightroom-like tone sliders as
     * smooth curve adjustments.
     */
    private fun buildTone(s: Settings, baseline: Double, raw: Boolean) {
        val white = baseline * 2.0.pow(-s.whites / 100.0 * 1.2)
        val blackPoint = -s.blacks / 100.0 * 0.04
        val contrast = (if (raw) BASE_CONTRAST else 0.0) + s.contrast / 100.0 * 0.6
        val sh = s.shadows / 100.0 * 0.14
        val hi = s.highlights / 100.0 * 0.14
        var prev = 0.0
        for (i in 0..LUT_SIZE) {
            val t = i.toDouble() / LUT_SIZE
            val x = t * t * LUT_MAX
            // Extended Reinhard: 0 -> 0, white -> 1, gentle shoulder.
            var y = if (raw) x * (1 + x / (white * white)) / (1 + x) else x / (white / baseline)
            y = (y - blackPoint) / (1 - blackPoint)
            var v = encode(y.coerceIn(0.0, 1.0))
            v += contrast * (0.5 - 0.5 * cos(PI * v) - v)
            v += sh * v * (1 - v) * (1 - v) * 6.75
            v += hi * v * v * (1 - v) * 6.75
            v = v.coerceIn(0.0, 1.0)
            if (v < prev) v = prev
            prev = v
            tone[i] = v.toFloat()
        }
    }

    /** Renders rows of [img] into ARGB [out] (same layout). */
    fun render(img: LinearImage, out: IntArray, rowFrom: Int = 0, rowTo: Int = img.height) {
        val m = matrix
        val w = img.width
        parallelRows(rowTo - rowFrom) { i ->
            val y = rowFrom + i
            val o = y * w
            for (x in 0 until w) {
                val ri = img.r[o + x]; val gi = img.g[o + x]; val bi = img.b[o + x]
                var r = lookup(m[0] * ri + m[1] * gi + m[2] * bi)
                var g = lookup(m[3] * ri + m[4] * gi + m[5] * bi)
                var b = lookup(m[6] * ri + m[7] * gi + m[8] * bi)
                if (sat != 1f || vib != 0f) {
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    val mx = maxOf(r, maxOf(g, b))
                    val mn = minOf(r, minOf(g, b))
                    // Vibrance boosts muted colours more than vivid ones.
                    val f = sat * (1f + vib * (1f - (mx - mn)).let { it * it })
                    r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
                }
                out[o + x] = (0xff shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
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

    private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    companion object {
        private const val LUT_SIZE = 4096
        private const val LUT_MAX = 64.0
        private const val LUT_MAX_F = 64f
        /** Raw files are rendered ~0.5 EV brighter than linear, like Adobe's Sony baseline. */
        private const val RAW_BASELINE = 1.45
        private const val BASE_CONTRAST = 0.18

        fun encode(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    }
}

/** Histogram of rendered ARGB pixels: 256 bins each for R, G, B. */
fun histogram(pixels: IntArray, step: Int = 3): Array<IntArray> {
    val h = Array(3) { IntArray(256) }
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        h[0][(p shr 16) and 0xff]++
        h[1][(p shr 8) and 0xff]++
        h[2][p and 0xff]++
        i += step
    }
    return h
}
