package dev.kogler.a7iiiconnect.develop

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin
import kotlin.random.Random

/** The smart library's pure parts: focus, faces, people, the best of a stack, search, sky. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartTest {

    private fun shot(name: String, t: Long) = Shot(null, LibraryPhoto(t, Uri.EMPTY, "$name.JPG", "image/jpeg", t))

    /** A unit vector near [base], as two photos of the same face would give. */
    private fun near(base: FloatArray, rnd: Random, spread: Float = 0.35f) =
        ShotInsight.normalize(FloatArray(base.size) { base[it] + rnd.nextFloat() * spread - spread / 2 })

    private fun person(rnd: Random) = ShotInsight.normalize(FloatArray(128) { rnd.nextFloat() * 2 - 1 })

    private fun face(embedding: FloatArray?, eyes: Float? = 0.9f, sharp: Float = 50f, width: Float = 0.2f) =
        FaceInfo(floatArrayOf(0.4f, 0.3f, 0.4f + width, 0.6f), eyes, 0.5f, sharp, embedding)

    @Test
    fun blurLowersSharpness() {
        val w = 200; val h = 150
        val sharp = FloatArray(w * h) { i -> if ((i % w / 6 + i / w / 6) % 2 == 0) 0.9f else 0.1f }
        val soft = FloatArray(w * h)
        dev.kogler.a7iiiconnect.develop.engine.Filters.box(sharp, soft, w, h, 2)
        assertTrue(Sharpness.of(sharp, w, h) > 1.5f * Sharpness.of(soft, w, h))
        // Only the sharpest part counts: a sharp corner in a blurred frame still scores as sharp.
        val bokeh = soft.copyOf()
        for (y in 0 until 50) for (x in 0 until 60) bokeh[y * w + x] = sharp[y * w + x]
        assertTrue(Sharpness.of(bokeh, w, h) > 0.8f * Sharpness.of(sharp, w, h))
    }

    @Test
    fun facesLineUpOnTheTemplate() {
        // The same numbers OpenCV's estimateAffinePartial2D gives for these points.
        val src = floatArrayOf(100f, 120f, 160f, 118f, 130f, 150f, 108f, 180f, 155f, 178f)
        val a = FaceAlign.similarity(src, FaceAlign.TEMPLATE)
        assertArrayEquals(floatArrayOf(0.64259f, -0.01572f, -25.55123f, 0.01572f, 0.64259f, -26.02632f), a, 0.001f)
    }

    @Test
    fun insightSurvivesTheFile() {
        val rnd = Random(1)
        val e = person(rnd)
        val i = ShotInsight(listOf(face(e, eyes = 0.1f), face(null, eyes = null)), 42.5f, listOf("Dog", "Beach"), "CAFE MOZART")
        val back = ShotInsight.decode(i.encode())!!
        assertEquals(2, back.faces.size)
        assertEquals(0.1f, back.faces[0].eyesOpen!!, 1e-4f)
        assertNull(back.faces[1].eyesOpen)
        assertNull(back.faces[1].embedding)
        assertTrue(People.cosine(e, back.faces[0].embedding!!) > 0.99f)
        assertEquals(listOf("Dog", "Beach"), back.labels)
        assertEquals("CAFE MOZART", back.text)
        assertEquals(42.5f, back.sharp, 1e-3f)
    }

    @Test
    fun facesGroupIntoPeople() {
        val rnd = Random(7)
        val anna = person(rnd); val ben = person(rnd)
        val insights = (0 until 12).associate { k ->
            "s$k" to ShotInsight(listOf(face(near(if (k % 3 == 0) ben else anna, rnd))), 10f, emptyList(), "")
        }
        val people = People.group(insights, emptyList())
        assertEquals(2, people.size)
        assertEquals(setOf(8, 4), people.map { it.faces.size }.toSet())

        // Named, remembered, and grouped again with new photos: the name and id stay.
        val annaGroup = people.first { it.faces.size == 8 }
        val named = People.rename(people, setOf(annaGroup.id), "Anna")
        val anchors = named.map { Anchor(it.id, it.name, it.centroid) }
        val more = insights + ("s99" to ShotInsight(listOf(face(near(anna, rnd))), 10f, emptyList(), ""))
        val again = People.group(more, anchors)
        val anna2 = again.first { it.name == "Anna" }
        assertEquals(annaGroup.id, anna2.id)
        assertTrue(FaceRef("s99", 0) in anna2.faces)
        assertEquals(2, again.count { it.faces.isNotEmpty() })
    }

    @Test
    fun bestShotHasOpenEyesAndFocus() {
        val a = shot("A", 1000); val b = shot("B", 1500); val c = shot("C", 2000)
        val insights = mapOf(
            a.key to ShotInsight(listOf(face(null, eyes = 0.9f, sharp = 30f)), 30f, emptyList(), ""),
            b.key to ShotInsight(listOf(face(null, eyes = 0.05f, sharp = 60f)), 60f, emptyList(), ""),
            c.key to ShotInsight(listOf(face(null, eyes = 0.95f, sharp = 55f)), 55f, emptyList(), ""),
        )
        // B is sharpest but blinked: C goes first, the others stay in order.
        assertEquals(listOf(c, a, b), Best.order(listOf(a, b, c), insights))
        assertEquals(listOf("Eyes closed"), Best.notes(b, insights))
        assertTrue("Softer" in Best.notes(a, insights, listOf(a, b, c)))
        // A small face far behind doesn't count.
        val crowd = ShotInsight(listOf(face(null, eyes = 0.0f, width = 0.02f)), 10f, emptyList(), "")
        assertEquals(0, Best.blinks(crowd))
        // Nothing looked at yet: the order stays as taken.
        assertEquals(listOf(a, b, c), Best.order(listOf(a, b, c), emptyMap()))
    }

    @Test
    fun searchFindsWhatsInThePhoto() {
        val s = shot("A", 1)
        val f = ShotFacts(s, ShotMeta(lens = "FE 85mm F1.8"), place = "Hallstatt",
            insight = ShotInsight(emptyList(), 1f, listOf("Dog", "Lake"), "Gasthof zur Post"), names = listOf("Anna"))
        assertTrue(f.found("dogs"))
        assertTrue(f.found("lake hallstatt"))
        assertTrue(f.found("gasthof"))
        assertTrue(f.found("anna"))
        assertTrue(f.found("85mm"))
        assertFalse(f.found("cat"))
        assertFalse(f.found("dog cat"))
        assertTrue(ShotFilter(query = "dog").matches(f))
        assertFalse(ShotFilter(people = setOf("Ben")).matches(f))
        // Saved filters keep the search and the people.
        val saved = SavedFilters.decode(SavedFilters.encode(ShotFilter(query = "dog", people = setOf("Anna"))))
        assertEquals("dog", saved.query)
        assertEquals(setOf("Anna"), saved.people)
    }

    /** A blue sky fading paler to a horizon, over rough ground (and a tree), [w] x [h] ARGB. */
    private fun landscape(w: Int, h: Int, sky: Boolean = true): IntArray {
        val rnd = Random(3)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val horizon = h * 0.45 + 12 * sin(x * 0.03)
            val tree = x in w / 5..w / 5 + 30 && y > h / 6
            if (sky && y < horizon && !tree) {
                val t = y / horizon
                val r = (70 + 110 * t).toInt(); val g = (130 + 80 * t).toInt(); val b = (225 + 20 * t).toInt().coerceAtMost(255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                val v = 40 + rnd.nextInt(90)
                (0xFF shl 24) or ((v + 20) shl 16) or ((v + 40) shl 8) or v
            }
        }
    }

    @Test
    fun skyIsFound() {
        val w = 320; val h = 200
        val m = SkyDetect.find(landscape(w, h), w, h)
        assertNotNull(m)
        m!!
        assertTrue(m.at(0.6f, 0.1f) > 0.9f)
        assertTrue(m.at(0.6f, 0.35f) > 0.8f)
        assertTrue(m.at(0.6f, 0.85f) < 0.1f)
        // The tree in front isn't sky.
        assertTrue(m.at((w / 5 + 15f) / w, 0.3f) < 0.3f)
        // No sky: rough everywhere.
        assertNull(SkyDetect.find(landscape(w, h, sky = false), w, h))
    }
}
