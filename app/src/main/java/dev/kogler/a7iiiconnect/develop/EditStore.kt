package dev.kogler.a7iiiconnect.develop

import android.content.Context
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Preset
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.io.File

/** Culling verdict for a shot. Unflagged shots are the ones still to review. */
enum class Flag { PICK, REJECT }

/**
 * Edits are kept beside the app, never written into the original file (non-destructive, like
 * Lightroom). One small text file per photo, named after its [LibraryPhoto.key], so edits follow
 * the file rather than its MediaStore row (and come back with Android's app-data backup).
 */
class EditStore(context: Context) {
    private val dir = File(context.filesDir, "develop").apply { mkdirs() }
    private val editDir = File(dir, "edits").apply { mkdirs() }
    private val flagFile = File(dir, "flags.txt")
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

    private var flagCache: MutableMap<String, Flag>? = null

    /** Pick/reject flags by shot key, in one small tab-separated file. */
    fun flags(): Map<String, Flag> = flagMap().toMap()

    fun setFlag(key: String, flag: Flag?) {
        val m = flagMap()
        if (flag == null) m.remove(key) else m[key] = flag
        writeFlags(m)
    }

    private fun writeFlags(m: Map<String, Flag>) =
        flagFile.writeText(m.entries.joinToString("") { "${it.key}\t${it.value.name}\n" })

    private fun flagMap(): MutableMap<String, Flag> = flagCache ?: runCatching {
        flagFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val flag = Flag.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return@mapNotNull null
            parts[0] to flag
        }.toMap(mutableMapOf())
    }.getOrDefault(mutableMapOf()).also { flagCache = it }

    /** Forgets edits and flags of shots whose files were deleted. */
    fun forget(shots: List<Shot>) {
        val m = flagMap()
        for (shot in shots) {
            m.remove(shot.key)
            shot.files.forEach { editFile(it).delete() }
        }
        writeFlags(m)
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
