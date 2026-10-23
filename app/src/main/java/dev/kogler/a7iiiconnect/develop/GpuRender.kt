package dev.kogler.a7iiiconnect.develop

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.kogler.a7iiiconnect.develop.engine.Guide
import dev.kogler.a7iiiconnect.develop.engine.LinearImage
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.Sampler

/**
 * Renders [sampler] through [look] with the preview's shader, offscreen; [sink] receives each
 * finished band of ARGB rows (at most [band] rows of sampler.width). Every render that isn't the
 * live preview goes through here: exports, sharp zoom, the histogram, the pictures the subject
 * and sky finders look at.
 *
 * A big render never holds the whole photo as a float texture: each band of rows is filled,
 * prepared ([Look.prepare]) and drawn in tiles at most [tile] pixels across. Bands and tiles
 * carry [Look.margin] pixels of the scene around them, so they meet without seams.
 */
fun renderBands(
    look: Look, sampler: Sampler, guide: Guide?, band: Int = 256, tile: Int = TILE,
    sink: (y: Int, rows: Int, px: IntArray) -> Unit,
) {
    val started = SystemClock.elapsedRealtime()
    val w = sampler.width; val h = sampler.height
    val scale = sampler.scale
    val params = look.shaderParams(scale)
    val pad = look.margin(scale)
    val bandRows = minOf(band, h)
    val tileW = minOf(w, tile)
    val guideBitmap = guide?.let { GpuPreview.guideBitmap(it) }
    val maps = if (params.maskCount > 0 && (params.subject != null || params.sky != null)) GpuPreview.mapsBitmap(params.subject, params.sky) else null
    val px = IntArray(w * bandRows)
    val gpu = Shaders.take()
    try {
        Offscreen(tileW, bandRows).use { off ->
            var y = 0
            while (y < h) {
                val rows = minOf(bandRows, h - y)
                val img = LinearImage(w, rows + 2 * pad)
                sampler.fill(y - pad, rows + 2 * pad, img)
                look.prepare(img, scale)
                var x = 0
                while (x < w) {
                    val cols = minOf(tileW, w - x)
                    // Past the photo's left and right edges the shader clamps, as in the preview.
                    val left = minOf(pad, x)
                    val right = minOf(pad, w - x - cols)
                    val ox = sampler.originX + x; val oy = sampler.originY + y
                    val scene = GpuPreview.sceneBitmap(img, x - left, cols + left + right)
                    val coords = if (params.maskCount > 0) GpuPreview.coordsBitmap(sampler.geometry, scale, cols, rows, ox, oy) else null
                    val place = GpuPreview.Placement(cols, rows, ox, oy, sampler.frameWidth, sampler.frameHeight, left, pad)
                    gpu.set(GpuPreview.prepare(scene, guide, guideBitmap, params, coords, maps, place))
                    off.draw(gpu, cols, rows, px, w, x)
                    scene.recycle()
                    coords?.recycle()
                    x += cols
                }
                sink(y, rows, px)
                y += rows
            }
        }
    } finally {
        Shaders.give(gpu)
    }
    if (w.toLong() * h > 4_000_000) Log.i("GpuRender", "${w}x$h in ${SystemClock.elapsedRealtime() - started} ms")
}

/** Widest tile drawn at once; every phone GPU takes textures this size with the margin. */
private const val TILE = 2048

/** Compiled shaders to reuse (compiling one takes a moment); each render takes its own. */
private object Shaders {
    private val idle = ArrayList<GpuPreview>()

    fun take(): GpuPreview = synchronized(idle) { idle.removeLastOrNull() } ?: GpuPreview()

    fun give(g: GpuPreview) = synchronized(idle) { if (idle.size < 2) idle += g }
}

/** A [width] x [height] offscreen surface that draws a [GpuPreview] and reads the pixels back. */
private class Offscreen(val width: Int, val height: Int) : AutoCloseable {
    private val reader = ImageReader.newInstance(
        width, height, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    // Not `apply { setPosition(0, 0, width, height) }`: inside it those names are the node's own
    // (still 0 x 0) size, and a 0 x 0 node draws nothing.
    private val node = RenderNode("develop").also { it.setPosition(0, 0, this.width, this.height) }
    private val renderer = HardwareRenderer().apply {
        setSurface(reader.surface)
        setContentRoot(node)
    }
    private val row = ByteArray(width * 4)

    /** Draws [gpu]'s frame ([w] x [h]) and copies it into [out] at column [x0] (row stride [stride]). */
    fun draw(gpu: GpuPreview, w: Int, h: Int, out: IntArray, stride: Int, x0: Int) {
        val c = node.beginRecording()
        gpu.draw(c, 0f, 0f, w.toFloat(), h.toFloat())
        node.endRecording()
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage() ?: error("the GPU render produced no image")
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            // Phones give RGBA in memory; Robolectric's offscreen surface comes back as BGRA.
            val ri = if (BGRA) 2 else 0
            val bi = if (BGRA) 0 else 2
            for (y in 0 until h) {
                buf.position(y * plane.rowStride)
                buf.get(row, 0, (w - 1) * ps + 4)
                val o = y * stride + x0
                for (x in 0 until w) {
                    val i = x * ps
                    out[o + x] = (0xff shl 24) or ((row[i + ri].toInt() and 0xff) shl 16) or
                        ((row[i + 1].toInt() and 0xff) shl 8) or (row[i + bi].toInt() and 0xff)
                }
            }
        } finally {
            image.close()
        }
    }

    override fun close() {
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
    }

    private companion object {
        val BGRA = Build.FINGERPRINT == "robolectric"
    }
}
