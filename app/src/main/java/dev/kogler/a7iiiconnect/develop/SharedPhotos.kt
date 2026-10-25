package dev.kogler.a7iiiconnect.develop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.kogler.a7iiiconnect.RecentPhotos
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Copies photos shared from another app into Pictures/A7III Connect/Shared, so they become
 * library shots that albums, culling and the computer sync know. Each is copied once: a file
 * already in the library (the same bytes shared before, or a camera shot shared back) is found
 * instead. Anything that isn't a JPEG is converted to one, and a photo whose capture time was
 * stripped (WhatsApp, Signal) gets the time it was shared, written into its EXIF so every side
 * reads the same time.
 */
class SharedPhotos(private val context: Context) {
    private val file = File(File(context.filesDir, "develop").apply { mkdirs() }, "shared.txt")

    private fun index(): Map<String, SharedImport.Entry> = runCatching { SharedImport.decode(file.readText()) }.getOrDefault(emptyMap())

    /** The app each shared shot came from, by photo key. */
    fun sources(): Map<String, String> = index().values.associate { it.key to it.source }

    /** What a share brought: the shot keys the photos are, without repeats, and how many couldn't be read. */
    data class Added(val keys: List<String>, val failed: Int)

    /**
     * Brings [uris] into the library; [source] is the sharing app's package. [progress] hears
     * how many are done.
     */
    fun add(uris: List<Uri>, source: String, progress: (Int) -> Unit = {}): Added {
        val index = index().toMutableMap()
        var photos = Library.load(context)
        val found = mutableListOf<String>()
        val inserted = mutableListOf<Pair<Long, String>>()
        var failed = 0
        for ((i, uri) in uris.withIndex()) {
            runCatching { copy(uri, index, photos.map { it.key }, inserted.map { it.second }) }
                .onFailure { failed++ }
                .getOrNull()?.let { (key, id, hash) ->
                    if (key != null) found += key
                    if (id != null) inserted += id to hash
                }
            progress(i + 1)
        }
        if (inserted.isNotEmpty()) {
            photos = Library.load(context)
            val byId = photos.associateBy { it.id }
            for ((id, hash) in inserted) byId[id]?.let {
                found += it.key
                index[hash] = SharedImport.Entry(hash, it.key, source)
            }
            file.writeText(SharedImport.encode(index.values))
        }
        // Albums hold shots, not files: a camera JPEG found here stands for its RAW twin's shot.
        val shotOf = Library.shots(photos).flatMap { shot -> shot.files.map { it.key to shot.key } }.toMap()
        return Added(found.mapNotNull { shotOf[it] }.distinct(), failed)
    }

    /**
     * One shared file: the library key it already is, or the MediaStore id of its new copy, with
     * its hash; neither when it's the same as one copied a moment ago in this share ([batch]).
     */
    private fun copy(uri: Uri, index: Map<String, SharedImport.Entry>, keys: List<String>, batch: List<String>): Triple<String?, Long?, String> {
        val resolver = context.contentResolver
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
        val tmp = File.createTempFile("shared", ".img", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)!!.use { input -> DigestInputStream(input, digest).use { d -> tmp.outputStream().use { d.copyTo(it, 64 * 1024) } } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash in batch) return Triple(null, null, hash)
            val exif = runCatching { ExifInterface(tmp) }.getOrNull()
            val taken = SharedImport.exifTime(
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
            // Whole seconds, as EXIF keeps it, so MediaStore reads back the time given here.
            val now = System.currentTimeMillis() / 1000 * 1000
            val name = SharedImport.fileName(display, now)
            SharedImport.existing(hash, name, taken, index, keys)?.let { return Triple(it, null, hash) }

            val jpeg = if (isJpeg(tmp)) tmp else toJpeg(tmp, exif)
            try {
                if (taken == null) stamp(jpeg, now)
                return Triple(null, insert(jpeg, name, taken ?: now), hash)
            } finally {
                if (jpeg !== tmp) jpeg.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    private fun isJpeg(f: File): Boolean = f.inputStream().use { val b = ByteArray(3); it.read(b) == 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() }

    /** PNG, WebP, HEIC and the like, decoded upright and saved as a JPEG; the camera details come along. */
    private fun toJpeg(src: File, exif: ExifInterface?): File {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = File.createTempFile("shared", ".jpg", context.cacheDir)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        if (exif != null) runCatching {
            val to = ExifInterface(out)
            for (tag in KEPT_TAGS) exif.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            to.saveAttributes()
        }
        return out
    }

    /** Writes [millis] as the capture time, so MediaStore and the computer both read it. */
    private fun stamp(jpeg: File, millis: Long) = runCatching {
        val exif = ExifInterface(jpeg)
        val (time, offset) = SharedImport.exifText(millis)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, time)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_DATETIME, time)
        exif.saveAttributes()
    }

    private fun insert(jpeg: File, name: String, taken: Long): Long {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}/${SharedImport.FOLDER}")
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)!!.use { out -> jpeg.inputStream().use { it.copyTo(out, 64 * 1024) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri.lastPathSegment!!.toLong()
    }

    private companion object {
        /** What a converted photo keeps from the original's EXIF (orientation is baked in). */
        val KEPT_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
    }
}
