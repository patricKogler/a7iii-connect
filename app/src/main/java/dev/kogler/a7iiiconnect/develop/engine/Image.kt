package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Scene-linear RGB, white balanced to the camera's as-shot setting (or sRGB for JPEGs) and
 * normalised so 1.0 is the sensor's clipping point.
 */
class LinearImage(val width: Int, val height: Int) {
    val r = FloatArray(width * height)
    val g = FloatArray(width * height)
    val b = FloatArray(width * height)

    fun copy(): LinearImage = LinearImage(width, height).also {
        r.copyInto(it.r); g.copyInto(it.g); b.copyInto(it.b)
    }

    /** Area-averaged copy whose longer edge is at most [maxEdge]. */
    fun downscaled(maxEdge: Int): LinearImage {
        val scale = maxEdge.toDouble() / maxOf(width, height)
        if (scale >= 1) return this
        val w = maxOf(1, (width * scale).toInt())
        val h = maxOf(1, (height * scale).toInt())
        val mid = LinearImage(w, height)
        resampleRows(this, mid)
        val out = LinearImage(w, h)
        resampleCols(mid, out)
        return out
    }

    /** Copy turned upright for an EXIF [orientation] (1-8). */
    fun oriented(orientation: Int): LinearImage {
        if (orientation <= 1 || orientation > 8) return this
        val swap = orientation >= 5
        val out = if (swap) LinearImage(height, width) else LinearImage(width, height)
        val ow = out.width
        val oh = out.height
        parallelRows(oh) { y ->
            for (x in 0 until ow) {
                // Source pixel for output (x, y), per the EXIF orientation definitions.
                val (sx, sy) = when (orientation) {
                    2 -> (width - 1 - x) to y
                    3 -> (width - 1 - x) to (height - 1 - y)
                    4 -> x to (height - 1 - y)
                    5 -> y to x
                    6 -> y to (height - 1 - x)
                    7 -> (width - 1 - y) to (height - 1 - x)
                    else -> (width - 1 - y) to x
                }
                val s = sy * width + sx
                val o = y * ow + x
                out.r[o] = r[s]; out.g[o] = g[s]; out.b[o] = b[s]
            }
        }
        return out
    }

