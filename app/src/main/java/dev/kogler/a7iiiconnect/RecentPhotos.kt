package dev.kogler.a7iiiconnect

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

data class Photo(val uri: Uri, val name: String, val takenMillis: Long)

/** Photos this app has copied, newest first, read back from the "A7III Connect" album. */
object RecentPhotos {
    const val ALBUM = "A7III Connect"

    fun load(context: Context, limit: Int = 60): List<Photo> {
        val out = mutableListOf<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM%")
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort,
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext() && out.size < limit) {
                    out += Photo(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                        c.getString(name) ?: "",
                        c.getLong(taken),
                    )
                }
            }
        }
        return out
    }

    fun count(context: Context): Int = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM%"), null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)
}
