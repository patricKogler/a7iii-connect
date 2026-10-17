package dev.kogler.a7iiiconnect.duo

import kotlin.random.Random

/** The kinds of edit battle. */
enum class Mode(val title: String, val blurb: String) {
    CLASSIC("Blind duel", "You both edit the same photo, then vote without knowing whose is whose."),
    THEME("Theme card", "You draw a theme. The edit that fits it better wins."),
    LIMITED("Limited tools", "Only two panels each, drawn at random."),
    SPEED("Speed round", "One minute each. The editor locks when the time is up."),
    COPYCAT("Copycat", "Each of you makes a look, then recreates the other's without seeing the settings. The closest copy wins."),
    RELAY("Relay", "One edit, made together. Take turns: Light, then Color, then Effects and Detail, then the finishing touches."),
    BEST_OF_3("Best of 3", "Three photos in a row, each with a surprise twist. First to two wins."),
    ;

    /** Modes where everyone edits the photo and then votes. */
    val isDuel get() = this != COPYCAT && this != RELAY
    val rounds get() = if (this == BEST_OF_3) 3 else 1
}

/**
 * What makes a round harder: a [theme] to edit towards, only some [tools] (by the editor's
 * tool names; empty means all), and a clock of [seconds] (0: no clock).
 */
data class Twist(val theme: String? = null, val tools: List<String> = emptyList(), val seconds: Int = 0) {
    val isPlain get() = theme == null && tools.isEmpty() && seconds == 0
    fun encode() = "${theme.orEmpty()}|${tools.joinToString(",")}|$seconds"

