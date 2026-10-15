package dev.kogler.a7iiiconnect.develop.engine

/** What the editor needs to know about a raw file before decoding its pixels. */
class RawInfo internal constructor(
    val make: String,
    val model: String,
    /** EXIF orientation, 1 = upright. */
    val orientation: Int,
    /** As-shot white balance multipliers for R, G, B with G = 1. */
    val asShot: FloatArray,
    /** Black level per CFA colour (R, G, B) and the white level, in raw units. */
    val black: IntArray,
    val white: Int,
    /** The biggest embedded JPEG preview (offset, length), if any. */
    val preview: LongRange?,
    internal val raw: RawIfd?,
    /** The camera's own lens-correction data for the lens used, if it wrote any. */
    val lens: LensData? = null,
)

/**
 * Sony's embedded lens corrections (tags 0x7032/0x7035/0x7037), as read by darktable: [n] knots
 * spread over the image radius with distortion, red/blue chromatic aberration and vignetting.
 */
class LensData(val distortion: IntArray, val caRed: IntArray, val caBlue: IntArray, val vignetting: IntArray) {
    val n get() = distortion.size
}

internal class RawIfd(
    val width: Int,
    val height: Int,
    val compression: Int,
    val bits: Int,
    val offset: Long,
    val bytes: Long,
    /** Colour (0 R, 1 G, 2 B) of the four pixels at (row & 1) * 2 + (col & 1). */
    val cfa: IntArray,
    val curve: IntArray?,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
)

/**
 * The sensor data: one 14-bit value per photosite, in a Bayer pattern. [left], [top], [width] and
 * [height] are the active area the camera wants shown (the A7III crops 6048x4024 to 6000x4000).
 */
class RawImage(
    val info: RawInfo,
    val rawWidth: Int,
    val rawHeight: Int,
    val data: ShortArray,
    val cfa: IntArray,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun color(row: Int, col: Int) = cfa[((row and 1) shl 1) or (col and 1)]
    fun at(row: Int, col: Int) = data[row * rawWidth + col].toInt() and 0xffff
}

/**
 * Sony ARW reader (ARW 2.x, as written by the A7III): the 8-bit-per-pixel "compressed" format and
 * uncompressed 14-bit data. Camera white balance and levels come from Sony's encrypted SR2 block.
 */
object Arw {

    fun readInfo(src: ByteSource): RawInfo {
        val tiff = Tiff.open(src)
        val chain = tiff.chain()
        val ifd0 = chain.first()
        val all = chain.toMutableList()
        for (ifd in chain) {
            ifd[TAG_SUBIFDS]?.let { e -> tiff.ints(e).forEach { off -> runCatching { all += tiff.readIfd(off) } } }
        }

        var preview: LongRange? = null
        for (ifd in all) {
            val off = tiff.int(ifd, TAG_JPEG_OFFSET) ?: continue
            val len = tiff.int(ifd, TAG_JPEG_LENGTH) ?: continue
            if (len > 0 && off + len <= src.size && (preview == null || len > preview.last - preview.first + 1)) {
                preview = off until off + len
            }
        }

        val rawIfd = all.filter { (tiff.int(it, TAG_PHOTOMETRIC) ?: 0L) == 32803L || (tiff.int(it, TAG_COMPRESSION) ?: 0L) == 32767L }
            .maxByOrNull { (tiff.int(it, TAG_WIDTH) ?: 0L) * (tiff.int(it, TAG_HEIGHT) ?: 0L) }
        val raw = rawIfd?.let { readRawIfd(tiff, it) }

        // Defaults for the A7III, in case the SR2 block is missing.
        val black = intArrayOf(512, 512, 512)
        var white = 16383
        val asShot = floatArrayOf(2f, 1f, 1.6f)
        ifd0[TAG_SR2_PRIVATE]?.let { e ->
            // Sony writes this pointer as four BYTEs; read them as one 32-bit offset.
            val pointer = if (Tiff.typeSize(e.type) == 1L) tiff.u32(tiff.bytes(e), 0) else tiff.ints(e).first()
            runCatching { readSr2(tiff, pointer, black, asShot)?.let { white = it } }
        }
        if (raw != null && raw.bits == 12 && raw.compression == 1) {
            for (i in 0..2) black[i] = black[i] shr 2
            white = white shr 2
        }

        val lens = runCatching {
            fun find(tag: Int) = (listOfNotNull(rawIfd) + all).firstNotNullOfOrNull { ifd -> ifd[tag]?.let { tiff.ints(it) } }
            val d = find(0x7037); val c = find(0x7035); val v = find(0x7032)
            if (d == null || c == null || v == null) null else {
                val n = d[0].toInt()
                if (n !in 2..16 || c[0].toInt() != 2 * n || v[0].toInt() != n || d.size < n + 1 || c.size < 2 * n + 1 || v.size < n + 1) null
                else LensData(
                    IntArray(n) { d[it + 1].toShort().toInt() },
                    IntArray(n) { c[it + 1].toShort().toInt() },
                    IntArray(n) { c[n + it + 1].toShort().toInt() },
                    IntArray(n) { v[it + 1].toShort().toInt() },
                )
            }
        }.getOrNull()

        return RawInfo(
            make = tiff.string(ifd0, TAG_MAKE) ?: "",
            model = tiff.string(ifd0, TAG_MODEL) ?: "",
            orientation = (tiff.int(ifd0, TAG_ORIENTATION) ?: 1L).toInt().coerceIn(1, 8),
            asShot = asShot,
            black = black,
            white = white,
            preview = preview,
            raw = raw,
            lens = lens,
        )
    }

