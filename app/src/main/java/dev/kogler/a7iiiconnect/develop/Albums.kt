package dev.kogler.a7iiiconnect.develop

import android.content.Context
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * One shot's place in an album: added, or taken out again, at [at]. Taken-out shots are kept
 * (not dropped) so two phones can later agree on what's in an album: the newer change wins.
 */
data class AlbumEntry(val key: String, val at: Long, val inAlbum: Boolean)

/**
 * A named set of shots, like a trip or a photo book. Shots are referenced by [Shot.key], never
 * copied, so a shot can be in many albums. A deleted album is kept as a [deletedAt] marker for
 * the same reason as taken-out shots.
 */
data class Album(
    val id: String,
    val name: String,
    val createdAt: Long,
    val renamedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val entries: Map<String, AlbumEntry> = emptyMap(),
) {
    /** The shots in the album, in the order they were added. */
    val keys: List<String> get() = entries.values.filter { it.inAlbum }.sortedBy { it.at }.map { it.key }
    val size: Int get() = entries.values.count { it.inAlbum }
    val alive: Boolean get() = deletedAt == null
    operator fun contains(key: String) = entries[key]?.inAlbum == true
}

/** Album rules, kept free of Android so they can be tested on the JVM. */
object Albums {

    fun create(name: String, keys: List<String>, now: Long, id: String = UUID.randomUUID().toString()): Album =
        add(Album(id, clean(name), now), keys, now)

    fun add(album: Album, keys: List<String>, now: Long): Album {
        val entries = album.entries.toMutableMap()
        // One millisecond apart, so the album keeps the order they were added in.
        keys.distinct().filter { it !in album }.forEachIndexed { i, k -> entries[k] = AlbumEntry(k, now + i, true) }
        return album.copy(entries = entries)
    }

    fun remove(album: Album, keys: Collection<String>, now: Long): Album {
        val entries = album.entries.toMutableMap()
        for (k in keys) if (k in album) entries[k] = AlbumEntry(k, now, false)
        return album.copy(entries = entries)
    }

    fun rename(album: Album, name: String, now: Long) = album.copy(name = clean(name), renamedAt = now)

    fun delete(album: Album, now: Long) = album.copy(deletedAt = now)

    /**
     * Puts two phones' albums together: the same album (by id) takes the newer name, stays
     * deleted if either side deleted it, and for each shot the newer add or take-out wins
     * (a tie takes it out, so nobody's removal is lost).
     */
    fun merge(mine: List<Album>, theirs: List<Album>): List<Album> {
        val byId = LinkedHashMap<String, Album>()
        for (a in mine) byId[a.id] = a
        for (b in theirs) {
            val a = byId[b.id]
            byId[b.id] = if (a == null) b else mergeOne(a, b)
        }
        return byId.values.toList()
    }

    private fun mergeOne(a: Album, b: Album): Album {
        val named = if (b.renamedAt > a.renamedAt) b else a
        val deleted = listOfNotNull(a.deletedAt, b.deletedAt).maxOrNull()
        val entries = (a.entries.keys + b.entries.keys).associateWith { k ->
            val x = a.entries[k]
            val y = b.entries[k]
            if (x == null) y!! else if (y == null) x else newer(x, y)
        }
        return Album(a.id, named.name, minOf(a.createdAt, b.createdAt), named.renamedAt, deleted, entries)
    }

    /** The later change to a shot; on a tie, taking it out. */
    private fun newer(x: AlbumEntry, y: AlbumEntry) = when {
        x.at != y.at -> if (x.at > y.at) x else y
        else -> if (!x.inAlbum) x else y
    }

    /**
     * Finds this phone's own key for a shot named by another phone: the same key, or else the same
     * file name stem taken at (nearly) the same time. Her DSC01234.JPG is my DSC01234.ARW when she
     * only has the JPEG; the hour of slack is the same as RAW+JPEG pairing allows.
     */
    fun resolver(local: Collection<String>): (String) -> String? {
        val known = local.toSet()
        val byStem = local.mapNotNull { k -> parse(k)?.let { (stem, t) -> Triple(stem, t, k) } }.groupBy { it.first }
        return { key ->
            if (key in known) key
            else parse(key)?.let { (stem, t) ->
                byStem[stem]?.filter { abs(it.second - t) <= MATCH_WINDOW_MS }?.minByOrNull { abs(it.second - t) }?.third
            }
        }
    }

    /** Albums with their shots under this phone's keys where [resolve] finds them (the rest stay as they are). */
    fun rekey(albums: List<Album>, resolve: (String) -> String?): List<Album> = albums.map { a ->
        val out = LinkedHashMap<String, AlbumEntry>()
        for (e in a.entries.values) {
            val k = resolve(e.key) ?: e.key
            val moved = if (k == e.key) e else e.copy(key = k)
            out[k] = out[k]?.let { newer(it, moved) } ?: moved
        }
        if (out == a.entries) a else a.copy(entries = out)
    }

    /** "DSC01234.ARW@1760000000000" as its lower-case stem and capture time. */
    private fun parse(key: String): Pair<String, Long>? {
        val at = key.lastIndexOf('@')
        if (at <= 0) return null
        val time = key.substring(at + 1).toLongOrNull() ?: return null
        return key.substring(0, at).substringBeforeLast('.').lowercase() to time
    }

    private const val MATCH_WINDOW_MS = 60 * 60 * 1000L

    /** Names are one line of text; an empty one becomes "Album". */
    fun clean(name: String) = name.replace(Regex("[\\t\\r\\n]+"), " ").trim().ifEmpty { "Album" }

    /**
     * Tab-separated lines, like the flags file:
     * `A id created renamed deleted(0 = no) name` for each album, then
     * `S id at +/- key` for each shot in it.
     */
    fun encode(albums: List<Album>): String = buildString {
        for (a in albums) {
            append("A\t${a.id}\t${a.createdAt}\t${a.renamedAt}\t${a.deletedAt ?: 0}\t${a.name}\n")
            for (e in a.entries.values.sortedBy { it.at }) append("S\t${a.id}\t${e.at}\t${if (e.inAlbum) "+" else "-"}\t${e.key}\n")
        }
    }

    fun decode(text: String): List<Album> {
        val albums = LinkedHashMap<String, Album>()
        val entries = HashMap<String, MutableMap<String, AlbumEntry>>()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            runCatching {
                when (p[0]) {
                    "A" -> albums[p[1]] = Album(p[1], p[5], p[2].toLong(), p[3].toLong(), p[4].toLong().takeIf { it != 0L })
                    "S" -> entries.getOrPut(p[1]) { mutableMapOf() }[p[4]] = AlbumEntry(p[4], p[2].toLong(), p[3] == "+")
                }
            }
        }
        return albums.values.map { it.copy(entries = entries[it.id] ?: emptyMap()) }
    }
}

/** The albums on this phone, in develop/albums.txt beside the flags. */
class AlbumStore(context: Context) {
    private val file = File(File(context.filesDir, "develop").apply { mkdirs() }, "albums.txt")

    /** Every album, deleted ones included (they matter when phones compare albums). */
    fun load(): List<Album> = runCatching { Albums.decode(file.readText()) }.getOrDefault(emptyList())

    fun save(albums: List<Album>) {
        // Written beside and then moved over, so a crash mid-write can't lose every album.
        val tmp = File(file.parentFile, "albums.txt.tmp")
        tmp.writeText(Albums.encode(albums))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}
