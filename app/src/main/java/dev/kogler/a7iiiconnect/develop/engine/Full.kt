package dev.kogler.a7iiiconnect.develop.engine

/**
 * A full-resolution photo as the sensor saw it (not yet turned upright), 16 bits per channel,
 * interleaved RGB. Half the memory of a [LinearImage], which matters at 24 MP.
 */
class FullImage(
    val width: Int,
    val height: Int,
    /** Sensor position of the top-left pixel, when this holds only part of the photo. */
    val left: Int = 0,
    val top: Int = 0,
    /** Size of the whole photo on the sensor. */
    val sensorWidth: Int = width,
    val sensorHeight: Int = height,
) {
    val data = ShortArray(width * height * 3)

    fun put(y: Int, row: LinearImage, rowIndex: Int, fromX: Int = 0) {
        val o = y * width * 3
        val s = rowIndex * row.width + fromX
        for (x in 0 until width) {
            data[o + 3 * x] = q(row.r[s + x]); data[o + 3 * x + 1] = q(row.g[s + x]); data[o + 3 * x + 2] = q(row.b[s + x])
        }
    }

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort()

    companion object {
        /** Full MHC demosaic of the whole active area. */
        fun demosaic(raw: RawImage, progress: (Float) -> Unit = {}): FullImage {
            val img = FullImage(raw.width, raw.height)
            if (nativeDemosaic(raw, 0, 0, raw.width, raw.height, img, progress)) return img
            val band = 128
            val buf = LinearImage(raw.width, band)
            var y = 0
            while (y < raw.height) {
                val y1 = minOf(raw.height, y + band)
                Demosaic.fullRows(raw, y, y1, buf)
                for (i in 0 until y1 - y) img.put(y + i, buf, i)
                y = y1
                progress(y.toFloat() / raw.height)
            }
            return img
        }

        /** Full MHC demosaic of sensor rows [y0, y1) and columns [x0, x1) only (sharp zoom). */
        fun demosaic(raw: RawImage, x0: Int, y0: Int, x1: Int, y1: Int): FullImage {
            val img = FullImage(x1 - x0, y1 - y0, x0, y0, raw.width, raw.height)
            if (nativeDemosaic(raw, x0, y0, x1, y1, img) {}) return img
            val band = 128
            val buf = LinearImage(raw.width, band)
            var y = y0
            while (y < y1) {
                val e = minOf(y1, y + band)
                Demosaic.fullRows(raw, y, e, buf)
                for (i in 0 until e - y) img.put(y - y0 + i, buf, i, x0)
                y = e
            }
            return img
        }

        /** The Rust demosaic, in bands for [progress]; false when it isn't available. */
        private fun nativeDemosaic(raw: RawImage, x0: Int, y0: Int, x1: Int, y1: Int, img: FullImage, progress: (Float) -> Unit): Boolean {
            if (!Native.available) return false
            val info = raw.info
            val scale = FloatArray(3) { c -> info.asShot[c] / (info.white - info.black[c]).toFloat() }
            val band = 512
            var y = y0
            while (y < y1) {
                val e = minOf(y1, y + band)
                val ok = Native.demosaic(
                    raw.data, raw.rawWidth, raw.cfa, raw.left, raw.top, raw.width, raw.height,
                    info.black, scale, x0, y, x1, e, img.data, (y - y0) * img.width * 3,
                )
                if (!ok) return false
                y = e
                progress((y - y0).toFloat() / (y1 - y0))
            }
            return true
        }

        /** From 8-bit sRGB pixels (a JPEG), one band at a time. */
        fun fromArgb(width: Int, height: Int, rows: (y0: Int, count: Int, out: IntArray) -> Unit): FullImage {
            val img = FullImage(width, height)
            val band = 128
            val px = IntArray(width * band)
            var y = 0
            while (y < height) {
                val n = minOf(band, height - y)
                rows(y, n, px)
                val lin = LinearImage.fromArgb(px, width, n)
                for (i in 0 until n) img.put(y + i, lin, i)
                y += n
            }
            return img
        }
    }
}