    private fun readRawIfd(tiff: Tiff, ifd: Tiff.Ifd): RawIfd {
        val width = tiff.int(ifd, TAG_WIDTH)!!.toInt()
        val height = tiff.int(ifd, TAG_HEIGHT)!!.toInt()
        val offset = tiff.int(ifd, TAG_STRIP_OFFSETS) ?: tiff.int(ifd, TAG_TILE_OFFSETS) ?: error("no raw data")
        val bytes = ifd[TAG_STRIP_BYTES]?.let { tiff.ints(it).sum() } ?: ifd[TAG_TILE_BYTES]?.let { tiff.ints(it).sum() } ?: 0L
        val cfa = ifd[TAG_CFA_PATTERN]?.let { e -> tiff.ints(e).take(4).map { it.toInt().coerceIn(0, 2) }.toIntArray() }
            ?.takeIf { it.size == 4 } ?: intArrayOf(0, 1, 1, 2)
        val curve = ifd[TAG_SONY_CURVE]?.let { e -> toneCurve(tiff.ints(e)) }
        val origin = ifd[TAG_CROP_ORIGIN]?.let { tiff.ints(it) }
        val size = ifd[TAG_CROP_SIZE]?.let { tiff.ints(it) }
        var cl = 0; var ct = 0; var cw = width; var ch = height
        if (origin != null && size != null && origin.size >= 2 && size.size >= 2) {
            cl = origin[0].toInt(); ct = origin[1].toInt(); cw = size[0].toInt(); ch = size[1].toInt()
            if (cl < 0 || ct < 0 || cw <= 0 || ch <= 0 || cl + cw > width || ct + ch > height) { cl = 0; ct = 0; cw = width; ch = height }
        }
        return RawIfd(
            width, height,
            compression = (tiff.int(ifd, TAG_COMPRESSION) ?: 1L).toInt(),
            bits = (tiff.int(ifd, TAG_BITS) ?: 14L).toInt(),
            offset = offset, bytes = bytes, cfa = cfa, curve = curve,
            cropLeft = cl, cropTop = ct, cropWidth = cw, cropHeight = ch,
        )
    }

    /** Sony stores the 11-bit to 14-bit expansion as four knee points; slope doubles after each. */
    internal fun toneCurve(knees: LongArray): IntArray {
        val points = IntArray(6)
        points[5] = 4095
        for (c in 0 until minOf(4, knees.size)) points[c + 1] = ((knees[c] shr 2) and 0xfff).toInt()
        val curve = IntArray(4096) { it }
        for (i in 0 until 5) {
            for (j in points[i] + 1..points[i + 1]) curve[j] = curve[j - 1] + (1 shl i)
        }
        return curve
    }

