package dev.kogler.a7iiiconnect.develop

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Photos shared into Shootback from another app (Google Photos, WhatsApp, Signal, Files): the
 * rules for copying each into the library once, kept free of Android so they can be tested on
 * the JVM. [SharedPhotos] does the copying.
 */
object SharedImport {
    /** The folder under the library's album that shared photos are copied to. */
    const val FOLDER = "Shared"

    /** A file shared in before: its bytes' SHA-256, the library photo it became, and the app it came from. */
    data class Entry(val hash: String, val key: String, val source: String)

    /** One line per entry: `hash key source`, tab-separated. */
    fun encode(entries: Collection<Entry>): String = buildString {
        entries.sortedBy { it.hash }.forEach { append("${it.hash}\t${it.key}\t${it.source}\n") }
    }

    fun decode(text: String): Map<String, Entry> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size >= 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) Entry(p[0], p[1], p.getOrElse(2) { "" }) else null
    }.associateBy { it.hash }

    /**
     * The library photo (by key) a shared file already is, or null to copy it in: the same bytes
     * shared before, or a photo of the same name taken at the same moment (a camera shot shared
     * back from Google Photos). [keys] are the library's photo keys, `name@takenMillis`.
     */
    fun existing(hash: String, name: String, taken: Long?, index: Map<String, Entry>, keys: Collection<String>): String? {
        index[hash]?.key?.let { if (it in keys) return it }
        if (taken == null) return null
        val wanted = bareName(name)
        return keys.firstOrNull { key ->
            val at = key.substringAfterLast('@').toLongOrNull() ?: return@firstOrNull false
            bareName(key.substringBeforeLast('@')) == wanted && sameMoment(at, taken)
        }
    }

    /**
     * Two capture times of one shot: equal to the second, or a whole time zone apart, since EXIF
     * without an offset is read as local time by some and as UTC by others.
     */
    fun sameMoment(a: Long, b: Long): Boolean {
        val d = abs(a - b)
        if (d > MAX_ZONE_MS) return false
        val off = d % QUARTER_HOUR_MS
        return off <= SAME_MOMENT_MS || QUARTER_HOUR_MS - off <= SAME_MOMENT_MS
    }

    /** "DSC01234 (1).JPG" and "dsc01234.jpg" are the same name: MediaStore's copy suffix and case aside. */
    private fun bareName(name: String) = name.replace(COPY_SUFFIX, "").lowercase()

    /**
     * The name the copy gets: the shared name as a .jpg (the library holds JPEGs; anything else is
     * converted), or one made from the time when the app gave none.
     */
    fun fileName(display: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val stem = display?.substringBeforeLast('.')?.trim()?.replace(UNSAFE, "_")?.takeIf { it.isNotEmpty() }
            ?: "Shared_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = zone }.format(now)
        val ext = display?.substringAfterLast('.', "") ?: ""
        return if (ext.equals("jpg", true) || ext.equals("jpeg", true)) "$stem.$ext" else "$stem.jpg"
    }

    /**
     * When EXIF says the photo was taken: DateTimeOriginal ("2026:10:25 14:18:04"), at its offset
     * ("+02:00") or else in [zone]. Null when it's missing or garbled, as after WhatsApp or Signal.
     */
    fun exifTime(value: String?, offset: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val text = value?.trim()?.takeIf { it.length >= 19 && !it.startsWith("0000") } ?: return null
        val tz = offset?.trim()?.takeIf { OFFSET.matches(it) }?.let { TimeZone.getTimeZone("GMT$it") } ?: zone
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = tz; isLenient = false }.parse(text.substring(0, 19))?.time
        }.getOrNull()
    }

    /** [millis] as EXIF DateTimeOriginal and its OffsetTimeOriginal, in [zone]. */
    fun exifText(millis: Long, zone: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val time = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(millis)
        val minutes = zone.getOffset(millis) / 60_000
        val offset = "%s%02d:%02d".format(Locale.US, if (minutes < 0) "-" else "+", abs(minutes) / 60, abs(minutes) % 60)
        return time to offset
    }

    /** The app a share came from, by package, as a word for people. */
    fun sourceLabel(source: String): String = when {
        source.isEmpty() -> "another app"
        "whatsapp" in source -> "WhatsApp"
        source.startsWith("org.thoughtcrime.securesms") -> "Signal"
        source == "com.google.android.apps.photos" -> "Google Photos"
        "telegram" in source -> "Telegram"
        source.startsWith("com.google.android.apps.messaging") -> "Messages"
        source.startsWith("com.google.android.gm") -> "Gmail"
        source.startsWith("com.google.android.documentsui") || source.startsWith("com.android.documentsui") -> "Files"
        else -> source.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** EXIF keeps whole seconds and MediaStore can be a second off, so this is "the same moment". */
    private const val SAME_MOMENT_MS = 2_000L
    private const val QUARTER_HOUR_MS = 15 * 60_000L
    private const val MAX_ZONE_MS = 14 * 60 * 60_000L
    private val COPY_SUFFIX = Regex(" \\(\\d+\\)(?=\\.[^.]*$)")
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\t\\n@]")
    private val OFFSET = Regex("[+-]\\d{2}:\\d{2}")
}
