package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The shapes a mask can take, like Lightroom's mask tools. */
enum class MaskKind(val label: String) {
    LINEAR("Linear gradient"),
    RADIAL("Radial gradient"),
    SUBJECT("Subject"),
    SKY("Sky"),
}

/** The sliders a mask carries: Lightroom's local adjustments that fit this engine. */
object LocalP {
    val all = mutableListOf<Param>()
    private fun p(key: String, label: String, min: Float = -100f, max: Float = 100f, decimals: Int = 0) =
        Param(key, label, Group.LIGHT, min, max, 0f, decimals).also { all += it }

    val EXPOSURE = p("exposure", "Exposure", -4f, 4f, 2)
    val CONTRAST = p("contrast", "Contrast")
    val HIGHLIGHTS = p("highlights", "Highlights")
    val SHADOWS = p("shadows", "Shadows")
    val TEMP = p("temp", "Temp")
    val TINT = p("tint", "Tint")
    val SATURATION = p("saturation", "Saturation")
    val CLARITY = p("clarity", "Clarity")
    val DEHAZE = p("dehaze", "Dehaze")

    val byKey: Map<String, Param> by lazy { all.associateBy { it.key } }
}

/**
 * One local adjustment. Positions are fractions of the upright photo's width and height (before
 * crop, straighten and lens corrections), so later framing changes don't move the mask.
 *
 * Linear: the effect is full at ([x0], [y0]) and gone at ([x1], [y1]).
 * Radial: centre ([x0], [y0]), radii [x1] (of the width) and [y1] (of the height), turned by
 * [angle] degrees; [feather] (0..100) is how much of the radius fades.
 * Subject: the photo's main subject, found on the device (see [MaskMaps.subject]).
 * Sky: the photo's sky, found on the device (see [MaskMaps.sky]).
 */
data class Mask(
    val kind: MaskKind,
    val x0: Float = 0.5f,
    val y0: Float = 0.5f,
    val x1: Float = 0.5f,
    val y1: Float = 0.5f,
    val angle: Float = 0f,
    val feather: Float = 50f,
    val invert: Boolean = false,
    /** Leaves the subject out (a sky gradient that doesn't darken the person in front). */
    val excludeSubject: Boolean = false,
    val values: Map<String, Float> = emptyMap(),
) {
    operator fun get(p: Param): Float = values[p.key] ?: p.default

    fun with(p: Param, v: Float): Mask {
        val c = v.coerceIn(p.min, p.max)
        return copy(values = if (c == p.default) values - p.key else values + (p.key to c))
    }

    val usesSubject get() = kind == MaskKind.SUBJECT || excludeSubject
    val usesSky get() = kind == MaskKind.SKY
    /** Placed by hand (with handles), rather than found in the photo. */
    val shaped get() = kind == MaskKind.LINEAR || kind == MaskKind.RADIAL

    fun encode(): String = buildString {
        append(kind.name.lowercase())
        if (kind != MaskKind.SUBJECT && kind != MaskKind.SKY) append(";x0=$x0;y0=$y0;x1=$x1;y1=$y1")
        if (kind == MaskKind.RADIAL) append(";angle=$angle;feather=$feather")
        if (invert) append(";invert=1")
        if (excludeSubject) append(";excludeSubject=1")
        for ((k, v) in values.toSortedMap()) append(";$k=$v")
    }

    companion object {
        /** A gradient from the top down to just above the middle: the usual sky darkening. */
        fun linear() = Mask(MaskKind.LINEAR, 0.5f, 0.15f, 0.5f, 0.5f)

        /** A circle around the centre, [aspect] = the photo's width / height. */
        fun radial(aspect: Float) = Mask(MaskKind.RADIAL, 0.5f, 0.5f, 0.25f, 0.25f * aspect)

        fun subject() = Mask(MaskKind.SUBJECT)

        fun sky() = Mask(MaskKind.SKY)

        fun decode(text: String): Mask? = runCatching {
            val parts = text.split(';')
            val kind = MaskKind.entries.first { it.name.lowercase() == parts[0].trim() }
            var m = Mask(kind)
            val values = HashMap<String, Float>()
            for (part in parts.drop(1)) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim().toFloatOrNull() ?: continue
                m = when (k) {
                    "x0" -> m.copy(x0 = v)
                    "y0" -> m.copy(y0 = v)
                    "x1" -> m.copy(x1 = v)
                    "y1" -> m.copy(y1 = v)
                    "angle" -> m.copy(angle = v)
                    "feather" -> m.copy(feather = v.coerceIn(0f, 100f))
                    "invert" -> m.copy(invert = v >= 0.5f)
                    "excludeSubject" -> m.copy(excludeSubject = v >= 0.5f)
                    else -> {
                        val p = LocalP.byKey[k]
                        if (p != null && v != p.default) values[k] = v.coerceIn(p.min, p.max)
                        m
                    }
                }
            }
            m.copy(values = values)
        }.getOrNull()
    }
}

