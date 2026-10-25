package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.floor

/**
 * DNG raws, as phones write them (Pixel, Vivo, Samsung's Expert RAW): Bayer data stored plain
 * (8 to 16 bits) or as lossless JPEG, in strips or tiles, with the levels, white balance and
 * colour matrix in tags, and the lens shading the raw still has as GainMap opcodes.
 */
object Dng {

    /** True for a file whose first directory says it's a DNG. */
    fun isDng(tiff: Tiff, ifd0: Tiff.Ifd) = TAG_DNG_VERSION in ifd0

    fun readInfo(tiff: Tiff, chain: List<Tiff.Ifd>): RawInfo {
        val ifd0 = chain.first()
        val all = chain.toMutableList()
        for (ifd in chain) ifd[TAG_SUBIFDS]?.let { e -> tiff.ints(e).forEach { off -> runCatching { all += tiff.readIfd(off) } } }

        // The raw is the biggest Bayer (CFA) directory; DNGs from phones put it in IFD0 or a SubIFD.
        val rawIfd = all.filter { (tiff.int(it, TAG_PHOTOMETRIC) ?: 0L) == PHOTOMETRIC_CFA }
            .maxByOrNull { (tiff.int(it, TAG_WIDTH) ?: 0L) * (tiff.int(it, TAG_HEIGHT) ?: 0L) }
            ?: error("no Bayer raw in this DNG")
        val raw = readRawIfd(tiff, rawIfd)

        // An 8-bit JPEG preview, if the phone wrote one (Pixels don't; the system thumbnail stands in).
        var preview: LongRange? = null
        for (ifd in all) {
            if (ifd === rawIfd) continue
            val compression = tiff.int(ifd, TAG_COMPRESSION) ?: continue
            if (compression != 6L && compression != 7L) continue
            if ((tiff.int(ifd, TAG_BITS) ?: 8L) != 8L) continue
            val off = tiff.int(ifd, TAG_STRIP_OFFSETS) ?: tiff.int(ifd, TAG_JPEG_OFFSET) ?: continue
            val len = tiff.int(ifd, TAG_STRIP_BYTES) ?: tiff.int(ifd, TAG_JPEG_LENGTH) ?: continue
            if (len > 0 && off + len <= tiff.src.size && (preview == null || len > preview.last - preview.first + 1)) preview = off until off + len
        }

        fun find(tag: Int) = rawIfd[tag] ?: ifd0[tag]
        val cfa = raw.cfa
        // Black level: one value, or one per position of a repeating 2x2 (or larger) block.
        val black = IntArray(3)
        val levels = find(TAG_BLACK_LEVEL)?.let { tiff.doubles(it) } ?: doubleArrayOf(0.0)
        val dims = find(TAG_BLACK_REPEAT)?.let { tiff.ints(it) }?.takeIf { it.size >= 2 && it[0] * it[1] == levels.size.toLong() }
        if (dims == null || levels.size == 1) {
            black.fill(Math.round(levels[0]).toInt())
        } else {
            val sum = DoubleArray(3); val n = IntArray(3)
            for (row in 0 until 2) for (col in 0 until 2) {
                val v = levels[(row % dims[0].toInt()) * dims[1].toInt() + (col % dims[1].toInt())]
                val c = cfa[row * 2 + col]
                sum[c] += v; n[c]++
            }
            for (c in 0..2) black[c] = if (n[c] > 0) Math.round(sum[c] / n[c]).toInt() else Math.round(levels[0]).toInt()
        }
        val white = find(TAG_WHITE_LEVEL)?.let { tiff.doubles(it).firstOrNull()?.toInt() } ?: ((1 shl raw.bits) - 1)

        // Colour: the two calibrated matrices, mixed for the shot's light, and its white balance.
        fun matrix(tag: Int) = find(tag)?.let { tiff.doubles(it) }?.takeIf { it.size == 9 }
        fun illuminant(tag: Int) = find(tag)?.let { tiff.ints(it).firstOrNull()?.toInt() }
        val (colorMatrix, asShot) = DngColor.resolve(
            DngColor.Calibration(matrix(TAG_COLOR_MATRIX1), matrix(TAG_CAMERA_CALIBRATION1), illuminant(TAG_ILLUMINANT1)),
            DngColor.Calibration(matrix(TAG_COLOR_MATRIX2), matrix(TAG_CAMERA_CALIBRATION2), illuminant(TAG_ILLUMINANT2)),
            analogBalance = find(TAG_ANALOG_BALANCE)?.let { tiff.doubles(it) }?.takeIf { it.size == 3 },
            neutral = find(TAG_AS_SHOT_NEUTRAL)?.let { tiff.doubles(it) }?.takeIf { it.size >= 3 && it.all { v -> v > 0 } },
            whiteXy = find(TAG_AS_SHOT_WHITE_XY)?.let { tiff.doubles(it) }?.takeIf { it.size == 2 && it[1] > 0 },
        )
        // Phones underexpose to keep highlights and say by how much the render should lift.
        val baseline = find(TAG_BASELINE_EXPOSURE)?.let { tiff.doubles(it).firstOrNull() }?.takeIf { it.isFinite() } ?: 0.0

        val gainMaps = find(TAG_OPCODE_LIST2)?.let { runCatching { opcodes(tiff.bytes(it)) }.getOrNull() }.orEmpty()
        val linearization = find(TAG_LINEARIZATION)?.let { e -> tiff.ints(e).map { it.toInt() }.toIntArray() }?.takeIf { it.size >= 2 }

        return RawInfo(
            make = tiff.string(ifd0, TAG_MAKE) ?: "",
            model = tiff.string(ifd0, TAG_MODEL) ?: "",
            orientation = (tiff.int(ifd0, TAG_ORIENTATION) ?: 1L).toInt().coerceIn(1, 8),
            asShot = asShot,
            black = black,
            white = white,
            preview = preview,
            raw = raw,
            colorMatrix = colorMatrix,
            baselineExposure = baseline,
            gainMaps = gainMaps,
            linearization = linearization,
        )
    }