/**
 * Samples a sensor-oriented [FullImage] through EXIF [orientation] and a [Geometry] at full
 * resolution (scale 1), for export.
 */
class FullSampler(
    private val img: FullImage,
    private val orientation: Int,
    private val geom: Geometry,
    region: Region? = null,
) : Sampler {
    override val scale = region?.scale ?: 1.0
    override val frameWidth = maxOf(1, (geom.outW * scale).toInt())
    override val frameHeight = maxOf(1, (geom.outH * scale).toInt())
    override val width = region?.width ?: frameWidth
    override val height = region?.height ?: frameHeight
    override val originX = region?.x0 ?: 0
    override val originY = region?.y0 ?: 0

    /** The geometry's map every [GRID_STEP] output pixels, for the Rust render. */
    private val grid: Pair<FloatArray, FloatArray?> by lazy {
        val nx = (width - 1) / GRID_STEP + 2
        val ny = (height - 1) / GRID_STEP + 2
        val map = FloatArray(nx * ny * 6)
        val vig = if (geom.hasVignetteCorrection) FloatArray(nx * ny) else null
        parallelRows(ny) { j ->
            val m = DoubleArray(6)
            val oy = (originY + j * GRID_STEP + 0.5) / scale
            for (i in 0 until nx) {
                geom.map((originX + i * GRID_STEP + 0.5) / scale, oy, m)
                val n = j * nx + i
                for (k in 0..5) map[n * 6 + k] = m[k].toFloat()
                if (vig != null) vig[n] = geom.vignetteGain(m[2], m[3])
            }
        }
        map to vig
    }

    /** Renders rows [y0, y1) with the Rust engine into [out]; false when it isn't available. */
    fun renderNative(look: Look, guide: Guide?, out: IntArray, y0: Int, y1: Int): Boolean {
        if (!Native.available) return false
        val (map, vig) = grid
        val masks = look.nativeMasks()
        val subject = if (masks != null) look.subjectMap else null
        val nx = (width - 1) / GRID_STEP + 2
        val ny = (height - 1) / GRID_STEP + 2
        return Native.render(
            look.nativeParams(), look.toneTable, look.nativeCurves(),
            guide?.let { intArrayOf(it.width, it.height) }, guide?.base, guide?.medium, guide?.dark, guide?.air,
            img.data,
            doubleArrayOf(img.width.toDouble(), img.height.toDouble(), img.left.toDouble(), img.top.toDouble(),
                img.sensorWidth.toDouble(), img.sensorHeight.toDouble(), orientation.toDouble()),
            map, floatArrayOf(originX.toFloat(), originY.toFloat(), nx.toFloat(), ny.toFloat(), GRID_STEP.toFloat()), vig,
            doubleArrayOf(width.toDouble(), height.toDouble(), frameWidth.toDouble(), frameHeight.toDouble(),
                originX.toDouble(), originY.toDouble(), scale),
            masks, subject?.let { intArrayOf(it.width, it.height) }, subject?.data,
            y0, y1, out,
        )
    }

    override fun fill(y0: Int, rows: Int, out: LinearImage, uv: FloatArray?) {
        val vig = geom.hasVignetteCorrection
        parallelRows(rows) { i ->
            val y = (originY + y0 + i).coerceIn(0, frameHeight - 1)
            val o = i * width
            val m = DoubleArray(6)
            val oy = (y + 0.5) / scale
            for (x in 0 until width) {
                geom.map((originX + x + 0.5) / scale, oy, m)
                if (uv != null) { uv[(o + x) * 2] = m[2].toFloat(); uv[(o + x) * 2 + 1] = m[3].toFloat() }
                out.r[o + x] = sample(0, m[0], m[1])
                out.g[o + x] = sample(1, m[2], m[3])
                out.b[o + x] = sample(2, m[4], m[5])
                if (vig) {
                    val g = geom.vignetteGain(m[2], m[3])
                    out.r[o + x] *= g; out.g[o + x] *= g; out.b[o + x] *= g
                }
            }
        }
    }

    private companion object {
        const val GRID_STEP = 8
    }

    /** Channel [c] at upright position (ux, uy), bilinear, black outside the photo. */
    private fun sample(c: Int, ux: Double, uy: Double): Float {
        val W = img.sensorWidth.toDouble(); val H = img.sensorHeight.toDouble()
        // Upright -> sensor, the continuous form of the EXIF orientation definitions.
        val sx: Double; val sy: Double
        when (orientation) {
            2 -> { sx = W - ux; sy = uy }
            3 -> { sx = W - ux; sy = H - uy }
            4 -> { sx = ux; sy = H - uy }
            5 -> { sx = uy; sy = ux }
            6 -> { sx = uy; sy = H - ux }
            7 -> { sx = W - uy; sy = H - ux }
            8 -> { sx = W - uy; sy = ux }
            else -> { sx = ux; sy = uy }
        }
        val x = sx - 0.5 - img.left; val y = sy - 0.5 - img.top
        val w = img.width; val h = img.height
        if (x < -1 || y < -1 || x > w || y > h) return 0f
        val xc = x.coerceIn(0.0, w - 1.0); val yc = y.coerceIn(0.0, h - 1.0)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val fx = (xc - x0).toFloat(); val fy = (yc - y0).toFloat()
        val d = img.data
        fun at(px: Int, py: Int) = (d[(py * w + px) * 3 + c].toInt() and 0xffff) * (1f / 65535f)
        val a = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val b = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return a + (b - a) * fy
    }
}

