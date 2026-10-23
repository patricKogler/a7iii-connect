package dev.kogler.a7iiiconnect.develop

import dev.kogler.a7iiiconnect.develop.engine.Geometry
import dev.kogler.a7iiiconnect.develop.engine.ImageSampler
import dev.kogler.a7iiiconnect.develop.engine.Look
import dev.kogler.a7iiiconnect.develop.engine.MaskImage
import dev.kogler.a7iiiconnect.develop.engine.Settings

/**
 * Finds a photo's sky for Sky masks, on the device and without a model: the sky is the smooth
 * area that reaches the top of the picture. It grows from the smooth parts of the top edge through
 * neighbours that are smooth and close in colour (so it follows a gradient from blue to pale
 * near the horizon but stops at a roofline), then takes in patches of the same colour seen through
 * branches, and finally a guided filter snaps the edges to the photo. Kept beside the edit.
 */
object SkyFinder {
    private const val EDGE = 1024

    /** The sky of [o], from the store or found now (blocking; call off the main thread). */
    fun find(store: EditStore, o: OpenedPhoto): MaskImage {
        o.preview.maps.sky?.let { return it }
        val found = store.loadSky(o.photo) ?: detect(o).also { store.saveSky(o.photo, it) }
        o.preview.maps.sky = found
        return found
    }

    private fun detect(o: OpenedPhoto): MaskImage {
        // The upright photo with the default look, the same picture Subject masks look at.
        val src = o.preview
        val geom = Geometry(Settings(), src.fullWidth.toDouble(), src.fullHeight.toDouble(), null)
        val sampler = ImageSampler(src.image, geom, EDGE)
        val w = sampler.width; val h = sampler.height
        val px = IntArray(w * h)
        renderBands(Look(src, Settings()), sampler, null, 256) { y, rows, band -> System.arraycopy(band, 0, px, y * w, rows * w) }
        return SkyDetect.find(px, w, h) ?: error("there's no sky along the top of this photo")
    }
}