    private fun readRawIfd(tiff: Tiff, ifd: Tiff.Ifd): RawIfd {
        val width = tiff.int(ifd, TAG_WIDTH)!!.toInt()
        val height = tiff.int(ifd, TAG_HEIGHT)!!.toInt()
        val tiled = TAG_TILE_OFFSETS in ifd
        val offsets = ifd[if (tiled) TAG_TILE_OFFSETS else TAG_STRIP_OFFSETS]?.let { tiff.ints(it) } ?: error("no raw data")
        val counts = ifd[if (tiled) TAG_TILE_BYTES else TAG_STRIP_BYTES]?.let { tiff.ints(it) } ?: error("no raw data sizes")
        val dims = ifd[TAG_CFA_REPEAT]?.let { tiff.ints(it) }
        val pattern = ifd[TAG_CFA_PATTERN]?.let { e -> tiff.ints(e).map { it.toInt().coerceIn(0, 2) } }
        val cfa = if (pattern != null && pattern.size >= 4 && (dims == null || (dims[0] == 2L && dims[1] == 2L))) pattern.take(4).toIntArray()
        else intArrayOf(0, 1, 1, 2)

        // ActiveArea (top, left, bottom, right) leaves out masked pixels; DefaultCrop is within it.
        val active = ifd[TAG_ACTIVE_AREA]?.let { tiff.ints(it) }?.takeIf { it.size == 4 }?.map { it.toInt() }
        val at = active?.get(0) ?: 0
        val al = active?.get(1) ?: 0
        val ah = active?.let { it[2] - it[0] } ?: height
        val aw = active?.let { it[3] - it[1] } ?: width
        val origin = ifd[TAG_CROP_ORIGIN]?.let { tiff.doubles(it) }
        val size = ifd[TAG_CROP_SIZE]?.let { tiff.doubles(it) }
        var cl = al; var ct = at; var cw = aw; var ch = ah
        if (origin != null && size != null && origin.size >= 2 && size.size >= 2) {
            cl = al + origin[0].toInt(); ct = at + origin[1].toInt(); cw = size[0].toInt(); ch = size[1].toInt()
        }
        if (cl < 0 || ct < 0 || cw <= 0 || ch <= 0 || cl + cw > width || ct + ch > height) { cl = 0; ct = 0; cw = width; ch = height }
        return RawIfd(
            width, height,
            compression = (tiff.int(ifd, TAG_COMPRESSION) ?: 1L).toInt(),
            bits = (tiff.int(ifd, TAG_BITS) ?: 16L).toInt(),
            offset = offsets.first(), bytes = counts.sum(), cfa = cfa, curve = null,
            cropLeft = cl, cropTop = ct, cropWidth = cw, cropHeight = ch,
            dng = true,
            offsets = offsets, counts = counts,
            tileWidth = if (tiled) tiff.int(ifd, TAG_TILE_WIDTH)?.toInt() ?: width else width,
            tileHeight = if (tiled) tiff.int(ifd, TAG_TILE_LENGTH)?.toInt() ?: height else tiff.int(ifd, TAG_ROWS_PER_STRIP)?.toInt()?.coerceIn(1, height) ?: height,
            little = tiff.little,
            activeTop = at, activeLeft = al, activeWidth = aw, activeHeight = ah,
        )
    }

