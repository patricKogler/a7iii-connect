package dev.kogler.a7iiiconnect.duo

import dev.kogler.a7iiiconnect.duo.Choice.Vote
import dev.kogler.a7iiiconnect.duo.DuoSession.Companion.GUEST
import dev.kogler.a7iiiconnect.duo.DuoSession.Companion.HOST
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoSessionTest {
    private val pick = Choice(Vote.PICK)
    private val out = Choice(Vote.REJECT)
    private val maybe = Choice(Vote.MAYBE)

    @Test
    fun matchesOnlyWhenBothKeepTheSameShot() {
        val s = DuoSession(listOf(1, 3))
        assertFalse(s.vote(HOST, 0, pick))
        assertTrue(s.vote(GUEST, 0, pick))
        assertFalse(s.vote(HOST, 1, Choice(Vote.PICK, 2)))
        assertFalse(s.vote(GUEST, 1, Choice(Vote.PICK, 1)))
        assertEquals(1, s.matches)
        assertEquals(listOf(1), s.disputes)
    }

    @Test
    fun revoteIsNotASecondMatch() {
        val s = DuoSession(listOf(1))
        s.vote(GUEST, 0, pick)
        assertTrue(s.vote(HOST, 0, pick))
        assertFalse(s.vote(HOST, 0, pick))
    }

    @Test
    fun outcomesScoreAndDebate() {
        val s = DuoSession(listOf(1, 1, 1, 1))
        s.vote(HOST, 0, pick); s.vote(GUEST, 0, pick)
        s.vote(HOST, 1, out); s.vote(GUEST, 1, out)
        s.vote(HOST, 2, pick); s.vote(GUEST, 2, out)
        s.vote(HOST, 3, maybe); s.vote(GUEST, 3, maybe)
        assertTrue(s.bothDone)
        assertEquals(75, s.score)
        assertEquals(1, s.bothOut)
        assertEquals(listOf(2, 3), s.disputes)
        assertEquals(pick, s.outcome(0))
        assertEquals(out, s.outcome(1))
        assertNull(s.outcome(2))
        assertEquals(2, s.nextDispute)
        s.decide(2, pick)
        assertEquals(3, s.nextDispute)
        s.decide(3, maybe)
        assertNull(s.nextDispute)
        assertEquals(pick, s.outcome(2))
        assertNull(s.outcome(3))
    }

    @Test
    fun votesSurviveTheWire() {
        val s = DuoSession(listOf(1, 2, 1))
        s.vote(HOST, 1, Choice(Vote.PICK, 1)); s.vote(HOST, 2, maybe)
        s.decide(0, out)
        val t = DuoSession(listOf(1, 2, 1))
        t.loadVotes(HOST, s.encodeVotes(HOST))
        t.loadDecisions(s.encodeDecisions())
        assertEquals("-,p1,m", t.encodeVotes(HOST))
        assertEquals(out, t.decision(0))
        assertEquals(2, t.progress(HOST))
    }

    @Test
    fun tagServesTheUri() {
        val tag = NdefTag("shootback://duo?code=AB12")
        val ok = NdefTag.OK
        assertArrayEquals(ok, tag.respond(byteArrayOf(0, 0xA4.toByte(), 4, 0, 7) + NdefTag.AID + byteArrayOf(0)))
        assertArrayEquals(ok, tag.respond(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 0x03)))
        val cc = tag.respond(byteArrayOf(0, 0xB0.toByte(), 0, 0, 15))
        assertEquals(17, cc.size)
        assertArrayEquals(ok, tag.respond(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 0x04)))
        val len = tag.respond(byteArrayOf(0, 0xB0.toByte(), 0, 0, 2))
        val n = (len[0].toInt() and 0xFF) shl 8 or (len[1].toInt() and 0xFF)
        val body = tag.respond(byteArrayOf(0, 0xB0.toByte(), 0, 2, n.toByte()))
        assertArrayEquals(NdefTag.uriMessage("shootback://duo?code=AB12"), body.copyOfRange(0, n))
        assertEquals("shootback://duo?code=AB12", String(body, 5, n - 5))
    }
}
