package dev.kogler.a7iiiconnect.computer

import dev.kogler.a7iiiconnect.develop.Albums
import java.security.MessageDigest
import kotlin.math.abs

/** A photo file on one side, as the sync sees it: [key] is the side's own name for it. */
data class SyncFile(val name: String, val taken: Long) {
    val key: String get() = "$name@$taken"
    val isRaw: Boolean get() = name.endsWith(".arw", ignoreCase = true)
    val stem: String get() = name.substringBeforeLast('.').replace(COPY_SUFFIX, "").lowercase()
}

/** An edit as stored on one side: the text in the phone's format (empty when there is none) and when it changed. */
data class SyncEdit(val text: String, val modified: Long)

/**
 * What the phone and the computer agreed on when they last synced, in this phone's keys. Telling
 * "changed here" from "changed there" needs it: a mark the phone has and the computer lacks was
 * either just made on the phone or just cleared on the computer, and only the last agreement
 * says which. [known] are the photos both had, so one deleted on either side is not copied back.
 */
data class Base(
    val marks: Map<String, String> = emptyMap(),
    val edits: Map<String, String> = emptyMap(),
    val known: Set<String> = emptySet(),
    /** Each preset both had, by name, as a hash of its text. */
    val presets: Map<String, String> = emptyMap(),
    /** The LUTs both had, by id. */
    val luts: Set<String> = emptySet(),
) {
    /** Tab-separated lines: `K key` for each known photo, `M key mark`, `E key hash`, `P name hash`, `L id`. */
    fun encode(): String = buildString {
        known.sorted().forEach { append("K\t$it\n") }
        marks.toSortedMap().forEach { (k, v) -> append("M\t$k\t$v\n") }
        edits.toSortedMap().forEach { (k, v) -> append("E\t$k\t$v\n") }
        presets.toSortedMap().forEach { (k, v) -> append("P\t$k\t$v\n") }
        luts.sorted().forEach { append("L\t$it\n") }
    }

    companion object {
        fun decode(text: String): Base {
            val marks = HashMap<String, String>()
            val edits = HashMap<String, String>()
            val known = HashSet<String>()
            val presets = HashMap<String, String>()
            val luts = HashSet<String>()
            for (line in text.lineSequence()) {
                val p = line.split('\t')
                when {
                    p[0] == "K" && p.size >= 2 -> known += p[1]
                    p[0] == "M" && p.size >= 3 -> marks[p[1]] = p[2]
                    p[0] == "E" && p.size >= 3 -> edits[p[1]] = p[2]
                    p[0] == "P" && p.size >= 3 -> presets[p[1]] = p[2]
                    p[0] == "L" && p.size >= 2 -> luts += p[1]
                }
            }
            return Base(marks, edits, known, presets, luts)
        }
    }
}

/** What one sync changes: on the phone (its keys) and on the computer (its keys); null clears. */
data class Changes<T>(val phone: Map<String, T?>, val computer: Map<String, T?>)

/** Which photos the computer sends the phone. */
enum class PhotoPolicy(val label: String) {
    OFF("None"), RECENT("Last 30 days"), ALL("All");

