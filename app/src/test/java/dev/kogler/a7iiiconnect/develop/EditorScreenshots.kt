package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlin.math.sin

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
    }

    private fun tool(name: String) {
        // Scrolling the tool strip animates, which never finishes while the clock is paused.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("tool-$name").performScrollTo()
        compose.mainClock.autoAdvance = false
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

    /** A 3:2 lake-and-mountains scene at sunset, so the panels have something to show. */
    private fun writeLandscape(file: File) {
        val w = 3000; val h = 2000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * 0.58f

        p.shader = LinearGradient(0f, 0f, 0f, horizon, intArrayOf(0xFF1B3A6B.toInt(), 0xFF6C7FB0.toInt(), 0xFFF2A65A.toInt(), 0xFFFFD39A.toInt()), floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        p.shader = RadialGradient(w * 0.68f, horizon - 120f, 520f, intArrayOf(0xFFFFF6D8.toInt(), 0x66FFC070, 0x00FFC070), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon, p)
        p.shader = null

        fun ridge(base: Float, amp: Float, freq: Float, phase: Float, color: Int) {
            val path = Path()
            path.moveTo(0f, horizon)
            var x = 0f
            while (x <= w) {
                val y = base - amp * (0.6f * sin(x / w * freq + phase) + 0.3f * sin(x / w * freq * 2.7f + phase * 2f) + 0.1f * sin(x / w * freq * 7.1f))
                path.lineTo(x, y)
                x += 10f
            }
            path.lineTo(w.toFloat(), horizon)
            path.close()
            p.color = color
            c.drawPath(path, p)
        }
        ridge(horizon - 330f, 260f, 5f, 0.3f, 0xFF5B5F86.toInt())
        ridge(horizon - 170f, 200f, 7f, 1.7f, 0xFF3C4466.toInt())
        ridge(horizon - 40f, 110f, 11f, 2.9f, 0xFF223322.toInt())

        // Lake: the sky mirrored and darkened.
        p.shader = LinearGradient(0f, horizon, 0f, h * 0.86f, intArrayOf(0xFFE89A5A.toInt(), 0xFF4A5A80.toInt(), 0xFF14233F.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, horizon, w.toFloat(), h * 0.86f, p)
        p.shader = null
        p.color = 0x55FFE0B0
        for (i in 0 until 14) {
            val y = horizon + 20f + i * 28f
            c.drawRect(w * 0.68f - 160f + i * 6f, y, w * 0.68f + 160f - i * 6f, y + 6f, p)
        }

        // Foreground shore with a few dark trees.
        p.color = 0xFF0F1A0E.toInt()
        val shore = Path()
        shore.moveTo(0f, h * 0.84f)
        shore.cubicTo(w * 0.3f, h * 0.80f, w * 0.6f, h * 0.90f, w.toFloat(), h * 0.83f)
        shore.lineTo(w.toFloat(), h.toFloat()); shore.lineTo(0f, h.toFloat()); shore.close()
        c.drawPath(shore, p)
        for ((tx, th) in listOf(220f to 520f, 330f to 420f, 2650f to 600f, 2780f to 470f)) {
            val tree = Path()
            tree.moveTo(tx, h * 0.84f - th); tree.lineTo(tx - th * 0.22f, h * 0.86f); tree.lineTo(tx + th * 0.22f, h * 0.86f); tree.close()
            c.drawPath(tree, p)
        }

        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
    }
}
