package dev.kogler.a7iiiconnect.develop

import android.content.Context
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import dev.kogler.a7iiiconnect.develop.engine.Preset
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.io.File

/** Where a shot stands after culling: picked or rejected. Shots with no mark are still to review. */
enum class Mark {
    PICK, REJECT;

    val rejected get() = this == REJECT
    val picked get() = this == PICK

    companion object {
        /** Reads a mark as [EditStore] stores it; null when there is nothing to read. */
        fun parse(v: String?): Mark? = when (v) {
            "PICK" -> PICK
            "REJECT" -> REJECT
            // 0.15 to 0.21 kept culling rounds: -1 rejected, 1 and up a pick that many rounds deep.
            else -> v?.toIntOrNull()?.let { if (it < 0) REJECT else if (it >= 1) PICK else null }
        }
    }
}

/** One of a photo's independent edits (Lightroom's virtual copies). [id] is stable: it names the files and the export. */
data class Version(val id: Int, val name: String)

/** A photo's versions and which one the editor (and everything else) shows. */
data class Versions(val list: List<Version>, val active: Int) {
    val current get() = list.first { it.id == active }
}

/** A saved state of one version to go back to. [auto] ones are made before a preset replaces the look. */
data class Checkpoint(val name: String, val time: Long, val settings: Settings, val auto: Boolean = false)

/**
 * Edits are kept beside the app, never written into the original file (non-destructive, like
 * Lightroom). One small text file per photo, named after its [LibraryPhoto.key], so edits follow
 * the file rather than its MediaStore row (and come back with Android's app-data backup).
 * Another [folder] keeps a separate set, as the edit battle does.
 */
private val markCaches = HashMap<String, MutableMap<String, Mark>>()

class EditStore(context: Context, folder: String = "develop") {
    private val dir = File(context.filesDir, folder).apply { mkdirs() }
    private val editDir = File(dir, "edits").apply { mkdirs() }
    private val markFile = File(dir, "flags.txt")
    private val prefs = context.getSharedPreferences("develop", Context.MODE_PRIVATE)

    init {
        // Edits can name imported LUTs; make them findable before anything renders one.
        LutLibrary.ensure(context)
    }

    private fun editFile(photo: LibraryPhoto) = editFile(photo.key)
    private fun editFile(key: String) = File(editDir, fileName(key) + ".txt")

    private val subjectDir = File(dir, "subjects").apply { mkdirs() }
    private fun subjectFile(photo: LibraryPhoto) = File(subjectDir, fileName(photo.key) + ".mask")
    private fun skyFile(photo: LibraryPhoto) = File(subjectDir, fileName(photo.key) + ".sky")

    /** The subject map found for this photo earlier (8 bits per pixel after a width/height header). */
    fun loadSubject(photo: LibraryPhoto): MaskImage? = loadMap(subjectFile(photo))

    fun saveSubject(photo: LibraryPhoto, m: MaskImage) = saveMap(subjectFile(photo), m)

    /** The sky map found for this photo earlier, stored like the subject's. */
    fun loadSky(photo: LibraryPhoto): MaskImage? = loadMap(skyFile(photo))

    fun saveSky(photo: LibraryPhoto, m: MaskImage) = saveMap(skyFile(photo), m)

    private fun loadMap(f: File): MaskImage? = runCatching {
        val b = f.takeIf { it.exists() }?.readBytes() ?: return null
        val w = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
        val h = ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        if (w <= 0 || h <= 0 || b.size < 4 + w * h) return null
        MaskImage(w, h, FloatArray(w * h) { (b[4 + it].toInt() and 0xff) / 255f })
    }.getOrNull()

