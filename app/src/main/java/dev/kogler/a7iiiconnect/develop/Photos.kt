package dev.kogler.a7iiiconnect.develop

import android.content.ContentUris
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
import dev.kogler.a7iiiconnect.develop.engine.FullImage
import dev.kogler.a7iiiconnect.develop.engine.FullSampler
import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.Guide
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.RawImage
import dev.kogler.a7iiiconnect.develop.engine.Region
import dev.kogler.a7iiiconnect.develop.engine.Sampler
import dev.kogler.a7iiiconnect.develop.engine.Settings
import dev.kogler.a7iiiconnect.develop.engine.sensorBounds
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** A photo opened for editing: a screen-sized preview, plus the full raw kept for export. */
class OpenedPhoto(
    val photo: LibraryPhoto,
    /** Upright preview, longer edge [PREVIEW_EDGE]. */
    val preview: DevelopSource,
    /** Smaller copy rendered while a slider is being dragged. */
    val draft: DevelopSource,
    val orientation: Int,
    val raw: RawImage?,
) {
    fun geometry(s: Settings, ignoreCrop: Boolean = false) =
        Geometry(s, preview.fullWidth.toDouble(), preview.fullHeight.toDouble(), preview.lens, ignoreCrop)
}

/** Export size and JPEG quality, like Lightroom's export options. */
data class ExportOptions(val maxEdge: Int? = null, val quality: Int = 92) {
    companion object {
        val SIZES = listOf<Pair<String, Int?>>("Full size" to null, "3000 px" to 3000, "2048 px" to 2048, "1080 px" to 1080)
        val QUALITIES = listOf("Maximum" to 100, "High" to 92, "Medium" to 80)
    }
}

const val PREVIEW_EDGE = 2048
private const val DRAFT_EDGE = 1024

object Photos {