    /**
     * Reads the SR2 private directory: an IFD pointing at an encrypted sub-IFD with the camera's
     * white balance, black levels and white level. Returns the white level, if present.
     */
    private fun readSr2(tiff: Tiff, privateIfd: Long, black: IntArray, asShot: FloatArray): Int? {
        val ifd = tiff.readIfd(privateIfd)
        val offset = tiff.int(ifd, 0x7200) ?: return null
        val length = (tiff.int(ifd, 0x7201) ?: return null).toInt()
        val keyEntry = ifd[0x7221] ?: return null
        // The key is the entry's four raw bytes read in the file's byte order.
        val key = tiff.u32(tiff.bytes(keyEntry), 0).toInt()
        if (length <= 0 || length > 10_000_000) return null
        val buf = tiff.src.read(offset, length)
        sonyDecrypt(buf, key)
        val sub = Tiff(ArraySource(buf), tiff.little, base = offset)
        val sr2 = sub.readIfd(offset)

        var white: Int? = null
        sr2[0x7310]?.let { e -> // BlackLevel, RGGB
            val v = sub.ints(e)
            if (v.size >= 4) { black[0] = v[0].toInt(); black[1] = ((v[1] + v[2]) / 2).toInt(); black[2] = v[3].toInt() }
        } ?: sr2[0x7300]?.let { e ->
            val v = sub.ints(e)
            if (v.size >= 4) { black[0] = v[0].toInt(); black[1] = ((v[1] + v[2]) / 2).toInt(); black[2] = v[3].toInt() }
        }
        val wb = sr2[0x7313]?.let { e -> sub.ints(e).let { v -> if (v.size >= 4) floatArrayOf(v[0].toFloat(), (v[1] + v[2]) / 2f, v[3].toFloat()) else null } } // RGGB
            ?: sr2[0x7303]?.let { e -> sub.ints(e).let { v -> if (v.size >= 4) floatArrayOf(v[1].toFloat(), (v[0] + v[3]) / 2f, v[2].toFloat()) else null } } // GRBG
        if (wb != null && wb.all { it > 0f }) {
            asShot[0] = wb[0] / wb[1]; asShot[1] = 1f; asShot[2] = wb[2] / wb[1]
        }
        sr2[0x787f]?.let { e -> white = sub.ints(e).minOrNull()?.toInt()?.takeIf { it > 1000 } }
        return white
    }

    /** Sony's SR2 cipher: a lagged-Fibonacci keystream XORed over big-endian 32-bit words. */
    internal fun sonyDecrypt(buf: ByteArray, startKey: Int) {
        val pad = IntArray(128)
        var key = startKey
        for (p in 0 until 4) {
            key = key * 48828125 + 1
            pad[p] = key
        }
        pad[3] = (pad[3] shl 1) or ((pad[0] xor pad[2]) ushr 31)
        for (p in 4 until 127) pad[p] = ((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) ushr 31)
        var p = 127
        val words = buf.size / 4
        for (w in 0 until words) {
            val v = pad[(p + 1) and 127] xor pad[(p + 65) and 127]
            pad[p and 127] = v
            p++
            val o = w * 4
            buf[o] = (buf[o].toInt() xor (v ushr 24)).toByte()
            buf[o + 1] = (buf[o + 1].toInt() xor (v ushr 16)).toByte()
            buf[o + 2] = (buf[o + 2].toInt() xor (v ushr 8)).toByte()
            buf[o + 3] = (buf[o + 3].toInt() xor v).toByte()
        }
    }