    private fun saveMap(f: File, m: MaskImage) {
        val b = ByteArray(4 + m.width * m.height)
        b[0] = (m.width shr 8).toByte(); b[1] = m.width.toByte(); b[2] = (m.height shr 8).toByte(); b[3] = m.height.toByte()
        for (i in m.data.indices) b[4 + i] = (m.data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        f.writeBytes(b)
    }

    fun load(photo: LibraryPhoto): Settings =
        runCatching { Settings.decode(editFile(photo).readText()) }.getOrDefault(Settings())

    fun save(photo: LibraryPhoto, settings: Settings) {
        val f = editFile(photo)
        if (settings.isDefault) f.delete() else f.writeText(settings.encode())
    }

    /** The edit of the shot with this key as stored, and when it was last changed (0 when it has none). */
    fun editOf(key: String): Pair<String, Long> {
        val f = editFile(key)
        return (runCatching { f.readText() }.getOrNull() ?: "") to f.lastModified()
    }

    /** Stores an edit that came from elsewhere, dated as it was there. */
    fun putEdit(key: String, settings: Settings, modified: Long) {
        val f = editFile(key)
        if (settings.isDefault) { f.delete(); return }
        f.writeText(settings.encode())
        if (modified > 0) f.setLastModified(modified)
    }

    /** Keys of the photos with edits. */
    fun editedKeys(photos: List<LibraryPhoto>): Set<String> {
        val names = editDir.list()?.toSet() ?: emptySet()
        return photos.filter { fileName(it.key) + ".txt" in names }.map { it.key }.toSet()
    }

    /** Edits from before 0.14 were named by MediaStore id; renames them to the photo's key. */
    fun migrate(photos: List<LibraryPhoto>) {
        val old = dir.listFiles { f -> f.isFile && f.name.removeSuffix(".txt").toLongOrNull() != null } ?: return
        if (old.isEmpty()) return
        val byId = photos.associateBy { it.id }
        for (f in old) {
            val photo = byId[f.name.removeSuffix(".txt").toLong()] ?: continue
            val target = editFile(photo)
            if (!target.exists()) f.renameTo(target) else f.delete()
        }
    }

    /** Culling marks by shot key, in one small tab-separated file. */
    fun marks(): Map<String, Mark> = synchronized(markLock) { markMap().toMap() }

    fun setMark(key: String, mark: Mark?) = synchronized(markLock) {
        val m = markMap()
        if (mark == null) m.remove(key) else m[key] = mark
        writeMarks(m)
    }

    /** One mark (or none) for many shots at once, written in one go. */
    fun setMarks(keys: Collection<String>, mark: Mark?) = synchronized(markLock) {
        val m = markMap()
        keys.forEach { if (mark == null) m.remove(it) else m[it] = mark }
        writeMarks(m)
    }

    /** Marks for many shots, each its own (null clears it), written in one go. */
    fun putMarks(marks: Map<String, Mark?>) = synchronized(markLock) {
        val m = markMap()
        marks.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        writeMarks(m)
    }

    private fun writeMarks(m: Map<String, Mark>) =
        markFile.writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" })

    // One copy per file for every store, so a sync in the background and the screen see each other's marks.
    private val markLock: Any get() = markCaches
    private fun markMap(): MutableMap<String, Mark> = markCaches.getOrPut(markFile.path) {
        runCatching {
            markFile.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                parts[0] to (Mark.parse(parts.getOrNull(1)) ?: return@mapNotNull null)
            }.toMap(mutableMapOf())
        }.getOrDefault(mutableMapOf())
    }

    /** Forgets edits and marks of shots whose files were deleted. */
    fun forget(shots: List<Shot>) = synchronized(markLock) {
        val m = markMap()
        for (shot in shots) {
            m.remove(shot.key)
            shot.files.forEach { editFile(it).delete(); subjectFile(it).delete(); skyFile(it).delete(); versionsOf(it).deleteRecursively() }
        }
        writeMarks(m)
    }

    private fun fileName(key: String) = key.replace(Regex("[^A-Za-z0-9._@-]"), "_")

    /** "Copy settings": survives leaving the editor, like Lightroom's clipboard. */
    var clipboard: Settings?
        get() = prefs.getString("clipboard", null)?.let { Settings.decode(it) }
        set(value) = prefs.edit().putString("clipboard", value?.encode()).apply()

