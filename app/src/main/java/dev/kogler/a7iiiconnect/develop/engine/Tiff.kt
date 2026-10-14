package dev.kogler.a7iiiconnect.develop.engine

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to a file's bytes, so a thumbnail read doesn't load a whole 25 MB raw. */
interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > bytes.size) throw EOFException("read $length at $offset of ${bytes.size}")
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > size) throw EOFException("read $length at $offset of $size")
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException()
            pos += n
        }
        return buf.array()
    }
}

/**
 * Minimal TIFF directory reader. [base] is subtracted from every offset, which lets the same code
 * read Sony's decrypted SR2 block, whose offsets point into the original file.
 */
class Tiff(val src: ByteSource, val little: Boolean, private val base: Long = 0) {

    class Entry(val tag: Int, val type: Int, val count: Int, val dataOffset: Long)

    class Ifd(val offset: Long, val entries: Map<Int, Entry>, val next: Long) {
        operator fun get(tag: Int) = entries[tag]
        operator fun contains(tag: Int) = tag in entries
    }

    fun u16(b: ByteArray, o: Int): Int =
        if (little) (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        else ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    fun u32(b: ByteArray, o: Int): Long =
        if (little) (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
        else (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    fun readIfd(offset: Long): Ifd {
        val n = u16(src.read(offset - base, 2), 0)
        require(n in 1..1000) { "bad IFD at $offset ($n entries)" }
        val b = src.read(offset - base + 2, n * 12 + 4)
        val entries = HashMap<Int, Entry>(n * 2)
        for (i in 0 until n) {
            val o = i * 12
            val type = u16(b, o + 2)
            val count = u32(b, o + 4)
            val bytes = typeSize(type) * count
            if (count > Int.MAX_VALUE) continue
            // Values of up to four bytes sit in the entry itself.
            val data = if (bytes <= 4) offset + 2 + o + 8 else u32(b, o + 8)
            entries[u16(b, o)] = Entry(u16(b, o), type, count.toInt(), data)
        }
        return Ifd(offset, entries, u32(b, n * 12))
    }

    /** The IFD chain starting at the file header (IFD0, IFD1, ...). */
    fun chain(): List<Ifd> {
        val out = mutableListOf<Ifd>()
        var next = u32(src.read(4 - base, 4), 0)
        while (next != 0L && out.size < 8) {
            val ifd = readIfd(next)
            out += ifd
            next = ifd.next
        }
        return out
    }

    fun bytes(e: Entry): ByteArray = src.read(e.dataOffset - base, (typeSize(e.type) * e.count).toInt())

    /** Integer values of a BYTE/SHORT/LONG entry. */
    fun ints(e: Entry): LongArray {
        val b = bytes(e)
        return LongArray(e.count) { i ->
            when (e.type) {
                1, 7 -> (b[i].toLong() and 0xff)
                6 -> b[i].toLong()
                3 -> u16(b, i * 2).toLong()
                8 -> u16(b, i * 2).toShort().toLong()
                4, 13 -> u32(b, i * 4)
                9 -> u32(b, i * 4).toInt().toLong()
                else -> 0L
            }
        }
    }

    fun int(ifd: Ifd, tag: Int): Long? = ifd[tag]?.let { ints(it).firstOrNull() }

    fun string(ifd: Ifd, tag: Int): String? =
        ifd[tag]?.let { String(bytes(it), Charsets.ISO_8859_1).trimEnd('\u0000', ' ') }

    companion object {
        fun open(src: ByteSource): Tiff {
            val h = src.read(0, 4)
            val little = when {
                h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
                h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
                else -> throw IllegalArgumentException("not a TIFF/ARW file")
            }
            return Tiff(src, little)
        }

        fun typeSize(type: Int): Long = when (type) {
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 1
        }
    }
}
