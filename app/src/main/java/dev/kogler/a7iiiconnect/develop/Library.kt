package dev.kogler.a7iiiconnect.develop

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.kogler.a7iiiconnect.RecentPhotos

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
}
