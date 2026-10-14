package dev.kogler.a7iiiconnect.develop.engine

/** Separable box blurs and the guided filter, the building blocks of every local adjustment. */
object Filters {

    /** Box blur of radius [r] (window 2r+1) with clamped edges; [dst] may equal [src]. */
    fun box(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { if (dst !== src) src.copyInto(dst); return }
        val tmp = FloatArray(w * h)
        parallelRows(h) { y ->
            val o = y * w
            val inv = 1f / (2 * r + 1)
            var sum = 0f
            for (k in -r..r) sum += src[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[o + x] = sum * inv
                sum += src[o + minOf(w - 1, x + r + 1)] - src[o + maxOf(0, x - r)]
            }
        }
        // Columns in blocks so each thread walks memory row by row.
        val block = 64
        parallelRows((w + block - 1) / block) { b ->
            val x0 = b * block
            val x1 = minOf(w, x0 + block)
            val n = x1 - x0
            val sums = FloatArray(n)
            val inv = 1f / (2 * r + 1)
            for (k in -r..r) {
                val row = k.coerceIn(0, h - 1) * w
                for (i in 0 until n) sums[i] += tmp[row + x0 + i]
            }
            for (y in 0 until h) {
                val o = y * w
                val add = minOf(h - 1, y + r + 1) * w
                val sub = maxOf(0, y - r) * w
                for (i in 0 until n) {
                    dst[o + x0 + i] = sums[i] * inv
                    sums[i] += tmp[add + x0 + i] - tmp[sub + x0 + i]
                }
            }
        }
    }

    /** Three box passes: close to a Gaussian with sigma about r. */
    fun blur(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Float) {
        val b = maxOf(0, (r * 0.95f).toInt())
        if (b == 0) { if (dst !== src) src.copyInto(dst); return }
        box(src, dst, w, h, b)
        box(dst, dst, w, h, b)
        box(dst, dst, w, h, b)
    }

    /**
     * Self-guided filter (He et al.): smooths [src] while keeping edges whose local variance is
     * well above [eps]. Used for halo-free base layers and edge-aware noise reduction.
     */
    fun guided(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, eps: Float) {
        val n = w * h
        val mean = FloatArray(n)
        val sq = FloatArray(n)
        box(src, mean, w, h, r)
        for (i in 0 until n) sq[i] = src[i] * src[i]
        box(sq, sq, w, h, r)
        // a and b in place of sq and mean.
        for (i in 0 until n) {
            val m = mean[i]
            val v = sq[i] - m * m
            val a = v / (v + eps)
            sq[i] = a
            mean[i] = m - a * m
        }
        box(sq, sq, w, h, r)
        box(mean, mean, w, h, r)
        for (i in 0 until n) dst[i] = sq[i] * src[i] + mean[i]
    }
}
