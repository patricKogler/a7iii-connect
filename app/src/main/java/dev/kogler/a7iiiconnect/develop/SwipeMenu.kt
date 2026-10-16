package dev.kogler.a7iiiconnect.develop

import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Param
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Swipe mode's menu: every slider the panels show, as a tree mirroring them (tool, then section
 * such as Mix or Grading, then color band or zone). Up/down on the photo moves through a
 * folder's rows, sliding right opens a folder, sliding left goes back up.
 */
internal sealed class SwipeNode(val id: String, val label: String)

internal class SwipeFolder(id: String, label: String, val children: List<SwipeNode>) : SwipeNode(id, label)

/** One slider. [format] null means the editor formats it as [param]. */
internal class SwipeLeaf(
    id: String,
    label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val get: (Settings) -> Float,
    val set: (Settings, Float) -> Settings,
    val param: Param? = null,
    val format: ((Float) -> String)? = null,
) : SwipeNode(id, label)

/**
 * A color wheel row: a swipe that starts sideways turns into a free 2D drag, the dot following
 * the finger (hue around, saturation outwards).
 */
internal class SwipeWheel(id: String, label: String, val hue: Param, val sat: Param) : SwipeNode(id, label) {
    /** The dot's position, -1..1 on each axis (y up). */
    fun xy(s: Settings): Pair<Float, Float> {
        val a = Math.toRadians(s[hue].toDouble()); val r = s[sat] / sat.max
        return (cos(a) * r).toFloat() to (sin(a) * r).toFloat()
    }

    /** The dot moved to ([x], [y]); it snaps to the middle near it, and keeps its hue there. */
    fun moveTo(s: Settings, x: Float, y: Float): Settings {
        val r = hypot(x, y).coerceAtMost(1f)
        if (r < SNAP) return s.with(sat, 0f)
        val h = ((Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat() % 360f) + 360f) % 360f
        return s.with(hue to h, sat to r * sat.max)
    }

    companion object { const val SNAP = 0.03f }
}

private fun leaf(p: Param, label: String = p.label) =
    SwipeLeaf(p.key, label, p.min, p.max, p.default, { it[p] }, { s, v -> s.with(p, v) }, p)

private fun folder(id: String, label: String, vararg children: SwipeNode?) = SwipeFolder(id, label, children.filterNotNull())

/** Temperature moves in mireds, like the panel's slider: 0..1 from 2000 K to 25000 K. */
internal fun kelvinToPos(k: Float) = ((500f - 1e6f / k) / 460f).coerceIn(0f, 1f)
internal fun posToKelvin(pos: Float) = 1e6f / (500f - pos * 460f)

/**
 * The menu for [s]. Sub-sliders (vignette midpoint and so on) only show when their panel shows
 * them. [asShot] is the photo's as-shot white balance (kelvin, tint).
 */
internal fun swipeTree(s: Settings, asShot: Pair<Float, Float>, isRaw: Boolean, hasLensProfile: Boolean): SwipeFolder {
    val (k0, t0) = asShot
    val temp = SwipeLeaf(
        P.TEMPERATURE.key, "Temp", 0f, 1f, kelvinToPos(k0),
        get = { kelvinToPos(it.temperature ?: k0) },
        set = { st, v -> st.withWhiteBalance(posToKelvin(v), st.tint ?: t0) },
        format = { "${(posToKelvin(it) / 50f).toInt() * 50}" },
    )
    val tint = SwipeLeaf(
        P.TINT.key, "Tint", -150f, 150f, t0,
        get = { it.tint ?: t0 },
        set = { st, v -> st.withWhiteBalance(st.temperature ?: k0, v) },
        format = { (if (it > 0) "+" else "") + it.toInt() },
    )
    fun sub(on: Boolean, vararg ps: Pair<Param, String>) = if (on) ps.map { (p, l) -> leaf(p, l) } else emptyList()
    val mono = s.profile == Profile.MONOCHROME
    val bandName = { i: Int -> P.MIX_BANDS[i].first.replaceFirstChar { it.uppercase() } }
    val mix = if (mono) {
        SwipeFolder("mix", "B&W mix", P.MIX_BANDS.indices.map { leaf(P.MIX_LUM[it], bandName(it)) })
    } else {
        SwipeFolder("mix", "Mix", P.MIX_BANDS.indices.map { i ->
            folder(P.MIX_BANDS[i].first, bandName(i), leaf(P.MIX_HUE[i], "Hue"), leaf(P.MIX_SAT[i], "Saturation"), leaf(P.MIX_LUM[i], "Luminance"))
        })
    }
    val grading = SwipeFolder(
        "grading", "Grading",
        P.GRADE_ZONES.indices.flatMap { z ->
            val name = P.GRADE_ZONES[z].replaceFirstChar { it.uppercase() }
            listOf(SwipeWheel(P.GRADE_ZONES[z], name, P.GRADE_HUE[z], P.GRADE_SAT[z]), leaf(P.GRADE_LUM[z], "$name luminance"))
        } + listOf(leaf(P.GRADE_BLENDING), leaf(P.GRADE_BALANCE)),
    )
    val lensOn = isRaw && hasLensProfile && s.on(P.LENS_PROFILE)
    return SwipeFolder(
        "", "Tools",
        listOf(
            SwipeFolder(
                "light", "Light",
                listOf(P.EXPOSURE, P.CONTRAST, P.HIGHLIGHTS, P.SHADOWS, P.WHITES, P.BLACKS).map { leaf(it) } +
                    folder("curve", "Tone curve", leaf(P.CURVE_HIGHLIGHTS), leaf(P.CURVE_LIGHTS), leaf(P.CURVE_DARKS), leaf(P.CURVE_SHADOWS)),
            ),
            folder("color", "Color", temp, tint, leaf(P.VIBRANCE), leaf(P.SATURATION), mix, grading),
            SwipeFolder(
                "effects", "Effects",
                listOf(leaf(P.TEXTURE), leaf(P.CLARITY), leaf(P.DEHAZE), leaf(P.VIGNETTE)) +
                    sub(s[P.VIGNETTE] != 0f, P.VIGNETTE_MIDPOINT to "Vignette midpoint", P.VIGNETTE_FEATHER to "Vignette feather", P.VIGNETTE_ROUNDNESS to "Vignette roundness", P.VIGNETTE_HIGHLIGHTS to "Vignette highlights") +
                    leaf(P.GRAIN) + sub(s[P.GRAIN] != 0f, P.GRAIN_SIZE to "Grain size", P.GRAIN_ROUGHNESS to "Grain roughness"),
            ),
            SwipeFolder(
                "detail", "Detail",
                listOf(leaf(P.SHARPEN)) +
                    sub(s[P.SHARPEN] != 0f, P.SHARPEN_RADIUS to "Sharpen radius", P.SHARPEN_DETAIL to "Sharpen detail", P.SHARPEN_MASKING to "Sharpen masking") +
                    leaf(P.NOISE) + sub(s[P.NOISE] != 0f, P.NOISE_DETAIL to "Noise detail") + leaf(P.COLOR_NOISE, "Color noise"),
            ),
            SwipeFolder(
                "optics", "Optics",
                sub(lensOn, P.LENS_DISTORTION to "Lens distortion", P.LENS_VIGNETTING to "Lens vignetting") +
                    listOf(leaf(P.DEFRINGE), leaf(P.MANUAL_DISTORTION, "Distortion"), leaf(P.MANUAL_VIGNETTING, "Vignetting")) +
                    sub(s[P.MANUAL_VIGNETTING] != 0f, P.MANUAL_VIGNETTING_MIDPOINT to "Vignetting midpoint"),
            ),
            SwipeFolder("geometry", "Geometry", listOf(P.VERTICAL, P.HORIZONTAL, P.ROTATE, P.SCALE, P.OFFSET_X, P.OFFSET_Y).map { leaf(it) }),
        ),
    )
}

