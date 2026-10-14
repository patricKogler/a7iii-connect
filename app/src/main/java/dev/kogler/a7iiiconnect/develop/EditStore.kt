package dev.kogler.a7iiiconnect.develop

import android.content.Context
import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.io.File

/**
 * Edits are kept beside the app, never written into the original file (non-destructive, like
 * Lightroom). One small text file per photo, keyed by its MediaStore id.
 */
class EditStore(context: Context) {
    private val dir = File(context.filesDir, "develop").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("develop", Context.MODE_PRIVATE)

    fun load(id: Long): Settings =
        runCatching { Settings.decode(File(dir, "$id.txt").readText()) }.getOrDefault(Settings())

    fun save(id: Long, settings: Settings) {
        val f = File(dir, "$id.txt")
        if (settings.isDefault) f.delete() else f.writeText(settings.encode())
    }

    fun editedIds(): Set<Long> =
        dir.listFiles()?.mapNotNull { it.name.removeSuffix(".txt").toLongOrNull() }?.toSet() ?: emptySet()

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
