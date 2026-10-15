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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kogler.a7iiiconnect.Phase
import dev.kogler.a7iiiconnect.SyncStatus
import dev.kogler.a7iiiconnect.develop.engine.LocalP
import dev.kogler.a7iiiconnect.develop.engine.Mask
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
        openEditor()
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

        compose.onNodeWithTag("compare").performClick()
        compose.onNodeWithTag("fullscreen").performClick()
        awaitRender(); shot("15-fullscreen")
        // A tap on the photo brings the controls back (after the double-tap timeout).
        compose.onRoot().performTouchInput { click(center) }
        settle(); settle()
        shot("16-fullscreen-controls")
    }

    /** The Masks tool: a linear gradient over the sky, then a radial one. */
    @Test
    fun masks() {
        openEditor(
            Settings().with(P.EXPOSURE to 0.2f, P.SHADOWS to 30f).copy(masks = listOf(
                Mask.linear().copy(y0 = 0.05f, y1 = 0.45f).with(LocalP.EXPOSURE, -0.9f).with(LocalP.TEMP, -25f).with(LocalP.DEHAZE, 20f),
                Mask.radial(1.5f).copy(x0 = 0.68f, y0 = 0.47f, x1 = 0.12f, y1 = 0.16f, angle = 10f).with(LocalP.EXPOSURE, 0.5f).with(LocalP.TINT, 15f),
            )),
        )
        tool("MASKS"); awaitRender()
        shot("17-masks-linear")
        compose.onNodeWithText("2 · Radial").performClick(); settle()
        shot("18-masks-radial")
        compose.onNodeWithTag("add-mask-linear").performClick(); awaitRender()
        shot("19-masks-new")
    }

    /** The phone turned sideways: the panel sits beside the photo. */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun landscape() {
        openEditor()
        shot("14-landscape")
    }

    private fun openEditor(edit: Settings = Settings().with(
        P.EXPOSURE to 0.25f, P.CONTRAST to 12f, P.HIGHLIGHTS to -45f, P.SHADOWS to 35f,
        P.WHITES to 8f, P.BLACKS to -12f, P.VIBRANCE to 18f, P.CLARITY to 12f, P.DEHAZE to 8f,
        P.VIGNETTE to -18f, P.GRADE_HUE[0] to 210f, P.GRADE_SAT[0] to 15f,
        P.GRADE_HUE[2] to 40f, P.GRADE_SAT[2] to 18f,
    )) {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "DSC09412.JPG")
        writeLandscape(file)
        val photos = (1L..6L).map { LibraryPhoto(it, Uri.fromFile(file), "DSC0941$it.JPG", "image/jpeg", 0L) }
        val store = EditStore(context)
        store.save(photos[0], edit)

        gpuPreviewEnabled = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorScreen(photos = photos, startIndex = 0, store = store, onBack = {}, onPhotoChanged = {})
            }
        }
        awaitRender()
    }

    @Test
    fun libraryAndCulling() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "DSC09412.JPG")
        writeLandscape(file)
        val day = 1_760_000_000_000L
        // Twelve shots five minutes apart over two days; the even ones come as RAW+JPEG pairs (the
        // RAW is a stand-in). One extra frame a second after DSC09515 makes a burst.
        val photos = (0 until 12).flatMap { i ->
            val t = day - (if (i < 7) 0L else 86_400_000L) - i * 300_000L
            val jpeg = LibraryPhoto(100L + i, Uri.fromFile(file), "DSC095${10 + i}.JPG", "image/jpeg", t)
            if (i % 2 == 0) listOf(jpeg.copy(id = 200L + i, name = jpeg.stem + ".ARW", mime = "image/x-sony-arw"), jpeg) else listOf(jpeg)
        } + LibraryPhoto(300L, Uri.fromFile(file), "DSC09530.JPG", "image/jpeg", day - 5 * 300_000L + 1_000L)
        val shots = Library.shots(photos)
        check(shots.size == 13) { "pairing made ${shots.size} shots" }
        val marks = mapOf(shots[1].key to Mark(2), shots[3].key to Mark.REJECTED, shots[5].key to Mark(1))

        var screen by mutableStateOf("library")
        var transfer by mutableStateOf(SyncStatus())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (screen == "library") {
                    LibraryScreen(
                        shots = shots, edited = setOf(shots[0].key), marks = marks,
                        exported = setOf(Photos.exportName(shots[0].primary).lowercase()),
                        onOpen = {}, onCull = { _, _ -> }, onTogether = { _, _ -> }, onDeleteRejected = {},
                        transfer = transfer, onCamera = {},
                    )
                } else {
                    // JPEG-only shots, so the cards load without a real ARW.
                    CullScreen(
                        shots = shots.filter { it.raw == null }, round = 1, marks = emptyMap(), onMark = { _, _ -> },
                        rejectedCount = 1, onDeleteRejected = {}, onBack = {},
                    )
                }
            }
        }
        repeat(20) { step() }
        shot("20-library")
        transfer = SyncStatus(running = true, phase = Phase.COPYING, done = 7, total = 23)
        repeat(10) { step() }
        shot("20b-library-transfer")
        transfer = SyncStatus()

        screen = "cull"
        repeat(40) { step() }
        shot("21-cull-stack")
        compose.onNodeWithTag("cull-pick").performClick()
        repeat(30) { step() }
        shot("22-cull")
        compose.onNodeWithTag("cull-card").performTouchInput { down(center); moveBy(Offset(width * 0.32f, 20f)) }
        repeat(4) { step() }
        shot("23-cull-pick")
        compose.onNodeWithTag("cull-card").performTouchInput { moveBy(Offset(-width * 0.64f, 0f)) }
        repeat(4) { step() }
        shot("24-cull-reject")
    }

    @Test
    fun libraryFilters() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "DSC09412.JPG")
        writeLandscape(file)
        val day = 1_760_000_000_000L
        val shots = Library.shots((0 until 12).map { i ->
            LibraryPhoto(100L + i, Uri.fromFile(file), "DSC096${10 + i}.JPG", "image/jpeg", day - i * 3_600_000L)
        })
        val lenses = listOf("FE 24-70mm F2.8 GM", "FE 85mm F1.8", "FE 24-70mm F2.8 GM", "FE 35mm F1.8")
        val meta = shots.mapIndexed { i, s ->
            val lens = lenses[i % 4]
            s.key to ShotMeta(
                lens = lens,
                focal = when (lens) { "FE 85mm F1.8" -> 85f; "FE 35mm F1.8" -> 35f; else -> listOf(24f, 35f, 50f, 70f)[i % 4] },
                aperture = listOf(1.8f, 2.8f, 4f, 8f)[i % 4],
                exposure = listOf(1.0 / 4000, 1.0 / 250, 1.0 / 60, 2.0)[i % 4],
                iso = listOf(100, 400, 3200, 6400)[i % 4],
                bias = listOf(0f, -0.7f, 0.3f, 0f)[i % 4],
                program = if (i % 3 == 0) 1 else 3,
                flash = false,
                portrait = i % 5 == 0,
                lat = if (i < 6) 47.56 + i % 2 else null,
                lon = if (i < 6) 13.65 else null,
            )
        }.toMap()
        val places = meta.filterValues { it.located }.mapValues { (_, m) -> if (m.lat!! < 48) "Hallstatt" else "Linz" }
        val facts = shots.map { ShotFacts(it, meta[it.key], place = places[it.key]) }

        // The engine: groups narrow each other, values within a group widen it.
        fun n(f: ShotFilter) = facts.count { f.matches(it) }
        check(n(ShotFilter()) == 12)
        check(n(ShotFilter(lenses = setOf("FE 85mm F1.8"))) == 3)
        check(n(ShotFilter(lenses = setOf("FE 85mm F1.8", "FE 35mm F1.8"))) == 6)
        check(n(ShotFilter(lenses = setOf("FE 85mm F1.8"), presets = setOf(FilterPreset.WIDE_OPEN))) == 0)
        check(n(ShotFilter(presets = setOf(FilterPreset.LONG_EXPOSURE))) == 3)
        check(n(ShotFilter(iso = 400..3200)) == 6)
        check(n(ShotFilter(located = false, framing = setOf(Framing.PORTRAIT))) == 1) { "located" }
        check(n(ShotFilter(places = setOf("Hallstatt"))) == 3) { "places" }
        check(n(ShotFilter(fromDay = dayOf(day - 3 * 3_600_000L), toDay = dayOf(day))) == shots.count { dayOf(it.takenMillis) >= dayOf(day - 3 * 3_600_000L) }) { "dates" }
        val saved = ShotFilter(lenses = setOf("FE 85mm F1.8"), places = setOf("Hallstatt"), fromDay = dayOf(day), toDay = dayOf(day), aperture = 1.8f..2.8f, modes = setOf(ShootingMode.MANUAL), exposure = 0.001..0.5)
        check(SavedFilters.decode(SavedFilters.encode(saved)) == saved) { "saved filter round trip" }
        check(ShotMeta.decode(meta.values.first().encode().split('\t')) == meta.values.first()) { "meta round trip" }

        var sheetFilter by mutableStateOf(ShotFilter())
        var sheetOpen by mutableStateOf(false)
        var savedFilters by mutableStateOf(emptyList<SavedFilter>())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LibraryScreen(
                    shots = shots, edited = emptySet(), marks = emptyMap(), exported = emptySet(),
                    onOpen = {}, onCull = { _, _ -> }, onTogether = { _, _ -> }, onDeleteRejected = {},
                    transfer = SyncStatus(), onCamera = {}, meta = meta, places = places,
                    sheetFilter = sheetFilter, onSheetFilter = { sheetFilter = it },
                    saved = savedFilters, onSaveFilter = { savedFilters = savedFilters + SavedFilter(it, sheetFilter) },
                    sheetOpen = sheetOpen, onSheetOpen = { sheetOpen = it },
                )
            }
        }
        repeat(20) { step() }
        compose.onNodeWithTag("filter").performClick()
        repeat(20) { step() }
        sheetShot("30-filter-sheet")
        compose.onNodeWithText("Low light", substring = true).performClick()
        compose.onNodeWithTag("more-filters").performClick()
        repeat(20) { step() }
        sheetShot("31-filter-more")
        sheetOpen = false
        sheetFilter = ShotFilter(lenses = setOf("FE 24-70mm F2.8 GM"), iso = 400..6400)
        repeat(20) { step() }
        shot("32-library-filtered")
        savedFilters = listOf(SavedFilter("Night 85", ShotFilter(lenses = setOf("FE 85mm F1.8"))))
        sheetFilter = savedFilters[0].filter
        repeat(20) { step() }
        shot("33-library-saved-filter")
    }

    @Test
    fun similarStacks() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "DSC09412.JPG")
        writeLandscape(file)
        val t = 1_760_000_000_000L
        fun at(n: Int, at: Long) = Shot(null, LibraryPhoto(n.toLong(), Uri.fromFile(file), "DSC0$n.JPG", "image/jpeg", at))
        // A burst, a look-alike 30 s later, then the same scene 10 minutes on (too late to stack).
        val shots = listOf(at(1, t), at(2, t + 500), at(3, t + 30_000), at(4, t + 600_000))
        val stacks = Similar.stacks(context, shots)
        check(stacks.map { s -> s.map { it.primary.id } } == listOf(listOf(1L, 2L, 3L), listOf(4L))) { "stacks: $stacks" }
    }

    private fun tool(name: String) {
        compose.onNodeWithTag("tool-$name").performClick()
        settle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** A bottom sheet is its own window, so it's the second root. */
    private fun sheetShot(name: String) {
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/$name.png")
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
