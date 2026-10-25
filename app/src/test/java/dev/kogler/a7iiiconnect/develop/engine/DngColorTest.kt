package dev.kogler.a7iiiconnect.develop.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DNG white balance and matrix mixing, with the tags of a Pixel 8a shot under tungsten light. */
class DngColorTest {
    private val cm1 = doubleArrayOf(1.1599, -0.6349, 0.0242, -0.3076, 1.1736, 0.1491, 0.0019, 0.1402, 0.6484)
    private val cm2 = doubleArrayOf(0.8395, -0.1991, -0.1213, -0.585, 1.4801, 0.1018, -0.188, 0.4021, 0.4593)
    private val cc1 = Mat3.diag(0.9957, 1.0, 0.9745)
    private val cc2 = Mat3.diag(0.9959, 1.0, 0.9816)
    private val a = DngColor.Calibration(cm1, cc1, 17)
    private val d65 = DngColor.Calibration(cm2, cc2, 21)

    @Test
    fun aPixelsWhiteXyGivesItsTungstenBalance() {
        val (m, mul) = DngColor.resolve(a, d65, doubleArrayOf(1.0, 1.0, 1.0), null, doubleArrayOf(0.426327, 0.404567))
        // Tungsten: red already strong, blue needs a big lift.
        assertTrue("red ${mul[0]}", mul[0] in 0.6f..1.6f)
        assertTrue("blue ${mul[2]}", mul[2] > 1.8f)
        // About 3000 K: the matrix is almost all of standard A's.
        val color = CameraColor(m!!)
        val (kelvin, _) = color.temperatureOf(mul)
        assertEquals(2950.0, kelvin, 250.0)
    }

    @Test
    fun aNeutralAndItsXyAgree() {
        val xy = doubleArrayOf(0.426327, 0.404567)
        val (m, mul) = DngColor.resolve(a, d65, null, null, xy)
        val neutral = Mat3.apply(m!!, doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1]))
        val (_, again) = DngColor.resolve(a, d65, null, neutral, null)
        assertArrayEquals(mul, again, 0.01f)
    }

    @Test
    fun daylightUsesTheD65Matrix() {
        val (m, _) = DngColor.resolve(a, d65, null, null, doubleArrayOf(0.3127, 0.3290))
        assertArrayEquals(Mat3.mul(cc2, cm2), m!!, 0.02)
    }

    @Test
    fun withoutAWhiteItFallsBackToDaylight() {
        val (m, mul) = DngColor.resolve(DngColor.Calibration(null, null, null), d65, null, null, null)
        val (kelvin, _) = CameraColor(m!!).temperatureOf(mul)
        assertEquals(6500.0, kelvin, 100.0)
    }
}
