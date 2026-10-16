package dev.kogler.a7iiiconnect.develop

/**
 * Picks the likely best of similar shots, and points out what's wrong with a shot: closed eyes
 * and softer focus. Only a suggestion: review still decides.
 */
object Best {
    /** An eye this open (0..1) or less counts as closed. */
    private const val CLOSED = 0.25f
    /** Faces narrower than this share of the picture are background people: their eyes don't count. */
    private const val MAIN_FACE = 0.05f
    /** Softer than this share of the sharpest in the stack gets "Softer". */
    private const val SOFTER = 0.75f
    /** The sharpest has to beat the next by this much to be called "Sharpest". */
    private const val CLEAR_LEAD = 1.1f

    /** People in the shot with their eyes shut (only faces big enough to matter). */
    fun blinks(i: ShotInsight?): Int =
        i?.faces?.count { it.width >= MAIN_FACE && (it.eyesOpen ?: 1f) <= CLOSED } ?: 0

    /** Higher is better; only meaningful against the rest of [stack]. */
    fun score(i: ShotInsight?, stack: List<ShotInsight?>): Float {
        if (i == null) return 0f
        val sharpest = stack.maxOfOrNull { it?.sharp ?: 0f }?.takeIf { it > 0f } ?: 1f
        val main = i.faces.filter { it.width >= MAIN_FACE }
        val smile = main.mapNotNull { it.smile }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
        return i.sharp / sharpest - 0.6f * blinks(i) + 0.1f * smile
    }

    /** [stack] with its likely best shot first; the rest stay in the order they were taken. */
    fun order(stack: List<Shot>, insights: Map<String, ShotInsight>): List<Shot> {
        if (stack.size < 2) return stack
        val all = stack.map { insights[it.key] }
        if (all.all { it == null }) return stack
        val best = stack.maxByOrNull { score(insights[it.key], all) } ?: return stack
        return listOf(best) + (stack - best)
    }

    /** Short notes for a shot's card: "Eyes closed", and next to similar shots "Sharpest" or "Softer". */
    fun notes(shot: Shot, insights: Map<String, ShotInsight>, stack: List<Shot> = emptyList()): List<String> {
        val i = insights[shot.key] ?: return emptyList()
        val out = mutableListOf<String>()
        when (val n = blinks(i)) {
            0 -> {}
            1 -> out += "Eyes closed"
            else -> out += "$n with eyes closed"
        }
        val others = stack.filter { it.key != shot.key }.mapNotNull { insights[it.key]?.sharp }
        if (others.isNotEmpty() && i.sharp > 0f) {
            val top = others.max()
            if (i.sharp >= top * CLEAR_LEAD) out += "Sharpest"
            else if (i.sharp < maxOf(top, i.sharp) * SOFTER) out += "Softer"
        }
        return out
    }
}
