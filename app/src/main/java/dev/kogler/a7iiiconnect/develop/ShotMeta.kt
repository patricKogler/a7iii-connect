package dev.kogler.a7iiiconnect.develop

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * What the camera wrote about one shot, for filtering the library. Any field can be missing
 * (a JPEG from somewhere else, a manual lens without contacts).
 */
data class ShotMeta(
    val lens: String? = null,
    /** Focal length in mm. */
    val focal: Float? = null,
    val aperture: Float? = null,
    /** Shutter speed in seconds. */
    val exposure: Double? = null,
    val iso: Int? = null,
    /** Exposure compensation in stops. */
    val bias: Float? = null,
    /** EXIF ExposureProgram: 1 manual, 2 program, 3 aperture priority, 4 shutter priority. */
    val program: Int? = null,
    val flash: Boolean? = null,
    /** The camera was held upright. */
    val portrait: Boolean? = null,
    val located: Boolean = false,
) {
    fun encode(): String = listOf(
        lens?.replace('\t', ' ')?.replace('\n', ' '), focal, aperture, exposure, iso, bias, program,
        flash?.let { if (it) 1 else 0 }, portrait?.let { if (it) 1 else 0 }, if (located) 1 else 0,
    ).joinToString("\t") { it?.toString() ?: "" }

    companion object {
        fun decode(fields: List<String>): ShotMeta? {
            if (fields.size != 10) return null
            fun f(i: Int) = fields[i].ifEmpty { null }
            return ShotMeta(
                lens = f(0),
                focal = f(1)?.toFloatOrNull(),
                aperture = f(2)?.toFloatOrNull(),
                exposure = f(3)?.toDoubleOrNull(),
                iso = f(4)?.toIntOrNull(),
                bias = f(5)?.toFloatOrNull(),
                program = f(6)?.toIntOrNull(),
                flash = f(7)?.let { it == "1" },
                portrait = f(8)?.let { it == "1" },
                located = f(9) == "1",
            )
        }

        fun of(exif: ExifInterface): ShotMeta {
            fun double(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() && it > 0 }
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            return ShotMeta(
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() && it != "----" },
                focal = double(ExifInterface.TAG_FOCAL_LENGTH)?.toFloat(),
                aperture = double(ExifInterface.TAG_F_NUMBER)?.toFloat(),
                exposure = double(ExifInterface.TAG_EXPOSURE_TIME),
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                bias = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0).toFloat() },
                program = exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, 0).takeIf { it > 0 },
                // Bit 0 of the EXIF Flash value: it fired.
                flash = exif.getAttribute(ExifInterface.TAG_FLASH)?.let { exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1 },
                portrait = if (orientation == 0) null else orientation in 5..8,
                located = exif.latLong != null,
            )
        }
    }
}

/**
 * The library's metadata, read once per shot and kept in develop/meta.txt, so filtering never
 * opens a file. Reads the JPEG twin when there is one (quicker than the ARW, same EXIF).
 */
object MetaIndex {
    private var cache: MutableMap<String, ShotMeta>? = null

    /** Everything already indexed. */
    @Synchronized
    fun known(context: Context): Map<String, ShotMeta> = HashMap(load(context))

    /** Reads the shots not indexed yet, reporting what it has after every [batch]. */
    fun index(context: Context, shots: List<Shot>, batch: Int = 25, keepGoing: () -> Boolean = { true }, onProgress: (Map<String, ShotMeta>) -> Unit) {
        val missing = synchronized(this) { load(context).let { have -> shots.filter { it.key !in have } } }
        if (missing.isEmpty()) return
        for (chunk in missing.chunked(batch)) {
            if (!keepGoing()) return
            val read = chunk.mapNotNull { shot -> read(context, shot)?.let { shot.key to it } }
            synchronized(this) { load(context).putAll(read); save(context) }
            onProgress(known(context))
        }
    }

    private fun read(context: Context, shot: Shot): ShotMeta? {
        for (photo in listOfNotNull(shot.jpeg, shot.raw)) {
            runCatching {
                context.contentResolver.openInputStream(photo.uri)?.use { return ShotMeta.of(ExifInterface(it)) }
            }
        }
        return null
    }

    private fun file(context: Context) = File(context.filesDir, "develop/meta.txt")

    private fun load(context: Context): MutableMap<String, ShotMeta> = cache ?: runCatching {
        file(context).readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            ShotMeta.decode(parts.drop(1))?.let { parts[0] to it }
        }.toMap(HashMap())
    }.getOrDefault(HashMap()).also { cache = it }

    private fun save(context: Context) {
        val m = cache ?: return
        runCatching {
            file(context).apply { parentFile?.mkdirs() }
                .writeText(m.entries.joinToString("") { "${it.key}\t${it.value.encode()}\n" })
        }
    }
}
