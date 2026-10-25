package dev.kogler.a7iiiconnect.develop.engine

import dev.kogler.a7iiiconnect.develop.ShotNames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-style DNGs (made by PiDNG, checked against LibRaw): 64x48 BGGR, black 64, white 1023,
 * values 64 + (7x + 13y) mod 900, stored plain, packed 12-bit, as lossless JPEG, and with a gain map.
 */
class DngTest {
    private fun load(name: String) = ArraySource(javaClass.getResourceAsStream("/dng/$name.dng")!!.readBytes())

    private fun expected(x: Int, y: Int) = 64 + (x * 7 + y * 13) % 900

    private fun assertPattern(img: RawImage) {
        assertEquals(64, img.rawWidth)
        assertEquals(48, img.rawHeight)
        for (y in 0 until 48) for (x in 0 until 64) assertEquals("at $x,$y", expected(x, y), img.at(y, x))
    }

    @Test
    fun readsTheTags() {
        val info = Arw.readInfo(load("plain16"))
        assertEquals("Google", info.make)
        assertEquals("Pixel 8a", info.model)
        assertEquals(6, info.orientation)
        assertArrayEquals(intArrayOf(64, 64, 64), info.black)
        assertEquals(1023, info.white)
        assertArrayEquals(floatArrayOf(2f, 1f, 1.5f), info.asShot, 1e-4f)
        // The D65 matrix (illuminant 2), not standard A's.
        assertArrayEquals(doubleArrayOf(0.7, -0.2, -0.05, -0.45, 1.25, 0.2, -0.1, 0.18, 0.6), info.colorMatrix!!, 1e-9)
        val img = Arw.decode(load("plain16"), info)
        assertArrayEquals(intArrayOf(2, 1, 1, 0), img.cfa)
        assertEquals(listOf(2, 2, 60, 44), listOf(img.left, img.top, img.width, img.height))
    }

    @Test
    fun decodesPlainPackedAndLosslessData() {
        assertPattern(Arw.decode(load("plain16")))
        assertPattern(Arw.decode(load("packed12")))
        assertPattern(Arw.decode(load("lossless")))
    }

    @Test
    fun appliesTheLensShadingGainMap() {
        val info = Arw.readInfo(load("gain"))
        assertEquals(4, info.gainMaps.size)
        val img = Arw.decode(load("gain"), info)
        // Every plane doubled above black.
        for (y in 0 until 48) for (x in 0 until 64) assertEquals("at $x,$y", 64 + 2 * (expected(x, y) - 64), img.at(y, x))
    }

    @Test
    fun gainMapsInterpolateBetweenPoints() {
        val m = GainMap(0, 0, 10, 10, 0, 1, 1, 1, 2, 2, 1.0, 1.0, 0.0, 0.0, 1, floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(1f, m.gain(0.0, 0.0), 1e-6f)
        assertEquals(2.5f, m.gain(0.5, 0.5), 1e-6f)
        assertEquals(4f, m.gain(2.0, 2.0), 1e-6f) // beyond the last point: the edge
    }

    @Test
    fun developsWithItsOwnMatrix() {
        val info = Arw.readInfo(load("plain16"))
        val img = Arw.decode(load("plain16"), info)
        assertNotNull(info.color.rgbFromCam)
        val half = Demosaic.halfSize(img)
        assertEquals(30, half.width)
        assertEquals(22, half.height)
        assertTrue(listOf(half.r, half.g, half.b).all { ch -> ch.all { it in 0f..1f } })
    }

    @Test
    fun pairsAPixelRawWithItsCover() {
        assertEquals("pxl_20261020_141803123", ShotNames.stem("PXL_20261020_141803123.RAW-02.ORIGINAL.dng"))
        assertEquals("pxl_20261020_141803123", ShotNames.stem("PXL_20261020_141803123.RAW-01.MP.COVER.jpg"))
        assertEquals("pxl_20261020_141803123", ShotNames.stem("PXL_20261020_141803123.jpg"))
        assertEquals("dsc01234", ShotNames.stem("DSC01234 (1).JPG"))
        assertEquals("img_20261020_141803", ShotNames.stem("IMG_20261020_141803.dng"))
        assertTrue(ShotNames.isRaw("PXL_20261020_141803123.RAW-02.ORIGINAL.dng"))
        assertTrue(ShotNames.isPhoto("IMG_20261020_141803.heic"))
    }
}
