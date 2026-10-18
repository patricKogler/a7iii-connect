package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GridLinesTest {
    // Two days: 5 shots, then 3, on a 3-column grid.
    private val lines = GridLines(listOf(10L to 5, 20L to 3), columns = 3)

    @Test
    fun headersThenShotsColumnsToALine() {
        assertArrayEquals(intArrayOf(0, 1, 4, 6, 7), lines.start)
        assertArrayEquals(booleanArrayOf(true, false, false, true, false), lines.header)
        assertArrayEquals(longArrayOf(10, 10, 10, 20, 20), lines.day)
        assertArrayEquals(intArrayOf(0, 1, 1, 1, 2, 2, 3, 4, 4, 4), lines.lineOf)
    }

    @Test
    fun topsAddHeaderAndRowHeights() {
        // Headers 40 px, rows 100 px, 2 px apart.
        assertEquals(0f, lines.top(0, 40f, 100f, 2f))
        assertEquals(42f, lines.top(1, 40f, 100f, 2f))
        assertEquals(144f, lines.top(2, 40f, 100f, 2f))
        assertEquals(246f, lines.top(3, 40f, 100f, 2f))
        assertEquals(390f, lines.top(5, 40f, 100f, 2f))
    }

    @Test
    fun lineAtFindsTheLineUnderAPosition() {
        assertEquals(0, lines.lineAt(0f, 40f, 100f, 2f))
        assertEquals(1, lines.lineAt(100f, 40f, 100f, 2f))
        assertEquals(3, lines.lineAt(250f, 40f, 100f, 2f))
        assertEquals(4, lines.lineAt(10_000f, 40f, 100f, 2f))
    }
}
