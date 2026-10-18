package dev.kogler.a7iiiconnect.develop

import android.content.Context
import java.io.File

/** Lines a face up the way [FaceEmbedder]'s model expects: a 112x112 crop, eyes and mouth in place. */
object FaceAlign {
    const val SIDE = 112

    /** Where the eyes, nose tip and mouth corners go (left to right on the picture), in the crop. */
    val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
    )

    /**
     * The scale, turn and shift (no mirroring) that best moves points [src] onto [dst] (x, y
     * pairs), least squares. Returns a 2x3 matrix row by row: x' = a0 x + a1 y + a2, y' = a3 x + a4 y + a5.
     */
    fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
        val n = src.size / 2
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) { sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
        sx /= n; sy /= n; dx /= n; dy /= n
        var num = 0.0; var cross = 0.0; var den = 0.0
        for (i in 0 until n) {
            val px = src[2 * i] - sx; val py = src[2 * i + 1] - sy
            val qx = dst[2 * i] - dx; val qy = dst[2 * i + 1] - dy
            num += px * qx + py * qy
            cross += px * qy - py * qx
            den += px * px + py * py
        }
        val a = num / den
        val b = cross / den
        return floatArrayOf(
            a.toFloat(), (-b).toFloat(), (dx - a * sx + b * sy).toFloat(),
            b.toFloat(), a.toFloat(), (dy - b * sx - a * sy).toFloat(),
        )
    }
}

/** One face in one shot. */
data class FaceRef(val shot: String, val index: Int)

/**
 * A person: faces that look like the same someone. [id] stays the same from one grouping to the
 * next ([People.group] starts from the people it found before), and a [name] given to a person
 * sticks to whoever looks like them.
 */
class Person(val id: String, val name: String?, val centroid: FloatArray, val faces: List<FaceRef>) {
    /** What filters and search use: the name when there is one, so two people given the same name are one. */
    val key get() = name ?: id
}

/** A remembered person: who, and what they look like. Kept in develop/people.txt. */
class Anchor(val id: String, val name: String?, val centroid: FloatArray)

/**
 * Everyone remembered, and the number the next new person's id gets. Ids are never handed out
 * twice: a name typed for p5 must land on the p5 the person was looking at, even if that group
 * was merged away and someone new turned up while the dialog was open.
 */
class Remembered(val people: List<Anchor>, val next: Int) {
    /** The next free number, counting everyone in [found] too. */
    fun nextAfter(found: List<Person>) = maxOf(next, firstFree(people.map { it.id }), firstFree(found.map { it.id }))

