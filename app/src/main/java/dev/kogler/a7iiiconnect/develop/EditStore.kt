package dev.kogler.a7iiiconnect.develop

import android.content.Context
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
}
