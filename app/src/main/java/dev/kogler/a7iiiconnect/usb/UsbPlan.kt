package dev.kogler.a7iiiconnect.usb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One file on the camera's card, as MTP describes it. */
data class CardFile(val handle: Int, val name: String, val size: Long, val created: Long) {
    val ext get() = name.substringAfterLast('.', "").lowercase()

    /**
     * Same shape as the Wi-Fi copy's key ("DSC01234.JPG|2026-10-14T12:34:56"), so a JPEG that
     * already came over Wi-Fi, or was copied and later deleted, isn't fetched again.
     */
    val key: String
        get() = "$name|" + if (created > 0) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(created)) else ""
}

object UsbPlan {
    /** Photos only; clips live in PRIVATE/M4ROOT and the library doesn't show them. */
    val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "arw", "hif", "heif", "heic")

    /** Folders on the card that never hold photos, skipped while walking it. */
    val SKIP_FOLDERS = setOf("PRIVATE", "AVF_INFO", "MP_ROOT")

    /**
     * What to copy: photos not copied before (by [CardFile.key]) and not already in the album
     * under the same name and size (FTP receives keep no list), newest first so the latest
     * shoot lands first if the phone fills up or the cable comes out.
     */
    fun select(files: List<CardFile>, copied: Set<String>, inAlbum: Set<Pair<String, Long>>): List<CardFile> =
        files.filter { it.ext in PHOTO_EXTENSIONS && it.key !in copied && (it.name to it.size) !in inAlbum }
            .sortedByDescending { it.created }
}
