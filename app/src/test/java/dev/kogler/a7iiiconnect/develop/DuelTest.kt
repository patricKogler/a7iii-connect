package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelTest {
    @Test
    fun theBetterOneStaysOn() {
        val d = Duel(listOf("a", "b", "c", "d"))
        assertEquals("a", d.best); assertEquals("b", d.challenger); assertEquals(3, d.rounds)
        d.choose(bestWins = true)
        assertEquals("c", d.challenger)
        d.choose(bestWins = false)
        assertEquals("c", d.best); assertEquals("d", d.challenger)
        d.choose(bestWins = true)
        assertTrue(d.done); assertNull(d.challenger)
        assertEquals(listOf("c"), d.kept)
        assertEquals(listOf("a", "b", "d"), d.dropped)
    }

    @Test
    fun keepBothLetsTheChallengerThrough() {
        val d = Duel(listOf("a", "b", "c"))
        d.keepBoth()
        assertEquals("a", d.best); assertEquals("c", d.challenger)
        d.choose(bestWins = false)
        assertTrue(d.done)
        assertEquals(listOf("c", "b"), d.kept)
        assertEquals(listOf("a"), d.dropped)
    }

    @Test
    fun undoGoesBackOneChoice() {
        val d = Duel(listOf("a", "b", "c"))
        assertFalse(d.canUndo)
        d.choose(bestWins = false)
        d.keepBoth()
        assertTrue(d.done)
        d.undo()
        assertFalse(d.done); assertEquals("b", d.best); assertEquals("c", d.challenger)
        d.undo()
        assertEquals("a", d.best); assertFalse(d.canUndo)
    }

    @Test
    fun oneShotIsAlreadyDecided() {
        val d = Duel(listOf("a"))
        assertTrue(d.done)
        assertEquals(listOf("a"), d.kept)
        assertEquals(0, d.rounds)
    }
}
