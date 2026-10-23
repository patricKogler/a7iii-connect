package dev.kogler.a7iiiconnect.develop.engine

/** Groups match Lightroom's panels; they also drive "paste selected settings". */
enum class Group(val label: String) {
    LIGHT("Light"),
    CURVE("Tone curve"),
    COLOR("White balance & presence"),
    MIXER("Color mixer"),
    GRADING("Color grading"),
    EFFECTS("Effects"),
    DETAIL("Detail"),
    OPTICS("Optics"),
    GEOMETRY("Geometry"),
    CROP("Crop & rotate"),
}

/** One adjustable value. [default] NaN means "unset" (white balance: As Shot). */
class Param(
    val key: String,
    val label: String,
    val group: Group,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
    val decimals: Int = 0,
) {
    val isSwitch get() = key.startsWith("on.")
}

/** Every adjustable value, Lightroom names and ranges. */
object P {
    val all = mutableListOf<Param>()
    private fun p(key: String, label: String, group: Group, min: Float = -100f, max: Float = 100f, default: Float = 0f, decimals: Int = 0) =
        Param(key, label, group, min, max, default, decimals).also { all += it }

    // Light
    val EXPOSURE = p("exposure", "Exposure", Group.LIGHT, -5f, 5f, decimals = 2)
    val CONTRAST = p("contrast", "Contrast", Group.LIGHT)
    val HIGHLIGHTS = p("highlights", "Highlights", Group.LIGHT)
    val SHADOWS = p("shadows", "Shadows", Group.LIGHT)
    val WHITES = p("whites", "Whites", Group.LIGHT)
    val BLACKS = p("blacks", "Blacks", Group.LIGHT)
    /** How strongly [Settings.lut] applies; it travels with the profile. */
    val LUT_AMOUNT = p("lut.amount", "Amount", Group.LIGHT, 0f, 100f, 100f)

    // Parametric tone curve
    val CURVE_HIGHLIGHTS = p("curveHighlights", "Highlights", Group.CURVE)
    val CURVE_LIGHTS = p("curveLights", "Lights", Group.CURVE)
    val CURVE_DARKS = p("curveDarks", "Darks", Group.CURVE)
    val CURVE_SHADOWS = p("curveShadows", "Shadows", Group.CURVE)

    // White balance and presence
    val TEMPERATURE = p("temperature", "Temp", Group.COLOR, 2000f, 25000f, Float.NaN)
    val TINT = p("tint", "Tint", Group.COLOR, -150f, 150f, Float.NaN)
    val VIBRANCE = p("vibrance", "Vibrance", Group.COLOR)
    val SATURATION = p("saturation", "Saturation", Group.COLOR)

    // Color mixer (HSL): 8 hue bands x hue / saturation / luminance
    val MIX_BANDS = listOf("red" to 0f, "orange" to 30f, "yellow" to 60f, "green" to 120f, "aqua" to 180f, "blue" to 240f, "purple" to 275f, "magenta" to 315f)
    val MIX_HUE = MIX_BANDS.map { (n, _) -> p("mix.$n.hue", n.replaceFirstChar { it.uppercase() }, Group.MIXER) }
    val MIX_SAT = MIX_BANDS.map { (n, _) -> p("mix.$n.sat", n.replaceFirstChar { it.uppercase() }, Group.MIXER) }
    val MIX_LUM = MIX_BANDS.map { (n, _) -> p("mix.$n.lum", n.replaceFirstChar { it.uppercase() }, Group.MIXER) }

    // Color grading: shadows, midtones, highlights, global
    val GRADE_ZONES = listOf("shadows", "midtones", "highlights", "global")
    val GRADE_HUE = GRADE_ZONES.map { p("grade.$it.hue", "Hue", Group.GRADING, 0f, 360f) }
    val GRADE_SAT = GRADE_ZONES.map { p("grade.$it.sat", "Saturation", Group.GRADING, 0f, 100f) }
    val GRADE_LUM = GRADE_ZONES.map { p("grade.$it.lum", "Luminance", Group.GRADING) }
    val GRADE_BLENDING = p("grade.blending", "Blending", Group.GRADING, 0f, 100f, 50f)
    val GRADE_BALANCE = p("grade.balance", "Balance", Group.GRADING)

