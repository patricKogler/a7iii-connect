package dev.kogler.a7iiiconnect.duo

/** A call on a shot in the review: keep it, out, or not sure. */
enum class Call(val token: String) {
    PICK("P"), REJECT("R"), MAYBE("M");

    companion object {
        fun of(token: String) = entries.firstOrNull { it.token == token }
    }
}

/** Where a shot is in the review. */
enum class ReviewState {
    /** Not everyone has made their call yet. */
    VOTING,
    /** Everyone made the same call (and not "not sure"). */
    AGREED,
    /** Different calls, or everyone unsure: to talk about. */
    DISPUTED,
    /** Talked over: everyone said the same, or a coin decided. */
    DECIDED,
    /** Someone sealed it at the end of a round; it's out of the review for good. */
    SEALED,
}

/** How a round of the review went, for the end and for looking back. */
data class ReviewTally(
    val kept: Int,
    val out: Int,
    /** Shots everyone made the same first call on, 0..100. */
    val score: Int,
    /** Shots everyone kept straight away. */
    val matches: Int,
    /** Shots that were talked over. */
    val talked: Int,
    val coins: Int,
) {
    val total get() = kept + out
    fun encode() = "$kept,$out,$score,$matches,$talked,$coins"

    companion object {
        fun decode(text: String): ReviewTally? = text.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 6 }
            ?.let { ReviewTally(it[0], it[1], it[2], it[3], it[4], it[5]) }
    }
}

/**
 * The review of a shared album, by everyone in it ([members], phone ids), worked out from the
 * facts the phones swapped. Nobody has to be online at the same time: each phone votes on the
 * album's shots whenever its owner likes, and what's agreed, what's to talk about and what
 * a coin decided all follow from the facts the same way on every phone.
 *
 * A round ends when every shot in the album is settled: then each person seals it, and the shots
 * that are out leave the album. Shots added later start the next round.
 */
