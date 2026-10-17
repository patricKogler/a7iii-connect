package dev.kogler.a7iiiconnect.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherReviewTest {
    private val me = "me"
    private val her = "her"
    private val members = listOf(me, her)
    private val keys = listOf("k1", "k2", "k3", "k4")

    /** A review with [facts] added one after another, the way phones would add them. */
    private class Play(val keys: List<String>, val members: List<String>) {
        var facts = emptyList<Fact>()
        var clock = 0L
        val review get() = TogetherReview("a", members, keys, facts)
        fun add(vararg f: Fact) { facts = Facts.merge(facts, f.toList()) }
        fun vote(phone: String, key: String, call: Call?) = add(review.voteFact(phone, key, call, ++clock))
        fun say(phone: String, key: String, call: Call) = add(*review.sayFacts(phone, key, call, ++clock).toTypedArray())
    }

    private fun play() = Play(keys, members)

    @Test
    fun agreedMatchedAndDisputed() {
        val p = play()
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.PICK)
        p.vote(me, "k2", Call.REJECT); p.vote(her, "k2", Call.REJECT)
        p.vote(me, "k3", Call.PICK); p.vote(her, "k3", Call.REJECT)
        p.vote(me, "k4", Call.MAYBE); p.vote(her, "k4", Call.MAYBE)
        val r = p.review
        assertEquals(ReviewState.AGREED, r.state("k1"))
        assertTrue(r.isMatch("k1"))
        assertEquals(Call.REJECT, r.outcome("k2"))
        assertFalse(r.isMatch("k2"))
        assertEquals(listOf("k3", "k4"), r.disputes)
        assertNull(r.outcome("k3"))
        assertFalse(r.roundDone)
    }

    @Test
    fun votingAloneWaitsForTheOther() {
        val p = play()
        for (k in keys) p.vote(me, k, Call.PICK)
        val r = p.review
        assertEquals(emptyList<String>(), r.toVote(me))
        assertEquals(keys, r.toVote(her))
        assertEquals(4, r.progress(me))
        assertEquals(0, r.progress(her))
        assertEquals(ReviewState.VOTING, r.state("k1"))
        assertEquals(emptyList<String>(), r.disputes)
    }

    @Test
    fun takingAVoteBackPutsTheShotBackInTheDeck() {
        val p = play()
        p.vote(me, "k1", Call.PICK)
        p.vote(me, "k1", null)
        assertEquals(keys, p.review.toVote(me))
    }

    @Test
    fun stackKeepsOneAndPutsTheRestOut() {
        val p = play()
        p.add(*p.review.stackFacts(me, listOf("k1", "k2", "k3"), chosen = 1, call = Call.PICK, now = 1).toTypedArray())
        assertEquals(Call.REJECT, p.review.vote(me, "k1"))
        assertEquals(Call.PICK, p.review.vote(me, "k2"))
        assertEquals(Call.REJECT, p.review.vote(me, "k3"))
        p.add(*p.review.stackFacts(her, listOf("k1", "k2", "k3"), chosen = null, call = Call.MAYBE, now = 2).toTypedArray())
        assertEquals(Call.MAYBE, p.review.vote(her, "k3"))
    }

    @Test
    fun talkingItOverSettlesOnceBothSayTheSame() {
        val p = play()
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.REJECT)
        p.say(me, "k1", Call.PICK)
        assertEquals(ReviewState.DISPUTED, p.review.state("k1"))
        p.say(her, "k1", Call.REJECT)
        assertTrue(p.review.isStuck("k1"))
        p.say(her, "k1", Call.PICK)
        val r = p.review
        assertEquals(ReviewState.DECIDED, r.state("k1"))
        assertEquals(Call.PICK, r.outcome("k1"))
        assertEquals(TogetherReview.AGREE, r.decision("k1")!!.slot)
    }

    @Test
    fun coinGoesWithOneOfTheCallsAndOnlyWhenStuck() {
        val p = play()
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.REJECT)
        assertNull(p.review.coinFact(me, "k1", 0, 1))
        p.say(me, "k1", Call.PICK); p.say(her, "k1", Call.REJECT)
        val herCall = p.review.coinFact(me, "k1", pick = 1, now = 50)!!
        assertEquals("R", herCall.value)
        p.add(herCall)
        val r = p.review
        assertEquals(Call.REJECT, r.outcome("k1"))
        assertEquals(her, r.coinWinner("k1"))
        assertNull(r.coinFact(me, "k1", 0, 60))
    }

    @Test
    fun twoPhonesFlippingApartKeepTheEarlierCoin() {
        val p = play()
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.REJECT)
        p.say(me, "k1", Call.PICK); p.say(her, "k1", Call.REJECT)
        val mineFlip = p.review.coinFact(me, "k1", 0, 100)!!
        val herFlip = p.review.coinFact(her, "k1", 1, 90)!!
        val a = TogetherReview("a", members, keys, Facts.merge(p.facts + mineFlip, listOf(herFlip)))
        val b = TogetherReview("a", members, keys, Facts.merge(p.facts + herFlip, listOf(mineFlip)))
        assertEquals(Call.REJECT, a.outcome("k1"))
        assertEquals(a.outcome("k1"), b.outcome("k1"))
    }

    @Test
    fun sealingARoundFreezesItAndTalliesIt() {
        val p = play()
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.PICK)
        p.vote(me, "k2", Call.REJECT); p.vote(her, "k2", Call.REJECT)
        p.vote(me, "k3", Call.PICK); p.vote(her, "k3", Call.REJECT)
        p.vote(me, "k4", Call.REJECT); p.vote(her, "k4", Call.MAYBE)
        assertEquals(emptyList<String>(), p.review.toSeal(me))
        p.say(me, "k3", Call.PICK); p.say(her, "k3", Call.PICK)
        p.say(me, "k4", Call.REJECT); p.say(her, "k4", Call.PICK)
        p.add(p.review.coinFact(her, "k4", 0, ++p.clock)!!)
        assertTrue(p.review.roundDone)
        assertEquals(keys, p.review.toSeal(me))
        val t = p.review.tally(keys)
        assertEquals(ReviewTally(kept = 2, out = 2, score = 50, matches = 1, talked = 2, coins = 1), t)

        p.add(*p.review.seal(me, 1000).toTypedArray())
        val r = p.review
        assertEquals(emptyList<String>(), r.open)
        assertEquals(emptyList<String>(), r.toSeal(me))
        // Her phone still has her own seal to do, of the very same shots.
        assertEquals(keys, r.toSeal(her))
        assertEquals(1, r.rounds.size)
        assertEquals(t, r.rounds[0].third)
        // A vote changed after the seal doesn't change what was sealed.
        p.vote(her, "k1", Call.REJECT)
        assertEquals(Call.PICK, p.review.outcome("k1"))
    }

    @Test
    fun shotsAddedAfterASealStartTheNextRound() {
        val p = play()
        for (k in keys) { p.vote(me, k, Call.PICK); p.vote(her, k, Call.PICK) }
        p.add(*p.review.seal(me, 100).toTypedArray())
        p.add(*p.review.seal(her, 101).toTypedArray())
        val next = Play(keys + "k5", members).also { it.facts = p.facts; it.clock = 200 }
        assertEquals(listOf("k5"), next.review.open)
        assertEquals(listOf("k5"), next.review.toVote(me))
        assertEquals(emptyList<String>(), next.review.toSeal(me))
        next.vote(me, "k5", Call.REJECT); next.vote(her, "k5", Call.REJECT)
        assertEquals(listOf("k5"), next.review.toSeal(me))
        assertEquals(2, next.review.rounds.size)
    }

    @Test
    fun tallyEncodesAndDecodes() {
        val t = ReviewTally(3, 4, 56, 2, 1, 0)
        assertEquals(t, ReviewTally.decode(t.encode()))
        assertNull(ReviewTally.decode("1,2"))
    }

    @Test
    fun otherAlbumsAndBattlesAreIgnored() {
        val p = play()
        p.add(Fact("other", Fact.Kind.VOTE, me, key = "k1", value = "P", at = 1))
        p.add(Fact("a", Fact.Kind.VOTE, me, battle = "b", key = "k1", value = "P", at = 1))
        assertEquals(keys, p.review.toVote(me))
    }

    @Test
    fun threePeopleNeedAllThree() {
        val p = Play(keys, listOf(me, her, "third"))
        p.vote(me, "k1", Call.PICK); p.vote(her, "k1", Call.PICK)
        assertEquals(ReviewState.VOTING, p.review.state("k1"))
        p.vote("third", "k1", Call.PICK)
        assertTrue(p.review.isMatch("k1"))
    }
}
