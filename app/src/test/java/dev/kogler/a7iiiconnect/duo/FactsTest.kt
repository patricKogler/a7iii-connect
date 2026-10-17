package dev.kogler.a7iiiconnect.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactsTest {
    private fun vote(phone: String, key: String, v: String, at: Long) = Fact("a", Fact.Kind.VOTE, phone, key = key, value = v, at = at)

    @Test
    fun mergeKeepsTheNewerOfEachFact() {
        val mine = listOf(vote("me", "k1", "P", 10), vote("me", "k2", "R", 10))
        val theirs = listOf(vote("me", "k1", "R", 20), vote("her", "k1", "P", 5), vote("me", "k2", "P", 5))
        val merged = Facts.merge(mine, theirs)
        assertEquals(3, merged.size)
        assertEquals("R", merged.first { it.phone == "me" && it.key == "k1" }.value)
        assertEquals("R", merged.first { it.phone == "me" && it.key == "k2" }.value)
        assertEquals("P", merged.first { it.phone == "her" }.value)
    }

    @Test
    fun mergeComesOutTheSameEitherWayRound() {
        val a = listOf(vote("me", "k1", "P", 10), vote("her", "k1", "M", 10))
        val b = listOf(vote("me", "k1", "R", 10), vote("her", "k2", "P", 3))
        assertEquals(Facts.merge(a, b).toSet(), Facts.merge(b, a).toSet())
    }

    @Test
    fun newsIsOnlyWhatChangesSomething() {
        val mine = listOf(vote("me", "k1", "P", 10))
        val incoming = listOf(vote("me", "k1", "P", 10), vote("me", "k1", "R", 9), vote("her", "k1", "R", 1))
        assertEquals(listOf(vote("her", "k1", "R", 1)), Facts.news(mine, incoming))
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val settings = "v=2\nprofile=COLOR\nexposure=0.5\n"
        val facts = listOf(
            vote("me", "DSC01.ARW@1", "P", 1),
            Fact("a", Fact.Kind.EDIT, "me", battle = "b1", slot = "0", value = Facts.packSettings(settings), at = 7),
            Fact("a", Fact.Kind.ROUND, "her", slot = "9", value = "1,2,3,4,5,6", at = 9),
            vote("me", "k", "with\ttab", 3),
        )
        val back = Facts.decode(Facts.encode(facts))
        assertEquals(facts.take(3), back.take(3))
        assertEquals("with tab", back[3].value)
        assertEquals(settings, Facts.unpackSettings(back[1].value))
    }

    @Test
    fun decodeSkipsBrokenLines() {
        assertEquals(emptyList<Fact>(), Facts.decode("junk\n?\ta\tb\tc\td\te\t1\tx\nv\ta\tb\tc\td\te\tnot-a-time\tx\n"))
    }

    @Test
    fun rekeyMovesFactsToThisPhonesKeysAndKeepsTheNewer() {
        val facts = listOf(vote("her", "DSC01.JPG@5", "P", 1), vote("her", "DSC01.ARW@5", "R", 2), vote("her", "DSC02.JPG@6", "M", 1))
        val resolve = { k: String -> if (k.startsWith("DSC01")) "DSC01.ARW@5" else null }
        val out = Facts.rekey(facts, resolve)
        assertEquals(2, out.size)
        assertEquals("R", out.first { it.key == "DSC01.ARW@5" }.value)
        assertTrue(out.any { it.key == "DSC02.JPG@6" })
    }

    @Test
    fun stableHashIsStableAndSpreads() {
        assertEquals(Facts.stableHash("battle/0"), Facts.stableHash("battle/0"))
        assertNotEquals(Facts.stableHash("battle/0"), Facts.stableHash("battle/1"))
        assertTrue(Facts.stableHash("x") >= 0)
        val heads = (0 until 200).count { Facts.stableHash("b$it/0") % 2 == 0 }
        assertTrue("$heads of 200", heads in 70..130)
    }

    @Test
    fun stemIgnoresFileTypeAndTime() {
        assertEquals("dsc01234", Facts.stem("DSC01234.ARW@1760000000000"))
        assertEquals(Facts.stem("DSC01234.JPG@1760000000999"), Facts.stem("DSC01234.ARW@1760000000000"))
    }
}
