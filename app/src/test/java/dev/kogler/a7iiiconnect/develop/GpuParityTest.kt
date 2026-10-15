package dev.kogler.a7iiiconnect.develop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import dev.kogler.a7iiiconnect.develop.engine.CameraColor
import dev.kogler.a7iiiconnect.develop.engine.Curve
import dev.kogler.a7iiiconnect.develop.engine.DevelopSource
import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Profile
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Renders the same photo and settings with the CPU engine and with the GPU preview shader (drawn
 * through Skia's software backend) and writes how far apart they are to
 * build/screenshots/gpu-parity.txt, plus side-by-side images (CPU | GPU | difference x8).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GpuParityTest {

    @Test
    fun gpuMatchesCpu() {
        val w = 900; val h = 600
        val bmp = TestScenes.landscape(w, h)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val jpegImg = LinearImage.fromArgb(px, w, h)
        // A stand-in raw: the same scene as darker "camera" values with a warm as-shot balance.
        val rawImg = LinearImage.fromArgb(px, w, h)
        for (i in rawImg.r.indices) { rawImg.r[i] *= 0.45f; rawImg.g[i] *= 0.5f; rawImg.b[i] *= 0.4f }
        val raw = DevelopSource(rawImg, CameraColor.forCamera("ILCE-7M3"), floatArrayOf(2.1f, 1f, 1.55f), true)
        val jpeg = DevelopSource(jpegImg, CameraColor.SRGB, floatArrayOf(1f, 1f, 1f), false)

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
            "jpeg-mixed" to (jpeg to Settings().with(P.EXPOSURE to -0.3f, P.SHADOWS to 40f, P.VIBRANCE to 25f, P.VIGNETTE to 30f)),
        )

        val outDir = File("build/screenshots").apply { mkdirs() }
        val report = StringBuilder()
        report.append("case\tmeanAbs\tp99\tmax\n")
        for ((name, pair) in cases) {
            val (src, s) = pair
            try {
                val geom = Geometry(s, w.toDouble(), h.toDouble(), null)
                val look = Look(src, s)
                val guide = look.guide(src.image, geom)
                val sampler = ImageSampler(src.image, geom)
                val sw = sampler.width; val sh = sampler.height

                val cpu = IntArray(sw * sh)
                look.render(sampler, guide, cpu, 0, sh)

                val scene = LinearImage(sw, sh)
                sampler.fill(0, sh, scene)
                val frame = GpuPreview.prepare(GpuPreview.sceneBitmap(scene), guide, guide?.let { GpuPreview.guideBitmap(it) }, look.shaderParams(sampler.scale))
                fun gpuRender(source: String): Bitmap {
                    val gpu = GpuPreview(source)
                    gpu.set(frame)
                    return hardwareDraw(sw, sh) { gpu.draw(it, 0f, 0f, sw.toFloat(), sh.toFloat()) }
                }
                val out = gpuRender(GpuPreview.AGSL)
                val g = IntArray(sw * sh)
                out.getPixels(g, 0, sw, 0, 0, sw, sh)

                val diffs = IntArray(sw * sh * 3)
                var sum = 0L
                for (i in 0 until sw * sh) for (c in 0..2) {
                    val sh2 = 16 - 8 * c
                    val d = abs(((cpu[i] shr sh2) and 0xff) - ((g[i] shr sh2) and 0xff))
                    diffs[i * 3 + c] = d
                    sum += d
                }
                diffs.sort()
                report.append("$name\t${"%.2f".format(sum.toDouble() / diffs.size)}\t${diffs[(diffs.size * 0.99).toInt()]}\t${diffs.last()}\n")

                val side = Bitmap.createBitmap(sw * 3, sh, Bitmap.Config.ARGB_8888)
                side.setPixels(cpu, 0, sw, 0, 0, sw, sh)
                side.setPixels(g, 0, sw, sw, 0, sw, sh)
                val dp = IntArray(sw * sh) { i ->
                    var v = 0
                    for (c in 0..2) {
                        val sh2 = 16 - 8 * c
                        val d = minOf(255, abs(((cpu[i] shr sh2) and 0xff) - ((g[i] shr sh2) and 0xff)) * 8)
                        v = v or (d shl sh2)
                    }
                    v or (0xff shl 24)
                }
                side.setPixels(dp, 0, sw, sw * 2, 0, sw, sh)
                File(outDir, "gpu-$name.png").outputStream().use { side.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Throwable) {
                report.append("$name\tFAILED\t${e.javaClass.simpleName}: ${e.message?.lines()?.take(12)?.joinToString(" | ")}\n")
            }
        }
        File(outDir, "gpu-parity.txt").writeText(report.toString())
        println(report)
    }

    /** Shaders only run on a hardware canvas: record into a RenderNode and render it offscreen. */
    private fun hardwareDraw(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("gpu").apply { setPosition(0, 0, w, h) }
        val c = node.beginRecording()
        draw(c)
        node.endRecording()
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val plane = image.planes[0]
            val buf = plane.buffer
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val o = y * plane.rowStride + x * plane.pixelStride
                // The offscreen surface comes back as BGRA.
                val b = buf.get(o).toInt() and 0xff; val g = buf.get(o + 1).toInt() and 0xff; val r = buf.get(o + 2).toInt() and 0xff
                val a = buf.get(o + 3).toInt() and 0xff
                px[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            image.close()
            return bmp
        } finally {
            renderer.destroy(); reader.close()
        }
    }
}