    // Effects
    val TEXTURE = p("texture", "Texture", Group.EFFECTS)
    val CLARITY = p("clarity", "Clarity", Group.EFFECTS)
    val DEHAZE = p("dehaze", "Dehaze", Group.EFFECTS)
    val VIGNETTE = p("vignette", "Vignette", Group.EFFECTS)
    val VIGNETTE_MIDPOINT = p("vignette.midpoint", "Midpoint", Group.EFFECTS, 0f, 100f, 50f)
    val VIGNETTE_FEATHER = p("vignette.feather", "Feather", Group.EFFECTS, 0f, 100f, 50f)
    val VIGNETTE_ROUNDNESS = p("vignette.roundness", "Roundness", Group.EFFECTS)
    val VIGNETTE_HIGHLIGHTS = p("vignette.highlights", "Highlights", Group.EFFECTS, 0f, 100f)
    val GRAIN = p("grain", "Grain", Group.EFFECTS, 0f, 100f)
    val GRAIN_SIZE = p("grain.size", "Size", Group.EFFECTS, 0f, 100f, 25f)
    val GRAIN_ROUGHNESS = p("grain.roughness", "Roughness", Group.EFFECTS, 0f, 100f, 50f)

    // Detail
    val SHARPEN = p("sharpen", "Sharpening", Group.DETAIL, 0f, 150f)
    val SHARPEN_RADIUS = p("sharpen.radius", "Radius", Group.DETAIL, 0.5f, 3f, 1f, 1)
    val SHARPEN_DETAIL = p("sharpen.detail", "Detail", Group.DETAIL, 0f, 100f, 25f)
    val SHARPEN_MASKING = p("sharpen.masking", "Masking", Group.DETAIL, 0f, 100f)
    val NOISE = p("noise", "Noise reduction", Group.DETAIL, 0f, 100f)
    val NOISE_DETAIL = p("noise.detail", "Detail", Group.DETAIL, 0f, 100f, 50f)
    val COLOR_NOISE = p("colorNoise", "Color noise reduction", Group.DETAIL, 0f, 100f)

    // Optics
    val LENS_PROFILE = p("on.lensProfile", "Use lens corrections", Group.OPTICS, 0f, 1f, 1f)
    val REMOVE_CA = p("on.removeCA", "Remove chromatic aberration", Group.OPTICS, 0f, 1f, 1f)
    val LENS_DISTORTION = p("lens.distortion", "Distortion", Group.OPTICS, 0f, 200f, 100f)
    val LENS_VIGNETTING = p("lens.vignetting", "Vignetting", Group.OPTICS, 0f, 200f, 100f)
    val MANUAL_DISTORTION = p("manual.distortion", "Distortion", Group.OPTICS)
    val MANUAL_VIGNETTING = p("manual.vignetting", "Vignetting", Group.OPTICS)
    val MANUAL_VIGNETTING_MIDPOINT = p("manual.vignetting.midpoint", "Midpoint", Group.OPTICS, 0f, 100f, 50f)
    val DEFRINGE = p("defringe", "Defringe", Group.OPTICS, 0f, 100f)

    // Geometry
    val VERTICAL = p("geo.vertical", "Vertical", Group.GEOMETRY)
    val HORIZONTAL = p("geo.horizontal", "Horizontal", Group.GEOMETRY)
    val ROTATE = p("geo.rotate", "Rotate", Group.GEOMETRY, -10f, 10f, decimals = 1)
    val SCALE = p("geo.scale", "Scale", Group.GEOMETRY, 50f, 150f, 100f)
    val OFFSET_X = p("geo.x", "X offset", Group.GEOMETRY)
    val OFFSET_Y = p("geo.y", "Y offset", Group.GEOMETRY)