    fun decode(src: ByteSource, info: RawInfo = readInfo(src)): RawImage {
        val r = info.raw ?: error("no raw image in this file")
        val w = r.width
        val h = r.height
        val data = ShortArray(w * h)
        when {
            r.compression == 32767 && r.bytes == w.toLong() * h -> decodeArw2(src.read(r.offset, w * h), w, h, r.curve ?: IntArray(4096) { it }, data)
            r.bytes >= w.toLong() * h * 2 -> decode16(src.read(r.offset, w * h * 2), w * h, data)
            r.bytes >= w.toLong() * h * 3 / 2 && r.bits == 12 -> decode12(src.read(r.offset, w * h * 3 / 2), w * h, data)
            else -> error("unsupported raw format (compression ${r.compression}, ${r.bytes} bytes for ${w}x$h)")
        }
        return RawImage(info, w, h, data, r.cfa, r.cropLeft, r.cropTop, r.cropWidth, r.cropHeight)
    }

    /**
     * Each 16-byte block holds 16 same-colour pixels from every other column: an 11-bit max and
     * min, the positions of those two, and 14 7-bit deltas from min (scaled up for wide blocks).
     */
    private fun decodeArw2(src: ByteArray, w: Int, h: Int, curve: IntArray, out: ShortArray) {
        parallelRows(h) { row ->
            val pix = IntArray(16)
            var dp = row * w
            var col = 0
            val base = row * w
            while (col < w - 30) {
                val v = le32(src, dp)
                val max = v and 0x7ff
                val min = (v ushr 11) and 0x7ff
                val imax = (v ushr 22) and 0x0f
                val imin = (v ushr 26) and 0x0f
                var sh = 0
                while (sh < 4 && (0x80 shl sh) <= max - min) sh++
                var bit = 30
                for (i in 0 until 16) {
                    pix[i] = when (i) {
                        imax -> max
                        imin -> min
                        else -> {
                            val b = dp + (bit shr 3)
                            val two = (src[b].toInt() and 0xff) or (if (b + 1 < src.size) (src[b + 1].toInt() and 0xff) shl 8 else 0)
                            val delta = (two ushr (bit and 7)) and 0x7f
                            bit += 7
                            minOf(0x7ff, (delta shl sh) + min)
                        }
                    }
                }
                for (i in 0 until 16) {
                    out[base + col] = curve[pix[i] shl 1].toShort()
                    col += 2
                }
                col -= if (col and 1 != 0) 1 else 31
                dp += 16
            }
        }
    }

    private fun decode16(src: ByteArray, n: Int, out: ShortArray) {
        for (i in 0 until n) out[i] = ((src[2 * i].toInt() and 0xff) or ((src[2 * i + 1].toInt() and 0xff) shl 8)).toShort()
    }

    private fun decode12(src: ByteArray, n: Int, out: ShortArray) {
        var i = 0
        var o = 0
        while (i + 1 < n) {
            val b0 = src[o].toInt() and 0xff
            val b1 = src[o + 1].toInt() and 0xff
            val b2 = src[o + 2].toInt() and 0xff
            out[i] = (b0 or ((b1 and 0x0f) shl 8)).toShort()
            out[i + 1] = ((b1 ushr 4) or (b2 shl 4)).toShort()
            i += 2; o += 3
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private const val TAG_WIDTH = 0x100
    private const val TAG_HEIGHT = 0x101
    private const val TAG_BITS = 0x102
    private const val TAG_COMPRESSION = 0x103
    private const val TAG_PHOTOMETRIC = 0x106
    private const val TAG_MAKE = 0x10f
    private const val TAG_MODEL = 0x110
    private const val TAG_STRIP_OFFSETS = 0x111
    private const val TAG_ORIENTATION = 0x112
    private const val TAG_STRIP_BYTES = 0x117
    private const val TAG_TILE_OFFSETS = 0x144
    private const val TAG_TILE_BYTES = 0x145
    private const val TAG_SUBIFDS = 0x14a
    private const val TAG_JPEG_OFFSET = 0x201
    private const val TAG_JPEG_LENGTH = 0x202
    private const val TAG_SONY_CURVE = 0x7010
    private const val TAG_CFA_PATTERN = 0x828e
    private const val TAG_CROP_ORIGIN = 0xc61f
    private const val TAG_CROP_SIZE = 0xc620
    private const val TAG_SR2_PRIVATE = 0xc634
}