/** A grey map over the upright photo, 0..1 (the subject: 1 on it, 0 off it). */
class MaskImage(val width: Int, val height: Int, val data: FloatArray) {
    /** Bilinear value at fractions ([u], [v]) of the photo. */
    fun at(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (v * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(width - 1, x0 + 1); val y1 = minOf(height - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        val a = data[y0 * width + x0] + (data[y0 * width + x1] - data[y0 * width + x0]) * fx
        val b = data[y1 * width + x0] + (data[y1 * width + x1] - data[y1 * width + x0]) * fx
        return a + (b - a) * fy
    }
}

/** Per-photo maps the masks need, shared by a photo's preview and draft sources. */
class MaskMaps {
    /** Null until it has been found (masks that use it have no effect until then). */
    @Volatile var subject: MaskImage? = null
    /** The sky, likewise (masks that use it have no effect until it's found). */
    @Volatile var sky: MaskImage? = null
}

/**
 * A photo's masks ready to evaluate: per mask, its shape in full-size pixels and its sliders,
 * flattened into [params] ([STRIDE] floats per mask; the Rust engine and the GPU shader read the
 * same layout). [add] sums every mask's sliders, weighted by its coverage, at one pixel.
 */
class MaskSet(masks: List<Mask>, private val srcW: Double, private val srcH: Double, val subject: MaskImage?, val sky: MaskImage? = null) {
    val count = minOf(MAX, masks.size)
    val params = FloatArray(count * STRIDE)
    /** Which of the [LOCALS] are non-zero in some mask. */
    val used = BooleanArray(LOCALS.size)

    init {
        for (k in 0 until count) {
            val m = masks[k]
            val o = k * STRIDE
            params[o] = (m.kind.ordinal + 1).toFloat()
            params[o + 1] = (m.x0 * srcW).toFloat(); params[o + 2] = (m.y0 * srcH).toFloat()
            params[o + 3] = (m.x1 * srcW).toFloat(); params[o + 4] = (m.y1 * srcH).toFloat()
            if (m.kind == MaskKind.RADIAL) {
                params[o + 3] = maxOf(1f, params[o + 3]); params[o + 4] = maxOf(1f, params[o + 4])
            }
            val a = m.angle * PI / 180
            params[o + 5] = cos(a).toFloat(); params[o + 6] = sin(a).toFloat()
            params[o + 7] = maxOf(0.02f, m.feather / 100f)
            params[o + 8] = if (m.invert) 1f else 0f
            params[o + 9] = if (m.excludeSubject) 1f else 0f
            for ((i, p) in LOCALS.withIndex()) {
                val v = m[p] * SCALES[i]
                params[o + 10 + i] = v
                if (v != 0f) used[i] = true
            }
        }
    }

    val any get() = used.any { it }
    fun uses(i: Int) = used[i]

    /** How much mask [k] covers full-size pixel ([x], [y]), 0..1. */
    fun weight(k: Int, x: Float, y: Float): Float {
        val o = k * STRIDE
        val p = params
        var w = when (p[o].toInt()) {
            1 -> {
                val dx = p[o + 3] - p[o + 1]; val dy = p[o + 4] - p[o + 2]
                val t = ((x - p[o + 1]) * dx + (y - p[o + 2]) * dy) / maxOf(1e-6f, dx * dx + dy * dy)
                1 - smoothstep(0f, 1f, t)
            }
            2 -> {
                val qx = x - p[o + 1]; val qy = y - p[o + 2]
                val rx = (qx * p[o + 5] + qy * p[o + 6]) / p[o + 3]
                val ry = (-qx * p[o + 6] + qy * p[o + 5]) / p[o + 4]
                1 - smoothstep(1 - p[o + 7], 1f, sqrt(rx * rx + ry * ry))
            }
            3 -> subjectAt(x, y)
            else -> skyAt(x, y)
        }
        if (p[o + 8] > 0.5f) w = 1 - w
        if (p[o + 9] > 0.5f) w *= 1 - subjectAt(x, y)
        return w
    }

    private fun subjectAt(x: Float, y: Float): Float = subject?.at((x / srcW).toFloat(), (y / srcH).toFloat()) ?: 0f
    private fun skyAt(x: Float, y: Float): Float = sky?.at((x / srcW).toFloat(), (y / srcH).toFloat()) ?: 0f

    /** Adds every mask's [LOCALS] at full-size pixel ([x], [y]) into [out]. */
    fun add(x: Float, y: Float, out: FloatArray) {
        for (k in 0 until count) {
            val w = weight(k, x, y)
            if (w <= 0f) continue
            val o = k * STRIDE + 10
            for (i in LOCALS.indices) out[i] += w * params[o + i]
        }
    }

    /** [count] and the photo's size, then [params]: what the Rust engine takes. */
    fun packed(): FloatArray = floatArrayOf(count.toFloat(), srcW.toFloat(), srcH.toFloat()) + params

    companion object {
        const val MAX = 8
        const val STRIDE = 19
        /** Order of the sliders in a mask's params (from offset 10) and in a pixel's sums. */
        val LOCALS = listOf(
            LocalP.EXPOSURE, LocalP.TEMP, LocalP.TINT, LocalP.CONTRAST, LocalP.HIGHLIGHTS,
            LocalP.SHADOWS, LocalP.CLARITY, LocalP.DEHAZE, LocalP.SATURATION,
        )
        /** Exposure stays in stops; the rest go from -100..100 to -1..1. */
        private val SCALES = floatArrayOf(1f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f, 0.01f)
        const val EV = 0; const val TEMP = 1; const val TINT = 2; const val CONTRAST = 3; const val HIGHLIGHTS = 4
        const val SHADOWS = 5; const val CLARITY = 6; const val DEHAZE = 7; const val SATURATION = 8

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
