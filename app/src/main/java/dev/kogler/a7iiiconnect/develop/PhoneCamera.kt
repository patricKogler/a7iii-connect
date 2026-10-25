package dev.kogler.a7iiiconnect.develop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Calendar

/**
 * Photos taken with this phone's own camera, shown in the library where they are (DCIM/Camera),
 * without copying them. Off until turned on; then from a start the person picks, so years of
 * old snaps don't flood in. Shots can be hidden from Shootback without deleting them.
 */
object PhoneCamera {
    /** Where camera apps save: Google Camera, Vivo's and most others. */
    val FOLDER = "${Environment.DIRECTORY_DCIM}/Camera/"

    enum class Start(val label: String) { TODAY("From today"), MONTH("Last 30 days"), ALL("Everything") }

    private const val PREFS = "library"

    /** What asking for photo access asks for: on Android 14 and later, picking some photos is an answer too. */
    val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            // Without it Android blanks the location in other apps' photos, and Places can't name them.
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()

    private fun granted(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Shootback may see every photo. */
    fun fullAccess(context: Context) = granted(context, Manifest.permission.READ_MEDIA_IMAGES)

    /** Shootback may see only the photos the person picked when asked (Android 14 and later). */
    fun partialAccess(context: Context) = !fullAccess(context) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("phone_camera", false)

    /** Turned on and allowed to look. */
    fun on(context: Context) = enabled(context) && (fullAccess(context) || partialAccess(context))

    fun start(context: Context): Start =
        context.getSharedPreferences(PREFS, 0).getString("phone_start", null)?.let { n -> Start.entries.find { it.name == n } } ?: Start.TODAY

    /** Phone shots taken before this don't show. */
    fun since(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong("phone_since", 0L)

    fun setEnabled(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val edit = prefs.edit().putBoolean("phone_camera", on)
        // First time on: from today, unless a start was picked before.
        if (on && !prefs.contains("phone_since")) edit.putString("phone_start", Start.TODAY.name).putLong("phone_since", sinceFor(Start.TODAY, System.currentTimeMillis()))
        edit.apply()
    }

    fun setStart(context: Context, start: Start) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("phone_start", start.name)
            .putLong("phone_since", sinceFor(start, System.currentTimeMillis()))
            .apply()
    }

    /** The first moment [start] takes in, chosen at [now]: today means since midnight. */
    fun sinceFor(start: Start, now: Long): Long = when (start) {
        Start.TODAY -> Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        Start.MONTH -> now - 30L * 24 * 60 * 60 * 1000
        Start.ALL -> 0L
    }

    private fun hiddenFile(context: Context) = File(File(context.filesDir, "develop").apply { mkdirs() }, "hidden.txt")

    /** Photo keys hidden from Shootback: still on the phone, out of the library, the deck and the sync. */
    fun hidden(context: Context): Set<String> =
        runCatching { hiddenFile(context).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    fun hide(context: Context, keys: Collection<String>) {
        val all = hidden(context) + keys
        hiddenFile(context).writeText(all.sorted().joinToString("\n", postfix = "\n"))
    }

    fun unhideAll(context: Context) {
        hiddenFile(context).delete()
    }
}