    companion object {
        fun firstFree(ids: List<String>) = (ids.mapNotNull { it.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
    }
}

/**
 * Groups faces into people, on the device. Faces join the person they look most like when they're
 * close enough ([JOIN]; same-person pairs measure about 0.7 to 1 with SFace, strangers under
 * about 0.3), otherwise they start someone new. People known from before go first, so their ids
 * and names stay put as photos come and go.
 */
object People {
    const val JOIN = 0.42f
    /** Two unnamed groups this alike are one person seen two ways; merged. */
    private const val MERGE = 0.5f

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** [sum] adds up the faces' vectors (and a remembered person's look, to start from). */
    private class Group(val id: String, val name: String?, val sum: FloatArray) {
        val faces = mutableListOf<FaceRef>()
        var centroid: FloatArray = ShotInsight.normalize(sum.copyOf())
        fun add(ref: FaceRef, e: FloatArray) {
            for (i in sum.indices) sum[i] += e[i]
            faces += ref
            centroid = ShotInsight.normalize(sum.copyOf())
        }
    }

    /**
     * People in [insights], starting from [known]; named people not in any photo now come back with
     * no faces. New people are numbered from [firstNew] on (or past [known], if that's higher).
     */
    fun group(insights: Map<String, ShotInsight>, known: List<Anchor>, firstNew: Int = 1): List<Person> {
        val groups = known.map { Group(it.id, it.name, it.centroid.copyOf()) }.toMutableList()
        var next = maxOf(firstNew, Remembered.firstFree(known.map { it.id }))
        val embeddings = HashMap<FaceRef, FloatArray>()
        for ((key, insight) in insights.entries.sortedBy { it.key }) {
            insight.faces.forEachIndexed { i, f ->
                val e = f.embedding ?: return@forEachIndexed
                if (groups.isNotEmpty() && e.size != groups[0].sum.size) return@forEachIndexed
                val ref = FaceRef(key, i)
                embeddings[ref] = e
                val best = groups.maxByOrNull { cosine(it.centroid, e) }
                if (best != null && cosine(best.centroid, e) >= JOIN) best.add(ref, e)
                else groups += Group("p${next++}", null, FloatArray(e.size)).also { it.add(ref, e) }
            }
        }
        // Unnamed groups that turn out alike join the bigger one.
        while (true) {
            var pair: Pair<Group, Group>? = null
            search@ for (a in groups) for (b in groups) {
                if (a !== b && b.name == null && b.faces.isNotEmpty() && a.faces.size >= b.faces.size && cosine(a.centroid, b.centroid) >= MERGE) {
                    pair = a to b
                    break@search
                }
            }
            val (a, b) = pair ?: break
            for (ref in b.faces) a.add(ref, embeddings.getValue(ref))
            groups.remove(b)
        }
        return groups.filter { it.faces.isNotEmpty() || it.name != null }.map { Person(it.id, it.name, it.centroid, it.faces.toList()) }
    }

    /** People worth showing: named ones, and anyone in at least [MIN_SHOTS] shots; most photographed first. */
    fun shown(people: List<Person>): List<Person> = people
        .filter { p -> p.faces.isNotEmpty() && (p.name != null || p.faces.map { it.shot }.distinct().size >= MIN_SHOTS) }
        .sortedWith(compareBy<Person> { it.name == null }.thenByDescending { p -> p.faces.map { it.shot }.distinct().size })

    private const val MIN_SHOTS = 2

    // --- Remembered people ---

    private fun file(context: Context) = File(context.filesDir, "develop/people.txt")

    fun load(context: Context): Remembered = runCatching { decode(file(context).readText()) }.getOrDefault(Remembered(emptyList(), 1))

    /** Remembers [people] (those in a photo, or named) for next time, after what was [known]. */
    fun save(context: Context, people: List<Person>, known: Remembered) {
        write(context, Remembered(people.filter { it.name != null || it.faces.size >= 2 }.map { Anchor(it.id, it.name, it.centroid) }, known.nextAfter(people)))
    }

    private fun write(context: Context, r: Remembered) {
        runCatching { file(context).apply { parentFile?.mkdirs() }.writeText(encode(r)) }
    }

    /** One line per person (id, name, look), and a `#next` line; older files without it still read. */
    fun encode(r: Remembered): String = "#next\t${r.next}\n" + r.people.joinToString("") {
        "${it.id}\t${it.name?.replace('\t', ' ')?.replace('\n', ' ') ?: ""}\t${ShotInsight.packEmbedding(it.centroid)}\n"
    }

    fun decode(text: String): Remembered {
        var next = 1
        val people = text.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0] == "#next") next = parts[1].toIntOrNull() ?: 1
            if (parts.size != 3) return@mapNotNull null
            val e = ShotInsight.unpackEmbedding(parts[2]) ?: return@mapNotNull null
            Anchor(parts[0], parts[1].ifEmpty { null }, e)
        }
        return Remembered(people, maxOf(next, Remembered.firstFree(people.map { it.id })))
    }

    /**
     * [known] with the people the user picked ([seen], as they were on screen) called [name]
     * (blank: forgets it). Goes by what's remembered now, not by an older list on screen, so a
     * grouping that finished meanwhile isn't undone; someone merged away meanwhile comes back
     * with the name and the look they had.
     */
    fun rename(known: Remembered, seen: List<Person>, name: String): Remembered {
        val n = name.trim().ifEmpty { null }
        val ids = seen.map { it.id }.toSet()
        val have = known.people.map { it.id }.toSet()
        val people = known.people.map { if (it.id in ids) Anchor(it.id, n, it.centroid) else it } +
            (if (n == null) emptyList<Anchor>() else seen.filter { it.id !in have }.map { Anchor(it.id, n, it.centroid) })
        return Remembered(people, known.next)
    }

    /** Names [seen] [name] in what's remembered (call off the main thread). */
    fun name(context: Context, seen: List<Person>, name: String) {
        synchronized(this) { write(context, rename(load(context), seen, name)) }
    }

    /** [people] with the ones in [ids] called [name] (blank: forgets the name). */
    fun rename(people: List<Person>, ids: Set<String>, name: String): List<Person> = people.map {
        if (it.id in ids) Person(it.id, name.trim().ifEmpty { null }, it.centroid, it.faces) else it
    }
}