class TogetherReview(
    val album: String,
    val members: List<String>,
    /** The shots in the album now, in album order. */
    val keys: List<String>,
    facts: List<Fact>,
) {
    private val votes = HashMap<String, HashMap<String, Call>>()
    private val says = HashMap<String, HashMap<String, Call>>()
    private val decisions = HashMap<String, Fact>()
    private val sealed = HashMap<String, Fact>()
    private val sealedBy = HashMap<String, HashSet<String>>()
    /** Rounds sealed, oldest first: who sealed, when, how it went. */
    val rounds: List<Triple<String, Long, ReviewTally>>

    init {
        val r = mutableListOf<Triple<String, Long, ReviewTally>>()
        for (f in facts) {
            if (f.album != album || f.battle.isNotEmpty()) continue
            when (f.kind) {
                Fact.Kind.VOTE -> Call.of(f.value)?.let { votes.getOrPut(f.phone) { HashMap() }[f.key] = it }
                Fact.Kind.SAY -> Call.of(f.value)?.let { says.getOrPut(f.phone) { HashMap() }[f.key] = it }
                Fact.Kind.DECIDE -> if (Call.of(f.value) != null && earlier(f, decisions[f.key])) decisions[f.key] = f
                Fact.Kind.SEALED -> if (Call.of(f.value) != null) {
                    if (earlier(f, sealed[f.key])) sealed[f.key] = f
                    sealedBy.getOrPut(f.phone) { HashSet() } += f.key
                }
                Fact.Kind.ROUND -> ReviewTally.decode(f.value)?.let { r += Triple(f.phone, f.at, it) }
                else -> {}
            }
        }
        rounds = r.sortedBy { it.second }
    }

    /** The earlier of two facts settling a shot (on a tie the one with the smaller value, the same on every phone). */
    private fun earlier(f: Fact, old: Fact?) = old == null || f.at < old.at || f.at == old.at && f.value < old.value

    /** The album's shots still in the review. */
    val open: List<String> get() = keys.filter { it !in sealed }

    fun vote(phone: String, key: String): Call? = votes[phone]?.get(key)
    fun say(phone: String, key: String): Call? = says[phone]?.get(key)
    fun decision(key: String): Fact? = decisions[key]
    fun isSealed(key: String) = key in sealed
    fun sealedBy(phone: String, key: String) = sealedBy[phone]?.contains(key) == true

    private fun allVoted(key: String) = members.all { vote(it, key) != null }

    fun state(key: String): ReviewState {
        if (key in sealed) return ReviewState.SEALED
        if (!allVoted(key)) return ReviewState.VOTING
        if (key in decisions) return ReviewState.DECIDED
        val first = vote(members[0], key)
        return if (first != Call.MAYBE && members.all { vote(it, key) == first }) ReviewState.AGREED else ReviewState.DISPUTED
    }

    /** What happens to the shot: kept or out. Null while it isn't settled. */
    fun outcome(key: String): Call? {
        sealed[key]?.let { return Call.of(it.value) }
        return when (state(key)) {
            ReviewState.DECIDED -> Call.of(decisions[key]!!.value)
            ReviewState.AGREED -> vote(members[0], key)
            else -> null
        }
    }

    /** The shots [phone] hasn't made a call on yet, in album order. */
    fun toVote(phone: String): List<String> = open.filter { vote(phone, it) == null }

    /** How many of the open shots [phone] has made a call on. */
    fun progress(phone: String): Int = open.count { vote(phone, it) != null }

    /** Everyone kept it straight away. */
    fun isMatch(key: String) = allVoted(key) && members.all { vote(it, key) == Call.PICK }

    /** Shots still to talk over, in album order. */
    val disputes: List<String> get() = open.filter { state(it) == ReviewState.DISPUTED }

    /** Everyone said what should happen, and not the same thing: agree, or flip a coin. */
    fun isStuck(key: String) = members.all { say(it, key) != null } && members.map { say(it, key) }.distinct().size > 1

    /** Everyone said the same while talking it over: that's the decision. */
    fun agreement(key: String): Call? = say(members[0], key)?.takeIf { c -> c != Call.MAYBE && members.all { say(it, key) == c } }

    /** All open shots are settled (and there are some). */
    val roundDone: Boolean get() = open.isNotEmpty() && open.all { outcome(it) != null }

    /**
     * What [phone] seals at the end of a round: the open shots once all are settled, and shots
     * someone else already sealed that [phone] hasn't.
     */
    fun toSeal(phone: String): List<String> {
        val theirs = sealed.keys.filter { !sealedBy(phone, it) }
        val mine = if (open.all { outcome(it) != null }) open else emptyList()
        return (mine + theirs).distinct()
    }

    /** How the review of [shots] went. */
    fun tally(shots: Collection<String>): ReviewTally {
        val list = shots.toList()
        val voted = list.filter { allVoted(it) }
        val same = voted.count { k -> members.map { vote(it, k) }.distinct().size == 1 }
        return ReviewTally(
            kept = list.count { outcome(it) == Call.PICK },
            out = list.count { outcome(it) == Call.REJECT },
            score = if (voted.isEmpty()) 0 else Math.round(100f * same / voted.size),
            matches = list.count { isMatch(it) },
            talked = list.count { decisions[it] != null },
            coins = list.count { decisions[it]?.slot == COIN },
        )
    }

    /** The facts for sealing the round: each shot as it came out, and the round's tally. */
    fun seal(phone: String, now: Long): List<Fact> {
        val keys = toSeal(phone)
        if (keys.isEmpty()) return emptyList()
        return keys.mapNotNull { k -> outcome(k)?.let { Fact(album, Fact.Kind.SEALED, phone, key = k, value = it.token, at = now) } } +
            Fact(album, Fact.Kind.ROUND, phone, slot = "$now", value = tally(keys).encode(), at = now)
    }

    // ---- Making facts ------------------------------------------------------------------------

    /** [phone]'s call on [key] (null takes it back). */
    fun voteFact(phone: String, key: String, call: Call?, now: Long) =
        Fact(album, Fact.Kind.VOTE, phone, key = key, value = call?.token ?: "", at = now)

    /**
     * [phone]'s calls on a stack of similar shots: keep the one at [chosen] and the rest out, or
     * [call] for all of them (out, or not sure).
     */
    fun stackFacts(phone: String, stack: List<String>, chosen: Int?, call: Call, now: Long): List<Fact> = stack.mapIndexed { i, k ->
        val c = if (call == Call.PICK) (if (i == chosen) Call.PICK else Call.REJECT) else call
        voteFact(phone, k, c, now)
    }

    /**
     * [phone] says [call] while talking [key] over; when that makes everyone agree, the decision
     * comes with it.
     */
    fun sayFacts(phone: String, key: String, call: Call, now: Long): List<Fact> {
        val say = Fact(album, Fact.Kind.SAY, phone, key = key, value = call.token, at = now)
        val after = members.all { m -> (if (m == phone) call else say(m, key)) == call }
        return if (after && key !in decisions) listOf(say, Fact(album, Fact.Kind.DECIDE, phone, slot = AGREE, key = key, value = call.token, at = now))
        else listOf(say)
    }

    /** A coin picks one of the calls made on a stuck shot ([pick] chooses the member, 0 until size). */
    fun coinFact(phone: String, key: String, pick: Int, now: Long): Fact? {
        if (!isStuck(key) || key in decisions) return null
        val winner = members[pick.mod(members.size)]
        return Fact(album, Fact.Kind.DECIDE, phone, slot = COIN, key = key, value = say(winner, key)!!.token, at = now)
    }

    /** Whose call the coin went with on [key] (null when it wasn't a coin). */
    fun coinWinner(key: String): String? {
        val d = decisions[key]?.takeIf { it.slot == COIN } ?: return null
        return members.firstOrNull { say(it, key)?.token == d.value }
    }

    companion object {
        const val AGREE = "agree"
        const val COIN = "coin"
    }
}
