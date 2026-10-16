package dev.kogler.a7iiiconnect.develop

import dev.kogler.a7iiiconnect.develop.engine.Filters
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The search itself, on plain pixels (so it can be tried on the computer). */
object SkyDetect {
    /** Share of the picture the sky must cover to count. */
    private const val MIN_AREA = 0.03f
    /** Biggest colour change between neighbouring sky pixels (0..1 per channel, summed). */
    private const val STEP = 0.05f

    /** A 0..1 sky map for ARGB [px] ([w] x [h]), or null when there's no sky along the top. */
    fun find(px: IntArray, w: Int, h: Int): MaskImage? {
        val n = w * h
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val lum = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = (c shr 16 and 0xFF) / 255f; g[i] = (c shr 8 and 0xFF) / 255f; b[i] = (c and 0xFF) / 255f
            lum[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
        }
        val texture = texture(lum, w, h)
        // Sky colours: blue (down to dusk), or a pale grey to white (overcast, haze, clouds).
        val blue = BooleanArray(n) { i -> b[i] > r[i] + 0.02f && b[i] >= g[i] - 0.03f && lum[i] > 0.06f }
        val skyish = BooleanArray(n) { i ->
            val mx = max(r[i], max(g[i], b[i])); val mn = min(r[i], min(g[i], b[i]))
            blue[i] || (mx - mn < 0.12f && lum[i] > 0.5f)
        }

        // How smooth is smooth: judged from the top tenth of the picture.
        val band = max(2, h / 10)
        val top = FloatArray(band * w) { texture[it] }.sorted()
        val calm = top[(top.size * 0.3f).toInt()]
        if (calm > 0.03f) return null
        val limit = (calm * 3f).coerceIn(0.01f, 0.05f)

        // Grow from the smooth sky-coloured pixels of the top rows. Blue ones when there are
        // enough, so a white wall reaching the top doesn't pass for an overcast sky.
        val sky = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0; var tail = 0
        val seedRows = min(h, max(2, h / 40))
        val blueSeeds = (0 until seedRows * w).count { texture[it] < limit && blue[it] }
        val seedOk = if (blueSeeds >= seedRows * w / 10) blue else skyish
        for (i in 0 until seedRows * w) {
            if (texture[i] < limit && seedOk[i] && !sky[i]) { sky[i] = true; queue[tail++] = i }
        }
        fun close(i: Int, j: Int) = abs(r[i] - r[j]) + abs(g[i] - g[j]) + abs(b[i] - b[j]) < STEP
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            fun visit(j: Int) {
                if (!sky[j] && texture[j] < limit && skyish[j] && close(i, j)) { sky[j] = true; queue[tail++] = j }
            }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        if (tail < n * MIN_AREA) return null

        // Patches of the same sky seen through branches or between buildings (smooth, and close in
        // colour to the sky found at that height).
        val rows = 32
        val sums = Array(rows) { FloatArray(4) }
        var lowest = 0
        for (i in 0 until n) if (sky[i]) {
            val s = sums[i / w * rows / h]
            s[0] += r[i]; s[1] += g[i]; s[2] += b[i]; s[3] += 1f
            lowest = max(lowest, i / w)
        }
        // Rows with no sky borrow the nearest row above that has some.
        var last: FloatArray? = null
        for (k in 0 until rows) {
            val s = sums[k]
            if (s[3] > 0) { for (c in 0..2) s[c] /= s[3]; last = s } else last?.let { for (c in 0..3) s[c] = it[c] }
        }
        val mask = FloatArray(n)
        for (i in 0 until n) {
            if (sky[i]) { mask[i] = 1f; continue }
            val y = i / w
            if (y > lowest || texture[i] >= limit * 1.5f || !skyish[i]) continue
            val s = sums[y * rows / h]
            if (s[3] <= 0f) continue
            val d = sqrt((r[i] - s[0]) * (r[i] - s[0]) + (g[i] - s[1]) * (g[i] - s[1]) + (b[i] - s[2]) * (b[i] - s[2]))
            if (d < 0.06f) mask[i] = 1f
        }

        fillHoles(mask, w, h, (n * 0.01f).toInt())

        // Snap to the photo's edges; luminance guides the mask.
        val fine = FloatArray(n)
        Filters.guidedBy(lum, mask, fine, w, h, max(2, max(w, h) / 200), 0.0005f)
        for (i in fine.indices) fine[i] = fine[i].coerceIn(0f, 1f)
        return MaskImage(w, h, fine)
    }

    /** Small bits inside the sky that don't touch the picture's edge (birds, wires, a stray cloud edge) become sky. */
    private fun fillHoles(mask: FloatArray, w: Int, h: Int, maxArea: Int) {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        for (start in mask.indices) {
            if (seen[start] || mask[start] > 0.5f) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var edge = false
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true
                fun visit(j: Int) { if (!seen[j] && mask[j] <= 0.5f) { seen[j] = true; queue[tail++] = j } }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (!edge && tail <= maxArea) for (k in 0 until tail) mask[queue[k]] = 1f
        }
    }

    /** Detail around each pixel: luminance gradient after a light blur, averaged over 5x5. */
    internal fun texture(lum: FloatArray, w: Int, h: Int): FloatArray {
        val soft = FloatArray(w * h)
        Filters.box(lum, soft, w, h, 1)
        val grad = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = soft[y * w + min(w - 1, x + 1)] - soft[y * w + max(0, x - 1)]
            val gy = soft[min(h - 1, y + 1) * w + x] - soft[max(0, y - 1) * w + x]
            grad[i] = abs(gx) + abs(gy)
        }
        val out = FloatArray(w * h)
        Filters.box(grad, out, w, h, 2)
        return out
    }
}
