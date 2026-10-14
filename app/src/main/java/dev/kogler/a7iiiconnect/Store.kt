package dev.kogler.a7iiiconnect

import android.content.Context
import java.io.File

/** Saved camera credentials and the list of photos already copied to the phone. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private val copiedFile = File(context.filesDir, "copied.txt")

    init {
        // Carry over what was typed into the link-test build.
        val old = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        if (!prefs.contains("ssid") && old.contains("ssid")) {
            prefs.edit().putString("ssid", old.getString("ssid", "")).putString("pass", old.getString("pass", "")).apply()
        }
    }

    var ssid: String
        get() = prefs.getString("ssid", "") ?: ""
        set(v) = prefs.edit().putString("ssid", v).apply()

    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    var smallCopies: Boolean
        get() = prefs.getBoolean("small", false)
        set(v) = prefs.edit().putBoolean("small", v).apply()

    /** Bluetooth address of the camera that receives the phone's location. */
    var gpsCameraAddress: String
        get() = prefs.getString("gpsAddress", "") ?: ""
        set(v) = prefs.edit().putString("gpsAddress", v).apply()

    var gpsEnabled: Boolean
        get() = prefs.getBoolean("gps", false)
        set(v) = prefs.edit().putBoolean("gps", v).apply()

    val hasCamera get() = ssid.isNotBlank()

    @Synchronized
    fun copiedKeys(): MutableSet<String> =
        if (copiedFile.exists()) copiedFile.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()

    @Synchronized
    fun markCopied(key: String) = copiedFile.appendText(key + "\n")

    companion object {
        /** File names repeat when the camera's counter wraps, so the capture time is part of the key. */
        fun keyFor(item: CameraItem) = "${item.title}|${item.date ?: ""}"
    }
}
