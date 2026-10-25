package dev.kogler.a7iiiconnect.develop

/**
 * How file names tell what a file is and which shot it belongs to, the same on the phone, in
 * Together and in the computer sync (the desktop's library.rs follows the same rules).
 */
object ShotNames {
    /** MediaStore's " (1)" on a second file of the same name. */
    private val COPY_SUFFIX = Regex(" \\(\\d+\\)$")

    /** A Pixel's RAW+JPEG pair: PXL_20261020_141803123.RAW-02.ORIGINAL.dng and PXL_….RAW-01.COVER.jpg (or .MP.COVER.jpg). */
    private val PIXEL_RAW = Regex("^(pxl_\\d{8}_\\d+)[._]raw-\\d+(\\..*)?$")

    /**
     * What the RAW and the JPEG of one shot share, lower case: dsc01234 for both DSC01234.ARW and
     * DSC01234.JPG, pxl_20261020_141803123 for both files of a Pixel's RAW+JPEG.
     */
    fun stem(name: String): String {
        val s = name.substringBeforeLast('.').replace(COPY_SUFFIX, "").lowercase()
        return PIXEL_RAW.find(s)?.groupValues?.get(1) ?: s
    }

    fun isRaw(name: String, mime: String = ""): Boolean =
        name.endsWith(".arw", true) || name.endsWith(".dng", true) || mime.contains("arw", true) || mime.contains("dng", true)

    /** HEIC/HEIF, as some phones (Vivo, Samsung, iPhones) save photos. */
    fun isHeif(name: String, mime: String = ""): Boolean =
        name.endsWith(".heic", true) || name.endsWith(".heif", true) || mime.startsWith("image/heic") || mime.startsWith("image/heif")

    /** A file the library shows: a RAW, a JPEG or a HEIC. */
    fun isPhoto(name: String, mime: String = ""): Boolean =
        isRaw(name, mime) || isHeif(name, mime) || mime.startsWith("image/jpeg") || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
}