    // Crop & rotate. The crop rectangle is in 0..1 of the straightened, rotated frame.
    val CROP_LEFT = p("crop.left", "", Group.CROP, 0f, 1f, 0f, 4)
    val CROP_TOP = p("crop.top", "", Group.CROP, 0f, 1f, 0f, 4)
    val CROP_RIGHT = p("crop.right", "", Group.CROP, 0f, 1f, 1f, 4)
    val CROP_BOTTOM = p("crop.bottom", "", Group.CROP, 0f, 1f, 1f, 4)
    val STRAIGHTEN = p("crop.angle", "Straighten", Group.CROP, -45f, 45f, decimals = 1)
    val ROTATE_90 = p("crop.rot90", "", Group.CROP, 0f, 3f)
    val FLIP_H = p("crop.flipH", "", Group.CROP, 0f, 1f)
    val FLIP_V = p("crop.flipV", "", Group.CROP, 0f, 1f)

    val byKey: Map<String, Param> by lazy { all.associateBy { it.key } }
}

/** A tone curve: control points (x, y) in 0..1, sorted by x. */
data class Curve(val points: List<Pair<Float, Float>> = IDENTITY) {
    val isIdentity get() = points == IDENTITY

    fun encode() = points.joinToString(";") { "${it.first},${it.second}" }

