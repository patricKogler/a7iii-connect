package dev.kogler.a7iiiconnect.develop.engine

/**
 * A saved look. User presets set every slider of their [groups] (like Lightroom's "include"
 * checkboxes); built-in ones only set the sliders they name and leave the rest of the edit alone.
 */
data class Preset(val name: String, val settings: Settings, val groups: Set<Group>?, val builtIn: Boolean = true) {

    fun applyTo(current: Settings): Settings {
        if (groups != null) return current.pasteFrom(settings, groups)
        return current.copy(
            values = current.values + settings.values,
            profile = if (settings.profile != Profile.COLOR) settings.profile else current.profile,
            curves = List(4) { if (settings.curves[it].isIdentity) current.curves[it] else settings.curves[it] },
        )
    }

    companion object {
        private fun look(name: String, profile: Profile = Profile.COLOR, curve: Curve? = null, vararg v: Pair<Param, Float>) =
            Preset(name, Settings(profile = profile).with(*v).let { if (curve != null) it.withCurve(0, curve) else it }, null)

        val BUILT_IN = listOf(
            look("Natural", Profile.COLOR, null, P.VIBRANCE to 15f, P.CLARITY to 8f, P.TEXTURE to 8f),
            look("Punchy", Profile.VIVID, null, P.CONTRAST to 20f, P.VIBRANCE to 20f, P.CLARITY to 15f, P.DEHAZE to 6f, P.BLACKS to -10f),
            look(
                "Matte", Profile.NEUTRAL, Curve(listOf(0f to 0.07f, 0.25f to 0.24f, 0.75f to 0.78f, 1f to 0.96f)),
                P.CONTRAST to -10f, P.SATURATION to -12f, P.GRAIN to 12f,
            ),
            look(
                "Warm film", Profile.COLOR, Curve(listOf(0f to 0.04f, 0.5f to 0.5f, 1f to 0.97f)),
                P.GRADE_HUE[0] to 200f, P.GRADE_SAT[0] to 18f, P.GRADE_HUE[2] to 42f, P.GRADE_SAT[2] to 22f,
                P.SATURATION to -8f, P.GRAIN to 22f, P.GRAIN_SIZE to 30f, P.VIGNETTE to -12f,
            ),
            look(
                "Teal & orange", Profile.COLOR, null,
                P.GRADE_HUE[0] to 190f, P.GRADE_SAT[0] to 30f, P.GRADE_HUE[2] to 35f, P.GRADE_SAT[2] to 28f,
                P.MIX_HUE[5] to -15f, P.MIX_SAT[1] to 10f, P.CONTRAST to 10f,
            ),
            look(
                "Moody", Profile.NEUTRAL, null,
                P.EXPOSURE to -0.3f, P.CONTRAST to 15f, P.HIGHLIGHTS to -30f, P.SHADOWS to -10f, P.SATURATION to -20f,
                P.CLARITY to 12f, P.VIGNETTE to -25f, P.GRADE_HUE[0] to 215f, P.GRADE_SAT[0] to 15f,
            ),
            look("B&W", Profile.MONOCHROME, null, P.CONTRAST to 10f, P.CLARITY to 10f),
            look(
                "B&W contrast", Profile.MONOCHROME, null,
                P.CONTRAST to 40f, P.CLARITY to 25f, P.BLACKS to -15f, P.WHITES to 10f, P.GRAIN to 18f,
                P.MIX_LUM[5] to -30f, P.MIX_LUM[2] to 15f,
            ),
            look(
                "B&W soft", Profile.MONOCHROME, Curve(listOf(0f to 0.06f, 1f to 0.95f)),
                P.CONTRAST to -20f, P.HIGHLIGHTS to -20f, P.SHADOWS to 20f,
            ),
        )
    }
}
