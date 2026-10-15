package dev.kogler.a7iiiconnect.duo

/** One player's call on a card: keep shot [keep] of the stack, throw it all out, or "not sure". */
data class Choice(val vote: Vote, val keep: Int = 0) {
    enum class Vote { PICK, REJECT, MAYBE }

    val token: String
        get() = when (vote) {
            Vote.PICK -> "p$keep"
            Vote.REJECT -> "r"
            Vote.MAYBE -> "m"
        }

    companion object {
        fun parse(token: String): Choice? = when {
            token == "r" -> Choice(Vote.REJECT)
            token == "m" -> Choice(Vote.MAYBE)
            token.startsWith("p") -> token.drop(1).toIntOrNull()?.let { Choice(Vote.PICK, it) }
            else -> null
        }
    }
}

/**
 * The shared culling game, without any Android in it. Two players ([HOST] owns the photos,
 * [GUEST] joined) vote on the same deck of cards without seeing each other's calls. Cards they
 * agree on are settled; the rest (different calls, or both unsure) go to the Debate round, where
 * either of them settles each one for both.
 *
 * [stackSizes] has one entry per card: 1 for a single shot, more for a stack of similar shots.
 */
class DuoSession(val stackSizes: List<Int>) {
    private val votes = listOf(HashMap<Int, Choice>(), HashMap<Int, Choice>())
    private val decisions = HashMap<Int, Choice>()

    val size get() = stackSizes.size

    fun voteOf(player: Int, item: Int): Choice? = votes[player][item]
    fun progress(player: Int) = votes[player].size
    fun isDone(player: Int) = votes[player].size >= size
    val bothDone get() = isDone(HOST) && isDone(GUEST)

    /**
     * Records [player]'s call on [item] (null takes it back). True when it makes a match: the
     * other player already kept the same shot.
     */
    fun vote(player: Int, item: Int, choice: Choice?): Boolean {
        if (item !in 0 until size) return false
        if (choice == null) {
            votes[player].remove(item)
            return false
        }
        val keep = choice.keep.coerceIn(0, stackSizes[item] - 1)
        val c = choice.copy(keep = if (choice.vote == Choice.Vote.PICK) keep else 0)
        val before = votes[player].put(item, c)
        return before != c && c.vote == Choice.Vote.PICK && votes[1 - player][item] == c
    }

    private fun both(item: Int): Pair<Choice, Choice>? {
        val a = votes[HOST][item] ?: return null
        val b = votes[GUEST][item] ?: return null
        return a to b
    }

    /** Both kept the same shot. */
    fun isMatch(item: Int) = both(item)?.let { (a, b) -> a == b && a.vote == Choice.Vote.PICK } == true

    val matches get() = (0 until size).count { isMatch(it) }
    val bothOut get() = (0 until size).count { both(it)?.let { (a, b) -> a == b && a.vote == Choice.Vote.REJECT } == true }

    /** Cards to talk about: different calls, or both unsure. In deck order. */
    val disputes: List<Int>
        get() = (0 until size).filter { i -> both(i)?.let { (a, b) -> a != b || a.vote == Choice.Vote.MAYBE } == true }

    /** Share of the cards both voted on where they made the same call, 0..100. */
    val score: Int
        get() {
            val voted = (0 until size).mapNotNull { both(it) }
            if (voted.isEmpty()) return 0
            return Math.round(100f * voted.count { (a, b) -> a == b } / voted.size)
        }

    fun decide(item: Int, choice: Choice?) {
        if (choice == null) decisions.remove(item) else decisions[item] = choice
    }

    fun decision(item: Int): Choice? = decisions[item]

    /** The first card of the Debate round nobody settled yet, or null when it's over. */
    val nextDispute: Int? get() = disputes.firstOrNull { it !in decisions }

    /** What happens to the card in the end: the Debate's call, else the agreed one. Null: left for later. */
    fun outcome(item: Int): Choice? {
        decisions[item]?.let { return it.takeIf { c -> c.vote != Choice.Vote.MAYBE } }
        val (a, b) = both(item) ?: return null
        return a.takeIf { a == b && a.vote != Choice.Vote.MAYBE }
    }

    /** One player's calls as a compact string ("p0,r,-,m"), for sending over the link. */
    fun encodeVotes(player: Int) = encode(votes[player])
    fun encodeDecisions() = encode(decisions)

    fun loadVotes(player: Int, text: String) = load(votes[player], text)
    fun loadDecisions(text: String) = load(decisions, text)

    private fun encode(m: Map<Int, Choice>) = (0 until size).joinToString(",") { m[it]?.token ?: "-" }

    private fun load(m: HashMap<Int, Choice>, text: String) {
        m.clear()
        if (text.isEmpty()) return
        text.split(',').forEachIndexed { i, t -> if (i < size) Choice.parse(t)?.let { m[i] = it } }
    }

    companion object {
        const val HOST = 0
        const val GUEST = 1
    }
}