    /** The sensor values of [r], one per photosite, as stored (before levels). */
    internal fun decode(src: ByteSource, r: RawIfd, info: RawInfo): ShortArray {
        val w = r.width
        val h = r.height
        val out = ShortArray(w * h)
        val tw = r.tileWidth
        val th = r.tileHeight
        val across = (w + tw - 1) / tw
        parallelRows(r.offsets.size) { i ->
            val bytes = src.read(r.offsets[i], r.counts[i].toInt())
            val samples = when (r.compression) {
                1 -> unpack(bytes, tw, th, r.bits, r.little)
                7 -> LosslessJpeg(bytes).decode()
                else -> error("unsupported DNG compression ${r.compression}")
            }
            val ty = (i / across) * th
            val tx = (i % across) * tw
            // A lossless JPEG tile may be coded two rows as one (width doubled): its samples still run in raster order.
            val rows = minOf(th, h - ty)
            val cols = minOf(tw, w - tx)
            for (y in 0 until rows) {
                val s = y * tw
                if (s >= samples.size) break
                val n = minOf(cols, samples.size - s)
                for (x in 0 until n) out[(ty + y) * w + tx + x] = samples[s + x].toShort()
            }
        }
        info.linearization?.let { table ->
            val last = table.size - 1
            for (i in out.indices) out[i] = table[minOf(out[i].toInt() and 0xffff, last)].toShort()
        }
        return out
    }

    /** Plain samples: 8 or 16 bits in the file's byte order, or packed most significant bit first, each row from a fresh byte. */
    internal fun unpack(b: ByteArray, width: Int, rows: Int, bits: Int, little: Boolean): IntArray {
        val out = IntArray(width * rows)
        when (bits) {
            16 -> {
                val n = minOf(out.size, b.size / 2)
                for (i in 0 until n) {
                    val lo = b[2 * i].toInt() and 0xff
                    val hi = b[2 * i + 1].toInt() and 0xff
                    out[i] = if (little) lo or (hi shl 8) else (lo shl 8) or hi
                }
            }
            8 -> for (i in 0 until minOf(out.size, b.size)) out[i] = b[i].toInt() and 0xff
            else -> {
                val rowBytes = (width * bits + 7) / 8
                for (y in 0 until rows) {
                    var bit = y.toLong() * rowBytes * 8
                    for (x in 0 until width) {
                        var v = 0
                        for (k in 0 until bits) {
                            val byte = (bit shr 3).toInt()
                            if (byte >= b.size) break
                            v = (v shl 1) or ((b[byte].toInt() ushr (7 - (bit and 7).toInt())) and 1)
                            bit++
                        }
                        out[y * width + x] = v
                    }
                }
            }
        }
        return out
    }

    /** The GainMap opcodes of an opcode list (always big-endian); other opcodes are skipped. */
    internal fun opcodes(b: ByteArray): List<GainMap> {
        fun u32(o: Int) = ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
        fun f64(o: Int) = java.lang.Double.longBitsToDouble((u32(o) shl 32) or u32(o + 4))
        fun f32(o: Int) = java.lang.Float.intBitsToFloat(u32(o).toInt())
        val out = mutableListOf<GainMap>()
        val count = u32(0).toInt()
        var o = 4
        repeat(count) {
            if (o + 16 > b.size) return out
            val id = u32(o).toInt()
            val size = u32(o + 12).toInt()
            val p = o + 16
            if (id == OPCODE_GAIN_MAP && size >= 76 && p + size <= b.size) {
                val pointsV = u32(p + 32).toInt()
                val pointsH = u32(p + 36).toInt()
                val mapPlanes = u32(p + 72).toInt()
                val n = pointsV * pointsH * mapPlanes
                if (pointsV > 0 && pointsH > 0 && mapPlanes > 0 && 76 + n * 4 <= size) {
                    out += GainMap(
                        top = u32(p).toInt(), left = u32(p + 4).toInt(), bottom = u32(p + 8).toInt(), right = u32(p + 12).toInt(),
                        plane = u32(p + 16).toInt(), planes = u32(p + 20).toInt(),
                        rowPitch = u32(p + 24).toInt().coerceAtLeast(1), colPitch = u32(p + 28).toInt().coerceAtLeast(1),
                        pointsV = pointsV, pointsH = pointsH,
                        spacingV = f64(p + 40), spacingH = f64(p + 48), originV = f64(p + 56), originH = f64(p + 64),
                        mapPlanes = mapPlanes,
                        gains = FloatArray(n) { f32(p + 76 + it * 4) },
                    )
                }
            }
            o = p + size
        }
        return out
    }

