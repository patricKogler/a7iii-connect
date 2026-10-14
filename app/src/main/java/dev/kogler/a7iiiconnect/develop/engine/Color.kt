package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.abs
import kotlin.math.hypot

/** Row-major 3x3 matrix helpers. */
object Mat3 {
    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-12) { "singular matrix" }
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    fun diag(x: Double, y: Double, z: Double) = doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, z)
}

/**
 * Colour handling for one source: how its RGB relates to CIE XYZ, how to turn a white balance in
 * Kelvin and tint into channel multipliers, and the matrix from white-balanced RGB to linear sRGB.
 * The approach follows dcraw/LibRaw (one D65 matrix) with Adobe-style Kelvin and tint.
 */
class CameraColor(private val xyzToCam: DoubleArray) {

    private val camToXyz = Mat3.invert(xyzToCam)

    /** White-balanced camera RGB -> linear sRGB. Maps (1,1,1) to (1,1,1). */
    val rgbFromCam: DoubleArray = run {
        val camRgb = Mat3.mul(xyzToCam, SRGB_TO_XYZ)
        for (r in 0..2) {
            val sum = camRgb[r * 3] + camRgb[r * 3 + 1] + camRgb[r * 3 + 2]
            for (c in 0..2) camRgb[r * 3 + c] /= sum
        }
        Mat3.invert(camRgb)
    }

    /** Channel multipliers (G = 1) that make a light of [kelvin] and [tint] neutral. */
    fun multipliers(kelvin: Double, tint: Double): DoubleArray {
        val (u, v) = uvOf(kelvin, tint)
        val d = 2 * u - 8 * v + 4
        val x = 3 * u / d
        val y = 2 * v / d
        val neutral = Mat3.apply(xyzToCam, doubleArrayOf(x / y, 1.0, (1 - x - y) / y))
        return doubleArrayOf(neutral[1] / neutral[0], 1.0, neutral[1] / neutral[2])
    }

    /** The Kelvin and tint that [mul] (G = 1) neutralises. */
    fun temperatureOf(mul: FloatArray): Pair<Double, Double> {
        val xyz = Mat3.apply(camToXyz, doubleArrayOf(1.0 / mul[0], 1.0 / mul[1], 1.0 / mul[2]))
        val s = xyz[0] + xyz[1] + xyz[2]
        val x = xyz[0] / s
        val y = xyz[1] / s
        val d = -2 * x + 12 * y + 3
        val u = 4 * x / d
        val v = 6 * y / d
        // Coarse scan over mireds, then refine.
        var best = 100.0
        var bestDist = Double.MAX_VALUE
        var lo = 1e6 / MAX_K
        var hi = 1e6 / MIN_K
        repeat(4) {
            val step = (hi - lo) / 64
            for (k in 0..64) {
                val mired = lo + step * k
                val (lu, lv) = locus(1e6 / mired)
                val dist = hypot(u - lu, v - lv)
                if (dist < bestDist) { bestDist = dist; best = mired }
            }
            lo = maxOf(1e6 / MAX_K, best - step)
            hi = minOf(1e6 / MIN_K, best + step)
        }
        val kelvin = 1e6 / best
        val (lu, lv) = locus(kelvin)
        val (nu, nv) = greenNormal(kelvin)
        val tint = ((u - lu) * nu + (v - lv) * nv) * TINT_SCALE
        return kelvin to tint
    }

    companion object {
        const val MIN_K = 2000.0
        const val MAX_K = 25000.0
        private const val TINT_SCALE = 3000.0

        val SRGB_TO_XYZ = doubleArrayOf(
            0.4124564, 0.3575761, 0.1804375,
            0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041,
        )
        val XYZ_TO_SRGB = Mat3.invert(SRGB_TO_XYZ)

        /** For JPEGs: the "camera" is sRGB itself. */
        val SRGB = CameraColor(XYZ_TO_SRGB)

        /** Adobe's D65 XYZ->camera matrices (x10000), as published in dcraw/LibRaw. */
        private val MATRICES = mapOf(
            "ILCE-7M3" to intArrayOf(7374, -2389, -551, -5435, 13162, 2519, -1006, 1795, 6552),
            "ILCE-7M4" to intArrayOf(7460, -2365, -588, -5687, 13442, 2474, -624, 1156, 6584),
            "ILCE-7RM3" to intArrayOf(6640, -1847, -503, -5238, 13010, 2474, -993, 1673, 6527),
            "ILCE-9" to intArrayOf(6389, -1703, -378, -4562, 12265, 2587, -670, 1489, 6550),
        )

        fun forCamera(model: String): CameraColor {
            val m = MATRICES[model.trim()] ?: MATRICES.getValue("ILCE-7M3")
            return CameraColor(DoubleArray(9) { m[it] / 10000.0 })
        }

        /** CIE 1960 uv of the Planckian locus (Kim et al. approximation of x, y). */
        fun locus(kelvin: Double): Pair<Double, Double> {
            val t = kelvin.coerceIn(1667.0, 25000.0)
            val t2 = t * t; val t3 = t2 * t
            val x = if (t <= 4000) -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
            else -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
            val x2 = x * x; val x3 = x2 * x
            val y = when {
                t <= 2222 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
            }
            val d = -2 * x + 12 * y + 3
            return (4 * x / d) to (6 * y / d)
        }

        /** Unit normal to the locus at [kelvin], pointing to green (+v). */
        private fun greenNormal(kelvin: Double): Pair<Double, Double> {
            val m = 1e6 / kelvin
            val (u1, v1) = locus(1e6 / (m + 1))
            val (u2, v2) = locus(1e6 / maxOf(1.0, m - 1))
            val du = u2 - u1; val dv = v2 - v1
            val len = hypot(du, dv)
            var nu = -dv / len; var nv = du / len
            if (nv < 0) { nu = -nu; nv = -nv }
            return nu to nv
        }

        /** Positive tint moves the assumed light toward green, which makes the photo more magenta. */
        fun uvOf(kelvin: Double, tint: Double): Pair<Double, Double> {
            val (u, v) = locus(kelvin)
            val (nu, nv) = greenNormal(kelvin)
            return (u + nu * tint / TINT_SCALE) to (v + nv * tint / TINT_SCALE)
        }
    }
}
