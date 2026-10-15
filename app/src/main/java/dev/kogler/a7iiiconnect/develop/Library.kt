package dev.kogler.a7iiiconnect.develop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.kogler.a7iiiconnect.RecentPhotos
import kotlin.math.abs

/** A photo in the A7III Connect album, as the editor sees it. */
data class LibraryPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val takenMillis: Long,
) {
    val isRaw: Boolean
        get() = mime.contains("arw", ignoreCase = true) || name.endsWith(".arw", ignoreCase = true)

    /** File name without extension: DSC01234 for both DSC01234.ARW and DSC01234.JPG. */
    val stem: String get() = name.substringBeforeLast('.')

    /**
     * Stable identity for edits and flags: file name plus capture time. Unlike the MediaStore id it
     * stays the same when the media database is rebuilt or the app data is restored from a backup,
     * and the time tells apart two DSC01234s after the camera's counter wraps.
     */
    val key: String get() = "$name@$takenMillis"
}

/** One press of the shutter: the RAW and its JPEG twin, or just one of them. */
data class Shot(val raw: LibraryPhoto?, val jpeg: LibraryPhoto?) {
    /** What the editor opens and what edits and flags hang off: the RAW when there is one. */
    val primary: LibraryPhoto get() = raw ?: jpeg!!
    val key: String get() = primary.key
    val takenMillis: Long get() = primary.takenMillis
    val files: List<LibraryPhoto> get() = listOfNotNull(raw, jpeg)
    fun contains(id: Long) = raw?.id == id || jpeg?.id == id
}

object Library {

    /** Every photo the app received, newest first (RAW and JPEG alike). */
    fun load(context: Context): List<LibraryPhoto> {
        val out = mutableListOf<LibraryPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}%"), null,
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val n = c.getString(name) ?: ""
                    val m = c.getString(mime) ?: ""
                    if (!(m.startsWith("image/jpeg") || m.contains("arw") || n.endsWith(".arw", true) ||
                            n.endsWith(".jpg", true) || n.endsWith(".jpeg", true))
                    ) continue
                    val t = if (c.isNull(taken) || c.getLong(taken) == 0L) c.getLong(added) * 1000 else c.getLong(taken)
                    out += LibraryPhoto(
                        c.getLong(id),
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                        n, m, t,
                    )
                }
            }
        }
        // Same capture time: show the RAW before its JPEG twin.
        return out.sortedWith(compareByDescending<LibraryPhoto> { it.takenMillis }.thenByDescending { it.name }.thenBy { !it.isRaw })
    }

    /**
     * Pairs each RAW with the JPEG of the same name taken at (nearly) the same time; newest first.
     * The time check keeps DSC01234 from two counter cycles apart; the hour of slack covers files
     * whose capture time MediaStore didn't read and fell back to when they arrived.
     */
    fun shots(photos: List<LibraryPhoto>): List<Shot> {
        val out = mutableListOf<Shot>()
        for ((_, same) in photos.groupBy { it.stem.lowercase() }) {
            val jpegs = same.filter { !it.isRaw }.toMutableList()
            for (raw in same.filter { it.isRaw }) {
                val twin = jpegs.filter { abs(it.takenMillis - raw.takenMillis) <= PAIR_WINDOW_MS }
                    .minByOrNull { abs(it.takenMillis - raw.takenMillis) }
                if (twin != null) jpegs -= twin
                out += Shot(raw, twin)
            }
            jpegs.forEach { out += Shot(null, it) }
        }
        return out.sortedWith(compareByDescending<Shot> { it.takenMillis }.thenByDescending { it.primary.name })
    }

    /** Names of the editor's exports, to badge the shots that have one. */
    fun exportedNames(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_PICTURES}/${Photos.EXPORT_ALBUM}%"), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it.lowercase() } }
        }
        return out
    }

    private const val PAIR_WINDOW_MS = 60 * 60 * 1000L
}
