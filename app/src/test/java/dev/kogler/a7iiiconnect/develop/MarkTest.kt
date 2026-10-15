package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkTest {
    @Test
    fun readsWhatItWrites() {
        for (m in Mark.entries) assertEquals(m, Mark.parse(m.toString()))
    }

    @Test
    fun oldRoundsBecomePicksAndRejects() {
        assertEquals(Mark.REJECT, Mark.parse("-1"))
        assertEquals(Mark.PICK, Mark.parse("1"))
        assertEquals(Mark.PICK, Mark.parse("3"))
        assertNull(Mark.parse("0"))
        assertNull(Mark.parse(null))
        assertNull(Mark.parse("junk"))
    }
}
