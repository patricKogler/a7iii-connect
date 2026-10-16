package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds bursts and near-duplicates so culling can show them together. Shots are compared in the
 * order they were taken: a frame joins the previous one's stack when it came within [SIMILAR_MS]
 * and looks alike, both to the previous frame and (a little more loosely) to the stack's first, so
 * a slow pan can't chain unrelated views together. Frames within [BURST_MS] get more slack, since a
 * burst follows a moving subject, but still have to look like each other.
 *
 * "Alike" is a [Look]: where the edges are (a 16x16 difference hash each way) and the colours (a
 * coarse histogram), plus the EXIF focal length and orientation when they're known.
 */
object Similar {
    private const val BURST_MS = 2_000L
    private const val SIMILAR_MS = 60_000L

    /** Share of edge bits that may differ, and how far apart the colours may be (0 to 1). */
    private const val EDGES = 0.22f
    private const val COLOURS = 0.30f
    private const val BURST_EDGES = 0.30f
    private const val BURST_COLOURS = 0.40f
    /** Extra slack against the stack's first frame. */
    private const val DRIFT = 0.06f
    /** Zooming in or out by more than this makes a different picture. */
    private const val FOCAL_RATIO = 1.15f

    private var cache: MutableMap<String, Look>? = null

    /** [shots] grouped into stacks (single shots are stacks of one), in capture order. */
    fun stacks(context: Context, shots: List<Shot>, progress: (Float) -> Unit = {}): List<List<Shot>> {
        val sorted = shots.sortedWith(compareBy<Shot> { it.takenMillis }.thenBy { it.primary.name })
        val meta = runCatching { MetaIndex.known(context) }.getOrDefault(emptyMap())
        val looks = HashMap<String, Look?>()
        fun look(shot: Shot) = looks.getOrPut(shot.key) { lookOf(context, shot) }
        val out = mutableListOf<MutableList<Shot>>()
        sorted.forEachIndexed { i, shot ->
            progress(i / sorted.size.toFloat())
            val stack = out.lastOrNull()
            val prev = stack?.last()
            val together = prev != null && run {
                val gap = shot.takenMillis - prev.takenMillis
                if (gap > SIMILAR_MS || !sameFraming(meta[prev.key], meta[shot.key])) return@run false
                val burst = gap <= BURST_MS
                alike(look(prev), look(shot), burst, 0f) && alike(look(stack.first()), look(shot), burst, DRIFT)
            }
            if (together) stack!! += shot else out += mutableListOf(shot)
        }
        save(context)
        return out
    }

    /** Whether two looks are close enough to stack; [slack] loosens both limits. */
    fun alike(a: Look?, b: Look?, burst: Boolean, slack: Float = 0f): Boolean {
        if (a == null || b == null) return false
        val edges = (if (burst) BURST_EDGES else EDGES) + slack
        val colours = (if (burst) BURST_COLOURS else COLOURS) + slack
        return a.edgeDistance(b) <= edges && a.colourDistance(b) <= colours
    }

    /** Same orientation and about the same focal length, as far as EXIF says. */
    internal fun sameFraming(a: ShotMeta?, b: ShotMeta?): Boolean {
        if (a == null || b == null) return true
        if (a.portrait != null && b.portrait != null && a.portrait != b.portrait) return false
        val fa = a.focal
        val fb = b.focal
        if (fa != null && fb != null && fa > 0 && fb > 0 && max(fa, fb) / minOf(fa, fb) > FOCAL_RATIO) return false
        return true
    }

    private fun lookOf(context: Context, shot: Shot): Look? {
        val cached = load(context)
        cached[shot.key]?.let { return it }
        val thumb = Photos.thumbnail(context, shot.jpeg ?: shot.primary) ?: return null
        val small = Bitmap.createScaledBitmap(thumb, Look.SIDE, Look.SIDE, true)
        val px = IntArray(Look.SIDE * Look.SIDE)
        small.getPixels(px, 0, Look.SIDE, 0, 0, Look.SIDE, Look.SIDE)
        if (small !== thumb) small.recycle()
        return Look.of(px).also { cached[shot.key] = it }
    }

