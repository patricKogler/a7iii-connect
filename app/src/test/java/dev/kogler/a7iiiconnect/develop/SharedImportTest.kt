package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class SharedImportTest {
    private val vienna = TimeZone.getTimeZone("Europe/Vienna")

    @Test
    fun theSameBytesSharedAgainAreNotCopiedTwice() {
        val index = mapOf("abc" to SharedImport.Entry("abc", "IMG-20261025-WA0003.jpg@1761400000000", "com.whatsapp"))
        val keys = listOf("IMG-20261025-WA0003.jpg@1761400000000")
        assertEquals(keys[0], SharedImport.existing("abc", "IMG-20261025-WA0007.jpg", null, index, keys))
        // Deleted from the library since: copied in again.
        assertNull(SharedImport.existing("abc", "IMG-20261025-WA0007.jpg", null, index, emptyList()))
    }

    @Test
    fun aCameraShotSharedBackIsTheOneInTheLibrary() {
        val keys = listOf("DSC01234.ARW@1761400000000", "DSC01234.JPG@1761400000000")
        assertEquals("DSC01234.JPG@1761400000000", SharedImport.existing("x", "DSC01234 (1).jpg", 1761400001000, emptyMap(), keys))
        // Same name, another day: a different shot.
        assertNull(SharedImport.existing("x", "DSC01234.JPG", 1761400000000 + 86_400_000, emptyMap(), keys))
        // EXIF without an offset, read as UTC on one side and local time on the other.
        assertEquals("DSC01234.JPG@1761400000000", SharedImport.existing("x", "DSC01234.JPG", 1761400000000 + 7_200_000, emptyMap(), keys))
        assertNull(SharedImport.existing("x", "DSC01234.JPG", 1761400000000 + 7_620_000, emptyMap(), keys))
        // No capture time (stripped by the messenger): only the bytes can tell.
        assertNull(SharedImport.existing("x", "DSC01234.JPG", null, emptyMap(), keys))
    }

    @Test
    fun namesTheCopyAsAJpeg() {
        assertEquals("IMG-20261025-WA0003.jpg", SharedImport.fileName("IMG-20261025-WA0003.jpg", 0))
        assertEquals("signal-2026-10-25-141804.jpg", SharedImport.fileName("signal-2026-10-25-141804.png", 0))
        assertEquals("PXL_20261025_141803123.JPEG", SharedImport.fileName("PXL_20261025_141803123.JPEG", 0))
        assertEquals("Shared_20261020_161804.jpg", SharedImport.fileName(null, 1792505884000, vienna))
        assertEquals("a_b.jpg", SharedImport.fileName("a/b.heic", 0))
    }

    @Test
    fun readsAndWritesExifTimes() {
        val t = SharedImport.exifTime("2026:10:20 16:18:04", "+02:00")
        assertEquals(1792505884000, t)
        assertEquals(1792505884000, SharedImport.exifTime("2026:10:20 16:18:04", null, vienna))
        assertNull(SharedImport.exifTime("0000:00:00 00:00:00", null))
        assertNull(SharedImport.exifTime("", null))
        assertNull(SharedImport.exifTime(null, null))
        assertEquals("2026:10:20 16:18:04" to "+02:00", SharedImport.exifText(1792505884000, vienna))
        val (time, offset) = SharedImport.exifText(1792505884000, TimeZone.getTimeZone("America/New_York"))
        assertEquals(1792505884000, SharedImport.exifTime(time, offset))
    }

    @Test
    fun readsWhatItWrites() {
        val entries = listOf(SharedImport.Entry("a", "x.jpg@1", "com.whatsapp"), SharedImport.Entry("b", "y.jpg@2", ""))
        assertEquals(entries.associateBy { it.hash }, SharedImport.decode(SharedImport.encode(entries)))
    }

    @Test
    fun namesTheAppItCameFrom() {
        assertEquals("WhatsApp", SharedImport.sourceLabel("com.whatsapp"))
        assertEquals("Signal", SharedImport.sourceLabel("org.thoughtcrime.securesms"))
        assertEquals("Google Photos", SharedImport.sourceLabel("com.google.android.apps.photos"))
        assertEquals("another app", SharedImport.sourceLabel(""))
    }
}