/** Upright position -> sensor position, per EXIF [orientation]. */
fun toSensor(orientation: Int, sensorW: Int, sensorH: Int, ux: Double, uy: Double): Pair<Double, Double> {
    val W = sensorW.toDouble(); val H = sensorH.toDouble()
    return when (orientation) {
        2 -> (W - ux) to uy
        3 -> (W - ux) to (H - uy)
        4 -> ux to (H - uy)
        5 -> uy to ux
        6 -> uy to (H - ux)
        7 -> (W - uy) to (H - ux)
        8 -> (W - uy) to ux
        else -> ux to uy
    }
}

/**
 * The sensor rectangle (x0, y0, x1, y1) a [region] of the output needs, with room for the
 * band margins and lens shifts.
 */
fun sensorBounds(geom: Geometry, orientation: Int, sensorW: Int, sensorH: Int, region: Region, pad: Int): IntArray {
    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    val m = DoubleArray(6)
    val steps = 8
    for (j in 0..steps) for (i in 0..steps) {
        val ox = (region.x0 + region.width * i / steps.toDouble()) / region.scale
        val oy = (region.y0 + region.height * j / steps.toDouble()) / region.scale
        geom.map(ox, oy, m)
        for (c in 0..2) {
            val (sx, sy) = toSensor(orientation, sensorW, sensorH, m[2 * c], m[2 * c + 1])
            x0 = minOf(x0, sx); x1 = maxOf(x1, sx); y0 = minOf(y0, sy); y1 = maxOf(y1, sy)
        }
    }
    return intArrayOf(
        (x0.toInt() - pad).coerceIn(0, sensorW - 1), (y0.toInt() - pad).coerceIn(0, sensorH - 1),
        (x1.toInt() + pad + 1).coerceIn(1, sensorW), (y1.toInt() + pad + 1).coerceIn(1, sensorH),
    )
}

/** Renders [sampler] through [look] in bands; [sink] receives each finished band of ARGB rows. */
fun renderBands(look: Look, sampler: Sampler, guide: Guide?, band: Int = 128, sink: (y: Int, rows: Int, px: IntArray) -> Unit) {
    val px = IntArray(sampler.width * band)
    var y = 0
    while (y < sampler.height) {
        val y1 = minOf(sampler.height, y + band)
        if (!(sampler is FullSampler && sampler.renderNative(look, guide, px, y, y1))) look.render(sampler, guide, px, y, y1)
        sink(y, y1 - y, px)
        y = y1
    }
}
