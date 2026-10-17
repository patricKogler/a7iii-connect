package dev.kogler.a7iiiconnect.duo

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How alike two renders of the same photo look, 0..100, for Copycat. Both are ARGB pixels of the
 * same size. The score follows the average colour difference a person would see (CIE76 ΔE in
 * Lab): 100 is identical, each ΔE costs two points, so a difference you can just about notice
 * (ΔE ≈ 2) still scores about 96 and a clearly different look ends up in the 50s or below.
 */
object Likeness {
    fun score(a: IntArray, b: IntArray): Int {
        require(a.size == b.size && a.isNotEmpty())
        var sum = 0.0
        for (i in a.indices) sum += deltaE(a[i], b[i])
        return (100 - 2 * sum / a.size).coerceIn(0.0, 100.0).roundToInt()
    }

    private fun deltaE(p: Int, q: Int): Double {
        val x = lab(p)
        val y = lab(q)
        val dl = x[0] - y[0]
        val da = x[1] - y[1]
        val db = x[2] - y[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** sRGB pixel to CIE Lab (D65). */
    private fun lab(argb: Int): DoubleArray {
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
}
