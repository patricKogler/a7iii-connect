package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Finds bursts and near-duplicates so culling can show them together. Shots are compared in the
 * order they were taken: a frame joins the previous one's stack when it came within [BURST_MS], or
 * within [SIMILAR_MS] and looks alike (a 64-bit difference hash of the thumbnail).
 */
object Similar {
    private const val BURST_MS = 2_000L
    private const val SIMILAR_MS = 60_000L
    private const val MAX_DISTANCE = 12

    private var cache: MutableMap<String, Long>? = null

    /** [shots] grouped into stacks (single shots are stacks of one), in capture order. */
    fun stacks(context: Context, shots: List<Shot>, progress: (Float) -> Unit = {}): List<List<Shot>> {
        val sorted = shots.sortedWith(compareBy<Shot> { it.takenMillis }.thenBy { it.primary.name })
        val hashes = HashMap<String, Long?>()
        fun hash(shot: Shot) = hashes.getOrPut(shot.key) { hashOf(context, shot) }
        val out = mutableListOf<MutableList<Shot>>()
        sorted.forEachIndexed { i, shot ->
            progress(i / sorted.size.toFloat())
            val prev = out.lastOrNull()?.last()
            val gap = if (prev == null) Long.MAX_VALUE else shot.takenMillis - prev.takenMillis
            val together = prev != null && (gap <= BURST_MS || gap <= SIMILAR_MS && alike(hash(prev), hash(shot)))
            if (together) out.last() += shot else out += mutableListOf(shot)
        }
        save(context)
        return out
    }

    private fun alike(a: Long?, b: Long?) = a != null && b != null && java.lang.Long.bitCount(a xor b) <= MAX_DISTANCE

    private fun hashOf(context: Context, shot: Shot): Long? {
        val cached = load(context)
        cached[shot.key]?.let { return it }
        val thumb = Photos.thumbnail(context, shot.jpeg ?: shot.primary) ?: return null
        return dHash(thumb).also { cached[shot.key] = it }
    }

    /** Difference hash: 9x8 grey thumbnail, one bit per horizontal neighbour pair. */
    fun dHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== bmp) small.recycle()
        val grey = px.map { (it shr 16 and 0xFF) * 299 + (it shr 8 and 0xFF) * 587 + (it and 0xFF) * 114 }
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = h shl 1
            if (grey[y * 9 + x] > grey[y * 9 + x + 1]) h = h or 1L
        }
        return h
    }

    private fun file(context: Context) = File(context.filesDir, "develop/hashes.txt")

    @Synchronized
    private fun load(context: Context): MutableMap<String, Long> = cache ?: runCatching {
        file(context).readLines().mapNotNull { line ->
            val (k, v) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            k to (v.toULongOrNull(16)?.toLong() ?: return@mapNotNull null)
        }.toMap(HashMap())
    }.getOrDefault(HashMap()).also { cache = it }

    @Synchronized
    private fun save(context: Context) {
        val m = cache ?: return
        runCatching { file(context).writeText(m.entries.joinToString("") { "${it.key}\t${it.value.toULong().toString(16)}\n" }) }
    }
}
