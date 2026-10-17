package dev.kogler.a7iiiconnect.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TogetherBattleTest {
    private val me = "me"
    private val her = "her"
    private val members = listOf(her, me)

    private class Game(val members: List<String>) {
        var facts = emptyList<Fact>()
        var clock = 1000L
        lateinit var id: String
        val b get() = TogetherBattle("a", id, members, facts)
        fun add(vararg f: Fact) { facts = Facts.merge(facts, f.toList()) }
        fun start(phone: String, mode: Mode, keys: List<String>, seed: Int = 1, extra: Twist = Twist()) {
            id = "b$seed"
            add(*BattleRules.start("a", phone, id, mode, keys, ++clock, Random(seed), extra).toTypedArray())
        }
    }

    private fun game(mode: Mode, keys: List<String> = listOf("k1"), starter: String = me, seed: Int = 1, extra: Twist = Twist()) =
        Game(members).also { it.start(starter, mode, keys, seed, extra) }

    private fun Game.duelRound(r: Int, myVote: String, herVote: String) {
        add(b.editFact(me, r, "mine$r", ++clock))
        add(b.editFact(her, r, "hers$r", ++clock))
        add(b.pickFact(me, r, myVote, ++clock))
        add(b.pickFact(her, r, herVote, ++clock))
    }

    @Test
    fun startsWithTheRightRoundsAndTwists() {
        assertEquals(Twist(), game(Mode.CLASSIC).b.rounds.single().twist)
        assertNotNull(game(Mode.THEME).b.rounds.single().twist.theme)
        val limited = game(Mode.LIMITED).b.rounds.single().twist
        assertEquals(2, limited.tools.size)
        assertTrue(limited.tools.all { it in BattleRules.TOOL_POOL })
        assertEquals(60, game(Mode.SPEED).b.rounds.single().twist.seconds)
        val bo3 = game(Mode.BEST_OF_3, listOf("k1", "k2", "k3")).b
        assertEquals(listOf("k1", "k2", "k3"), bo3.rounds.map { it.key })
        assertEquals(me, bo3.players.first())
    }

    @Test
    fun extraTwistsStackOnTop() {
        val t = game(Mode.THEME, extra = Twist(seconds = 60, tools = listOf("LIGHT"))).b.rounds.single().twist
        assertNotNull(t.theme)
        assertEquals(60, t.seconds)
        assertEquals(listOf("LIGHT"), t.tools)
    }

    @Test
    fun twistEncodesAndDecodes() {
        val t = Twist("Golden hour", listOf("LIGHT", "COLOR"), 60)
        assertEquals(t, Twist.decode(t.encode()))
        assertEquals(Twist(), Twist.decode(Twist().encode()))
    }

    @Test
    fun duelGoesEditThenVoteThenResult() {
        val g = game(Mode.CLASSIC)
        assertEquals(BattleStatus.YOUR_TURN, g.b.status(me))
        g.add(g.b.editFact(me, 0, "mine", ++g.clock))
        assertEquals(Stage.EDIT, g.b.stage(0))
        assertEquals(BattleStatus.WAITING, g.b.status(me))
        assertEquals(BattleStatus.YOUR_TURN, g.b.status(her))
        g.add(g.b.editFact(her, 0, "hers", ++g.clock))
        assertEquals(Stage.VOTE, g.b.stage(0))
        assertEquals(BattleStatus.YOUR_TURN, g.b.status(me))
        g.add(g.b.pickFact(me, 0, her, ++g.clock))
        g.add(g.b.pickFact(her, 0, her, ++g.clock))
        assertEquals(Stage.DONE, g.b.stage(0))
        assertEquals(her, g.b.winner(0))
        assertFalse(g.b.flipped(0))
        assertTrue(g.b.done)
        assertEquals(her, g.b.champion)
        assertEquals(BattleStatus.RESULT, g.b.status(me))
        g.add(g.b.sealFact(me, ++g.clock))
        assertEquals(BattleStatus.SEALED, g.b.status(me))
        assertEquals(BattleStatus.RESULT, g.b.status(her))
        assertEquals(listOf(me), g.b.sealers)
        assertEquals(g.clock, g.b.sealedAt(me))
        assertNull(g.b.sealedAt(her))
    }

    @Test
    fun splitVoteGoesToTheSameCoinOnEveryPhone() {
        val winners = (1..40).map { seed ->
            val g = game(Mode.CLASSIC, seed = seed)
            g.duelRound(0, me, her)
            assertTrue(g.b.flipped(0))
            // The other phone, with the same facts in another order, sees the same winner.
            val other = TogetherBattle("a", g.id, listOf(me, her), g.facts.reversed())
            assertEquals(g.b.winner(0), other.winner(0))
            g.b.winner(0)
        }
        assertTrue(me in winners && her in winners)
    }

    @Test
    fun bestOfThreeStopsAtTwoWins() {
        val g = game(Mode.BEST_OF_3, listOf("k1", "k2", "k3"))
        g.duelRound(0, me, me)
        assertEquals(1, g.b.current!!.index)
        assertFalse(g.b.done)
        g.duelRound(1, me, me)
        assertTrue(g.b.done)
        assertNull(g.b.current)
        assertEquals(2, g.b.wins(me))
        assertEquals(me, g.b.champion)
    }

    @Test
    fun bestOfThreeGoesTheDistance() {
        val g = game(Mode.BEST_OF_3, listOf("k1", "k2", "k3"))
        g.duelRound(0, me, me)
        g.duelRound(1, her, her)
        assertEquals(2, g.b.current!!.index)
        g.duelRound(2, her, her)
        assertEquals(her, g.b.champion)
        assertEquals(1, g.b.wins(me))
    }

    @Test
    fun copycatLooksThenCopiesClosestWins() {
        val g = game(Mode.COPYCAT)
        assertEquals(her, g.b.target(me))
        assertEquals(me, g.b.target(her))
        g.add(g.b.lookFact(me, "myLook", ++g.clock))
        assertEquals(Stage.EDIT, g.b.copyStage)
        assertEquals(BattleStatus.WAITING, g.b.status(me))
        g.add(g.b.lookFact(her, "herLook", ++g.clock))
        assertEquals(Stage.VOTE, g.b.copyStage)
        assertEquals(BattleStatus.YOUR_TURN, g.b.status(me))
        g.add(g.b.copyFact(me, 91, "myCopy", ++g.clock))
        g.add(g.b.copyFact(her, 87, "herCopy", ++g.clock))
        assertEquals(91 to "myCopy", g.b.copy(me))
        assertEquals(me, g.b.copyWinner)
        assertEquals(me, g.b.champion)
        assertTrue(g.b.done)
    }

    @Test
    fun relayTakesTurnsThroughTheLegs() {
        val g = game(Mode.RELAY, starter = her)
        assertEquals(listOf(her, me), g.b.players)
        assertEquals(BattleStatus.YOUR_TURN, g.b.status(her))
        assertEquals(BattleStatus.WAITING, g.b.status(me))
        assertNull(g.b.relayEdit)
        for (n in BattleRules.LEGS.indices) {
            val who = g.b.holder(n)
            assertEquals(if (n % 2 == 0) her else me, who)
            g.add(g.b.legFact(who, n, "after$n", ++g.clock))
        }
        assertTrue(g.b.relayDone)
        assertTrue(g.b.done)
        assertEquals("after3", g.b.relayEdit)
        assertNull(g.b.champion)
        assertEquals(BattleStatus.RESULT, g.b.status(me))
    }

    @Test
    fun timerStartIsKept() {
        val g = game(Mode.SPEED)
        g.add(g.b.startFact(me, 0, 5000))
        assertEquals(5000L, g.b.started(me, 0))
        assertNull(g.b.started(her, 0))
    }

    @Test
    fun droppedBattleSaysSo() {
        val g = game(Mode.CLASSIC)
        g.add(g.b.dropFact(her, ++g.clock))
        assertEquals(BattleStatus.DROPPED, g.b.status(me))
        assertEquals(her, g.b.droppedBy)
    }

    @Test
    fun listsBattlesNewestFirst() {
        val g = game(Mode.CLASSIC, seed = 1)
        g.start(me, Mode.RELAY, listOf("k2"), seed = 2)
        val all = TogetherBattle.all("a", members, g.facts)
        assertEquals(listOf("b2", "b1"), all.map { it.id })
        assertEquals(Mode.RELAY, all[0].mode)
    }
}
