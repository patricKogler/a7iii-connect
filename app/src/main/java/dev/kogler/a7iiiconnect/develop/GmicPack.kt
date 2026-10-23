package dev.kogler.a7iiiconnect.develop

/**
 * The G'MIC film LUT pack's index (film_luts.json in github.com/YahiaAngelo/Film-Luts): a list of
 * { name, lut_file, category, thumbnail }. See [LutLibrary.downloadGmicPack].
 */
object GmicPack {
    const val BASE = "https://raw.githubusercontent.com/YahiaAngelo/Film-Luts/main/"
    const val INDEX = "film_luts.json"

    data class Item(val id: String, val name: String, val file: String, val category: String, val thumbnail: String?)

    /** The pack's own category names, made readable; the rest are kept as they are. */
    private val CATEGORIES = mapOf(
        "Negative New" to "Negative",
        "Negative Old" to "Negative (classic)",
        "Negative Color" to "Negative (color)",
        "Colorslide" to "Slide",
        "Bw" to "B&W",
        "Instant Pro" to "Instant (pro)",
        "Instant Consumer" to "Instant",
        "Fujixtransiii" to "Fuji X-Trans III",
        "Print" to "Cinema print",
    )

    /** Word fixes for the pack's title-cased names ("Kodak Portra 400 Vc" → "… VC"). */
    private val WORDS = mapOf(
        "Vc" to "VC", "Nc" to "NC", "Uc" to "UC", "Hc" to "HC", "Bw" to "BW", "Cn" to "CN",
        "Iii" to "III", "Hie" to "HIE", "Hs" to "HS", "Hp" to "HP", "Xp" to "XP", "Fp" to "FP",
        "Hps" to "HPS", "Ir" to "IR", "Apx" to "APX", "Gx" to "GX", "Xtrans" to "X-Trans",
    )

    fun parse(json: String): List<Item> {
        val obj = Regex("""\{[^{}]*\}""")
        fun field(o: String, k: String) = Regex(""""$k"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(o)?.groupValues?.get(1)
        return obj.findAll(json).mapNotNull { m ->
            val o = m.value
            val file = field(o, "lut_file")?.takeIf { it.endsWith(".cube", ignoreCase = true) } ?: return@mapNotNull null
            val name = field(o, "name") ?: file.substringAfterLast('/').substringBeforeLast('.')
            val category = field(o, "category") ?: "G'MIC"
            Item(
                // Push/pull variants differ only by "+" / "-", so those are spelt out.
                id = "gmic-" + file.removePrefix("luts/").substringBeforeLast('.').lowercase()
                    .replace("+", "p").replace(Regex("(?<=_)-+$")) { "m".repeat(it.value.length) }
                    .replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                name = name.split(' ').joinToString(" ") { WORDS[it] ?: it },
                file = file,
                category = CATEGORIES[category] ?: category,
                thumbnail = field(o, "thumbnail"),
            )
        }.distinctBy { it.id }.toList()
    }
}