    companion object {
        /** sRGB 8-bit -> linear. */
        val SRGB_TO_LINEAR = FloatArray(256) { i ->
            val v = i / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }

        /** From packed ARGB pixels (a decoded JPEG). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): LinearImage {
            val img = LinearImage(width, height)
            parallelRows(height) { y ->
                val o = y * width
                for (x in 0 until width) {
                    val p = pixels[o + x]
                    img.r[o + x] = SRGB_TO_LINEAR[(p shr 16) and 0xff]
                    img.g[o + x] = SRGB_TO_LINEAR[(p shr 8) and 0xff]
                    img.b[o + x] = SRGB_TO_LINEAR[p and 0xff]
                }
            }
            return img
        }

        private fun weights(src: Int, dst: Int): Array<Pair<Int, FloatArray>> {
            val scale = src.toDouble() / dst
            return Array(dst) { i ->
                val a = i * scale
                val b = (i + 1) * scale
                val first = floor(a).toInt()
                val last = minOf(src - 1, ceil(b).toInt() - 1)
                val ws = FloatArray(last - first + 1) { k ->
                    val lo = maxOf(a, (first + k).toDouble())
                    val hi = minOf(b, (first + k + 1).toDouble())
                    ((hi - lo) / scale).toFloat()
                }
                first to ws
            }
        }

        private fun resampleRows(src: LinearImage, dst: LinearImage) {
            val ws = weights(src.width, dst.width)
            parallelRows(src.height) { y ->
                val so = y * src.width
                val dO = y * dst.width
                for (x in 0 until dst.width) {
                    val (first, w) = ws[x]
                    var r = 0f; var g = 0f; var b = 0f
                    for (k in w.indices) {
                        val i = so + first + k
                        r += src.r[i] * w[k]; g += src.g[i] * w[k]; b += src.b[i] * w[k]
                    }
                    dst.r[dO + x] = r; dst.g[dO + x] = g; dst.b[dO + x] = b
                }
            }
        }

        private fun resampleCols(src: LinearImage, dst: LinearImage) {
            val ws = weights(src.height, dst.height)
            parallelRows(dst.height) { y ->
                val (first, w) = ws[y]
                val dO = y * dst.width
                for (x in 0 until dst.width) {
                    var r = 0f; var g = 0f; var b = 0f
                    for (k in w.indices) {
                        val i = (first + k) * src.width + x
                        r += src.r[i] * w[k]; g += src.g[i] * w[k]; b += src.b[i] * w[k]
                    }
                    dst.r[dO + x] = r; dst.g[dO + x] = g; dst.b[dO + x] = b
                }
            }
        }
    }
}

/** Turns the Bayer mosaic into RGB. */
object Demosaic {

    /**
     * One RGB pixel per 2x2 Bayer cell (half width and height). Fast and clean, used for the
     * on-screen preview.
     */
    fun halfSize(raw: RawImage): LinearImage {
        val w = raw.width / 2
        val h = raw.height / 2
        val img = LinearImage(w, h)
        val lv = levels(raw)
        parallelRows(h) { y ->
            val sum = FloatArray(3)
            val cnt = IntArray(3)
            for (x in 0 until w) {
                sum.fill(0f); cnt.fill(0)
                for (dy in 0..1) for (dx in 0..1) {
                    val row = raw.top + 2 * y + dy
                    val col = raw.left + 2 * x + dx
                    val c = raw.color(row, col)
                    sum[c] += (raw.at(row, col) - lv.black[c]) * lv.scale[c]
                    cnt[c]++
                }
                val o = y * w + x
                img.r[o] = clip(sum[0] / maxOf(1, cnt[0]))
                img.g[o] = clip(sum[1] / maxOf(1, cnt[1]))
                img.b[o] = clip(sum[2] / maxOf(1, cnt[2]))
            }
        }
        return img
    }

    /**
     * Full resolution for rows [y0, y1) of the active area, with Malvar-He-Cutler
     * gradient-corrected interpolation. Writes into [out] (width = raw.width).
     */
    fun fullRows(raw: RawImage, y0: Int, y1: Int, out: LinearImage) {
        val lv = levels(raw)
        val w = raw.width
        fun v(row: Int, col: Int): Float {
            // Mirror at the edges of the active area, keeping the Bayer phase.
            var r = row; var c = col
            if (r < 0) r = -r else if (r >= raw.height) r = 2 * raw.height - 2 - r
            if (c < 0) c = -c else if (c >= w) c = 2 * w - 2 - c
            val rr = raw.top + r; val cc = raw.left + c
            val color = raw.color(rr, cc)
            return (raw.at(rr, cc) - lv.black[color]) * lv.scale[color]
        }
        parallelRows(y1 - y0) { i ->
            val y = y0 + i
            val o = i * w
            for (x in 0 until w) {
                val c = raw.color(raw.top + y, raw.left + x)
                val center = v(y, x)
                // Laplacian-style corrections from the MHC paper (weights / 8).
                val cross = v(y - 1, x) + v(y + 1, x) + v(y, x - 1) + v(y, x + 1)
                val far = v(y - 2, x) + v(y + 2, x) + v(y, x - 2) + v(y, x + 2)
                var r: Float; var g: Float; var b: Float
                if (c == 1) {
                    g = center
                    val horiz = v(y, x - 1) + v(y, x + 1)
                    val vert = v(y - 1, x) + v(y + 1, x)
                    val diag = v(y - 1, x - 1) + v(y - 1, x + 1) + v(y + 1, x - 1) + v(y + 1, x + 1)
                    val farH = v(y, x - 2) + v(y, x + 2)
                    val farV = v(y - 2, x) + v(y + 2, x)
                    // Colour along the row, then along the column.
                    val alongRow = (5 * center + 4 * horiz - diag - farH + 0.5f * farV) / 8
                    val alongCol = (5 * center + 4 * vert - diag - farV + 0.5f * farH) / 8
                    val rowColor = raw.color(raw.top + y, raw.left + x + 1)
                    if (rowColor == 0) { r = alongRow; b = alongCol } else { b = alongRow; r = alongCol }
                } else {
                    g = (4 * center + 2 * cross - far) / 8
                    val diag = v(y - 1, x - 1) + v(y - 1, x + 1) + v(y + 1, x - 1) + v(y + 1, x + 1)
                    val other = (6 * center + 2 * diag - 1.5f * far) / 8
                    if (c == 0) { r = center; b = other } else { b = center; r = other }
                }
                out.r[o + x] = clip(r); out.g[o + x] = clip(g); out.b[o + x] = clip(b)
            }
        }
    }

    private class Levels(val black: IntArray, val scale: FloatArray)

    /** Black subtraction and as-shot white balance in one multiply per photosite. */
    private fun levels(raw: RawImage): Levels {
        val info = raw.info
        val scale = FloatArray(3) { c -> info.asShot[c] / (info.white - info.black[c]).toFloat() }
        return Levels(info.black, scale)
    }

    // After white balance a clipped photosite reads above 1 in red or blue; capping every channel
    // at 1 keeps blown highlights white instead of pink.
    private fun clip(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}