    /** Which groups "Paste settings" takes; crop is left out by default, as in Lightroom. */
    var pasteGroups: Set<Group>
        get() = prefs.getString("pasteGroups", null)?.split(',')?.mapNotNull { n -> Group.entries.firstOrNull { it.name == n } }?.toSet()
            ?: (Group.entries.toSet() - Group.CROP - Group.GEOMETRY)
        set(value) = prefs.edit().putString("pasteGroups", value.joinToString(",") { it.name }).apply()

    var exportOptions: ExportOptions
        get() = ExportOptions(prefs.getInt("exportEdge", 0).takeIf { it > 0 }, prefs.getInt("exportQuality", 92))
        set(value) = prefs.edit().putInt("exportEdge", value.maxEdge ?: 0).putInt("exportQuality", value.quality).apply()

    /** Swipe on the photo to pick and change sliders (Snapseed style). */
    var swipeAdjust: Boolean
        get() = prefs.getBoolean("swipeAdjust", false)
        set(value) = prefs.edit().putBoolean("swipeAdjust", value).apply()

    private val presetDir = File(dir, "presets").apply { mkdirs() }

    fun userPresets(): List<Preset> = presetDir.listFiles()?.sortedBy { it.name.lowercase() }?.mapNotNull { f ->
        runCatching {
            val text = f.readText()
            val groups = text.lineSequence().firstOrNull { it.startsWith("groups=") }?.removePrefix("groups=")
                ?.split(',')?.mapNotNull { n -> Group.entries.firstOrNull { it.name == n } }?.toSet() ?: Group.entries.toSet()
            Preset(f.name.removeSuffix(".txt"), Settings.decode(text), groups, builtIn = false)
        }.getOrNull()
    } ?: emptyList()

    fun savePreset(name: String, settings: Settings, groups: Set<Group>) {
        val safe = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Preset" }
        File(presetDir, "$safe.txt").writeText("groups=${groups.joinToString(",") { it.name }}\n" + Settings().pasteFrom(settings, groups).encode())
    }

    fun deletePreset(name: String) { File(presetDir, "$name.txt").delete() }

    // Versions. The active version's edit stays in edits/ so the library, albums and exports
    // read it as before; the others wait in versions/<photo>/<id>.txt beside each version's
    // checkpoints and undo history.
    private val versionDir = File(dir, "versions")
    private fun versionsOf(photo: LibraryPhoto) = File(versionDir, fileName(photo.key))
    private fun stashFile(photo: LibraryPhoto, id: Int) = File(versionsOf(photo), "$id.txt")
    private fun checkpointFile(photo: LibraryPhoto, id: Int) = File(versionsOf(photo), "$id.checkpoints")
    private fun historyFile(photo: LibraryPhoto, id: Int) = File(versionsOf(photo), "$id.history")

    fun versions(photo: LibraryPhoto): Versions {
        val lines = runCatching { File(versionsOf(photo), "index.txt").readLines() }.getOrNull()
        val list = lines?.drop(1)?.mapNotNull { l ->
            val t = l.split('\t', limit = 2)
            t[0].toIntOrNull()?.let { Version(it, t.getOrNull(1)?.takeIf { n -> n.isNotBlank() } ?: "Version ${it + 1}") }
        }?.takeIf { it.isNotEmpty() } ?: listOf(Version(0, "Version 1"))
        val active = lines?.firstOrNull()?.removePrefix("active=")?.toIntOrNull()?.takeIf { a -> list.any { it.id == a } } ?: list[0].id
        return Versions(list, active)
    }

    private fun writeVersions(photo: LibraryPhoto, v: Versions) {
        versionsOf(photo).mkdirs()
        File(versionsOf(photo), "index.txt").writeText("active=${v.active}\n" + v.list.joinToString("") { "${it.id}\t${clean(it.name)}\n" })
    }

    private fun stashed(photo: LibraryPhoto, id: Int): Settings =
        runCatching { Settings.decode(stashFile(photo, id).readText()) }.getOrDefault(Settings())

    private fun stash(photo: LibraryPhoto, id: Int, s: Settings) {
        versionsOf(photo).mkdirs()
        stashFile(photo, id).writeText(s.encode())
    }

