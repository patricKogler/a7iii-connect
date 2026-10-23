package dev.kogler.a7iiiconnect.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kogler.a7iiiconnect.develop.engine.CameraColor
import dev.kogler.a7iiiconnect.develop.engine.Curve
import dev.kogler.a7iiiconnect.develop.engine.DevelopSource
import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.LocalP
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.Mask
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The develop shader through [renderBands] (drawn by Skia's software backend):
 *
 * - Tiles: the photo rendered in small bands and tiles matches it rendered in one piece, so
 *   exports (which are tiled) show no seams.
 * - Golden images: each case matches its picture in src/test/resources/golden, so a look
 *   doesn't change by accident (and a broken render, such as an all-black one, fails). Every
 *   render is also written to build/screenshots/golden: after a deliberate change, or for a
 *   new case, look at those and check them in.
 *
 * Writes the numbers to build/screenshots/render.txt and, per case, golden | now | difference x8.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {

    private class Diff(val mean: Double, val p99: Int, val max: Int)

    @Test
    fun rendersMatchTilesAndGoldens() {
        // Small, and tiles checked only where neighbours matter: Skia's software backend runs
        // the shader slowly, and CI has a time limit.
        val w = 600; val h = 400
        val tileCases = setOf("raw-local", "raw-effects", "raw-grain", "raw-noise", "mask-radial")
        val bmp = TestScenes.landscape(w, h)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val jpegImg = LinearImage.fromArgb(px, w, h)
        // A stand-in raw: the same scene as darker "camera" values with a warm as-shot balance.
        val rawImg = LinearImage.fromArgb(px, w, h)
        for (i in rawImg.r.indices) { rawImg.r[i] *= 0.45f; rawImg.g[i] *= 0.5f; rawImg.b[i] *= 0.4f }
        // Some sensor noise, for noise reduction to work on.
        var seed = 12345
        fun noise(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xffff) / 65536f - 0.5f }
        val noisyImg = LinearImage(w, h)
        for (i in rawImg.r.indices) {
            noisyImg.r[i] = maxOf(0f, rawImg.r[i] * (1 + 0.25f * noise()))
            noisyImg.g[i] = maxOf(0f, rawImg.g[i] * (1 + 0.15f * noise()))
            noisyImg.b[i] = maxOf(0f, rawImg.b[i] * (1 + 0.3f * noise()))
        }
        val camera = CameraColor.forCamera("ILCE-7M3")
        val raw = DevelopSource(rawImg, camera, floatArrayOf(2.1f, 1f, 1.55f), true)
        val noisy = DevelopSource(noisyImg, camera, floatArrayOf(2.1f, 1f, 1.55f), true)
        val jpeg = DevelopSource(jpegImg, CameraColor.SRGB, floatArrayOf(1f, 1f, 1f), false)
        // A stand-in subject: the trees on the left shore.
        raw.maps.subject = MaskImage(90, 60, FloatArray(90 * 60) { i ->
            val x = i % 90; val y = i / 90
            if (x in 4..14 && y in 32..52) 1f else 0f
        })
        // A stand-in sky: the top third, fading out towards the horizon.
        raw.maps.sky = MaskImage(90, 60, FloatArray(90 * 60) { i -> ((24 - i / 90) / 8f).coerceIn(0f, 1f) })

        val cases = listOf(
            "raw-default" to (raw to Settings()),
            "raw-light" to (raw to Settings().with(
                P.EXPOSURE to 0.6f, P.CONTRAST to 30f, P.HIGHLIGHTS to -60f, P.SHADOWS to 50f, P.WHITES to 15f, P.BLACKS to -10f,
            )),
            "raw-local" to (raw to Settings().with(P.CLARITY to 50f, P.TEXTURE to 40f, P.DEHAZE to 30f)),
            "raw-color" to (raw to Settings().with(
                P.TEMPERATURE to 4300f, P.TINT to 12f, P.VIBRANCE to 40f, P.SATURATION to -20f,
                P.MIX_HUE[1] to 30f, P.MIX_SAT[5] to -60f, P.MIX_LUM[5] to 40f,
                P.GRADE_HUE[0] to 210f, P.GRADE_SAT[0] to 40f, P.GRADE_HUE[2] to 40f, P.GRADE_SAT[2] to 35f, P.GRADE_LUM[1] to 20f,
            )),
            "raw-mono" to (raw to Settings(profile = Profile.MONOCHROME).with(P.MIX_LUM[5] to -50f, P.MIX_LUM[1] to 40f, P.CONTRAST to 20f)),
            "raw-curves" to (raw to Settings().withCurve(0, Curve(listOf(0f to 0.05f, 0.3f to 0.22f, 0.7f to 0.8f, 1f to 1f)))
                .withCurve(1, Curve(listOf(0f to 0f, 0.5f to 0.56f, 1f to 1f)))),
            "raw-effects" to (raw to Settings().with(P.VIGNETTE to -50f, P.VIGNETTE_ROUNDNESS to 30f, P.SHARPEN to 80f, P.SHARPEN_MASKING to 30f)),
            "raw-grain" to (raw to Settings().with(P.GRAIN to 50f)),
            "raw-noise" to (noisy to Settings().with(P.NOISE to 60f, P.COLOR_NOISE to 50f, P.DEFRINGE to 50f, P.SHARPEN to 40f)),
            "jpeg-mixed" to (jpeg to Settings().with(P.EXPOSURE to -0.3f, P.SHADOWS to 40f, P.VIBRANCE to 25f, P.VIGNETTE to 30f)),
            "mask-linear" to (raw to Settings().copy(masks = listOf(
                Mask.linear().with(LocalP.EXPOSURE, -1f).with(LocalP.TEMP, -30f).with(LocalP.DEHAZE, 40f).with(LocalP.CONTRAST, 25f),
            ))),
            "mask-radial" to (raw to Settings().with(P.CLARITY to 10f).copy(masks = listOf(
                Mask.radial(1.5f).copy(x0 = 0.68f, y0 = 0.5f, angle = 25f).with(LocalP.EXPOSURE, 0.6f).with(LocalP.SATURATION, 40f).with(LocalP.TINT, 20f),
                Mask.radial(1.5f).copy(invert = true, feather = 80f).with(LocalP.EXPOSURE, -0.8f).with(LocalP.CLARITY, 30f),
            ))),
            "mask-subject" to (raw to Settings().copy(masks = listOf(
                Mask.subject().with(LocalP.SHADOWS, 70f).with(LocalP.EXPOSURE, 1f),
                Mask.linear().copy(excludeSubject = true).with(LocalP.HIGHLIGHTS, -60f),
            ))),
            "mask-sky" to (raw to Settings().copy(masks = listOf(
                Mask.sky().with(LocalP.EXPOSURE, -0.7f).with(LocalP.TEMP, -25f).with(LocalP.SATURATION, 30f),
                Mask.subject().with(LocalP.EXPOSURE, 0.5f),
            ))),
        )

        val outDir = File("build/screenshots").apply { mkdirs() }
        val recorded = File(outDir, "golden").apply { mkdirs() }
        val goldens = File("src/test/resources/golden")
        val report = StringBuilder("case\ttiles mean/p99/max\tgolden mean/p99/max\n")
        val failures = ArrayList<String>()
        for ((name, pair) in cases) {
            val (src, s) = pair
            val geom = Geometry(s, w.toDouble(), h.toDouble(), null)
            val look = Look(src, s)
            val guide = look.guide(src.image, geom)
            val sampler = ImageSampler(src.image, geom)
            val sw = sampler.width; val sh = sampler.height
            fun render(band: Int, tile: Int): IntArray {
                val out = IntArray(sw * sh)
                renderBands(look, sampler, guide, band, tile) { y, rows, part -> System.arraycopy(part, 0, out, y * sw, rows * sw) }
                return out
            }
            val whole = render(sh, sw)
            val t = if (name in tileCases) diff(whole, render(64, 256)) else null
            if (t != null && (t.max > 2 || t.mean > 0.05)) failures += "$name: tiles differ (mean ${"%.3f".format(t.mean)}, max ${t.max})"

            val goldenFile = File(goldens, "$name.png")
            val now = Bitmap.createBitmap(whole, sw, sh, Bitmap.Config.ARGB_8888)
            var line = "-"
            if (goldenFile.exists()) {
                val g = BitmapFactory.decodeFile(goldenFile.path)
                if (g.width != sw || g.height != sh) {
                    failures += "$name: golden is ${g.width}x${g.height}, render is ${sw}x$sh"
                } else {
                    val gp = IntArray(sw * sh)
                    g.getPixels(gp, 0, sw, 0, 0, sw, sh)
                    val d = diff(gp, whole)
                    line = "${"%.2f".format(d.mean)}/${d.p99}/${d.max}"
                    if (d.mean > 0.5 || d.p99 > 4) failures += "$name: differs from its golden (mean ${"%.2f".format(d.mean)}, p99 ${d.p99})"
                    File(outDir, "render-$name.png").outputStream().use { sideBySide(gp, whole, sw, sh).compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } else {
                line = "no golden"
                failures += "$name: no golden; check in build/screenshots/golden/$name.png once it looks right"
            }
            File(recorded, "$name.png").outputStream().use { now.compress(Bitmap.CompressFormat.PNG, 100, it) }
            report.append("$name\t${t?.let { "%.3f/%d/%d".format(it.mean, it.p99, it.max) } ?: "-"}\t$line\n")
        }
        File(outDir, "render.txt").writeText(report.toString())
        println(report)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun diff(a: IntArray, b: IntArray): Diff {
        val d = IntArray(a.size * 3)
        var sum = 0L
        for (i in a.indices) for (c in 0..2) {
            val sh = 16 - 8 * c
            val v = abs(((a[i] shr sh) and 0xff) - ((b[i] shr sh) and 0xff))
            d[i * 3 + c] = v
            sum += v
        }
        d.sort()
        return Diff(sum.toDouble() / d.size, d[(d.size * 0.99).toInt()], d.last())
    }

    private fun sideBySide(a: IntArray, b: IntArray, w: Int, h: Int): Bitmap {
        val side = Bitmap.createBitmap(w * 3, h, Bitmap.Config.ARGB_8888)
        side.setPixels(a, 0, w, 0, 0, w, h)
        side.setPixels(b, 0, w, w, 0, w, h)
        val dp = IntArray(w * h) { i ->
            var v = 0
            for (c in 0..2) {
                val sh = 16 - 8 * c
                val d = minOf(255, abs(((a[i] shr sh) and 0xff) - ((b[i] shr sh) and 0xff)) * 8)
                v = v or (d shl sh)
            }
            v or (0xff shl 24)
        }
        side.setPixels(dp, 0, w, w * 2, 0, w, h)
        return side
    }
}