    private fun file(context: Context) = File(context.filesDir, "develop/looks.txt")

    @Synchronized
    private fun load(context: Context): MutableMap<String, Look> = cache ?: runCatching {
        file(context).readLines().mapNotNull { line ->
            val (k, v) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            k to (Look.decode(v) ?: return@mapNotNull null)
        }.toMap(HashMap())
    }.getOrDefault(HashMap()).also { cache = it }

    @Synchronized
    private fun save(context: Context) {
        val m = cache ?: return
        runCatching {
            file(context).parentFile?.mkdirs()
            file(context).writeText(m.entries.joinToString("") { "${it.key}\t${it.value.encode()}\n" })
        }
    }
}

/**
 * What a picture looks like, small enough to keep for every shot: 512 edge bits (is each pixel of a
 * 17x17 grey thumbnail brighter than its right neighbour, and than the one below) and a 64-bin
 * colour histogram (4 levels each of red, green and blue).
 */
class Look(val edges: LongArray, val colours: ByteArray) {

    /** Share of edge bits that differ: 0 identical, about 0.5 for unrelated pictures. */
    fun edgeDistance(o: Look): Float {
        var n = 0
        for (i in edges.indices) n += java.lang.Long.bitCount(edges[i] xor o.edges[i])
        return n / (edges.size * 64f)
    }

    /** How much of the colour histogram would have to move: 0 the same colours, 1 none shared. */
    fun colourDistance(o: Look): Float {
        var d = 0
        for (i in colours.indices) d += abs((colours[i].toInt() and 0xFF) - (o.colours[i].toInt() and 0xFF))
        return d / (2f * COLOUR_TOTAL)
    }

    fun encode(): String = edges.joinToString("") { java.lang.Long.toHexString(it).padStart(16, '0') } + ":" +
        colours.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        const val SIDE = 17
        private const val WORDS = 8
        private const val BINS = 64
        /** The histogram's bins add up to (about) this. */
        private const val COLOUR_TOTAL = 255

        /** From [SIDE]x[SIDE] ARGB pixels, row by row. */
        fun of(px: IntArray): Look {
            require(px.size == SIDE * SIDE)
            val grey = IntArray(px.size) { (px[it] shr 16 and 0xFF) * 299 + (px[it] shr 8 and 0xFF) * 587 + (px[it] and 0xFF) * 114 }
            val edges = LongArray(WORDS)
            var bit = 0
            fun put(on: Boolean) {
                if (on) edges[bit / 64] = edges[bit / 64] or (1L shl (bit % 64))
                bit++
            }
            for (y in 0 until SIDE - 1) for (x in 0 until SIDE - 1) put(grey[y * SIDE + x] > grey[y * SIDE + x + 1])
            for (y in 0 until SIDE - 1) for (x in 0 until SIDE - 1) put(grey[y * SIDE + x] > grey[(y + 1) * SIDE + x])
            val counts = IntArray(BINS)
            for (p in px) counts[(p shr 22 and 3) * 16 + (p shr 14 and 3) * 4 + (p shr 6 and 3)]++
            val colours = ByteArray(BINS) { (counts[it] * COLOUR_TOTAL / px.size).toByte() }
            return Look(edges, colours)
        }

        fun decode(s: String): Look? {
            val (e, c) = s.split(':').takeIf { it.size == 2 } ?: return null
            if (e.length != WORDS * 16 || c.length != BINS * 2) return null
            val edges = LongArray(WORDS) { e.substring(it * 16, it * 16 + 16).toULongOrNull(16)?.toLong() ?: return null }
            val colours = ByteArray(BINS) { c.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
            return Look(edges, colours)
        }
    }
}
