package dev.kogler.a7iiiconnect.develop

import android.content.Context
import java.io.File

/**
 * Shots that are really reminders: a list on a screen, a product in a shop. Marked by hand (a
 * swipe down in Review, or "Mark as note" in the library), they leave Review and the timeline
 * for the Notes view. A note keeps only its JPEG: its RAW goes to the trash with the rejects at
 * the end of a review. Once a month Photos offers to clear out the notes older than a month.
 *
 * Kept by file key (every file of the shot), so a note stays a note once its RAW is gone and
 * the JPEG becomes the shot's key. Phone only for now; the computer sees them as photos.
 */
class NoteStore(context: Context) {
    private val dir = File(context.filesDir, "develop").apply { mkdirs() }
    private val file = File(dir, "notes.txt")
    private val prefs = context.getSharedPreferences("library", 0)

    /** Each note's file keys and when it was marked. */
    @Synchronized
    fun load(): Map<String, Long> = runCatching {
        file.readLines().mapNotNull { line ->
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toLongOrNull() ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save(notes: Map<String, Long>) {
        file.writeText(notes.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    /** Marks [shots] as notes ([on]) or photos again; returns the notes now. */
    @Synchronized
    fun set(shots: List<Shot>, on: Boolean, now: Long = System.currentTimeMillis()): Map<String, Long> {
        val notes = load().toMutableMap()
        for (shot in shots) for (f in shot.files) if (on) notes.putIfAbsent(f.key, now) else notes.remove(f.key)
        save(notes)
        return notes
    }

    /** Forgets the notes of files that are gone. */
    @Synchronized
    fun forget(keys: Collection<String>): Map<String, Long> {
        val notes = load() - keys.toSet()
        save(notes)
        return notes
    }

    /** When the last declutter was done or put off; the first note marked counts as a start. */
    var lastDeclutter: Long
        get() = prefs.getLong("notes_declutter", 0L)
        set(v) = prefs.edit().putLong("notes_declutter", v).apply()
}

object Notes {
    const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    fun isNote(shot: Shot, notes: Map<String, Long>) = shot.files.any { it.key in notes }

    /** When [shot] became a note: the earliest mark of its files. */
    fun markedAt(shot: Shot, notes: Map<String, Long>): Long? = shot.files.mapNotNull { notes[it.key] }.minOrNull()

    /** The RAWs notes no longer need: a note with a JPEG keeps just the JPEG (a RAW on its own stays). */
    fun spareRaws(shots: List<Shot>, notes: Map<String, Long>): List<LibraryPhoto> =
        shots.filter { it.raw != null && it.jpeg != null && isNote(it, notes) }.mapNotNull { it.raw }

    /** Notes taken and marked more than a month before [now], oldest first. */
    fun old(shots: List<Shot>, notes: Map<String, Long>, now: Long): List<Shot> =
        shots.filter { shot ->
            val marked = markedAt(shot, notes) ?: return@filter false
            now - maxOf(marked, shot.takenMillis) > MONTH_MS
        }.sortedBy { it.takenMillis }

    /** Whether to offer the monthly declutter: old notes, and a month since the last one. */
    fun declutterDue(shots: List<Shot>, notes: Map<String, Long>, last: Long, now: Long): Boolean =
        now - last > MONTH_MS && old(shots, notes, now).isNotEmpty()
}
