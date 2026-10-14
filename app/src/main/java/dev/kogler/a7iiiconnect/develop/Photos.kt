package dev.kogler.a7iiiconnect.develop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import dev.kogler.a7iiiconnect.develop.engine.Arw
import dev.kogler.a7iiiconnect.develop.engine.CameraColor
import dev.kogler.a7iiiconnect.develop.engine.ChannelSource
import dev.kogler.a7iiiconnect.develop.engine.Demosaic
import dev.kogler.a7iiiconnect.develop.engine.DevelopSource
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.RawImage
import dev.kogler.a7iiiconnect.develop.engine.Renderer
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** A photo opened for editing: a screen-sized preview, plus the full raw kept for export. */
class OpenedPhoto(
    val photo: LibraryPhoto,
    /** Upright preview, longer edge [PREVIEW_EDGE]. */
    val preview: DevelopSource,
    /** Smaller copy rendered while a slider is being dragged. */
    val draft: DevelopSource,
    val orientation: Int,
    val raw: RawImage?,
)

const val PREVIEW_EDGE = 2048
private const val DRAFT_EDGE = 1024

object Photos {

    private val thumbs = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun cachedThumbnail(photo: LibraryPhoto): Bitmap? = thumbs.get(photo.id)

    /** Grid thumbnail: the JPEG embedded in a raw, or the system thumbnail for a JPEG. */
    fun thumbnail(context: Context, photo: LibraryPhoto): Bitmap? {
        thumbs.get(photo.id)?.let { return it }
        val bmp = runCatching {
            if (photo.isRaw) embeddedPreview(context, photo.uri, 400)
            else context.contentResolver.loadThumbnail(photo.uri, Size(400, 400), null)
        }.getOrNull() ?: return null
        thumbs.put(photo.id, bmp)
        return bmp
    }

    /** The camera's own JPEG inside the ARW (1616x1080 on the A7III), turned upright. */
    fun embeddedPreview(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src = ChannelSource(ch)
                val info = Arw.readInfo(src)
                val range = info.preview ?: return null
                val bytes = src.read(range.first, (range.last - range.first + 1).toInt())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                return rotate(bmp, info.orientation)
            }
        }
        return null
    }

    /** Decodes a photo for editing. Raw: half-size demosaic; JPEG: subsampled decode. */
    fun open(context: Context, photo: LibraryPhoto): OpenedPhoto {
        if (photo.isRaw) {
            context.contentResolver.openFileDescriptor(photo.uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val src = ChannelSource(ch)
                    val info = Arw.readInfo(src)
                    val raw = Arw.decode(src, info)
                    val color = CameraColor.forCamera(info.model)
                    val half = Demosaic.halfSize(raw)
                    val preview = half.downscaled(PREVIEW_EDGE).oriented(info.orientation)
                    val draft = preview.downscaled(DRAFT_EDGE)
                    return OpenedPhoto(
                        photo,
                        DevelopSource(preview, color, info.asShot, true),
                        DevelopSource(draft, color, info.asShot, true),
                        info.orientation, raw,
                    )
                }
            }
        }
        val orientation = exifOrientation(context, photo.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("can't decode ${photo.name}")
        val lin = toLinear(bmp).also { bmp.recycle() }.downscaled(PREVIEW_EDGE).oriented(orientation)
        val one = floatArrayOf(1f, 1f, 1f)
        return OpenedPhoto(
            photo,
            DevelopSource(lin, CameraColor.SRGB, one, false),
            DevelopSource(lin.downscaled(DRAFT_EDGE), CameraColor.SRGB, one, false),
            orientation, null,
        )
    }

    private fun toLinear(bmp: Bitmap): LinearImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return LinearImage.fromArgb(px, bmp.width, bmp.height)
    }

    /**
     * Renders [settings] at full resolution and saves a JPEG to Pictures/[EXPORT_ALBUM], with the
     * original's EXIF (date, camera, lens, exposure, GPS). Returns the new file.
     */
    fun export(context: Context, opened: OpenedPhoto, settings: Settings, progress: (Float) -> Unit): Uri {
        val photo = opened.photo
        val bitmap: Bitmap
        if (opened.raw != null) {
            val raw = opened.raw
            val source = DevelopSource(LinearImage(1, 1), opened.preview.color, opened.preview.baked, true)
            val renderer = Renderer(source, settings)
            bitmap = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
            val band = 128
            val buf = LinearImage(raw.width, band)
            val px = IntArray(raw.width * band)
            var y = 0
            while (y < raw.height) {
                val y1 = minOf(raw.height, y + band)
                Demosaic.fullRows(raw, y, y1, buf)
                renderer.render(buf, px, 0, y1 - y)
                bitmap.setPixels(px, 0, raw.width, 0, y, raw.width, y1 - y)
                y = y1
                progress(0.9f * y / raw.height)
            }
        } else {
            val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            bitmap = context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("can't decode ${photo.name}")
            val renderer = Renderer(opened.preview, settings)
            val band = 128
            val px = IntArray(bitmap.width * band)
            var y = 0
            while (y < bitmap.height) {
                val rows = minOf(band, bitmap.height - y)
                bitmap.getPixels(px, 0, bitmap.width, 0, y, bitmap.width, rows)
                val lin = LinearImage.fromArgb(px, bitmap.width, rows)
                renderer.render(lin, px, 0, rows)
                bitmap.setPixels(px, 0, bitmap.width, 0, y, bitmap.width, rows)
                y += rows
                progress(0.9f * y / bitmap.height)
            }
        }

        val name = photo.name.substringBeforeLast('.') + "-edit.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$EXPORT_ALBUM")
            put(MediaStore.Images.Media.DATE_TAKEN, photo.takenMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            progress(0.97f)
            runCatching { copyExif(context, photo.uri, uri, opened.orientation) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        progress(1f)
        return uri
    }

    private fun copyExif(context: Context, from: Uri, to: Uri, orientation: Int) {
        val src = context.contentResolver.openInputStream(from)!!.use { ExifInterface(it) }
        context.contentResolver.openFileDescriptor(to, "rw")!!.use { pfd ->
            val dst = ExifInterface(pfd.fileDescriptor)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            // Pixels are written as the sensor saw them; the tag turns them upright.
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "A7III Connect")
            dst.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(System.currentTimeMillis()),
            )
            dst.saveAttributes()
        }
    }

    private fun exifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrDefault(1)

    fun rotate(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxEdge) s *= 2
        return s
    }

    const val EXPORT_ALBUM = "A7III Edits"

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_MAP_DATUM,
    )
}
