package dev.kogler.a7iiiconnect.develop.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * A 3D colour lookup table, the way film looks are shared (.cube files, Hald PNGs): display sRGB
 * in, display sRGB out. [data] holds [size]³ RGB triplets, red fastest, then green, then blue
 * (the .cube and Hald order).
 */
class Lut(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2 && data.size == size * size * size * 3) { "a LUT needs size³ colours" }
    }

    /** The table's colour for (r, g, b) in 0..1, trilinear between its nodes. */
    fun at(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val m = size - 1
        val x = r.coerceIn(0f, 1f) * m; val y = g.coerceIn(0f, 1f) * m; val z = b.coerceIn(0f, 1f) * m
        val x0 = minOf(x.toInt(), m - 1); val y0 = minOf(y.toInt(), m - 1); val z0 = minOf(z.toInt(), m - 1)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        for (c in 0..2) {
            fun v(i: Int, j: Int, k: Int) = data[(((z0 + k) * size + y0 + j) * size + x0 + i) * 3 + c]
            val a = (v(0, 0, 0) * (1 - fx) + v(1, 0, 0) * fx) * (1 - fy) + (v(0, 1, 0) * (1 - fx) + v(1, 1, 0) * fx) * fy
            val b2 = (v(0, 0, 1) * (1 - fx) + v(1, 0, 1) * fx) * (1 - fy) + (v(0, 1, 1) * (1 - fx) + v(1, 1, 1) * fx) * fy
            out[c] = a * (1 - fz) + b2 * fz
        }
        return out
    }

    /** The same table sampled at [n]³ nodes. */
    fun resized(n: Int): Lut {
        if (n == size) return this
        val out = FloatArray(n * n * n * 3)
        val px = FloatArray(3)
        var o = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            at(r / (n - 1f), g / (n - 1f), b / (n - 1f), px)
            out[o++] = px[0]; out[o++] = px[1]; out[o++] = px[2]
        }
        return Lut(n, out)
    }

    /** Compact storage: 16 bits per value, read back by [read]. */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeShort(size)
        for (v in data) d.writeShort((v.coerceIn(0f, 1f) * 65535f).roundToInt())
        d.flush()
    }

    companion object {
        /** What every LUT is resampled to: the common .cube size, small enough to be one texture. */
        const val SIZE = 33

        private const val MAGIC = 0x53424c31 // "SBL1"

        fun read(input: InputStream): Lut {
            val d = DataInputStream(input.buffered())
            require(d.readInt() == MAGIC) { "not a stored LUT" }
            val n = d.readUnsignedShort()
            return Lut(n, FloatArray(n * n * n * 3) { d.readUnsignedShort() / 65535f })
        }

        /** An Adobe/Resolve .cube file (3D only), with its TITLE when it has one. */
        fun parseCube(text: String): Pair<Lut, String?> {
            var size = 0
            var title: String? = null
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var values: FloatArray? = null
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substring(5).trim().trim('"').takeIf { it.isNotEmpty() }
                    "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 2..256 } ?: error("bad LUT_3D_SIZE")
                        values = FloatArray(size * size * size * 3)
                    }
                    "LUT_1D_SIZE" -> error("a 1D LUT, not a colour look")
                    "DOMAIN_MIN" -> for (c in 0..2) min[c] = parts.getOrNull(c + 1)?.toFloatOrNull() ?: 0f
                    "DOMAIN_MAX" -> for (c in 0..2) max[c] = parts.getOrNull(c + 1)?.toFloatOrNull() ?: 1f
                    "LUT_3D_INPUT_RANGE" -> {
                        val lo = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                        val hi = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
                        for (c in 0..2) { min[c] = lo; max[c] = hi }
                    }
                    else -> {
                        val v = values ?: continue
                        if (parts.size < 3 || n >= v.size) continue
                        for (c in 0..2) v[n++] = parts[c].toFloatOrNull() ?: error("bad colour line: $line")
                    }
                }
            }
            val v = values ?: error("no LUT_3D_SIZE")
            require(n == v.size) { "expected ${v.size / 3} colours, found ${n / 3}" }
            // Inputs outside 0..1 (a log domain): sample the table back onto 0..1.
            val lut = Lut(size, v)
            if (min.all { it == 0f } && max.all { it == 1f }) return lut to title
            val out = FloatArray(v.size)
            val px = FloatArray(3)
            var o = 0
            fun pos(i: Int, c: Int) = (i / (size - 1f) - min[c]) / (max[c] - min[c])
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                lut.at(pos(r, 0), pos(g, 1), pos(b, 2), px)
                out[o++] = px[0]; out[o++] = px[1]; out[o++] = px[2]
            }
            return Lut(size, out) to title
        }

        /**
         * A Hald CLUT image (RawTherapee, G'MIC, darktable): a level-L image is L³ x L³ pixels
         * holding an L²-sized table, row by row. [argb] is the image's pixels.
         */
        fun fromHald(width: Int, height: Int, argb: IntArray): Lut {
            require(width == height) { "a Hald image is square" }
            val level = generateSequence(2) { it + 1 }.first { it * it * it >= width }
            require(level * level * level == width) { "${width}x$height isn't a Hald image size" }
            val n = level * level
            return Lut(n, FloatArray(n * n * n * 3) { i ->
                val p = argb[i / 3]
                ((p shr (16 - 8 * (i % 3))) and 0xff) / 255f
            })
        }

        /** The table that changes nothing. */
        fun identity(n: Int = SIZE): Lut {
            var o = 0
            val d = FloatArray(n * n * n * 3)
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                d[o++] = r / (n - 1f); d[o++] = g / (n - 1f); d[o++] = b / (n - 1f)
            }
            return Lut(n, d)
        }
    }
}

/**
 * The LUTs that edits can name (`lut=` in [Settings]), by id. The app fills it from its imported
 * files (see LutLibrary); a LUT is read the first time a render asks for it. An id nobody has
 * (an edit from another phone) renders without a LUT.
 */
object Luts {
    private val loaders = HashMap<String, () -> Lut?>()
    private val loaded = HashMap<String, Lut>()

    @Synchronized
    fun register(id: String, load: () -> Lut?) {
        loaders[id] = load
        loaded.remove(id)
    }

    @Synchronized
    fun unregister(id: String) {
        loaders.remove(id)
        loaded.remove(id)
    }

    @Synchronized
    operator fun get(id: String): Lut? = loaded[id] ?: loaders[id]?.let { load ->
        runCatching(load).getOrNull()?.also { loaded[id] = it }
    }
}