    /**
     * Applies [info]'s gain maps to [data] in place: each map lifts its plane's pixels (one Bayer
     * colour, picked by its top, left and pitch) above black, as DNG's stage-2 opcodes ask.
     */
    internal fun applyGainMaps(data: ShortArray, r: RawIfd, info: RawInfo) {
        val maps = info.gainMaps
        if (maps.isEmpty()) return
        val w = r.width
        for (m in maps) {
            val top = maxOf(0, m.top)
            val bottom = minOf(r.activeHeight, m.bottom)
            val left = maxOf(0, m.left)
            val right = minOf(r.activeWidth, m.right)
            if (top >= bottom || left >= right) continue
            val rowsInMap = (bottom - top + m.rowPitch - 1) / m.rowPitch
            parallelRows(rowsInMap) { k ->
                val row = top + k * m.rowPitch
                val v = row.toDouble() / r.activeHeight
                val y = row + r.activeTop
                var col = left
                while (col < right) {
                    val x = col + r.activeLeft
                    val c = r.cfa[((y and 1) shl 1) or (x and 1)]
                    val b = info.black[c]
                    val i = y * w + x
                    val raw = data[i].toInt() and 0xffff
                    if (raw > b) {
                        val g = m.gain(v, col.toDouble() / r.activeWidth)
                        data[i] = minOf(65535, Math.round(b + (raw - b) * g)).toShort()
                    }
                    col += m.colPitch
                }
            }
        }
    }

    internal const val TAG_DNG_VERSION = 0xc612
    private const val PHOTOMETRIC_CFA = 32803L
    private const val OPCODE_GAIN_MAP = 9
    private const val TAG_WIDTH = 0x100
    private const val TAG_HEIGHT = 0x101
    private const val TAG_BITS = 0x102
    private const val TAG_COMPRESSION = 0x103
    private const val TAG_PHOTOMETRIC = 0x106
    private const val TAG_MAKE = 0x10f
    private const val TAG_MODEL = 0x110
    private const val TAG_STRIP_OFFSETS = 0x111
    private const val TAG_ORIENTATION = 0x112
    private const val TAG_ROWS_PER_STRIP = 0x116
    private const val TAG_STRIP_BYTES = 0x117
    private const val TAG_TILE_WIDTH = 0x142
    private const val TAG_TILE_LENGTH = 0x143
    private const val TAG_TILE_OFFSETS = 0x144
    private const val TAG_TILE_BYTES = 0x145
    private const val TAG_SUBIFDS = 0x14a
    private const val TAG_JPEG_OFFSET = 0x201
    private const val TAG_JPEG_LENGTH = 0x202
    private const val TAG_CFA_REPEAT = 0x828d
    private const val TAG_CFA_PATTERN = 0x828e
    private const val TAG_LINEARIZATION = 0xc618
    private const val TAG_BLACK_REPEAT = 0xc619
    private const val TAG_BLACK_LEVEL = 0xc61a
    private const val TAG_WHITE_LEVEL = 0xc61d
    private const val TAG_CROP_ORIGIN = 0xc61f
    private const val TAG_CROP_SIZE = 0xc620
    private const val TAG_COLOR_MATRIX1 = 0xc621
    private const val TAG_COLOR_MATRIX2 = 0xc622
    private const val TAG_CAMERA_CALIBRATION1 = 0xc623
    private const val TAG_CAMERA_CALIBRATION2 = 0xc624
    private const val TAG_ANALOG_BALANCE = 0xc627
    private const val TAG_AS_SHOT_NEUTRAL = 0xc628
    private const val TAG_AS_SHOT_WHITE_XY = 0xc629
    private const val TAG_BASELINE_EXPOSURE = 0xc62a
    private const val TAG_ILLUMINANT1 = 0xc65a
    private const val TAG_ILLUMINANT2 = 0xc65b
    private const val TAG_ACTIVE_AREA = 0xc68d
    private const val TAG_OPCODE_LIST2 = 0xc741
}