    /** Makes version [id] the one shown. */
    fun switchVersion(photo: LibraryPhoto, id: Int) {
        val v = versions(photo)
        if (id == v.active || v.list.none { it.id == id }) return
        stash(photo, v.active, load(photo))
        save(photo, stashed(photo, id))
        stashFile(photo, id).delete()
        writeVersions(photo, v.copy(active = id))
    }

    /** Adds a version holding [settings] and makes it the active one. */
    fun addVersion(photo: LibraryPhoto, settings: Settings) {
        val v = versions(photo)
        val id = v.list.maxOf { it.id } + 1
        var n = v.list.size + 1
        while (v.list.any { it.name == "Version $n" }) n++
        stash(photo, v.active, load(photo))
        save(photo, settings)
        writeVersions(photo, Versions(v.list + Version(id, "Version $n"), id))
    }

    fun renameVersion(photo: LibraryPhoto, id: Int, name: String) {
        val v = versions(photo)
        writeVersions(photo, v.copy(list = v.list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }))
    }

    /** Deletes version [id] with its checkpoints and history; the last version can't go. */
    fun deleteVersion(photo: LibraryPhoto, id: Int) {
        val v = versions(photo)
        if (v.list.size <= 1 || v.list.none { it.id == id }) return
        val rest = v.list.filter { it.id != id }
        if (id == v.active) {
            val next = rest[0].id
            save(photo, stashed(photo, next))
            stashFile(photo, next).delete()
            writeVersions(photo, Versions(rest, next))
        } else {
            stashFile(photo, id).delete()
            writeVersions(photo, v.copy(list = rest))
        }
        checkpointFile(photo, id).delete()
        historyFile(photo, id).delete()
    }

    fun checkpoints(photo: LibraryPhoto, id: Int): List<Checkpoint> = readBlocks(checkpointFile(photo, id)).mapNotNull { (head, s) ->
        val t = head.split('\t', limit = 3)
        val time = t.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Checkpoint(t.getOrElse(2) { "Checkpoint" }, time, s, t.getOrNull(1) == "auto")
    }

    fun saveCheckpoints(photo: LibraryPhoto, id: Int, list: List<Checkpoint>) =
        writeBlocks(checkpointFile(photo, id), list.map { "${it.time}\t${if (it.auto) "auto" else "saved"}\t${clean(it.name)}" to it.settings })

    /** Undo steps of version [id] and the one it's at, kept so undo works after leaving the editor. */
    fun history(photo: LibraryPhoto, id: Int): Pair<List<Settings>, Int>? {
        val blocks = readBlocks(historyFile(photo, id)).takeIf { it.isNotEmpty() } ?: return null
        val at = blocks.indexOfFirst { it.first == "at" }.takeIf { it >= 0 } ?: (blocks.size - 1)
        return blocks.map { it.second } to at
    }

    fun saveHistory(photo: LibraryPhoto, id: Int, steps: List<Settings>, at: Int) {
        val drop = (steps.size - MAX_HISTORY).coerceIn(0, at)
        writeBlocks(historyFile(photo, id), steps.drop(drop).mapIndexed { i, s -> (if (i == at - drop) "at" else "") to s })
    }

    // Several settings in one file, each under a "#<tab>header" line (settings lines never start with #).
    private fun readBlocks(f: File): List<Pair<String, Settings>> = runCatching {
        val out = ArrayList<Pair<String, Settings>>()
        var head: String? = null
        val body = StringBuilder()
        fun flush() { head?.let { out += it to Settings.decode(body.toString()) }; body.clear() }
        for (line in f.readLines()) {
            if (line.startsWith("#\t")) { flush(); head = line.removePrefix("#\t") } else body.append(line).append('\n')
        }
        flush()
        out
    }.getOrDefault(emptyList())

    private fun writeBlocks(f: File, blocks: List<Pair<String, Settings>>) {
        if (blocks.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        f.writeText(blocks.joinToString("") { (h, s) -> "#\t$h\n" + s.encode() })
    }

    private fun clean(name: String) = name.replace(Regex("[\\t\\n\\r]"), " ").trim()

    private companion object {
        const val MAX_HISTORY = 80
    }
}
