package dev.kogler.a7iiiconnect.develop

import android.content.Context
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Preset
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.io.File

/**
 * Where a shot stands after culling. Shots with no mark are still to review.
 * [level] -1 is rejected, 1 is a first-round pick, and each later round a shot survives adds one.
 * [settled] means the shot was shown in round level + 1 and stayed behind, so that round is done
 * with it.
 */
data class Mark(val level: Int, val settled: Boolean = false) {
    val rejected get() = level < 0
    val picked get() = level >= 1

    companion object {
        val REJECTED = Mark(-1)
    }
}

/** True when [round] still has to show this shot: unmarked for round 1, an unsettled survivor after. */
fun Mark?.dueIn(round: Int): Boolean =
    if (round == 1) this == null else this != null && level == round - 1 && !settled

/**
 * Edits are kept beside the app, never written into the original file (non-destructive, like
 * Lightroom). One small text file per photo, named after its [LibraryPhoto.key], so edits follow
 * the file rather than its MediaStore row (and come back with Android's app-data backup).
 */
class EditStore(context: Context) {
    private val dir = File(context.filesDir, "develop").apply { mkdirs() }
    private val editDir = File(dir, "edits").apply { mkdirs() }
    private val markFile = File(dir, "flags.txt")
    private val prefs = context.getSharedPreferences("develop", Context.MODE_PRIVATE)

    private fun editFile(photo: LibraryPhoto) = File(editDir, fileName(photo.key) + ".txt")

    fun load(photo: LibraryPhoto): Settings =
        runCatching { Settings.decode(editFile(photo).readText()) }.getOrDefault(Settings())

    fun save(photo: LibraryPhoto, settings: Settings) {
        val f = editFile(photo)
        if (settings.isDefault) f.delete() else f.writeText(settings.encode())
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

    private var markCache: MutableMap<String, Mark>? = null

    /** Culling marks by shot key, in one small tab-separated file. */
    fun marks(): Map<String, Mark> = markMap().toMap()

    fun setMark(key: String, mark: Mark?) {
        val m = markMap()
        if (mark == null) m.remove(key) else m[key] = mark
        writeMarks(m)
    }

    private fun writeMarks(m: Map<String, Mark>) =
        markFile.writeText(m.entries.joinToString("") { "${it.key}\t${it.value.level}\t${if (it.value.settled) 1 else 0}\n" })

    private fun markMap(): MutableMap<String, Mark> = markCache ?: runCatching {
        markFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val mark = when (val v = parts.getOrNull(1)) {
                // 0.14 wrote PICK / REJECT.
                "PICK" -> Mark(1)
                "REJECT" -> Mark.REJECTED
                else -> Mark(v?.toIntOrNull() ?: return@mapNotNull null, parts.getOrNull(2) == "1")
            }
            parts[0] to mark
        }.toMap(mutableMapOf())
    }.getOrDefault(mutableMapOf()).also { markCache = it }

    /** Forgets edits and marks of shots whose files were deleted. */
    fun forget(shots: List<Shot>) {
        val m = markMap()
        for (shot in shots) {
            m.remove(shot.key)
            shot.files.forEach { editFile(it).delete() }
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
}