/** One DNG GainMap: gains on a grid over the image, for one plane, bilinear in between. */
class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val plane: Int, val planes: Int, val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double, val originV: Double, val originH: Double,
    val mapPlanes: Int,
    /** [pointsV] rows of [pointsH] points of [mapPlanes] gains each. */
    val gains: FloatArray,
) {
    /** The gain at ([v], [h]), fractions of the image's height and width. */
    fun gain(v: Double, h: Double): Float {
        val fy = if (pointsV == 1 || spacingV <= 0) 0.0 else ((v - originV) / spacingV).coerceIn(0.0, (pointsV - 1).toDouble())
        val fx = if (pointsH == 1 || spacingH <= 0) 0.0 else ((h - originH) / spacingH).coerceIn(0.0, (pointsH - 1).toDouble())
        val y0 = floor(fy).toInt().coerceAtMost(pointsV - 1)
        val x0 = floor(fx).toInt().coerceAtMost(pointsH - 1)
        val y1 = minOf(y0 + 1, pointsV - 1)
        val x1 = minOf(x0 + 1, pointsH - 1)
        val ty = (fy - y0).toFloat()
        val tx = (fx - x0).toFloat()
        fun at(y: Int, x: Int) = gains[(y * pointsH + x) * mapPlanes]
        val a = at(y0, x0) + (at(y0, x1) - at(y0, x0)) * tx
        val b = at(y1, x0) + (at(y1, x1) - at(y1, x0)) * tx
        return a + (b - a) * ty
    }
}

/**
 * Lossless JPEG (ITU T.81 process 14), as DNG stores raw data: Huffman-coded differences from a
 * predicted neighbour, for one to four interleaved components.
 */
internal class LosslessJpeg(private val b: ByteArray) {
    private var width = 0
    private var height = 0
    private var precision = 16
    private val compIds = IntArray(4)
    private var comps = 0
    private val tables = arrayOfNulls<Huffman>(4)
    private var restart = 0

    private class Huffman(counts: IntArray, symbols: IntArray) {
        /** For each code length 1..16: the first code, the last code (or -1) and where its symbols start. */
        val first = IntArray(17)
        val last = IntArray(17) { -1 }
        val start = IntArray(17)
        val values = symbols

        init {
            var code = 0
            var k = 0
            for (len in 1..16) {
                first[len] = code
                start[len] = k
                if (counts[len - 1] > 0) last[len] = code + counts[len - 1] - 1
                code += counts[len - 1]
                k += counts[len - 1]
                code = code shl 1
            }
        }
    }

    /** All samples, row by row, each row [comps] samples per pixel, interleaved. */
    fun decode(): IntArray {
        var o = 2
        require((b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xff) == 0xd8) { "not a JPEG" }
        val tableOf = IntArray(4)
        var predictor = 1
        var transform = 0
        while (o + 4 <= b.size) {
            if ((b[o].toInt() and 0xff) != 0xff) { o++; continue }
            val marker = b[o + 1].toInt() and 0xff
            if (marker == 0xff) { o++; continue }
            val len = u16(o + 2)
            val p = o + 4
            when (marker) {
                0xc3 -> {
                    precision = b[p].toInt() and 0xff
                    height = u16(p + 1)
                    width = u16(p + 3)
                    comps = b[p + 5].toInt() and 0xff
                    require(comps in 1..4) { "lossless JPEG with $comps components" }
                    for (c in 0 until comps) compIds[c] = b[p + 6 + c * 3].toInt() and 0xff
                }
                0xc4 -> {
                    var q = p
                    while (q < o + 2 + len) {
                        val id = b[q].toInt() and 0x0f
                        val counts = IntArray(16) { b[q + 1 + it].toInt() and 0xff }
                        val n = counts.sum()
                        tables[id and 3] = Huffman(counts, IntArray(n) { b[q + 17 + it].toInt() and 0xff })
                        q += 17 + n
                    }
                }
                0xdd -> restart = u16(p)
                0xda -> {
                    val ns = b[p].toInt() and 0xff
                    for (i in 0 until ns) {
                        val id = b[p + 1 + i * 2].toInt() and 0xff
                        val c = compIds.indexOf(id).takeIf { it in 0 until comps } ?: i
                        tableOf[c] = (b[p + 2 + i * 2].toInt() ushr 4) and 3
                    }
                    predictor = b[p + 1 + ns * 2].toInt() and 0xff
                    transform = b[p + 3 + ns * 2].toInt() and 0x0f
                    return scan(o + 2 + len, tableOf, predictor, transform)
                }
                0xd9 -> break
            }
            o += 2 + len
        }
        error("lossless JPEG without a scan")
    }