    private val thumbs = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(96L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    fun cachedThumbnail(photo: LibraryPhoto): Bitmap? = thumbs.get(photo.id)

    /**
     * Grid thumbnail: the JPEG embedded in a raw, or the system thumbnail for a JPEG. Its shorter
     * edge is at least [edge] pixels (the cell it fills; a library grid cell by default), so the
     * square crop never gets stretched. A cached one too small for [edge] is loaded again bigger.
     */
    fun thumbnail(context: Context, photo: LibraryPhoto, edge: Int = thumbEdge(context)): Bitmap? {
        thumbs.get(photo.id)?.takeIf { minOf(it.width, it.height) >= edge }?.let { return it }
        val bmp = runCatching {
            // A phone's DNG has no quick preview inside; its system thumbnail is quick, like a JPEG's.
            if (photo.isRaw && !photo.isDng) embeddedPreview(context, photo.uri, edge, shortEdge = true)
            else runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(edge * 3 / 2, edge * 3 / 2), null) }
                .getOrNull()
                // Some phones keep only small system thumbnails; those would look soft, so decode the photo.
                ?.takeIf { minOf(it.width, it.height) >= edge }
                ?: decodeJpeg(context, photo.uri, edge, shortEdge = true)
        }.getOrNull()?.let { fitShortEdge(it, edge) } ?: return null
        thumbs.put(photo.id, bmp)
        return bmp
    }

    /** [px] rounded up to a step of thumbnail sizes, so cells a few pixels apart share one. */
    fun thumbStep(px: Int): Int = ((px + 63) / 64 * 64).coerceIn(128, 1024)

    /**
     * Pixels across one library grid cell with the phone held upright: as many 100 dp columns as fit
     * between the grid's 4 dp padding, 3 dp apart, as the grid lays them out.
     */
    private fun thumbEdge(context: Context): Int {
        val dm = context.resources.displayMetrics
        val across = minOf(dm.widthPixels, dm.heightPixels) - 8 * dm.density
        val gap = 3 * dm.density
        val columns = ((across + gap) / (100 * dm.density + gap)).toInt().coerceAtLeast(1)
        return thumbStep(((across - gap * (columns - 1)) / columns).roundToInt())
    }

    /** Scales [bmp] down (filtered) so its shorter edge is [edge]; smaller pictures are left as they are. */
    private fun fitShortEdge(bmp: Bitmap, edge: Int): Bitmap {
        val short = minOf(bmp.width, bmp.height)
        if (short <= edge) return bmp
        val k = edge.toFloat() / short
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * The camera's own JPEG inside the ARW (1616x1080 on the A7III), turned upright, subsampled so
     * its longer edge (or shorter, with [shortEdge]) stays at least [maxEdge].
     */
    fun embeddedPreview(context: Context, uri: Uri, maxEdge: Int, shortEdge: Boolean = false): Bitmap? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src = ChannelSource(ch)
                val info = Arw.readInfo(src)
                // Phone DNGs often carry no JPEG: Android's own raw decoder draws one instead.
                val range = info.preview ?: return decodeJpeg(context, uri, maxEdge, shortEdge)
                val bytes = src.read(range.first, (range.last - range.first + 1).toInt())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, shortEdge) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                return rotate(bmp, info.orientation)
            }
        }
        return null
    }

    /**
     * The picture for the culling deck: the JPEG twin when there is one (full detail for zooming),
     * otherwise the camera's preview inside the RAW. Upright, longer edge about [maxEdge].
     */
    fun cullImage(context: Context, shot: Shot, maxEdge: Int): Bitmap? = runCatching {
        shot.jpeg?.let { decodeJpeg(context, it.uri, maxEdge) } ?: shot.raw?.let { embeddedPreview(context, it.uri, maxEdge) }
    }.getOrNull()

    /** Upright JPEG, subsampled so its longer edge (or shorter, with [shortEdge]) is at least [maxEdge] (or the full size). */
    private fun decodeJpeg(context: Context, uri: Uri, maxEdge: Int, shortEdge: Boolean = false): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, shortEdge) }
        val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return rotate(bmp, exifOrientation(context, uri))
    }

    /**
     * A sharp piece of a JPEG for checking focus: [area] is the visible part of the upright photo
     * (fractions 0..1), decoded at about [screenWidth] pixels across. Null for unusual orientations.
     */
    fun jpegRegion(context: Context, uri: Uri, area: android.graphics.RectF, screenWidth: Int): Bitmap? = runCatching {
        val orientation = exifOrientation(context, uri)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(input) ?: return@runCatching null
            try {
                val fw = decoder.width.toFloat()
                val fh = decoder.height.toFloat()
                // The visible upright area, in the file's own (unrotated) pixel grid.
                val (x0, x1, y0, y1) = when (orientation) {
                    ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED ->
                        listOf(area.left, area.right, area.top, area.bottom)
                    ExifInterface.ORIENTATION_ROTATE_180 -> listOf(1 - area.right, 1 - area.left, 1 - area.bottom, 1 - area.top)
                    ExifInterface.ORIENTATION_ROTATE_90 -> listOf(area.top, area.bottom, 1 - area.right, 1 - area.left)
                    ExifInterface.ORIENTATION_ROTATE_270 -> listOf(1 - area.bottom, 1 - area.top, area.left, area.right)
                    else -> return@runCatching null
                }
                val rect = android.graphics.Rect(
                    (x0 * fw).toInt().coerceIn(0, decoder.width - 1), (y0 * fh).toInt().coerceIn(0, decoder.height - 1),
                    (x1 * fw).toInt().coerceIn(1, decoder.width), (y1 * fh).toInt().coerceIn(1, decoder.height),
                )
                if (rect.width() <= 0 || rect.height() <= 0) return@runCatching null
                val across = if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) rect.height() else rect.width()
                var sample = 1
                while (across / (sample * 2) >= screenWidth) sample *= 2
                val bmp = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                rotate(bmp, orientation)
            } finally {
                decoder.recycle()
            }
        }
    }.getOrNull()

    /** Decodes a photo for editing. Raw: half-size demosaic; JPEG: subsampled decode. */
    fun open(context: Context, photo: LibraryPhoto): OpenedPhoto {
        if (photo.isRaw) {
            context.contentResolver.openFileDescriptor(photo.uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val src = ChannelSource(ch)
                    val info = Arw.readInfo(src)
                    val raw = Arw.decode(src, info)
                    val color = info.color
                    val half = Demosaic.halfSize(raw)
                    val preview = half.downscaled(PREVIEW_EDGE).oriented(info.orientation)
                    val swap = info.orientation >= 5
                    val source = DevelopSource(
                        preview, color, info.asShot, true,
                        if (swap) raw.height else raw.width, if (swap) raw.width else raw.height, info.lens,
                    )
                    return OpenedPhoto(photo, source, source.withImage(preview.downscaled(DRAFT_EDGE)), info.orientation, raw)
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
        val swap = orientation >= 5
        val source = DevelopSource(
            lin, CameraColor.SRGB, one, false,
            if (swap) bounds.outHeight else bounds.outWidth, if (swap) bounds.outWidth else bounds.outHeight,
        )
        return OpenedPhoto(photo, source, source.withImage(lin.downscaled(DRAFT_EDGE)), orientation, null)
    }

    /** Upright linear pixels of a JPEG, subsampled to at least [minEdge] on the long side. */
    private fun decodeLinear(context: Context, photo: LibraryPhoto, minEdge: Int): LinearImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, minEdge) }
        val bmp = context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("can't decode ${photo.name}")
        return toLinear(bmp).also { bmp.recycle() }.oriented(exifOrientation(context, photo.uri))
    }

    /**
     * Sharp zoom: renders [region] of the photo from the full-resolution raw (only the sensor
     * area it needs is demosaiced). Null for JPEGs, whose preview is already their best.
     */
    fun renderTile(opened: OpenedPhoto, settings: Settings, guide: Guide?, region: Region): Bitmap? {
        val raw = opened.raw ?: return null
        val geom = opened.geometry(settings)
        val look = Look(opened.preview, settings)
        val b = sensorBounds(geom, opened.orientation, raw.width, raw.height, region, look.margin(region.scale) + 8)
        val part = FullImage.demosaic(raw, b[0], b[1], b[2], b[3])
        val sampler = FullSampler(part, opened.orientation, geom, region)
        val bmp = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
        renderBands(look, sampler, guide, 256) { y, rows, px -> bmp.setPixels(px, 0, region.width, 0, y, region.width, rows) }
        return bmp
    }

    private fun toLinear(bmp: Bitmap): LinearImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return LinearImage.fromArgb(px, bmp.width, bmp.height)
    }

    /**
     * [settings] rendered from the opened preview (longer edge up to [maxEdge], at most
     * [PREVIEW_EDGE]): quick, since the RAW is already demosaiced. For showing, not for export.
     */
    fun renderPreview(opened: OpenedPhoto, settings: Settings, maxEdge: Int): Bitmap {
        val geom = opened.geometry(settings)
        val look = Look(opened.preview, settings)
        val guide = look.guide(opened.preview.image, geom)
        val sampler = ImageSampler(opened.preview.image, geom, maxEdge)
        val bitmap = Bitmap.createBitmap(sampler.width, sampler.height, Bitmap.Config.ARGB_8888)
        renderBands(look, sampler, guide) { y, rows, px -> bitmap.setPixels(px, 0, sampler.width, 0, y, sampler.width, rows) }
        return bitmap
    }

    /** [settings] rendered at the export size of [options] (the whole photo, upright). */
    fun render(context: Context, opened: OpenedPhoto, settings: Settings, options: ExportOptions, progress: (Float) -> Unit = {}): Bitmap {
        val photo = opened.photo
        // Subject masks need the photo's subject (from the store, or found now).
        if (settings.masks.any { it.usesSubject }) runCatching { SubjectFinder.find(context, EditStore(context), opened) }
        val geom = opened.geometry(settings)
        val look = Look(opened.preview, settings)
        // The large-radius maps come from the preview, so the export looks like the screen.
        val guide = look.guide(opened.preview.image, geom)
        val sampler: Sampler = when {
            options.maxEdge != null && options.maxEdge <= 3000 -> {
                // Small exports: resample a half-size (raw) or subsampled (JPEG) image.
                val img = if (opened.raw != null) Demosaic.halfSize(opened.raw).oriented(opened.orientation)
                else decodeLinear(context, photo, options.maxEdge * 2)
                ImageSampler(img, geom, options.maxEdge)
            }
            opened.raw != null -> FullSampler(FullImage.demosaic(opened.raw) { progress(0.4f * it) }, opened.orientation, geom)
            else -> {
                val bmp = context.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it) }
                    ?: error("can't decode ${photo.name}")
                val full = FullImage.fromArgb(bmp.width, bmp.height) { y, n, px -> bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, n) }
                bmp.recycle()
                FullSampler(full, opened.orientation, geom)
            }
        }
        progress(0.4f)
        var bitmap = Bitmap.createBitmap(sampler.width, sampler.height, Bitmap.Config.ARGB_8888)
        renderBands(look, sampler, guide) { y, rows, px ->
            bitmap.setPixels(px, 0, sampler.width, 0, y, sampler.width, rows)
            progress(0.4f + 0.5f * (y + rows) / sampler.height)
        }
        val edge = options.maxEdge
        if (edge != null && maxOf(bitmap.width, bitmap.height) > edge) {
            val k = edge.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt().coerceAtLeast(1), (bitmap.height * k).toInt().coerceAtLeast(1), true)
        }
        return bitmap
    }

    /**
     * Renders [settings] at the chosen size and saves a JPEG to Pictures/[EXPORT_ALBUM], with the
     * original's EXIF (date, camera, lens, exposure, GPS). Returns the new file.
     */
    fun export(
        context: Context, opened: OpenedPhoto, settings: Settings, options: ExportOptions,
        version: Int = 0, progress: (Float) -> Unit,
    ): Uri {
        val photo = opened.photo
        val bitmap = render(context, opened, settings, options, progress)

        val name = exportName(photo, version)
        val resolver = context.contentResolver
        // Re-exporting replaces the earlier export instead of adding "(1)", "(2)" copies.
        previousExport(context, photo, name)?.let { old ->
            val replaced = runCatching {
                resolver.openOutputStream(old, "wt")!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, it) }
            }.isSuccess
            if (replaced) {
                bitmap.recycle()
                progress(0.97f)
                runCatching { copyExif(context, photo.uri, old) }
                progress(1f)
                return old
            }
            // Not ours to overwrite (made before a reinstall): save a new one beside it.
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$EXPORT_ALBUM")
            put(MediaStore.Images.Media.DATE_TAKEN, photo.takenMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, it) }
            bitmap.recycle()
            progress(0.97f)
            runCatching { copyExif(context, photo.uri, uri) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        progress(1f)
        return uri
    }

    /** The first version exports as DSC01234-edit.jpg, later ones as DSC01234-edit-v2.jpg and so on. */
    fun exportName(photo: LibraryPhoto, version: Int = 0) = photo.stem + "-edit" + (if (version > 0) "-v${version + 1}" else "") + ".jpg"

    /** This photo's earlier export: same name, taken within a day (not a DSC01234 from another cycle). */
    private fun previousExport(context: Context, photo: LibraryPhoto, name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$EXPORT_ALBUM%", name), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.isNull(1) || kotlin.math.abs(c.getLong(1) - photo.takenMillis) < 24 * 3600_000L) {
                    return@runCatching ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
            null
        }
    }.getOrNull()

    internal fun copyExif(context: Context, from: Uri, to: Uri) {
        val src = context.contentResolver.openInputStream(from)!!.use { ExifInterface(it) }
        context.contentResolver.openFileDescriptor(to, "rw")!!.use { pfd ->
            val dst = ExifInterface(pfd.fileDescriptor)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            // Pixels are written upright.
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Shootback")
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

    private fun sampleSize(w: Int, h: Int, maxEdge: Int, shortEdge: Boolean = false): Int {
        val edge = if (shortEdge) minOf(w, h) else maxOf(w, h)
        var s = 1
        while (edge / (s * 2) >= maxEdge) s *= 2
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
