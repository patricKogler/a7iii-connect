package dev.kogler.a7iiiconnect.develop

import dev.kogler.a7iiiconnect.develop.engine.Group
import dev.kogler.a7iiiconnect.develop.engine.Lut
import dev.kogler.a7iiiconnect.develop.engine.Luts
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Preset
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Film LUTs: reading .cube and Hald files, storing them, and how edits carry one. */
class LutTest {

    private fun cube(n: Int, title: String? = null, map: (Float, Float, Float) -> Triple<Float, Float, Float>) = buildString {
        if (title != null) append("TITLE \"$title\"\n")
        append("# a comment\nLUT_3D_SIZE $n\n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val (x, y, z) = map(r / (n - 1f), g / (n - 1f), b / (n - 1f))
            append("$x $y $z\n")
        }
    }

    @Test
    fun readsACube() {
        val (lut, title) = Lut.parseCube(cube(5, "Swap") { r, g, b -> Triple(b, g, r) })
        assertEquals("Swap", title)
        assertEquals(5, lut.size)
        // Red is the fastest index: the second colour is (r = 1/4, g = 0, b = 0) swapped.
        assertArrayEquals(floatArrayOf(0f, 0f, 0.25f), lut.data.copyOfRange(3, 6), 1e-6f)
        assertArrayEquals(floatArrayOf(0.7f, 0.2f, 0.1f), lut.at(0.1f, 0.2f, 0.7f), 1e-5f)
    }

    @Test
    fun aCubeWithAWiderDomainIsSampledBackOntoZeroToOne() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" +
            (0 until 8).joinToString("\n") { i -> "${(i and 1) * 2f} ${(i shr 1 and 1) * 2f} ${(i shr 2 and 1) * 2f}" }
        val (lut, _) = Lut.parseCube(text)
        // The table maps x to x over 0..2; on 0..1, input 1 now lands half way along it.
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.at(1f, 1f, 1f), 1e-5f)
    }

    @Test
    fun readsAHaldImage() {
        // Level 2: an 8 x 8 image holding a 4³ table, red fastest.
        val n = 4
        val px = IntArray(64) { i ->
            val r = i % n; val g = i / n % n; val b = i / (n * n)
            (0xff shl 24) or ((r * 85) shl 16) or ((g * 85) shl 8) or (b * 85)
        }
        val lut = Lut.fromHald(8, 8, px)
        assertEquals(4, lut.size)
        assertArrayEquals(floatArrayOf(1f / 3, 2f / 3, 1f), lut.at(1f / 3, 2f / 3, 1f), 1e-5f)
    }

    @Test
    fun resamplingAndStorageKeepTheLook() {
        val (lut, _) = Lut.parseCube(cube(17) { r, g, b -> Triple(r * r, (g + b) / 2, 1 - b) })
        val small = lut.resized(Lut.SIZE)
        val out = ByteArrayOutputStream()
        small.write(out)
        val back = Lut.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Lut.SIZE, back.size)
        for (q in listOf(floatArrayOf(0.1f, 0.5f, 0.9f), floatArrayOf(0.75f, 0.3f, 0.2f), floatArrayOf(1f, 1f, 0f))) {
            assertArrayEquals(lut.at(q[0], q[1], q[2]), back.at(q[0], q[1], q[2]), 0.005f)
        }
        val id = Lut.identity()
        assertArrayEquals(floatArrayOf(0.3f, 0.6f, 0.9f), id.at(0.3f, 0.6f, 0.9f), 1e-5f)
    }

    @Test
    fun editsCarryTheLutWithTheProfile() {
        val s = Settings(profile = Profile.NEUTRAL, lut = "kodak-portra").with(P.LUT_AMOUNT to 60f, P.EXPOSURE to 0.5f)
        assertEquals(s, Settings.decode(s.encode()))
        assertTrue("lut=kodak-portra" in s.encode())

        // Resetting Light drops it, as it drops the profile.
        assertNull(s.reset(Group.LIGHT).lut)
        assertEquals(s.copy(lut = null).with(P.LUT_AMOUNT to 100f), s.withoutProfile().copy(profile = Profile.NEUTRAL))
        assertEquals(s, s.reset(Group.LIGHT).withProfileOf(s).with(P.EXPOSURE to 0.5f))

        // A built-in look replaces it; "None" goes back to no LUT.
        assertNull(Preset.NONE.applyTo(s).lut)
        val portra = Preset.FILM.first()
        assertTrue(portra.isApplied(portra.applyTo(s)))
        assertNull(portra.applyTo(s).lut)
    }

    @Test
    fun anUnknownLutRendersWithout() {
        assertNull(Luts["nobody-has-this"])
        Luts.register("test-id") { Lut.identity(3) }
        assertEquals(3, Luts["test-id"]?.size)
        Luts.unregister("test-id")
        assertNull(Luts["test-id"])
    }

    @Test
    fun everyFilmStockIsDistinct() {
        val looks = Preset.FILM.map { it.settings }
        assertEquals(looks.size, looks.toSet().size)
        assertTrue(Preset.FILM.all { it in Preset.BUILT_IN && it.film })
    }
}
