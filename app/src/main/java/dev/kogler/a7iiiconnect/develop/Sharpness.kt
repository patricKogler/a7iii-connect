package dev.kogler.a7iiiconnect.develop

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How sharp the sharpest part of a picture is. The photo is cut into tiles and each tile's detail
 * is measured (the energy of a Laplacian, after a light blur so sensor noise counts less); the
 * score is the average of the best tiles. Looking only at the best tiles means a shallow depth of
 * field doesn't count against a shot: what matters is whether the part in focus is crisp.
 *
 * Scores compare shots of the same scene (a burst); they mean little across different scenes.
 */
object Sharpness {
    /** Tile side in pixels. */
    private const val TILE = 24
    /** Share of tiles that make up "the sharpest part". */
    private const val BEST = 0.08f

    /**
     * Grey 0..1 pixels, row by row. [region] (left, top, right, bottom in pixels) limits the
     * measurement to a face, say; null measures everything.
     */
    fun of(grey: FloatArray, w: Int, h: Int, region: IntArray? = null): Float {
        val l = max(1, region?.get(0) ?: 1)
        val t = max(1, region?.get(1) ?: 1)
        val r = min(w - 1, region?.get(2) ?: (w - 1))
        val b = min(h - 1, region?.get(3) ?: (h - 1))
        if (r - l < 3 || b - t < 3) return 0f
        // A face is small: make its tiles smaller too, so there are enough of them.
        val tile = min(TILE, max(4, min(r - l, b - t) / 4))
        val cols = (r - l + tile - 1) / tile
        val rows = (b - t + tile - 1) / tile
        val sums = DoubleArray(cols * rows)
        val counts = IntArray(cols * rows)
        for (y in t until b) {
            if (y < 2 || y >= h - 2) continue
            for (x in l until r) {
                if (x < 2 || x >= w - 2) continue
                val c = blur(grey, w, x, y)
                val lap = 4 * c - blur(grey, w, x - 1, y) - blur(grey, w, x + 1, y) - blur(grey, w, x, y - 1) - blur(grey, w, x, y + 1)
                val i = ((y - t) / tile) * cols + (x - l) / tile
                sums[i] += (lap * lap).toDouble()
                counts[i]++
            }
        }
        val energies = (sums.indices).filter { counts[it] > 0 }.map { sums[it] / counts[it] }.sortedDescending()
        if (energies.isEmpty()) return 0f
        val n = max(1, (energies.size * BEST).toInt())
        // Square root, so twice as blurry reads about half the score; scaled to roughly 0..100.
        return (sqrt(energies.take(n).average()) * 1000).toFloat()
    }

    /** 3x3 binomial blur at one pixel. */
    private fun blur(g: FloatArray, w: Int, x: Int, y: Int): Float {
        val i = y * w + x
        return (4 * g[i] + 2 * (g[i - 1] + g[i + 1] + g[i - w] + g[i + w]) + g[i - w - 1] + g[i - w + 1] + g[i + w - 1] + g[i + w + 1]) / 16f
    }

    /** Grey 0..1 from ARGB pixels. */
    fun grey(px: IntArray): FloatArray = FloatArray(px.size) {
        val c = px[it]
        (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }
}
