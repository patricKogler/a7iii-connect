package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.kogler.a7iiiconnect.develop.engine.Filters
import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.util.concurrent.TimeUnit

/**
 * Finds a photo's main subject for Subject masks, on the device: Google's ML Kit subject
 * segmentation (its model comes with Google Play services and is fetched on first use) marks the
 * subject roughly, then a guided filter snaps the mask to the photo's edges. The result is kept
 * beside the edit, so each photo is only looked at once.
 */
object SubjectFinder {
    /** Long edge of the picture the model sees, and of the stored mask. */
    private const val EDGE = 1024

    /** The subject of [o], from the store or found now (blocking; call off the main thread). */
    fun find(context: Context, store: EditStore, o: OpenedPhoto): MaskImage {
        o.preview.maps.subject?.let { return it }
        val found = store.loadSubject(o.photo) ?: segment(context, o).also { store.saveSubject(o.photo, it) }
        o.preview.maps.subject = found
        return found
    }

    private fun segment(context: Context, o: OpenedPhoto): MaskImage {
        // The upright photo with the default look: what the model expects to see.
        val src = o.preview
        val geom = Geometry(Settings(), src.fullWidth.toDouble(), src.fullHeight.toDouble(), null)
        val sampler = ImageSampler(src.image, geom, EDGE)
        val w = sampler.width; val h = sampler.height
        val px = IntArray(w * h)
        renderBands(Look(src, Settings()), sampler, null, 256) { y, rows, band -> System.arraycopy(band, 0, px, y * w, rows * w) }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)

        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        try {
            // The model is an optional Play services module: ask for it, then wait for it.
            val modules = ModuleInstall.getClient(context)
            fun available() = Tasks.await(modules.areModulesAvailable(segmenter), 10, TimeUnit.SECONDS).areModulesAvailable()
            if (!available()) {
                Tasks.await(modules.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()), 30, TimeUnit.SECONDS)
                var waited = 0
                while (!available()) {
                    if (waited++ > 120) error("the subject model didn't download; check the connection")
                    Thread.sleep(1000)
                }
            }
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)), 2, TimeUnit.MINUTES)
            val buf = result.foregroundConfidenceMask ?: error("no subject mask")
            val rough = FloatArray(w * h)
            buf.rewind()
            buf.get(rough, 0, minOf(rough.size, buf.remaining()))

            // Snap the edges to the photo: luminance guides the mask.
            val lum = FloatArray(w * h) { i ->
                val c = px[i]
                (0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)) / 255f
            }
            val fine = FloatArray(w * h)
            Filters.guidedBy(lum, rough, fine, w, h, maxOf(2, maxOf(w, h) / 256), 0.001f)
            for (i in fine.indices) fine[i] = fine[i].coerceIn(0f, 1f)
            return MaskImage(w, h, fine)
        } finally {
            segmenter.close()
            bmp.recycle()
        }
    }
}
