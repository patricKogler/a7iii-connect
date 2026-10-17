package dev.kogler.a7iiiconnect.duo

import java.util.Base64

/**
 * One thing a phone did in a shared album, together with the others in it: a vote in the
 * review, a locked-in battle edit, a seal at the end. Everything people do together is a list of
 * these, kept on every member's phone and swapped whenever the phones meet.
 *
 * A fact belongs to the phone that made it ([phone]), so two phones never write the same fact.
 * When they do have the same one ([id]), the later [at] wins (a vote changed, or taken back with
 * an empty [value]). That makes swapping simple: put both lists together, keep the newer of each.
 *
 * [key] is a shot (by [Shot.key]); the other phone names the same shot with its own key, so
 * received facts are re-keyed like albums are ([Albums.rekey]).
 */
data class Fact(
    val album: String,
    val kind: Kind,
    val phone: String,
    /** The battle a battle fact is about ("" for the review). */
    val battle: String = "",
    /** Which round, leg or step of the battle ("" when there's only one). */
    val slot: String = "",
    val key: String = "",
    val value: String = "",
    val at: Long,
) {
    enum class Kind(val code: String) {
        /** Review: [phone]'s first call on shot [key]: P, R or M (not sure); "" took it back. */
        VOTE("v"),
        /** Review, talking it over: what [phone] says should happen to [key] now: P or R. */
        SAY("s"),
        /** Review: [key] is settled as [value] (P or R); [slot] is how: "agree" or "coin". */
        DECIDE("d"),
        /** Review: [phone] sealed [key] as [value] at the end of a round. */
        SEALED("x"),
        /** Review: [phone] sealed a round; [value] is its tally ([ReviewTally.encode]). */
        ROUND("r"),

        /** A battle: its [value] is [BattleSpec.encode]; [phone] started it. */
        BATTLE("b"),
        /** A battle round's photo [key] and its twist ([value], [Twist.encode]), [slot] the round. */
        SHOT("h"),
        /** [phone] started editing round [slot] (the clock of a timed round runs from [at]). */
        START("t"),
        /** [phone]'s locked-in edit for round [slot] (settings, see [packSettings]). */
        EDIT("e"),
        /** Copycat: the look [phone] made for the other to copy, round [slot]. */
        LOOK("l"),
        /** Copycat: [phone]'s copy of the other's look: "score;settings". */
        COPY("c"),
        /** Relay: leg [slot] by [phone], the edit as it stands after it. */
        LEG("g"),
        /** [phone]'s vote in round [slot]: the phone whose edit it likes more. */
        PICK("p"),
        /** [phone] sealed the battle at the end. */
        SEAL("z"),
        /** [phone] called the battle off. */
        DROP("o"),
        ;

        companion object {
            fun of(code: String) = entries.firstOrNull { it.code == code }
        }
    }

    /** Two facts with the same id are the same fact, one maybe newer. */
    val id: String get() = "$album\u0000${kind.code}\u0000$phone\u0000$battle\u0000$slot\u0000$key"
}

/** Fact rules: putting phones' facts together, re-keying, and writing them down. Free of Android. */
object Facts {

    /** Both lists put together: for each fact, the newer one (on a tie, the larger value, so both phones agree). */
    fun merge(mine: List<Fact>, theirs: List<Fact>): List<Fact> {
        val byId = LinkedHashMap<String, Fact>()
        for (f in mine + theirs) {
            val old = byId[f.id]
            if (old == null || f.at > old.at || f.at == old.at && f.value > old.value) byId[f.id] = f
        }
        return byId.values.toList()
    }

    /** Only the facts that change something in [mine]: what's worth sending after a change. */
    fun news(mine: List<Fact>, incoming: List<Fact>): List<Fact> {
        val known = mine.associateBy { it.id }
        return incoming.filter { f -> known[f.id].let { it == null || f.at > it.at || f.at == it.at && f.value > it.value } }
    }

    /** Facts with their shots under this phone's keys where [resolve] finds them. */
    fun rekey(facts: List<Fact>, resolve: (String) -> String?): List<Fact> {
        val moved = facts.map { f -> if (f.key.isEmpty()) f else resolve(f.key)?.takeIf { it != f.key }?.let { f.copy(key = it) } ?: f }
        return if (moved == facts) facts else merge(emptyList(), moved)
    }

    /** One fact per line, tab-separated: `kind album phone battle slot key at value`. */
    fun encode(facts: List<Fact>): String = buildString {
        for (f in facts) {
            append(f.kind.code).append('\t').append(f.album).append('\t').append(f.phone).append('\t')
                .append(f.battle).append('\t').append(f.slot).append('\t').append(f.key).append('\t')
                .append(f.at).append('\t').append(clean(f.value)).append('\n')
        }
    }

    fun decode(text: String): List<Fact> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 8) return@mapNotNull null
        val kind = Fact.Kind.of(p[0]) ?: return@mapNotNull null
        val at = p[6].toLongOrNull() ?: return@mapNotNull null
        Fact(p[1], kind, p[2], p[3], p[4], p[5], p[7], at)
    }.toList()

    private fun clean(v: String) = v.replace(Regex("[\\t\\r\\n]"), " ")

    /** Settings text (several lines) as one line, for a fact's value. */
    fun packSettings(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    fun unpackSettings(value: String): String = runCatching { String(Base64.getUrlDecoder().decode(value)) }.getOrDefault("")

    /**
     * A number from text that every phone works out the same: for coin flips that must come out
     * the same on both phones without asking each other (FNV-1a).
     */
    fun stableHash(text: String): Int {
        var h = 0x811C9DC5.toInt()
        for (b in text.toByteArray()) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return h and Int.MAX_VALUE
    }

    /** "DSC01234.ARW@1760000000000" -> "dsc01234": the same on every phone, whichever file it has. */
    fun stem(key: String): String = key.substringBeforeLast('@').substringBeforeLast('.').lowercase()
}
