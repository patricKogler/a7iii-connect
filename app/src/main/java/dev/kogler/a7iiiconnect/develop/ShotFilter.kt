package dev.kogler.a7iiiconnect.develop

/** Everything a shot can be filtered on, gathered once per library load. */
data class ShotFacts(
    val shot: Shot,
    val meta: ShotMeta?,
    val edited: Boolean = false,
    val exported: Boolean = false,
    /** Where it was taken, when the shot has coordinates and the place has been named. */
    val place: String? = null,
    /** What looking at the shot found (faces, focus, what's in it, text), once it has been. */
    val insight: ShotInsight? = null,
    /** [Person.key]s of the people in it. */
    val people: Set<String> = emptySet(),
    /** Names of the people in it, for search. */
    val names: List<String> = emptyList(),
) {
    /** Lower-case words search looks through: what's in it, its text, the place, the lens, who. */
    val words: List<String> by lazy {
        val i = insight
        val parts = mutableListOf<String>()
        if (i != null) {
            parts += i.labels
            parts += i.text
            if (i.faces.isNotEmpty()) parts += "person people face portrait"
            if (i.faces.size >= 3) parts += "group"
        }
        place?.let { parts += it }
        meta?.lens?.let { parts += it }
        parts += names
        parts.joinToString(" ").lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    /** Every word of [query] starts a word of the shot ("dogs" finds Dog too). */
    fun found(query: String): Boolean = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { q ->
        val stem = if (q.length > 3 && q.endsWith('s')) q.dropLast(1) else q
        words.any { it.startsWith(q) || it.startsWith(stem) }
    }
}

/** Plain-language filters for the basic sheet; each is a fixed combination of the exact ones. */
enum class FilterPreset(val label: String) {
    LOW_LIGHT("Low light"),
    LONG_EXPOSURE("Long exposure"),
    WIDE_OPEN("Wide open"),
    FAST_ACTION("Fast action");

    fun matches(m: ShotMeta?): Boolean = m != null && when (this) {
        LOW_LIGHT -> (m.iso ?: 0) >= 3200 || (m.exposure ?: 0.0) >= 1.0 / 30
        LONG_EXPOSURE -> (m.exposure ?: 0.0) >= 1.0
        WIDE_OPEN -> (m.aperture ?: 99f) <= 2.0f
        FAST_ACTION -> (m.exposure ?: 1.0) <= 1.0 / 1000
    }
}

enum class Framing(val label: String) { LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

enum class FileKind(val label: String) { PAIR("RAW+JPEG"), RAW("RAW only"), JPEG("JPEG only") }

enum class ShootingMode(val program: Int, val label: String) {
    MANUAL(1, "Manual"), PROGRAM(2, "Program"), APERTURE(3, "Aperture priority"), SHUTTER(4, "Shutter priority");
}

/**
 * The filter sheet's state. Values ticked within one group widen it (any of them), groups narrow
 * each other (all of them); presets each narrow. Ranges are inclusive and null means "any".
 */
data class ShotFilter(
    val presets: Set<FilterPreset> = emptySet(),
    val lenses: Set<String> = emptySet(),
    val framing: Set<Framing> = emptySet(),
    val places: Set<String> = emptySet(),
    val fromDay: Long? = null,
    val toDay: Long? = null,
    val focal: ClosedFloatingPointRange<Float>? = null,
    val aperture: ClosedFloatingPointRange<Float>? = null,
    val exposure: ClosedFloatingPointRange<Double>? = null,
    val iso: IntRange? = null,
    val bias: ClosedFloatingPointRange<Float>? = null,
    val modes: Set<ShootingMode> = emptySet(),
    val flash: Boolean? = null,
    val located: Boolean? = null,
    val files: Set<FileKind> = emptySet(),
    val exported: Boolean? = null,
    /** Words to look for in what's in the photo, its text, place, lens and people. */
    val query: String? = null,
    /** [Person.key]s: shots with any of them. */
    val people: Set<String> = emptySet(),
    /** Where the shots came from; set by the Camera / Phone / Shared chips, which show it themselves. */
    val sources: Set<Source> = emptySet(),
) {
    val isEmpty get() = this == ShotFilter()

    fun matches(f: ShotFacts): Boolean {
        val m = f.meta
        if (presets.any { !it.matches(m) }) return false
        if (lenses.isNotEmpty() && m?.lens !in lenses) return false
        if (framing.isNotEmpty() && framingOf(m) !in framing) return false
        if (places.isNotEmpty() && f.place !in places) return false
        val day = dayOf(f.shot.takenMillis)
        if (fromDay != null && day < fromDay) return false
        if (toDay != null && day > toDay) return false
        if (focal != null && m?.focal?.let { it in focal } != true) return false
        if (aperture != null && m?.aperture?.let { it in aperture } != true) return false
        if (exposure != null && m?.exposure?.let { it in exposure } != true) return false
        if (iso != null && m?.iso?.let { it in iso } != true) return false
        if (bias != null && m?.bias?.let { it in bias } != true) return false
        if (modes.isNotEmpty() && modes.none { it.program == m?.program }) return false
        if (flash != null && m?.flash != flash) return false
        if (located != null && (m?.located ?: false) != located) return false
        if (files.isNotEmpty() && kindOf(f.shot) !in files) return false
        if (exported != null && f.exported != exported) return false
        if (people.isNotEmpty() && people.none { it in f.people }) return false
        if (sources.isNotEmpty() && f.shot.source !in sources) return false
        if (!query.isNullOrBlank() && !f.found(query)) return false
        return true
    }

    /** What's set, one removable chip each, for the row above the grid. */
    fun chips(personName: (String) -> String = { it }): List<Pair<String, ShotFilter>> = buildList {
        query?.takeIf { it.isNotBlank() }?.let { add("\u201c${it.trim()}\u201d" to copy(query = null)) }
        people.forEach { add(personName(it) to copy(people = people - it)) }
        presets.forEach { add(it.label to copy(presets = presets - it)) }
        lenses.forEach { add(it to copy(lenses = lenses - it)) }
        framing.forEach { add(it.label to copy(framing = framing - it)) }
        places.forEach { add(it to copy(places = places - it)) }
        if (fromDay != null || toDay != null) add(dates(fromDay, toDay) to copy(fromDay = null, toDay = null))
        focal?.let { add("${mm(it.start)}–${mm(it.endInclusive)}" to copy(focal = null)) }
        aperture?.let { add("${fStop(it.start)}–${fStop(it.endInclusive)}" to copy(aperture = null)) }
        exposure?.let { add("${shutter(it.start)}–${shutter(it.endInclusive)}" to copy(exposure = null)) }
        iso?.let { add("ISO ${it.first}–${it.last}" to copy(iso = null)) }
        bias?.let { add("${ev(it.start)} to ${ev(it.endInclusive)}" to copy(bias = null)) }
        modes.forEach { add(it.label to copy(modes = modes - it)) }
        flash?.let { add((if (it) "Flash" else "No flash") to copy(flash = null)) }
        located?.let { add((if (it) "With location" else "No location") to copy(located = null)) }
        files.forEach { add(it.label to copy(files = files - it)) }
        exported?.let { add((if (it) "Exported" else "Not exported") to copy(exported = null)) }
    }

    companion object {
        fun framingOf(m: ShotMeta?) = when (m?.portrait) {
            true -> Framing.PORTRAIT
            false -> Framing.LANDSCAPE
            null -> null
        }

        fun kindOf(s: Shot) = when {
            s.raw != null && s.jpeg != null -> FileKind.PAIR
            s.raw != null -> FileKind.RAW
            else -> FileKind.JPEG
        }

        fun dates(from: Long?, to: Long?): String {
            val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            fun d(t: Long) = fmt.format(java.util.Date(t))
            return when {
                from != null && to != null -> if (from == to) d(from) else "${d(from)} – ${d(to)}"
                from != null -> "Since ${d(from)}"
                else -> "Until ${d(to!!)}"
            }
        }

        fun mm(v: Float) = "${Math.round(v)}mm"
        fun fStop(v: Float) = "f/" + if (v >= 10 || v == Math.round(v).toFloat()) "${Math.round(v)}" else "%.1f".format(java.util.Locale.US, v)
        fun shutter(s: Double) = if (s >= 0.3) {
            (if (s == Math.round(s).toDouble()) "${Math.round(s)}" else "%.1f".format(java.util.Locale.US, s)) + "s"
        } else "1/${Math.round(1 / s)}"
        fun ev(v: Float) = when {
            v > 0 -> "+%.1f EV".format(java.util.Locale.US, v)
            v < 0 -> "%.1f EV".format(java.util.Locale.US, v)
            else -> "0 EV"
        }
    }
}

/** A filter saved under a name; it gets its own chip next to All, To review and the rest. */
data class SavedFilter(val name: String, val filter: ShotFilter)

object SavedFilters {
    private const val PREFS = "library"
    private const val KEY = "saved_filters"

    fun load(context: android.content.Context): List<SavedFilter> = runCatching {
        val arr = org.json.JSONArray(context.getSharedPreferences(PREFS, 0).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedFilter(o.getString("name"), decode(o.getJSONObject("filter")))
        }
    }.getOrDefault(emptyList())

    fun save(context: android.content.Context, all: List<SavedFilter>) {
        val arr = org.json.JSONArray()
        all.forEach { arr.put(org.json.JSONObject().put("name", it.name).put("filter", encode(it.filter))) }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun encode(f: ShotFilter): org.json.JSONObject = org.json.JSONObject().apply {
        fun names(key: String, set: Set<Enum<*>>) { if (set.isNotEmpty()) put(key, org.json.JSONArray(set.map { it.name })) }
        fun range(key: String, a: Number?, b: Number?) { if (a != null && b != null) put(key, org.json.JSONArray(listOf(a.toDouble(), b.toDouble()))) }
        names("presets", f.presets)
        if (f.lenses.isNotEmpty()) put("lenses", org.json.JSONArray(f.lenses.toList()))
        names("framing", f.framing)
        if (f.places.isNotEmpty()) put("places", org.json.JSONArray(f.places.toList()))
        f.fromDay?.let { put("fromDay", it) }
        f.toDay?.let { put("toDay", it) }
        range("focal", f.focal?.start, f.focal?.endInclusive)
        range("aperture", f.aperture?.start, f.aperture?.endInclusive)
        range("exposure", f.exposure?.start, f.exposure?.endInclusive)
        range("iso", f.iso?.first, f.iso?.last)
        range("bias", f.bias?.start, f.bias?.endInclusive)
        names("modes", f.modes)
        f.flash?.let { put("flash", it) }
        f.located?.let { put("located", it) }
        names("files", f.files)
        f.exported?.let { put("exported", it) }
        f.query?.takeIf { it.isNotBlank() }?.let { put("query", it) }
        if (f.people.isNotEmpty()) put("people", org.json.JSONArray(f.people.toList()))
        names("sources", f.sources)
    }

    fun decode(o: org.json.JSONObject): ShotFilter {
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        fun pair(key: String) = o.optJSONArray(key)?.takeIf { it.length() == 2 }?.let { it.getDouble(0) to it.getDouble(1) }
        fun bool(key: String) = if (o.has(key)) o.getBoolean(key) else null
        fun long(key: String) = if (o.has(key)) o.getLong(key) else null
        return ShotFilter(
            presets = strings("presets").mapNotNull { n -> FilterPreset.entries.find { it.name == n } }.toSet(),
            lenses = strings("lenses").toSet(),
            framing = strings("framing").mapNotNull { n -> Framing.entries.find { it.name == n } }.toSet(),
            places = strings("places").toSet(),
            fromDay = long("fromDay"),
            toDay = long("toDay"),
            focal = pair("focal")?.let { it.first.toFloat()..it.second.toFloat() },
            aperture = pair("aperture")?.let { it.first.toFloat()..it.second.toFloat() },
            exposure = pair("exposure")?.let { it.first..it.second },
            iso = pair("iso")?.let { it.first.toInt()..it.second.toInt() },
            bias = pair("bias")?.let { it.first.toFloat()..it.second.toFloat() },
            modes = strings("modes").mapNotNull { n -> ShootingMode.entries.find { it.name == n } }.toSet(),
            flash = bool("flash"),
            located = bool("located"),
            files = strings("files").mapNotNull { n -> FileKind.entries.find { it.name == n } }.toSet(),
            exported = bool("exported"),
            query = o.optString("query").takeIf { it.isNotBlank() },
            people = strings("people").toSet(),
            sources = strings("sources").mapNotNull { n -> Source.entries.find { it.name == n } }.toSet(),
        )
    }
}
