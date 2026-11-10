package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kogler.a7iiiconnect.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class NotesTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = 1_760_000_000_000L

    /** A Pixel shot: RAW (DNG) and JPEG, or just the JPEG. */
    private fun pixel(i: Int, t: Long, uri: Uri = Uri.EMPTY, raw: Boolean = true): List<LibraryPhoto> {
        val jpeg = LibraryPhoto(100L + i, uri, "PXL_20261109_0${i}.RAW-01.COVER.jpg", "image/jpeg", t, Source.PHONE)
        return if (raw) listOf(LibraryPhoto(200L + i, uri, "PXL_20261109_0${i}.RAW-02.ORIGINAL.dng", "image/x-adobe-dng", t, Source.PHONE), jpeg) else listOf(jpeg)
    }

    @Test
    fun aNoteStaysANoteOnceItsRawIsGone() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = NoteStore(context)
        val shot = Library.shots(pixel(1, day)).single()
        val notes = store.set(listOf(shot), true, now = day)
        assertTrue(Notes.isNote(shot, notes))
        assertEquals(listOf(shot.raw), Notes.spareRaws(listOf(shot), notes))

        // The RAW goes: the JPEG alone is the shot now, and still a note, with nothing left to trash.
        val after = store.forget(listOf(shot.raw!!.key))
        val jpegOnly = Library.shots(pixel(1, day, raw = false)).single()
        assertTrue(Notes.isNote(jpegOnly, after))
        assertTrue(Notes.spareRaws(listOf(jpegOnly), after).isEmpty())
        assertEquals(after, NoteStore(context).load())

        // And back to being a photo.
        assertFalse(Notes.isNote(jpegOnly, store.set(listOf(jpegOnly), false)))
    }

    @Test
    fun aRawWithoutJpegIsKept() {
        val raw = LibraryPhoto(5, Uri.EMPTY, "DSC00005.ARW", "image/x-sony-arw", day)
        val shot = Shot(raw, null)
        assertTrue(Notes.spareRaws(listOf(shot), mapOf(raw.key to day)).isEmpty())
    }

    @Test
    fun declutterComesMonthlyForOldNotes() {
        val month = Notes.MONTH_MS
        val old = Library.shots(pixel(1, day - 2 * month)).single()
        val fresh = Library.shots(pixel(2, day)).single()
        // Marked long after it was taken still gets a month's grace from the marking.
        val lateMarked = Library.shots(pixel(3, day - 3 * month)).single()
        val notes = (old.files.map { it.key to day - 2 * month } + fresh.files.map { it.key to day } +
            lateMarked.files.map { it.key to day - 1000 }).toMap()
        val shots = listOf(fresh, old, lateMarked)
        assertEquals(listOf(old), Notes.old(shots, notes, day))
        assertTrue(Notes.declutterDue(shots, notes, last = day - month - 1, now = day))
        assertFalse(Notes.declutterDue(shots, notes, last = day - 1000, now = day))
        assertFalse(Notes.declutterDue(listOf(fresh), notes, last = 0, now = day))
    }

    @Test
    fun trashButtonSaysWhatGoes() {
        assertEquals("Delete 3 rejected", trashLabel(3, 0))
        assertEquals("Delete 6 note RAWs", trashLabel(0, 6))
        assertEquals("Delete 4 rejected and 1 note RAW", trashLabel(4, 1))
    }

    /** Review with a note swipe, the Notes view and the declutter, as PNGs in app/build/screenshots. */
    @Test
    fun screens() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "note.jpg")
        val bmp = TestScenes.landscape(3000, 2000)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = Uri.fromFile(file)
        val shots = Library.shots((0 until 8).flatMap { i -> pixel(i, day - i * 600_000L - (if (i > 3) 40L * 86_400_000 else 0), uri, raw = i % 2 == 0) })
        var notes by mutableStateOf(shots.drop(4).flatMap { s -> s.files.map { it.key to day - 40L * 86_400_000 } }.toMap())
        var screen by mutableStateOf("library")
        var filter by mutableStateOf(QuickFilter.ALL)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                when (screen) {
                    "library" -> LibraryScreen(
                        shots = shots, edited = emptySet(), marks = emptyMap(), exported = emptySet(),
                        onOpen = {}, onCull = { _, _ -> }, onTogether = {}, onDeleteRejected = {},
                        transfer = SyncStatus(), onCamera = {},
                        filter = filter, onQuickFilter = { filter = it },
                        notes = notes, onNote = { _, _ -> }, spareRaws = Notes.spareRaws(shots, notes).size,
                        declutterDue = filter == QuickFilter.ALL, onDeclutter = {},
                    )
                    "declutter" -> NotesDeclutter(
                        shots = shots.filter { Notes.isNote(it, notes) },
                        preselected = shots.filter { Notes.isNote(it, notes) }.drop(1).map { it.key }.toSet(),
                        onTrash = {}, onDone = {},
                    )
                    else -> CullScreen(
                        shots = shots.take(4).filter { it.raw == null }, title = "Review", marks = emptyMap(), onMark = { _, _ -> },
                        rejectedCount = 0, onDeleteRejected = {}, onBack = {},
                        onNote = { picked, on -> notes = if (on) notes + picked.flatMap { s -> s.files.map { it.key to day } } else notes }, spareRaws = 0,
                    )
                }
            }
        }
        repeat(20) { step() }
        // The four old notes are out of the timeline; the card offers to clear them out.
        compose.onNodeWithTag("declutter-card").assertExists()
        shot("30-notes-library")
        filter = QuickFilter.NOTES
        repeat(20) { step() }
        compose.onNodeWithTag("review").assertDoesNotExist()
        shot("31-notes-view")

        screen = "cull"
        repeat(40) { step() }
        compose.onNodeWithTag("cull-card").performTouchInput { down(center); moveBy(Offset(10f, height * 0.25f)) }
        repeat(4) { step() }
        shot("32-cull-note")
        compose.onNodeWithTag("cull-card").performTouchInput { up() }
        repeat(20) { step() }
        compose.onNodeWithTag("cull-note").performClick()
        repeat(20) { step() }
        assertEquals(8, notes.size)

        screen = "declutter"
        repeat(20) { step() }
        shot("33-notes-declutter")
        compose.onNodeWithTag("declutter-trash").assertExists()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun step() {
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }
}
