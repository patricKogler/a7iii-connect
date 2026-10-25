package dev.kogler.a7iiiconnect.develop.engine


/**
 * The base look applied before the sliders, like Lightroom's profiles. For raws it sets the
 * filmic curve's contrast (its toe and shoulder) and a saturation boost; JPEGs are already
 * rendered by the camera, so only Monochrome changes them.
 */
enum class Profile(val label: String, val contrast: Double, val saturation: Float) {
    COLOR("Color", 1.45, 1.10f),
    VIVID("Vivid", 1.65, 1.28f),
    NEUTRAL("Neutral", 1.2, 1.0f),
    MONOCHROME("Monochrome", 1.5, 0f),
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
    /** Upright, scene-linear preview pixels. */
    val image: LinearImage,
    val color: CameraColor,
    /** Multipliers already applied to [image] (as-shot WB for raws, 1,1,1 for JPEGs). */
    val baked: FloatArray,
    val isRaw: Boolean,
    /** Full-size upright dimensions; geometry works in these units. */
    val fullWidth: Int = image.width,
    val fullHeight: Int = image.height,
    val lens: LensData? = null,
    /** The subject and other maps masks use; shared with [withImage] copies. */
    val maps: MaskMaps = MaskMaps(),
    /** Stops the render adds on top of Exposure (a DNG's BaselineExposure). */
    val baseline: Double = 0.0,
) {
    /** The as-shot Kelvin and tint, shown when the photo uses "As Shot". */
    val asShot: Pair<Double, Double> by lazy {
        if (isRaw) color.temperatureOf(baked) else 6500.0 to 0.0
    }

    /** Same photo at a different preview size. */
    fun withImage(img: LinearImage) = DevelopSource(img, color, baked, isRaw, fullWidth, fullHeight, lens, maps, baseline)
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
