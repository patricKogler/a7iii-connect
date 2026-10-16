package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LookTest {
    private val n = Look.SIDE

    /** A dark ridge line under a sky: [shift] moves it sideways, [tint] warms the sky. */
    private fun scene(shift: Int = 0, tint: Int = 0, flip: Boolean = false): Look = Look.of(IntArray(n * n) { i ->
        val x = i % n + shift
        val y = (i / n).let { if (flip) n - 1 - it else it }
        val ridge = 8 + (3 * sin(x * 0.7)).toInt()
        val (r, g, b) = if (y < ridge) Triple(120 + tint + y * 4, 170 + y * 3, 230) else Triple(30 + x, 50 + y, 25)
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    })

    @Test
    fun theSameSceneIsAlike() {
        val a = scene()
        assertEquals(0f, a.edgeDistance(a))
        assertEquals(0f, a.colourDistance(a))
        assertTrue(Similar.alike(a, scene(shift = 0, tint = 10), burst = false))
    }

    @Test
    fun aDifferentPictureIsNot() {
        val a = scene()
        // Upside down: the same colours but the edges all differ.
        assertFalse(Similar.alike(a, scene(flip = true), burst = true))
        // Another place entirely: a flat grey wall.
        val wall = Look.of(IntArray(n * n) { 0xFF808080.toInt() })
        assertFalse(Similar.alike(a, wall, burst = true))
    }

    @Test
    fun survivesTheCache() {
        val a = scene(shift = 2, tint = 20)
        val b = Look.decode(a.encode())!!
        assertEquals(0f, a.edgeDistance(b))
        assertEquals(0f, a.colourDistance(b))
    }

    @Test
    fun zoomingOrTurningTheCameraStartsAnewStack() {
        assertTrue(Similar.sameFraming(ShotMeta(focal = 24f), ShotMeta(focal = 26f)))
        assertFalse(Similar.sameFraming(ShotMeta(focal = 24f), ShotMeta(focal = 70f)))
        assertFalse(Similar.sameFraming(ShotMeta(portrait = true), ShotMeta(portrait = false)))
        assertTrue(Similar.sameFraming(null, ShotMeta(focal = 70f)))
    }
}
