package dev.kogler.a7iiiconnect.develop.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DngLookTest {
    @Test
    fun theShoulderFitsLiftedHighlightsIntoTheCurve() {
        val sh = DngLook.Shoulder(3.2)
        assertEquals(0.3, sh.apply(0.3), 0.0)
        assertEquals(1.0, sh.apply(3.2), 1e-9)
        // Same slope either side of the knee, and rising all the way.
        val k = DngLook.Shoulder.KNEE
        assertEquals(1.0, (sh.apply(k + 1e-6) - sh.apply(k)) / 1e-6, 1e-3)
        var prev = 0.0
        for (i in 0..320) {
            val y = sh.apply(i / 100.0)
            assertTrue(y >= prev)
            prev = y
        }
        assertEquals(0.9, DngLook.Shoulder(0.8).apply(0.9), 0.0)
    }

    @Test
    fun theProfileCurveInterpolates() {
        val c = floatArrayOf(0f, 0f, 0.5f, 0.4f, 1f, 1f)
        assertEquals(0.2, DngLook.curve(c, 0.25), 1e-6)
        assertEquals(0.7, DngLook.curve(c, 0.75), 1e-6)
        assertEquals(1.0, DngLook.curve(c, 2.0), 1e-6)
    }

    @Test
    fun theBlackFloorIsTheDarkEndOfTheShot() {
        val img = LinearImage(100, 100)
        for (i in 0 until 10_000) { val v = 0.004f + (i % 100) / 100f; img.r[i] = v; img.g[i] = v; img.b[i] = v }
        assertEquals(0.004f, DngLook.blackFloor(img), 1e-6f)
        for (i in 0 until 10_000) { img.r[i] = 0.5f; img.g[i] = 0.5f; img.b[i] = 0.5f }
        assertEquals(0.01f, DngLook.blackFloor(img), 0f)
    }
}