    companion object {
        const val RECENT_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Works out a sync between this phone and a computer from what each side has, without touching
 * either: which photos go where, and how marks and edits are put together. Kept apart from the
 * phone's storage and the network so it can be tested on its own.
 */
object SyncPlan {
    /** The same file on both sides: the same name (MediaStore's " (1)" aside), taken around the same time. */
    fun sameFile(a: SyncFile, b: SyncFile) =
        a.isRaw == b.isRaw && a.stem == b.stem && a.name.substringAfterLast('.').equals(b.name.substringAfterLast('.'), true) &&
            abs(a.taken - b.taken) <= Albums.MATCH_WINDOW_MS

    /** Each of [files] with its counterpart in [other], if it has one. */
    fun counterparts(files: List<SyncFile>, other: List<SyncFile>): Map<SyncFile, SyncFile> {
        val byStem = other.groupBy { it.stem }
        return files.mapNotNull { f ->
            byStem[f.stem]?.filter { sameFile(f, it) }?.minByOrNull { abs(it.taken - f.taken) }?.let { f to it }
        }.toMap()
    }

    /** The phone's photos the computer hasn't got, leaving out ones it had and let go of. */
    fun toUpload(phone: List<SyncFile>, computer: List<SyncFile>, base: Base): List<SyncFile> {
        val there = counterparts(phone, computer)
        return phone.filter { it !in there && it.key !in base.known }
    }

    /** The computer's photos the phone hasn't got and [policy] asks for, leaving out ones the phone let go of. */
    fun toDownload(phone: List<SyncFile>, computer: List<SyncFile>, base: Base, policy: PhotoPolicy, now: Long): List<SyncFile> {
        if (policy == PhotoPolicy.OFF) return emptyList()
        val here = counterparts(computer, phone)
        return computer.filter { f ->
            f !in here && f.key !in base.known && (policy == PhotoPolicy.ALL || f.taken >= now - PhotoPolicy.RECENT_MS)
        }
    }

    /** The keys of the photos both sides have, under either side's name: what [Base.known] keeps. */
    fun known(phone: List<SyncFile>, computer: List<SyncFile>): Set<String> =
        counterparts(phone, computer).flatMap { (p, c) -> listOf(p.key, c.key) }.toSet()

    /**
     * Shot keys, the way both apps name a shot for its mark and edit: the RAW when there is one,
     * paired with the JPEG of the same name taken around the same time.
     */
    fun shotKeys(files: List<SyncFile>): List<String> {
        val out = mutableListOf<String>()
        for ((_, same) in files.groupBy { it.stem }) {
            val jpegs = same.filter { !it.isRaw }.toMutableList()
            for (raw in same.filter { it.isRaw }) {
                jpegs.filter { abs(it.taken - raw.taken) <= Albums.MATCH_WINDOW_MS }.minByOrNull { abs(it.taken - raw.taken) }?.let { jpegs -= it }
                out += raw.key
            }
            jpegs.forEach { out += it.key }
        }
        return out
    }

    /** Each phone shot key with the computer's key for the same shot, where it has it. */
    fun pairs(phoneShots: List<String>, computerShots: List<String>): Map<String, String> {
        val resolve = Albums.resolver(computerShots)
        return phoneShots.mapNotNull { k -> resolve(k)?.let { k to it } }.toMap()
    }

    /**
     * Marks put together three ways: a side that changed since [base] wins over one that didn't,
     * and when both changed, the phone (the one in hand) wins. Only shots both sides have count.
     * Answers the changes and the marks both now agree on.
     */
    fun marks(
        phone: Map<String, String>,
        computer: Map<String, String>,
        base: Map<String, String>,
        pairs: Map<String, String>,
    ): Pair<Changes<String>, Map<String, String>> {
        val toPhone = HashMap<String, String?>()
        val toComputer = HashMap<String, String?>()
        val agreed = HashMap<String, String>()
        for ((pk, ck) in pairs) {
            val p = phone[pk]
            val c = computer[ck]
            val result = when {
                p == c -> p
                p == base[pk] -> c
                else -> p
            }
            if (result != p) toPhone[pk] = result
            if (result != c) toComputer[ck] = result
            if (result != null) agreed[pk] = result
        }
        return Changes(toPhone, toComputer) to agreed
    }

    /**
     * Edits put together the same way, by what they say (texts are compared as given, so the
     * caller writes them out alike first). When both sides changed one, the later change wins.
     * Answers the changes and a hash of each edit both now have.
     */
    fun edits(
        phone: Map<String, SyncEdit>,
        computer: Map<String, SyncEdit>,
        base: Map<String, String>,
        pairs: Map<String, String>,
    ): Pair<Changes<SyncEdit>, Map<String, String>> {
        val none = SyncEdit("", 0)
        val toPhone = HashMap<String, SyncEdit?>()
        val toComputer = HashMap<String, SyncEdit?>()
        val agreed = HashMap<String, String>()
        for ((pk, ck) in pairs) {
            val p = phone[pk] ?: none
            val c = computer[ck] ?: none
            val b = base[pk] ?: hash("")
            val result = when {
                p.text == c.text -> p
                hash(p.text) == b -> c
                hash(c.text) == b -> p
                c.modified > p.modified -> c
                else -> p
            }
            if (result.text != p.text) toPhone[pk] = result.takeIf { it.text.isNotEmpty() }
            if (result.text != c.text) toComputer[ck] = result.takeIf { it.text.isNotEmpty() }
            if (result.text.isNotEmpty()) agreed[pk] = hash(result.text)
        }
        return Changes(toPhone, toComputer) to agreed
    }

    /**
     * Presets put together like edits, matched by name: one made or changed on a side goes to the
     * other, one deleted on a side is deleted on the other, and when both changed it the later wins.
     */
    fun presets(
        phone: Map<String, SyncEdit>,
        computer: Map<String, SyncEdit>,
        base: Map<String, String>,
    ): Pair<Changes<SyncEdit>, Map<String, String>> =
        edits(phone, computer, base, (phone.keys + computer.keys + base.keys).associateWith { it })

    /** What one sync does with LUTs, by id. */
    data class LutPlan(val download: Set<String>, val upload: Set<String>, val deleteHere: Set<String>, val deleteThere: Set<String>, val agreed: Set<String>)

    /**
     * LUTs are files that don't change, so only which ones each side has matters: a new one is
     * copied over, and one that both had and a side deleted since ([known]) goes on the other.
     */
    fun luts(phone: Set<String>, computer: Set<String>, known: Set<String>): LutPlan {
        val download = computer - phone - known
        val upload = phone - computer - known
        val deleteHere = (phone intersect known) - computer
        val deleteThere = (computer intersect known) - phone
        return LutPlan(download, upload, deleteHere, deleteThere, (phone intersect computer) + download + upload)
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}

/** What MediaStore adds to a name already taken: "DSC01234 (1).JPG". */
private val COPY_SUFFIX = Regex(" \\(\\d+\\)$")
