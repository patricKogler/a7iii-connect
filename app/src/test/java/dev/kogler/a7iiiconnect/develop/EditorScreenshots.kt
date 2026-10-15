package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kogler.a7iiiconnect.develop.engine.P
import dev.kogler.a7iiiconnect.develop.engine.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the editor's panels to PNGs (app/build/screenshots) on the JVM, with a generated
 * landscape photo. Run by the "Screenshots" workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class EditorScreenshots {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editorPanels() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "DSC09412.JPG")
        writeLandscape(file)
        val photos = (1L..6L).map { LibraryPhoto(it, Uri.fromFile(file), "DSC0941$it.JPG", "image/jpeg", 0L) }
        val store = EditStore(context)
        store.save(
            1L,
            Settings().with(
                P.EXPOSURE to 0.25f, P.CONTRAST to 12f, P.HIGHLIGHTS to -45f, P.SHADOWS to 35f,
                P.WHITES to 8f, P.BLACKS to -12f, P.VIBRANCE to 18f, P.CLARITY to 12f, P.DEHAZE to 8f,
                P.VIGNETTE to -18f, P.GRADE_HUE[0] to 210f, P.GRADE_SAT[0] to 15f,
                P.GRADE_HUE[2] to 40f, P.GRADE_SAT[2] to 18f,
            ),
        )

        gpuPreviewEnabled = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorScreen(photos = photos, startIndex = 0, store = store, onBack = {}, onPhotoChanged = {})
            }
        }
        awaitRender()
        shot("01-light")

        compose.onNodeWithText("Curve").performClick(); settle()
        compose.onNodeWithText("RGB").performClick(); settle()
        shot("02-tone-curve")

        tool("COLOR")
        shot("03-color")
        compose.onNodeWithText("Mix").performClick(); settle()
        shot("04-color-mix")
        compose.onNodeWithText("Grading").performClick(); settle()
        shot("05-color-grading")

        tool("EFFECTS"); shot("06-effects")
        tool("DETAIL"); shot("07-detail")
        tool("OPTICS"); shot("08-optics")
        tool("GEOMETRY"); shot("09-geometry")
        tool("PRESETS"); shot("10-presets")
        tool("CROP"); awaitRender(); shot("11-crop")

        // Closing the panel brings the filmstrip back.
        tool("CROP"); awaitRender(); shot("12-no-panel")
        tool("LIGHT")
        compose.onNodeWithTag("compare").performClick()
        awaitRender(); shot("13-split")
    }

    private fun tool(name: String) {
        compose.onNodeWithTag("tool-$name").performClick()
        settle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun step() {
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    private fun settle() = repeat(8) { step() }

    /** Waits until a new preview render lands and nothing else renders for a moment. */
    private fun awaitRender(timeoutMs: Long = 120_000) {
        val start = System.currentTimeMillis()
        val before = renderCount
        var last = before
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            step()
            val n = renderCount
            if (n != last) { last = n; stableSince = System.currentTimeMillis() }
            if (n != before && System.currentTimeMillis() - stableSince > 1500) break
        }
        settle()
    }

    private fun writeLandscape(file: File) {
        val bmp = TestScenes.landscape(3000, 2000)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
    }
}
