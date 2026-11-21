package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.pow

/**
 * A DNG's colour as the spec describes it: up to two XYZ-to-camera matrices, each for one light,
 * mixed by the inverse of the shot's colour temperature, and the as-shot white given either as a
 * camera neutral or as a CIE xy white.
 */
object DngColor {
    class Calibration(val matrix: DoubleArray?, val calibration: DoubleArray?, val illuminant: Int?) {
        val kelvin get() = illuminant?.let { KELVIN[it] }
    }

    /** The XYZ-to-camera matrix for this shot and its as-shot multipliers (R, G, B with G = 1). */
    fun resolve(
        c1: Calibration, c2: Calibration, analogBalance: DoubleArray?, neutral: DoubleArray?, whiteXy: DoubleArray?,
    ): Pair<DoubleArray?, FloatArray> {
        val ab = analogBalance?.let { Mat3.diag(it[0], it[1], it[2]) }
        fun one(c: Calibration) = c.matrix?.let { m -> c.calibration?.let { Mat3.mul(it, m) } ?: m }
        val m1 = one(c1); val m2 = one(c2)
        val k1 = c1.kelvin; val k2 = c2.kelvin

        // Weight of the first matrix for a light of [kelvin], linear in mireds between the two.
        fun xyzToCam(kelvin: Double): DoubleArray? {
            val m = when {
                m1 == null -> m2
                m2 == null || k1 == null || k2 == null || k1 == k2 -> if (m2 != null && k2 == D65_K) m2 else m1
                else -> {
                    val lo = minOf(k1, k2); val hi = maxOf(k1, k2)
                    val t = kelvin.coerceIn(lo, hi)
                    val w = (1 / t - 1 / hi) / (1 / lo - 1 / hi)
                    val (cool, warm) = if (k1 < k2) m2 to m1 else m1 to m2
                    DoubleArray(9) { warm[it] * w + cool[it] * (1 - w) }
                }
            } ?: return null
            return ab?.let { Mat3.mul(it, m) } ?: m
        }

        fun multipliers(n: DoubleArray) = floatArrayOf((n[1] / n[0]).toFloat(), 1f, (n[1] / n[2]).toFloat())

        if (neutral != null) {
            // The light the neutral stands for decides the matrix, which decides the light: a few rounds settle it.
            var kelvin = D65_K
            var m = xyzToCam(kelvin) ?: return null to multipliers(neutral)
            repeat(4) {
                val xyz = Mat3.apply(Mat3.invert(m), neutral)
                val s = xyz[0] + xyz[1] + xyz[2]
                if (s > 0) kelvin = cct(xyz[0] / s, xyz[1] / s)
                m = xyzToCam(kelvin) ?: m
            }
            return m to multipliers(neutral)
        }
        if (whiteXy != null) {
            val (x, y) = whiteXy[0] to whiteXy[1]
            val m = xyzToCam(cct(x, y)) ?: return null to floatArrayOf(2f, 1f, 1.6f)
            val n = Mat3.apply(m, doubleArrayOf(x / y, 1.0, (1 - x - y) / y))
            if (n.all { it > 0 }) return m to multipliers(n)
        }
        val m = xyzToCam(D65_K) ?: return null to floatArrayOf(2f, 1f, 1.6f)
        return m to CameraColor(m).multipliers(6500.0, 0.0).let { v -> FloatArray(3) { v[it].toFloat() } }
    }

    /** Correlated colour temperature of a CIE xy white (McCamy), kept to a sensible range. */
    fun cct(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return (449 * n.pow(3) + 3525 * n * n + 6823.3 * n + 5520.33).coerceIn(CameraColor.MIN_K, CameraColor.MAX_K)
    }

    private const val D65_K = 6504.0

    /** EXIF LightSource codes to Kelvin. */
    private val KELVIN = mapOf(
        1 to 5500.0, 2 to 4200.0, 3 to 2850.0, 4 to 5500.0, 9 to 5500.0, 10 to 6500.0, 11 to 7500.0,
        12 to 6430.0, 13 to 5000.0, 14 to 4150.0, 15 to 3525.0, 17 to 2856.0, 18 to 4874.0, 19 to 6774.0,
        20 to 5503.0, 21 to D65_K, 22 to 7504.0, 23 to 5003.0, 24 to 3200.0,
    )
}