    companion object {
        fun decode(text: String): Twist {
            val p = text.split('|')
            return Twist(
                theme = p.getOrNull(0)?.takeIf { it.isNotBlank() },
                tools = p.getOrNull(1)?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                seconds = p.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** A relay leg: who's on it edits only with [tools] (empty: any). */
data class Leg(val title: String, val tools: List<String>)

/** Where a round (or the whole battle, for Copycat and Relay) stands. */
enum class Stage { EDIT, VOTE, DONE }

/** What a battle needs from one person right now, for the list of battles. */
enum class BattleStatus { YOUR_TURN, WAITING, RESULT, SEALED, DROPPED }

/** Starting battles. */
object BattleRules {
    const val SPEED_SECONDS = 60

    val THEMES = listOf(
        "Moody", "Summer film", "Black and white", "Golden hour", "Neon night", "Faded seventies",
        "Dreamy pastel", "High drama", "Cold and lonely", "Warm and cosy", "Old postcard", "Pop art",
        "Matte and minimal", "Horror film", "Teal and orange", "Underwater", "Too bright, on purpose",
        "Autumn", "Winter morning", "Album cover", "Old newspaper", "Moonlight", "Sunburnt", "Dream sequence",
    )

    /** Panels a Limited tools round can draw from (the editor's tool names). */
    val TOOL_POOL = listOf("LIGHT", "COLOR", "EFFECTS", "DETAIL", "CROP", "MASKS")

    val LEGS = listOf(
        Leg("Light", listOf("LIGHT")),
        Leg("Color", listOf("COLOR")),
        Leg("Effects and Detail", listOf("EFFECTS", "DETAIL")),
        Leg("Finishing touches", emptyList()),
    )

    /** The twist a round of [mode] gets; Best of 3 draws one of the others for each round. */
    fun twistFor(mode: Mode, random: Random): Twist = when (mode) {
        Mode.THEME -> Twist(theme = THEMES.random(random))
        Mode.LIMITED -> Twist(tools = TOOL_POOL.shuffled(random).take(2).sortedBy { TOOL_POOL.indexOf(it) })
        Mode.SPEED -> Twist(seconds = SPEED_SECONDS)
        Mode.BEST_OF_3 -> twistFor(listOf(Mode.CLASSIC, Mode.THEME, Mode.LIMITED, Mode.SPEED).random(random), random)
        else -> Twist()
    }

    /**
     * The facts that start a battle of [mode] by [phone] over [keys] (one shot per round; a
     * Best of 3 over fewer shots uses them again). [extra] twists add to every round.
     */
    fun start(
        album: String, phone: String, id: String, mode: Mode, keys: List<String>, now: Long,
        random: Random = Random.Default, extra: Twist = Twist(),
    ): List<Fact> {
        require(keys.isNotEmpty())
        val battle = Fact(album, Fact.Kind.BATTLE, phone, battle = id, value = mode.name, at = now)
        val rounds = (0 until mode.rounds).map { r ->
            val t = twistFor(mode, random)
            val twist = Twist(
                theme = t.theme ?: extra.theme,
                tools = t.tools.ifEmpty { extra.tools },
                seconds = if (t.seconds > 0) t.seconds else extra.seconds,
            )
            Fact(album, Fact.Kind.SHOT, phone, battle = id, slot = "$r", key = keys[r % keys.size], value = twist.encode(), at = now)
        }
        return listOf(battle) + rounds
    }
}

/** One round of a battle: the photo and its twist. */
data class BattleRound(val index: Int, val key: String, val twist: Twist)

/**
 * An edit battle in a shared album, worked out from the phones' facts like the review is
 * ([TogetherReview]). Nobody has to wait for anybody: each edit, look, copy, leg and vote is
 * locked in on its own phone and reaches the others when the phones next meet.
 *
 * Votes that split are settled by a coin that comes out the same on every phone, seeded by the
 * battle and round ([Facts.stableHash]).
 */
class TogetherBattle(val album: String, val id: String, members: List<String>, facts: List<Fact>) {
    private val mine = facts.filter { it.album == album && it.battle == id }
    private val spec = mine.firstOrNull { it.kind == Fact.Kind.BATTLE }

    val exists get() = spec != null
    val mode: Mode = spec?.value?.let { v -> Mode.entries.firstOrNull { it.name == v } } ?: Mode.CLASSIC
    val createdBy: String = spec?.phone ?: ""
    val createdAt: Long = spec?.at ?: 0L
    val droppedBy: String? = mine.firstOrNull { it.kind == Fact.Kind.DROP }?.phone

    /** Everyone in it: the one who started it first, then the others (the relay's order). */
    val players: List<String> = (listOf(createdBy).filter { it.isNotEmpty() && it in members } + members.sorted()).distinct()

    val rounds: List<BattleRound> = mine.filter { it.kind == Fact.Kind.SHOT }
        .mapNotNull { f -> f.slot.toIntOrNull()?.let { BattleRound(it, f.key, Twist.decode(f.value)) } }
        .sortedBy { it.index }

    private fun get(kind: Fact.Kind, phone: String, slot: String) =
        mine.firstOrNull { it.kind == kind && it.phone == phone && it.slot == slot && it.value.isNotEmpty() }

    // ---- Duels: edit, then vote ----------------------------------------------------------------

    fun edit(phone: String, round: Int): String? = get(Fact.Kind.EDIT, phone, "$round")?.value
    fun started(phone: String, round: Int): Long? = get(Fact.Kind.START, phone, "$round")?.at
    fun pick(phone: String, round: Int): String? = get(Fact.Kind.PICK, phone, "$round")?.value

    fun stage(round: Int): Stage = when {
        players.any { edit(it, round) == null } -> Stage.EDIT
        players.any { pick(it, round) == null } -> Stage.VOTE
        else -> Stage.DONE
    }

    /** The phones with the most votes in [round] (more than one: a split vote). */
    private fun leaders(round: Int): List<String> {
        val counts = players.associateWith { p -> players.count { pick(it, round) == p } }
        val top = counts.values.maxOrNull() ?: 0
        return players.filter { counts[it] == top }
    }

    /** Whose edit won [round]; a split vote goes to a coin that lands the same on every phone. */
    fun winner(round: Int): String? {
        if (stage(round) != Stage.DONE) return null
        return coin(leaders(round), "$id/$round")
    }

    /** The votes in [round] split, so the coin decided. */
    fun flipped(round: Int) = stage(round) == Stage.DONE && leaders(round).size > 1

    private fun coin(among: List<String>, seed: String): String? =
        if (among.isEmpty()) null else among[Facts.stableHash(seed) % among.size]

    /** Rounds won by [phone] so far. */
    fun wins(phone: String) = rounds.count { winner(it.index) == phone }

    /** The round being played: the first that isn't done, or null once the battle is over. */
    val current: BattleRound?
        get() = if (mode.isDuel) rounds.firstOrNull { stage(it.index) != Stage.DONE }?.takeIf { !seriesDecided } else null

    /** Someone has won more than half the rounds: the rest aren't needed. */
    private val seriesDecided get() = rounds.size > 1 && players.any { wins(it) * 2 > rounds.size }

    // ---- Copycat: everyone makes a look, then copies someone else's -------------------------

    fun look(phone: String): String? = get(Fact.Kind.LOOK, phone, "0")?.value

    /** [phone]'s copy: how close it got (0..100) and its settings. */
    fun copy(phone: String): Pair<Int, String>? = get(Fact.Kind.COPY, phone, "0")?.value?.let { v ->
        val score = v.substringBefore(';').toIntOrNull() ?: return@let null
        score to v.substringAfter(';', "")
    }

    /** Whose look [phone] copies: the next player round the table. */
    fun target(phone: String): String {
        val i = players.indexOf(phone)
        return if (i < 0 || players.size < 2) phone else players[(i + 1) % players.size]
    }

    val copyStage: Stage
        get() = when {
            players.any { look(it) == null } -> Stage.EDIT
            players.any { copy(it) == null } -> Stage.VOTE
            else -> Stage.DONE
        }

    /** The closest copy; a tie goes to the coin. */
    val copyWinner: String?
        get() {
            if (copyStage != Stage.DONE) return null
            val top = players.maxOf { copy(it)!!.first }
            return coin(players.filter { copy(it)!!.first == top }, "$id/copy")
        }

    // ---- Relay: one edit, in turns ------------------------------------------------------------

    fun leg(n: Int): Fact? = mine.firstOrNull { it.kind == Fact.Kind.LEG && it.slot == "$n" && it.value.isNotEmpty() }
    val legsDone: Int get() = BattleRules.LEGS.indices.takeWhile { leg(it) != null }.size
    fun holder(n: Int): String = players[n % players.size]
    val relayDone get() = legsDone >= BattleRules.LEGS.size

    /** The relay's edit as it stands (packed settings), or null before the first leg. */
    val relayEdit: String? get() = (legsDone - 1).takeIf { it >= 0 }?.let { leg(it)?.value }

    // ---- The whole battle ----------------------------------------------------------------------

    val done: Boolean
        get() = when (mode) {
            Mode.COPYCAT -> copyStage == Stage.DONE
            Mode.RELAY -> relayDone
            else -> rounds.isNotEmpty() && current == null
        }

    /** Who won the battle (null for a relay, which nobody wins, or before it's over). */
    val champion: String?
        get() = when {
            !done -> null
            mode == Mode.COPYCAT -> copyWinner
            mode == Mode.RELAY -> null
            else -> {
                val top = players.maxOf { wins(it) }
                coin(players.filter { wins(it) == top }, "$id/series")
            }
        }

    fun sealedBy(phone: String) = mine.any { it.kind == Fact.Kind.SEAL && it.phone == phone }
    /** When [phone] sealed it, for the date on the seal. */
    fun sealedAt(phone: String): Long? = mine.filter { it.kind == Fact.Kind.SEAL && it.phone == phone }.minOfOrNull { it.at }
    val sealers: List<String> get() = players.filter { sealedBy(it) }

    fun status(me: String): BattleStatus = when {
        droppedBy != null -> BattleStatus.DROPPED
        done -> if (sealedBy(me)) BattleStatus.SEALED else BattleStatus.RESULT
        mode == Mode.COPYCAT -> when {
            look(me) == null -> BattleStatus.YOUR_TURN
            copyStage == Stage.EDIT -> BattleStatus.WAITING
            copy(me) == null -> BattleStatus.YOUR_TURN
            else -> BattleStatus.WAITING
        }
        mode == Mode.RELAY -> if (holder(legsDone) == me) BattleStatus.YOUR_TURN else BattleStatus.WAITING
        else -> {
            val r = current?.index ?: 0
            when {
                edit(me, r) == null -> BattleStatus.YOUR_TURN
                stage(r) == Stage.EDIT -> BattleStatus.WAITING
                pick(me, r) == null -> BattleStatus.YOUR_TURN
                else -> BattleStatus.WAITING
            }
        }
    }

    // ---- Making facts ------------------------------------------------------------------------

    private fun fact(kind: Fact.Kind, phone: String, slot: String, value: String, now: Long) =
        Fact(album, kind, phone, battle = id, slot = slot, value = value, at = now)

    fun startFact(phone: String, round: Int, now: Long) = fact(Fact.Kind.START, phone, "$round", "1", now)
    fun editFact(phone: String, round: Int, packed: String, now: Long) = fact(Fact.Kind.EDIT, phone, "$round", packed, now)
    fun pickFact(phone: String, round: Int, whose: String, now: Long) = fact(Fact.Kind.PICK, phone, "$round", whose, now)
    fun lookFact(phone: String, packed: String, now: Long) = fact(Fact.Kind.LOOK, phone, "0", packed, now)
    fun copyFact(phone: String, score: Int, packed: String, now: Long) = fact(Fact.Kind.COPY, phone, "0", "${score.coerceIn(0, 100)};$packed", now)
    fun legFact(phone: String, n: Int, packed: String, now: Long) = fact(Fact.Kind.LEG, phone, "$n", packed, now)
    fun sealFact(phone: String, now: Long) = fact(Fact.Kind.SEAL, phone, "", "1", now)
    fun dropFact(phone: String, now: Long) = fact(Fact.Kind.DROP, phone, "", "1", now)

    companion object {
        /** Every battle in [album], newest first. */
        fun all(album: String, members: List<String>, facts: List<Fact>): List<TogetherBattle> =
            facts.filter { it.album == album && it.kind == Fact.Kind.BATTLE }.map { TogetherBattle(album, it.battle, members, facts) }
                .sortedByDescending { it.createdAt }
    }
}
