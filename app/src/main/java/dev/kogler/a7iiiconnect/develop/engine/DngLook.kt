package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.exp

/** How a phone's DNG is rendered toward its own JPEG: its tone curve, black floor and highlight shoulder. */
object DngLook {
    /** Phones' own renders sit about a third of a stop above their DNG's baseline. */
    const val LIFT = 0.35

    /**
     * The darkest level a photo really has (flare and the sensor's floor): the 0.5th percentile
     * of its brightness, capped so a low-key shot keeps its shadows.
     */
    fun blackFloor(img: LinearImage): Float {
        val n = img.r.size
        if (n == 0) return 0f
        val step = maxOf(1, n / 50_000)
        val v = FloatArray((n + step - 1) / step) { val i = it * step; (img.r[i] + img.g[i] + img.b[i]) / 3f }
        v.sort()
        return v[v.size / 200].coerceIn(0f, 0.01f)
    }

    /** A DNG ProfileToneCurve (x, y pairs sorted by x) at [x], linear in between. */
    fun curve(c: FloatArray, x: Double): Double {
        val n = c.size / 2
        var i = 0
        while (i < n && c[i * 2] < x) i++
        if (i == 0) return c[1].toDouble()
        if (i >= n) return c[n * 2 - 1].toDouble()
        val x0 = c[i * 2 - 2]; val y0 = c[i * 2 - 1]; val x1 = c[i * 2]; val y1 = c[i * 2 + 1]
        val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
        return y0 + (y1 - y0) * t
    }

    /**
     * Fits scene values up to [max] into 0..1 for a camera's 0..1 tone curve: unchanged below a
     * knee, then an exponential shoulder that leaves the knee at the same slope and reaches 1 at
     * [max], so lifted highlights keep their gradation instead of clipping.
     */
    class Shoulder(private val max: Double) {
        private val a: Double = if (max <= 1.0) 0.0 else {
            // Slope at the knee: (1 - K) a / (1 - e^-a) / (max - K) = 1. The left side grows with a.
            val target = (max - KNEE) / (1 - KNEE)
            var lo = 1e-6; var hi = 60.0
            repeat(60) {
                val m = (lo + hi) / 2
                if (m / (1 - exp(-m)) < target) lo = m else hi = m
            }
            (lo + hi) / 2
        }

        fun apply(x: Double): Double {
            if (a == 0.0 || x <= KNEE) return x.coerceIn(0.0, 1.0)
            val u = ((x - KNEE) / (max - KNEE)).coerceAtMost(1.0)
            return KNEE + (1 - KNEE) * (1 - exp(-a * u)) / (1 - exp(-a))
        }

        companion object { const val KNEE = 0.6 }
    }
}
