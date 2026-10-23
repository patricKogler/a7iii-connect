package dev.kogler.a7iiiconnect.develop.engine

/**
 * A saved look. User presets set every slider of their [groups] (like Lightroom's "include"
 * checkboxes). Built-in ones replace the whole look (profile, tone, curves, color, effects) so
 * tapping one after another swaps them rather than stacking, and leave the photo's own
 * corrections alone: exposure, white balance, detail, optics, geometry, crop and masks.
 */
data class Preset(
    val name: String, val settings: Settings, val groups: Set<Group>?, val builtIn: Boolean = true,
    /** One of the film stocks, listed apart from the other looks. */
    val film: Boolean = false,
) {

    fun applyTo(current: Settings): Settings {
        if (groups != null) return current.pasteFrom(settings, groups)
        return current.copy(
            values = current.values.filterKeys { it !in LOOK_KEYS } + settings.values.filterKeys { it in LOOK_KEYS },
            profile = settings.profile,
            curves = settings.curves,
            lut = settings.lut,
        )
    }

    /** Whether [current] already wears this look. */
    fun isApplied(current: Settings) = applyTo(current) == current

    companion object {
        private val LOOK_GROUPS = setOf(Group.LIGHT, Group.CURVE, Group.COLOR, Group.MIXER, Group.GRADING, Group.EFFECTS)
        private val CORRECTIONS = setOf(P.EXPOSURE, P.TEMPERATURE, P.TINT)

        /** The sliders a built-in look owns. */
        val LOOK_KEYS: Set<String> = P.all.filter { it.group in LOOK_GROUPS && it !in CORRECTIONS }.map { it.key }.toSet()

        private fun look(name: String, profile: Profile = Profile.COLOR, curve: Curve? = null, vararg v: Pair<Param, Float>) =
            Preset(name, Settings(profile = profile).with(*v).let { if (curve != null) it.withCurve(0, curve) else it }, null)

        /**
         * A film stock: a master curve and, for its colour casts, red / green / blue curves (each a
         * list of points), on top of the sliders.
         */
        private fun film(
            name: String, profile: Profile, curve: Curve, rgb: List<Curve>? = null, vararg v: Pair<Param, Float>,
        ): Preset {
            var s = Settings(profile = profile).with(*v).withCurve(0, curve)
            rgb?.forEachIndexed { i, c -> s = s.withCurve(i + 1, c) }
            return Preset(name, s, null, film = true)
        }

        private fun c(vararg p: Pair<Float, Float>) = Curve(p.toList())

        /**
         * Hand-tuned film stocks, built from the app's own sliders (no third-party LUTs), so they
         * ship with the app. Approximations of each stock's character, not measured emulations.
         */
        val FILM = listOf(
            // Soft contrast, warm and forgiving skin, pastel greens, lifted blacks.
            film(
                "Portra 400", Profile.NEUTRAL, c(0f to 0.05f, 0.25f to 0.25f, 0.6f to 0.63f, 1f to 0.96f),
                listOf(c(0f to 0.02f, 0.5f to 0.52f, 1f to 1f), Curve(), c(0f to 0.03f, 0.5f to 0.49f, 1f to 0.95f)),
                P.CONTRAST to -8f, P.HIGHLIGHTS to -20f, P.SHADOWS to 12f, P.SATURATION to -8f, P.VIBRANCE to 8f,
                P.MIX_HUE[0] to 6f, P.MIX_HUE[1] to 4f, P.MIX_SAT[1] to -6f, P.MIX_LUM[1] to 8f,
                P.MIX_HUE[3] to 22f, P.MIX_SAT[3] to -25f, P.MIX_LUM[3] to 6f, P.MIX_SAT[5] to -12f,
                P.GRAIN to 14f, P.GRAIN_SIZE to 30f,
            ),
            // Fine grain, deep saturated reds and blues, punchy contrast.
            film(
                "Ektar 100", Profile.VIVID, c(0f to 0f, 0.25f to 0.21f, 0.75f to 0.8f, 1f to 1f),
                listOf(c(0f to 0f, 0.5f to 0.52f, 1f to 1f), Curve(), Curve()),
                P.CONTRAST to 12f, P.HIGHLIGHTS to -15f, P.BLACKS to -8f, P.VIBRANCE to 15f,
                P.MIX_SAT[0] to 15f, P.MIX_HUE[1] to -6f, P.MIX_SAT[5] to 18f, P.MIX_LUM[5] to -10f, P.MIX_HUE[3] to 8f,
                P.GRADE_HUE[0] to 215f, P.GRADE_SAT[0] to 8f, P.GRAIN to 6f, P.GRAIN_SIZE to 15f,
            ),
            // Warm, golden, a little nostalgic: yellow highlights, soft blacks.
            film(
                "Gold 200", Profile.COLOR, c(0f to 0.04f, 0.25f to 0.24f, 0.75f to 0.78f, 1f to 0.97f),
                listOf(c(0f to 0.02f, 0.5f to 0.53f, 1f to 1f), c(0f to 0.01f, 0.5f to 0.51f, 1f to 0.99f), c(0f to 0f, 0.5f to 0.45f, 1f to 0.9f)),
                P.HIGHLIGHTS to -15f, P.SATURATION to -4f, P.VIBRANCE to 10f,
                P.MIX_HUE[1] to -4f, P.MIX_SAT[2] to 10f, P.MIX_HUE[3] to 18f, P.MIX_SAT[3] to -15f, P.MIX_SAT[5] to -10f,
                P.GRAIN to 18f, P.GRAIN_SIZE to 35f, P.VIGNETTE to -8f,
            ),
            // Airy and cool: minty greens, cyan shadows, pastel skin.
            film(
                "Fuji 400H", Profile.NEUTRAL, c(0f to 0.06f, 0.3f to 0.33f, 0.7f to 0.74f, 1f to 0.97f),
                listOf(c(0f to 0f, 0.5f to 0.49f, 1f to 0.98f), c(0f to 0.03f, 0.5f to 0.51f, 1f to 1f), c(0f to 0.07f, 0.5f to 0.52f, 1f to 1f)),
                P.CONTRAST to -12f, P.HIGHLIGHTS to -10f, P.SHADOWS to 15f, P.WHITES to 6f, P.SATURATION to -10f,
                P.MIX_HUE[0] to -6f, P.MIX_SAT[1] to -10f, P.MIX_LUM[1] to 6f,
                P.MIX_HUE[3] to 35f, P.MIX_SAT[3] to -15f, P.MIX_LUM[3] to 10f, P.MIX_HUE[4] to -10f,
                P.GRAIN to 14f, P.GRAIN_SIZE to 30f,
            ),
            // The everyday consumer film: green-cyan shadows, punchy midtones, visible grain.
            film(
                "Superia 400", Profile.COLOR, c(0f to 0.03f, 0.25f to 0.22f, 0.75f to 0.79f, 1f to 0.98f),
                listOf(c(0f to 0f, 0.5f to 0.49f, 1f to 1f), c(0f to 0.04f, 0.5f to 0.51f, 1f to 1f), c(0f to 0.04f, 0.5f to 0.5f, 1f to 0.96f)),
                P.CONTRAST to 8f, P.VIBRANCE to 6f,
                P.MIX_HUE[3] to 25f, P.MIX_SAT[3] to 8f, P.MIX_SAT[1] to -5f, P.MIX_HUE[5] to -8f,
                P.GRAIN to 24f, P.GRAIN_SIZE to 40f, P.GRAIN_ROUGHNESS to 55f,
            ),
            // Tungsten movie stock: teal shadows, warm glowing highlights, strong grain.
            film(
                "CineStill 800T", Profile.NEUTRAL, c(0f to 0.05f, 0.3f to 0.27f, 0.7f to 0.74f, 1f to 0.97f),
                listOf(c(0f to 0f, 0.5f to 0.48f, 0.85f to 0.88f, 1f to 1f), Curve(), c(0f to 0.08f, 0.5f to 0.55f, 1f to 0.94f)),
                P.CONTRAST to 10f, P.HIGHLIGHTS to -25f, P.SATURATION to -6f, P.VIBRANCE to 8f,
                P.GRADE_HUE[0] to 195f, P.GRADE_SAT[0] to 20f, P.GRADE_HUE[2] to 25f, P.GRADE_SAT[2] to 18f,
                P.MIX_SAT[0] to 12f, P.MIX_HUE[2] to -10f, P.MIX_SAT[3] to -20f,
                P.GRAIN to 28f, P.GRAIN_SIZE to 45f, P.GRAIN_ROUGHNESS to 55f, P.VIGNETTE to -10f,
            ),
            // Classic press black and white: bold contrast, deep blacks, gritty grain.
            film(
                "Tri-X 400", Profile.MONOCHROME, c(0f to 0.02f, 0.25f to 0.19f, 0.75f to 0.82f, 1f to 1f), null,
                P.CONTRAST to 25f, P.BLACKS to -10f, P.CLARITY to 12f,
                P.MIX_LUM[0] to 8f, P.MIX_LUM[1] to 12f, P.MIX_LUM[5] to -15f,
                P.GRAIN to 32f, P.GRAIN_SIZE to 40f, P.GRAIN_ROUGHNESS to 65f, P.VIGNETTE to -8f,
            ),
            // Softer black and white with a long grey scale.
            film(
                "HP5 Plus", Profile.MONOCHROME, c(0f to 0.05f, 0.3f to 0.29f, 0.7f to 0.72f, 1f to 0.96f), null,
                P.CONTRAST to 5f, P.HIGHLIGHTS to -15f, P.SHADOWS to 10f,
                P.MIX_LUM[1] to 8f, P.MIX_LUM[5] to -8f,
                P.GRAIN to 28f, P.GRAIN_SIZE to 45f, P.GRAIN_ROUGHNESS to 50f,
            ),
        )

        /** Back to the plain Color profile with no look, keeping the photo's corrections. */
        val NONE = look("None")

        val BUILT_IN = listOf(
            NONE,
            look("Natural", Profile.COLOR, null, P.VIBRANCE to 15f, P.CLARITY to 8f, P.TEXTURE to 8f),
            look("Punchy", Profile.VIVID, null, P.CONTRAST to 20f, P.VIBRANCE to 20f, P.CLARITY to 15f, P.DEHAZE to 6f, P.BLACKS to -10f),
            look(
                "Bright & airy", Profile.COLOR, Curve(listOf(0f to 0.05f, 0.5f to 0.56f, 1f to 1f)),
                P.CONTRAST to -15f, P.HIGHLIGHTS to -25f, P.SHADOWS to 35f, P.WHITES to 12f,
                P.VIBRANCE to 10f, P.SATURATION to -6f, P.CLARITY to -8f,
                P.MIX_LUM[1] to 10f, P.MIX_SAT[1] to -8f, P.MIX_LUM[3] to 8f,
            ),
            look(
                "Golden hour", Profile.COLOR, null,
                P.CONTRAST to 8f, P.HIGHLIGHTS to -20f, P.SHADOWS to 10f, P.VIBRANCE to 18f,
                P.GRADE_HUE[1] to 38f, P.GRADE_SAT[1] to 14f, P.GRADE_HUE[2] to 45f, P.GRADE_SAT[2] to 25f,
                P.MIX_SAT[1] to 12f, P.MIX_HUE[2] to -8f, P.VIGNETTE to -10f,
            ),
            look(
                "Cool & clean", Profile.NEUTRAL, null,
                P.CONTRAST to 12f, P.WHITES to 8f, P.BLACKS to -8f, P.VIBRANCE to 8f, P.SATURATION to -10f,
                P.GRADE_HUE[0] to 210f, P.GRADE_SAT[0] to 14f, P.GRADE_HUE[2] to 200f, P.GRADE_SAT[2] to 6f,
                P.MIX_SAT[1] to -15f, P.MIX_SAT[5] to 10f, P.CLARITY to 6f,
            ),
            look(
                "Matte", Profile.NEUTRAL, Curve(listOf(0f to 0.07f, 0.25f to 0.24f, 0.75f to 0.78f, 1f to 0.96f)),
                P.CONTRAST to -10f, P.SATURATION to -12f, P.GRAIN to 12f,
            ),
            look(
                "Faded", Profile.NEUTRAL, Curve(listOf(0f to 0.14f, 0.3f to 0.32f, 0.7f to 0.72f, 1f to 0.92f)),
                P.CONTRAST to -18f, P.SATURATION to -25f, P.VIBRANCE to 5f,
                P.GRADE_HUE[0] to 160f, P.GRADE_SAT[0] to 10f, P.GRADE_HUE[2] to 50f, P.GRADE_SAT[2] to 10f, P.GRAIN to 15f,
            ),
            look(
                "Warm film", Profile.COLOR, Curve(listOf(0f to 0.04f, 0.5f to 0.5f, 1f to 0.97f)),
                P.GRADE_HUE[0] to 200f, P.GRADE_SAT[0] to 18f, P.GRADE_HUE[2] to 42f, P.GRADE_SAT[2] to 22f,
                P.SATURATION to -8f, P.GRAIN to 22f, P.GRAIN_SIZE to 30f, P.VIGNETTE to -12f,
            ),
            look(
                "Portrait film", Profile.NEUTRAL, Curve(listOf(0f to 0.05f, 0.25f to 0.25f, 0.75f to 0.77f, 1f to 0.97f)),
                P.CONTRAST to -5f, P.HIGHLIGHTS to -15f, P.SHADOWS to 10f, P.VIBRANCE to 6f, P.SATURATION to -10f,
                P.MIX_HUE[1] to 5f, P.MIX_SAT[1] to -10f, P.MIX_LUM[1] to 8f, P.MIX_HUE[3] to 20f, P.MIX_SAT[3] to -20f,
                P.GRADE_HUE[0] to 180f, P.GRADE_SAT[0] to 8f, P.GRADE_HUE[2] to 35f, P.GRADE_SAT[2] to 12f,
                P.CLARITY to -5f, P.GRAIN to 14f,
            ),
            look(
                "Chrome", Profile.NEUTRAL, Curve(listOf(0f to 0f, 0.25f to 0.21f, 0.75f to 0.8f, 1f to 1f)),
                P.CONTRAST to 10f, P.SATURATION to -18f, P.VIBRANCE to -5f,
                P.MIX_SAT[0] to 8f, P.MIX_LUM[5] to -15f, P.MIX_SAT[5] to -10f, P.MIX_HUE[3] to 10f, P.MIX_SAT[3] to -25f,
                P.GRADE_HUE[0] to 200f, P.GRADE_SAT[0] to 8f, P.CLARITY to 10f,
            ),
            look(
                "Teal & orange", Profile.COLOR, null,
                P.GRADE_HUE[0] to 190f, P.GRADE_SAT[0] to 30f, P.GRADE_HUE[2] to 35f, P.GRADE_SAT[2] to 28f,
                P.MIX_HUE[5] to -15f, P.MIX_SAT[1] to 10f, P.CONTRAST to 10f,
            ),
            look(
                "Cinematic", Profile.NEUTRAL, Curve(listOf(0f to 0.05f, 0.3f to 0.26f, 0.7f to 0.74f, 1f to 0.95f)),
                P.CONTRAST to 12f, P.HIGHLIGHTS to -30f, P.SHADOWS to 5f, P.SATURATION to -15f,
                P.GRADE_HUE[0] to 195f, P.GRADE_SAT[0] to 22f, P.GRADE_HUE[2] to 38f, P.GRADE_SAT[2] to 15f,
                P.MIX_SAT[2] to -20f, P.MIX_SAT[3] to -30f, P.MIX_HUE[3] to 25f, P.VIGNETTE to -15f,
            ),
            look(
                "Moody", Profile.NEUTRAL, null,
                P.CONTRAST to 15f, P.HIGHLIGHTS to -40f, P.WHITES to -10f, P.SHADOWS to -10f, P.SATURATION to -20f,
                P.CLARITY to 12f, P.VIGNETTE to -25f, P.GRADE_HUE[0] to 215f, P.GRADE_SAT[0] to 15f,
            ),
            look(
                "Night city", Profile.COLOR, null,
                P.CONTRAST to 15f, P.HIGHLIGHTS to -35f, P.BLACKS to -12f, P.VIBRANCE to 15f,
                P.GRADE_HUE[0] to 225f, P.GRADE_SAT[0] to 25f, P.GRADE_HUE[2] to 30f, P.GRADE_SAT[2] to 15f,
                P.MIX_HUE[2] to -12f, P.MIX_SAT[6] to 15f, P.MIX_SAT[7] to 15f, P.CLARITY to 10f, P.DEHAZE to 8f,
            ),
            look(
                "Landscape", Profile.VIVID, null,
                P.HIGHLIGHTS to -35f, P.SHADOWS to 25f, P.WHITES to 10f, P.BLACKS to -12f, P.VIBRANCE to 15f,
                P.MIX_LUM[5] to -15f, P.MIX_SAT[5] to 10f, P.MIX_HUE[3] to 10f, P.CLARITY to 18f, P.TEXTURE to 15f, P.DEHAZE to 15f,
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
            look(
                "B&W film", Profile.MONOCHROME, Curve(listOf(0f to 0.03f, 0.25f to 0.22f, 0.75f to 0.79f, 1f to 0.98f)),
                P.CONTRAST to 15f, P.MIX_LUM[0] to 10f, P.MIX_LUM[1] to 15f, P.MIX_LUM[5] to -20f,
                P.GRAIN to 30f, P.GRAIN_SIZE to 35f, P.GRAIN_ROUGHNESS to 60f, P.VIGNETTE to -10f,
            ),
            look(
                "Sepia", Profile.MONOCHROME, Curve(listOf(0f to 0.05f, 1f to 0.96f)),
                P.CONTRAST to 5f, P.GRADE_HUE[3] to 38f, P.GRADE_SAT[3] to 22f, P.GRAIN to 15f, P.VIGNETTE to -15f,
            ),
        ) + FILM
    }
}