/** Where swipe mode is in the menu: the path of ids from the root to the highlighted row. */
internal class SwipeNav {
    /** The child last highlighted in each folder (keyed by the folder's path), so going back in returns there. */
    private val last = HashMap<String, String>()
    /** The last plain slider (not sub-folder) highlighted in each folder, for the panel's "Basic" and "Sliders" chips. */
    private val lastLeaf = HashMap<String, String>()

    private fun key(p: List<String>) = p.joinToString("/")

    fun remember(path: List<String>, root: SwipeFolder) {
        var f: SwipeFolder = root
        for (i in path.indices) {
            last[key(path.take(i))] = path[i]
            val n = f.children.firstOrNull { it.id == path[i] } ?: break
            if (n !is SwipeFolder) lastLeaf[key(path.take(i))] = n.id
            f = n as? SwipeFolder ?: break
        }
    }

    /** The folder at [folderPath] and the rows' highlighted index; ids that no longer exist fall back. */
    fun folderAt(root: SwipeFolder, folderPath: List<String>): SwipeFolder {
        var f = root
        for (id in folderPath) f = f.children.firstOrNull { it.id == id } as? SwipeFolder ?: return f
        return f
    }

    /** [path] made valid for [root]: missing ids become the remembered or first row. */
    fun resolve(root: SwipeFolder, path: List<String>): List<String> {
        val out = ArrayList<String>()
        var f = root
        for (id in path) {
            val n = f.children.firstOrNull { it.id == id } ?: f.children.firstOrNull { it.id == last[key(out)] } ?: f.children.first()
            out += n.id
            f = n as? SwipeFolder ?: return out
        }
        if (out.isEmpty()) out += f.children.first().id
        return out
    }

    /** [path] extended down remembered (or first) rows until it ends on a slider. */
    fun descend(root: SwipeFolder, path: List<String>): List<String> {
        val out = resolve(root, path).toMutableList()
        var n: SwipeNode = folderAt(root, out.dropLast(1)).children.first { it.id == out.last() }
        while (n is SwipeFolder) {
            val next = n.children.firstOrNull { it.id == last[key(out)] } ?: n.children.first()
            out += next.id
            n = next
        }
        return out
    }

    /** The path to the last plain slider shown directly in [folderPath] (the folder's own sliders, not a sub-folder's). */
    fun ownLeaf(root: SwipeFolder, folderPath: List<String>): List<String> {
        val f = folderAt(root, folderPath)
        val id = lastLeaf[key(folderPath)]?.takeIf { l -> f.children.any { it.id == l } }
            ?: f.children.first { it !is SwipeFolder }.id
        return folderPath + id
    }

    /** Into the folder [path] ends on: its remembered (or first) row. */
    fun into(root: SwipeFolder, path: List<String>): List<String> = resolve(root, path + "")

    fun node(root: SwipeFolder, path: List<String>): SwipeNode? =
        folderAt(root, path.dropLast(1)).children.firstOrNull { it.id == path.lastOrNull() }
}
