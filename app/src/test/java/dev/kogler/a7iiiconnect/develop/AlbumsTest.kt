package dev.kogler.a7iiiconnect.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsTest {
    private val lisbon = Albums.create("Lisbon", listOf("a@1", "b@2"), now = 100, id = "x")

    @Test
    fun keepsTheOrderShotsWereAdded() {
        val more = Albums.add(lisbon, listOf("c@3", "a@1"), now = 200)
        assertEquals(listOf("a@1", "b@2", "c@3"), more.keys)
        assertEquals(3, more.size)
    }

    @Test
    fun takenOutShotsCanComeBack() {
        val out = Albums.remove(lisbon, listOf("a@1"), now = 200)
        assertEquals(listOf("b@2"), out.keys)
        assertFalse("a@1" in out)
        val back = Albums.add(out, listOf("a@1"), now = 300)
        assertEquals(listOf("b@2", "a@1"), back.keys)
    }

    @Test
    fun undoPutsATakenOutShotBackInItsPlace() {
        val out = Albums.remove(lisbon, listOf("a@1"), now = 200)
        assertEquals(lisbon, Albums.restore(out, "a@1", lisbon.entries["a@1"]))
        assertEquals(listOf("a@1", "b@2"), Albums.restore(out, "a@1", lisbon.entries["a@1"]).keys)
        // A shot that wasn't in the album before goes again, without a trace.
        assertEquals(lisbon.entries.keys, Albums.restore(Albums.add(lisbon, listOf("c@3"), now = 300), "c@3", null).entries.keys)
    }

    @Test
    fun readsWhatItWrites() {
        val albums = listOf(
            Albums.remove(lisbon, listOf("b@2"), now = 150),
            Albums.delete(Albums.create("Old", listOf("z@9"), now = 50, id = "y"), now = 60),
        )
        assertEquals(albums, Albums.decode(Albums.encode(albums)))
    }

    @Test
    fun namesStayOnOneLine() {
        val a = Albums.create("Summer\tin\nVienna ", emptyList(), now = 1)
        assertEquals("Summer in Vienna", a.name)
        assertEquals("Album", Albums.clean("  "))
        assertEquals(a.name, Albums.decode(Albums.encode(listOf(a))).single().name)
    }

    @Test
    fun skipsLinesItCantRead() {
        val text = Albums.encode(listOf(lisbon)) + "S\tx\tnot-a-time\t+\tq@1\njunk\n"
        assertEquals(lisbon, Albums.decode(text).single())
    }

    @Test
    fun mergeTakesTheNewerChangePerShot() {
        // She took b out after I added c; both changes survive.
        val mine = Albums.add(lisbon, listOf("c@3"), now = 200)
        val hers = Albums.remove(lisbon, listOf("b@2"), now = 300)
        val merged = Albums.merge(listOf(mine), listOf(hers)).single()
        assertEquals(listOf("a@1", "c@3"), merged.keys)
    }

    @Test
    fun mergeOnATieTakesTheShotOut() {
        val mine = Albums.remove(lisbon, listOf("a@1"), now = 200)
        val hers = lisbon.copy(entries = lisbon.entries + ("a@1" to AlbumEntry("a@1", 200, true)))
        assertFalse("a@1" in Albums.merge(listOf(mine), listOf(hers)).single())
        assertFalse("a@1" in Albums.merge(listOf(hers), listOf(mine)).single())
    }

    @Test
    fun mergeKeepsTheNewerNameAndAnyDelete() {
        val mine = Albums.rename(lisbon, "Lisboa", now = 500)
        val hers = Albums.delete(Albums.rename(lisbon, "Portugal", now = 400), now = 450)
        val merged = Albums.merge(listOf(mine), listOf(hers)).single()
        assertEquals("Lisboa", merged.name)
        assertEquals(450L, merged.deletedAt)
        assertFalse(merged.alive)
    }

    @Test
    fun mergeAddsAlbumsOnlyOneSideHas() {
        val other = Albums.create("Hike", listOf("h@1"), now = 10, id = "h")
        val merged = Albums.merge(listOf(lisbon), listOf(other))
        assertEquals(listOf("x", "h"), merged.map { it.id })
        assertTrue(merged.all { it.alive })
        assertNull(merged.first().deletedAt)
    }

    @Test
    fun findsMyKeyForHerCopyOfAShot() {
        val mine = listOf("DSC01234.ARW@1000", "DSC01235.ARW@5000", "DSC01234.ARW@99999999999")
        val resolve = Albums.resolver(mine)
        assertEquals("DSC01235.ARW@5000", resolve("DSC01235.ARW@5000"))
        // She only has the JPEG, and her clock read it a second later.
        assertEquals("DSC01234.ARW@1000", resolve("dsc01234.jpg@2000"))
        // The same name from another round of the camera's counter is another shot.
        assertNull(resolve("DSC01236.JPG@1000"))
        assertNull(resolve("DSC01234.JPG@200000000"))
        assertNull(resolve("not a key"))
    }

    @Test
    fun findsMyCopyWhenMediaStoreGotTheTimeWrong() {
        val hour = 60 * 60 * 1000L
        // Her phone read the capture time as UTC; mine guessed it from when the RAW came, a day later.
        val resolve = Albums.resolver(listOf("DSC01234.ARW@${1000 + 20 * hour}", "DSC09999.ARW@1000"))
        assertEquals("DSC01234.ARW@${1000 + 20 * hour}", resolve("DSC01234.JPG@${1000 + 2 * hour}"))
        // A copy MediaStore renamed because the name was taken is still the same shot.
        assertEquals("DSC09999.ARW@1000", resolve("DSC09999 (1).JPG@1000"))
    }

    @Test
    fun rekeyMovesShotsToMyKeysAndKeepsTheNewerChange() {
        val hers = Albums.remove(Albums.create("Trip", listOf("DSC1.JPG@1000", "DSC2.JPG@2000", "DSC3.JPG@3000"), now = 10, id = "t"), listOf("DSC2.JPG@2000"), now = 50)
        // I had already added DSC2 myself, before she took it out.
        val both = hers.copy(entries = hers.entries + ("DSC2.ARW@2000" to AlbumEntry("DSC2.ARW@2000", 20, true)))
        val resolve = Albums.resolver(listOf("DSC1.ARW@1000", "DSC2.ARW@2000"))
        val moved = Albums.rekey(listOf(both), resolve).single()
        assertEquals(listOf("DSC1.ARW@1000", "DSC3.JPG@3000"), moved.keys)
        assertFalse("DSC2.ARW@2000" in moved)
        assertEquals(3, moved.entries.size)
        // Nothing to move: the same album back.
        assertTrue(Albums.rekey(listOf(lisbon), Albums.resolver(emptyList())).single() === lisbon)
    }

    @Test
    fun albumsGoOnlyToInvitedPhones() {
        val hike = Albums.create("Hike", listOf("h@1"), now = 10, id = "h", owner = "me")
        assertFalse(hike.shared)
        assertTrue(Albums.sharedKeys(listOf(lisbon, hike), "anna").isEmpty())
        val withAnna = Albums.invite(hike, "me", "Patrick", "anna", "Anna")
        assertTrue(withAnna.shared)
        assertEquals(mapOf("me" to "Patrick", "anna" to "Anna"), withAnna.members)
        assertEquals(setOf("h@1"), Albums.sharedKeys(listOf(withAnna, lisbon), "anna"))
        // Someone else connecting gets nothing.
        assertTrue(Albums.sharedWith(listOf(withAnna), "ben").isEmpty())
        // Stopped sharing: nothing goes, even to Anna.
        assertTrue(Albums.sharedKeys(listOf(Albums.share(withAnna, false)), "anna").isEmpty())
        // A deleted album shares no shots, but still goes over so Anna learns it's gone.
        val gone = Albums.delete(withAnna, now = 20)
        assertTrue(Albums.sharedKeys(listOf(gone), "anna").isEmpty())
        assertEquals(listOf(gone), Albums.sharedWith(listOf(gone), "anna"))
        assertEquals(withAnna, Albums.decode(Albums.encode(listOf(withAnna))).single())
    }

    @Test
    fun aMembersShotsReachTheOwner() {
        val mine = Albums.invite(Albums.create("Hike", listOf("h@1"), now = 10, id = "h", owner = "me"), "me", "Patrick", "anna", "Anna")
        // Anna's copy, after she added a shot from her own library.
        val hers = Albums.add(mine, listOf("anna1@5"), now = 30)
        val merged = Albums.merge(listOf(mine), listOf(hers)).single()
        assertEquals(listOf("h@1", "anna1@5"), merged.keys)
        assertEquals("me", merged.owner)
        assertTrue(merged.ownedBy("me"))
        assertFalse(merged.ownedBy("anna"))
    }

    @Test
    fun mergeKeepsMyChoiceAboutSharing() {
        // I stopped sharing it; her phone sends it again as shared.
        val mine = Albums.share(lisbon, false)
        val hers = Albums.add(Albums.share(lisbon, true), listOf("c@3"), now = 300)
        val merged = Albums.merge(listOf(mine), listOf(hers)).single()
        assertFalse(merged.shared)
        assertEquals(listOf("a@1", "b@2", "c@3"), merged.keys)
    }

    @Test
    fun readsAlbumsWrittenBeforeSharing() {
        val old = "A\tx\t100\t100\t0\tLisbon\nS\tx\t100\t+\ta@1\n"
        val a = Albums.decode(old).single()
        assertEquals("Lisbon", a.name)
        assertFalse(a.shared)
        assertTrue(a.members.isEmpty())
        assertTrue(a.ownedBy("anyone"))
        assertEquals(listOf("a@1"), a.keys)
    }
}