    private fun u16(o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    // Bit reader over the entropy-coded data: 0xFF 0x00 is a literal 0xFF; a marker ends the data (zeros follow).
    private var pos = 0
    private var acc = 0L
    private var bits = 0

    private fun fill() {
        while (bits <= 56) {
            var v = 0
            if (pos < b.size) {
                v = b[pos].toInt() and 0xff
                if (v == 0xff) {
                    val next = if (pos + 1 < b.size) b[pos + 1].toInt() and 0xff else 0
                    if (next == 0) pos += 2 else v = 0 // a marker: stay on it
                } else pos++
            }
            acc = (acc shl 8) or v.toLong()
            bits += 8
        }
    }

    private fun peekBit(n: Int): Int { if (bits < n) fill(); return ((acc ushr (bits - n)) and ((1L shl n) - 1)).toInt() }
    private fun skip(n: Int) { bits -= n }

    private fun readBits(n: Int): Int { if (n == 0) return 0; val v = peekBit(n); skip(n); return v }

    private fun symbol(h: Huffman): Int {
        var code = 0
        for (len in 1..16) {
            code = (code shl 1) or readBits(1)
            if (h.last[len] >= 0 && code <= h.last[len] && code >= h.first[len]) return h.values[h.start[len] + code - h.first[len]]
        }
        error("bad Huffman code")
    }

    private fun diff(h: Huffman): Int {
        val ssss = symbol(h)
        return when (ssss) {
            0 -> 0
            16 -> 32768
            else -> {
                val v = readBits(ssss)
                if (v < (1 shl (ssss - 1))) v - (1 shl ssss) + 1 else v
            }
        }
    }

    /** Moves past a restart marker: the bits left in the byte go, then the marker. */
    private fun restartHere() {
        bits = 0; acc = 0
        while (pos + 1 < b.size && !((b[pos].toInt() and 0xff) == 0xff && (b[pos + 1].toInt() and 0xff) in 0xd0..0xd7)) pos++
        pos += 2
    }

    private fun scan(start: Int, tableOf: IntArray, predictor: Int, transform: Int): IntArray {
        pos = start
        val rowLen = width * comps
        val out = IntArray(rowLen * height)
        val initial = 1 shl (precision - transform - 1)
        val hs = Array(comps) { tables[tableOf[it]] ?: tables[0] ?: error("missing Huffman table") }
        require(restart <= 0 || restart % width == 0) { "restart interval of $restart pixels in rows of $width" }
        var first = 0 // the row prediction last started over at: the top, or the row after a restart
        for (y in 0 until height) {
            if (restart > 0 && y > 0 && (y * width) % restart == 0) { restartHere(); first = y }
            val row = y * rowLen
            for (x in 0 until width) {
                for (c in 0 until comps) {
                    val i = row + x * comps + c
                    val pred = when {
                        x == 0 && y == first -> initial
                        y == first -> out[i - comps]
                        x == 0 -> out[i - rowLen]
                        else -> {
                            val ra = out[i - comps]
                            val rb = out[i - rowLen]
                            val rc = out[i - rowLen - comps]
                            when (predictor) {
                                2 -> rb
                                3 -> rc
                                4 -> ra + rb - rc
                                5 -> ra + ((rb - rc) shr 1)
                                6 -> rb + ((ra - rc) shr 1)
                                7 -> (ra + rb) ushr 1
                                else -> ra
                            }
                        }
                    }
                    out[i] = (pred + diff(hs[c])) and 0xffff
                }
            }
        }
        if (transform > 0) for (i in out.indices) out[i] = out[i] shl transform
        return out
    }
}
