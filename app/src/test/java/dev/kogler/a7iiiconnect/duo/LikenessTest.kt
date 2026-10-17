package dev.kogler.a7iiiconnect.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LikenessTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun image(f: (Int) -> Int) = IntArray(64) { f(it) }

    @Test
    fun identicalIsAHundred() {
        val a = image { rgb(it * 3, 100, 255 - it * 2) }
        assertEquals(100, Likeness.score(a, a.copyOf()))
    }

    @Test
    fun closerScoresHigher() {
        val target = image { rgb(120, 90, 60) }
        val near = image { rgb(124, 92, 60) }
        val far = image { rgb(60, 90, 160) }
        val n = Likeness.score(target, near)
        val f = Likeness.score(target, far)
        assertTrue("near $n", n in 90..99)
        assertTrue("far $f", f < 40)
    }

    @Test
    fun blackAgainstWhiteIsZero() {
        assertEquals(0, Likeness.score(image { rgb(0, 0, 0) }, image { rgb(255, 255, 255) }))
    }
}