    /** Monotone cubic (Fritsch-Carlson) through the points, sampled into [n] values. */
    fun table(n: Int): FloatArray {
        val pts = points.sortedBy { it.first }
        if (pts.size < 2) return FloatArray(n) { it / (n - 1f) }
        val xs = pts.map { it.first.toDouble() }
        val ys = pts.map { it.second.toDouble() }
        val k = pts.size
        val d = DoubleArray(k - 1) { (ys[it + 1] - ys[it]) / maxOf(1e-6, xs[it + 1] - xs[it]) }
        val m = DoubleArray(k)
        m[0] = d[0]; m[k - 1] = d[k - 2]
        for (i in 1 until k - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in 0 until k - 1) {
            if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9) { val t = 3 / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        return FloatArray(n) { j ->
            val x = j / (n - 1.0)
            val v = when {
                x <= xs[0] -> ys[0]
                x >= xs[k - 1] -> ys[k - 1]
                else -> {
                    var i = 0
                    while (i < k - 2 && x > xs[i + 1]) i++
                    val h = xs[i + 1] - xs[i]
                    val t = (x - xs[i]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }
            v.toFloat().coerceIn(0f, 1f)
        }
    }

    companion object {
        val IDENTITY = listOf(0f to 0f, 1f to 1f)
        fun decode(s: String): Curve = runCatching {
            Curve(s.split(';').map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }.sortedBy { it.first })
        }.getOrDefault(Curve())
    }
}

/**
 * One photo's edit. Only values that differ from their default are stored, so two edits are
 * equal exactly when they look the same.
 */
data class Settings(
    val values: Map<String, Float> = emptyMap(),
    val profile: Profile = Profile.COLOR,
    /** Point curves: master, red, green, blue. */
    val curves: List<Curve> = List(4) { Curve() },
    /** Local adjustments, applied in this order on top of the global sliders. */
    val masks: List<Mask> = emptyList(),
    /** A film look from a LUT (see [Luts]), on top of the profile; null for none. */
    val lut: String? = null,
) {
    operator fun get(p: Param): Float = values[p.key] ?: p.default
    fun on(p: Param) = get(p) >= 0.5f

    fun with(p: Param, v: Float): Settings {
        val clamped = if (v.isNaN()) v else v.coerceIn(p.min, p.max)
        return copy(values = if (clamped == p.default || (clamped.isNaN() && p.default.isNaN())) values - p.key else values + (p.key to clamped))
    }

    fun with(vararg pairs: Pair<Param, Float>): Settings = pairs.fold(this) { s, (p, v) -> s.with(p, v) }

    val temperature: Float? get() = values[P.TEMPERATURE.key]
    val tint: Float? get() = values[P.TINT.key]

    fun withWhiteBalance(kelvin: Float?, tint: Float?): Settings = copy(
        values = values - P.TEMPERATURE.key - P.TINT.key +
            listOfNotNull(kelvin?.let { P.TEMPERATURE.key to it }, tint?.let { P.TINT.key to it }),
    )

    fun withCurve(channel: Int, curve: Curve) = copy(curves = curves.toMutableList().also { it[channel] = curve })

    fun withMask(index: Int, mask: Mask) = copy(masks = masks.toMutableList().also { it[index] = mask })

    fun reset(group: Group): Settings = copy(
        values = values.filterKeys { P.byKey[it]?.group != group },
        profile = if (group == Group.LIGHT) Profile.COLOR else profile,
        curves = if (group == Group.CURVE) List(4) { Curve() } else curves,
        lut = if (group == Group.LIGHT) null else lut,
    )

    /** These settings on the plain Color profile with no LUT: what the Light sliders alone did. */
    fun withoutProfile(): Settings = copy(profile = Profile.COLOR, lut = null, values = values - P.LUT_AMOUNT.key)

    /** The look these settings start from: the profile and the LUT with its amount. */
    fun withProfileOf(from: Settings): Settings = copy(
        profile = from.profile, lut = from.lut,
        values = (values - P.LUT_AMOUNT.key) + listOfNotNull(from.values[P.LUT_AMOUNT.key]?.let { P.LUT_AMOUNT.key to it }),
    )

    /** These settings with [groups] taken from [from] (Lightroom's selective paste). */
    fun pasteFrom(from: Settings, groups: Set<Group>): Settings {
        val kept = values.filterKeys { P.byKey[it]?.group !in groups }
        val taken = from.values.filterKeys { P.byKey[it]?.group in groups }
        // Masks belong to one photo's content, so they stay with it.
        return copy(
            values = kept + taken,
            profile = if (Group.LIGHT in groups) from.profile else profile,
            curves = if (Group.CURVE in groups) from.curves else curves,
            lut = if (Group.LIGHT in groups) from.lut else lut,
        )
    }

    fun isDefault(group: Group): Boolean = values.keys.none { P.byKey[it]?.group == group } &&
        (group != Group.CURVE || curves.all { it.isIdentity }) && (group != Group.LIGHT || profile == Profile.COLOR && lut == null)

    val isDefault get() = this == Settings()

    fun encode(): String = buildString {
        append("v=2\n")
        append("profile=${profile.name}\n")
        if (lut != null) append("lut=$lut\n")
        for ((k, v) in values.toSortedMap()) append("$k=$v\n")
        curves.forEachIndexed { i, c -> if (!c.isIdentity) append("curve.$i=${c.encode()}\n") }
        masks.forEachIndexed { i, m -> append("mask.$i=${m.encode()}\n") }
    }

    companion object {
        fun decode(text: String): Settings {
            var profile = Profile.COLOR
            var lut: String? = null
            val values = HashMap<String, Float>()
            val curves = MutableList(4) { Curve() }
            val masks = sortedMapOf<Int, Mask>()
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                when {
                    k == "profile" -> profile = Profile.entries.firstOrNull { it.name == v } ?: Profile.COLOR
                    k == "lut" -> lut = v.takeIf { it.isNotEmpty() }
                    k.startsWith("curve.") && k.length == 7 -> k[6].digitToIntOrNull()?.takeIf { it in 0..3 }?.let { curves[it] = Curve.decode(v) }
                    k.startsWith("mask.") -> k.removePrefix("mask.").toIntOrNull()?.let { i -> Mask.decode(v)?.let { masks[i] = it } }
                    else -> {
                        val p = P.byKey[k] ?: continue
                        val f = v.toFloatOrNull() ?: continue
                        if (f != p.default) values[k] = f
                    }
                }
            }
            return Settings(values, profile, curves, masks.values.toList(), lut)
        }
    }
}
