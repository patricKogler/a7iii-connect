package dev.kogler.a7iiiconnect.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPlanTest {
    private val jpg = CardFile(1, "DSC01234.JPG", 6_000_000, 1_000_000)
    private val arw = CardFile(2, "DSC01234.ARW", 25_000_000, 1_000_000)
    private val newer = CardFile(3, "DSC01235.ARW", 25_000_000, 2_000_000)
    private val clip = CardFile(4, "C0001.MP4", 90_000_000, 3_000_000)

    @Test
    fun photosOnlyNewestFirst() {
        assertEquals(listOf(newer, jpg, arw), UsbPlan.select(listOf(jpg, arw, newer, clip), emptySet(), emptySet()))
    }

    @Test
    fun skipsCopiedAndAlreadyInAlbum() {
        val picked = UsbPlan.select(listOf(jpg, arw, newer), copied = setOf(jpg.key), inAlbum = setOf(newer.name to newer.size))
        assertEquals(listOf(arw), picked)
    }

    @Test
    fun sameNameDifferentSizeIsANewShot() {
        // The camera's counter wrapped: same file name, different photo.
        val picked = UsbPlan.select(listOf(newer), emptySet(), inAlbum = setOf(newer.name to 1L))
        assertEquals(listOf(newer), picked)
    }
}
