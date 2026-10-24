package dev.kogler.a7iiiconnect.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    private val t = 1_760_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun readsThePairingCode() {
        val c = Computer.parse("shootback://pair?v=1&id=ab-12&name=Patrick%27s%20laptop&host=192.168.1.20&port=47820&key=0123456789abcdef0123456789abcdef")!!
        assertEquals("ab-12", c.id)
        assertEquals("Patrick's laptop", c.name)
        assertEquals("192.168.1.20", c.host)
        assertEquals(47820, c.port)
        assertNull(Computer.parse("shootback://pair?v=2&id=a&host=h&port=1&key=0123456789abcdef"))
        assertNull(Computer.parse("https://example.com"))
        assertNull(Computer.parse("shootback://pair?v=1&id=a&host=h&port=1&key=short"))
    }

    @Test
    fun photosGoWhereTheyAreMissing() {
        val phone = listOf(SyncFile("DSC00001.JPG", t), SyncFile("DSC00002.JPG", t + hour), SyncFile("DSC00003 (1).JPG", t + 2 * hour))
        // The computer's copy of 1 is dated a few hours off (MediaStore guessed the time zone); 3 is the same file.
        val computer = listOf(SyncFile("DSC00001.JPG", t + 3 * hour), SyncFile("DSC00003.JPG", t + 2 * hour), SyncFile("DSC00009.ARW", t - 40L * 24 * hour), SyncFile("DSC00010.ARW", t))
        val base = Base()
        assertEquals(listOf("DSC00002.JPG"), SyncPlan.toUpload(phone, computer, base).map { it.name })
        assertEquals(listOf("DSC00010.ARW"), SyncPlan.toDownload(phone, computer, base, PhotoPolicy.RECENT, t + 5 * hour).map { it.name })
        assertEquals(listOf("DSC00009.ARW", "DSC00010.ARW"), SyncPlan.toDownload(phone, computer, base, PhotoPolicy.ALL, t).map { it.name })
        assertTrue(SyncPlan.toDownload(phone, computer, base, PhotoPolicy.OFF, t).isEmpty())
    }

    @Test
    fun aPhotoLetGoOfIsNotCopiedBack() {
        val both = listOf(SyncFile("DSC00001.JPG", t))
        val base = Base(known = SyncPlan.known(both, both))
        // Deleted on the computer: the phone doesn't send it again.
        assertTrue(SyncPlan.toUpload(both, emptyList(), base).isEmpty())
        // Deleted on the phone: the computer's copy doesn't come back.
        assertTrue(SyncPlan.toDownload(emptyList(), both, base, PhotoPolicy.ALL, t).isEmpty())
    }

    @Test
    fun shotsAreNamedByTheirRaw() {
        val files = listOf(SyncFile("DSC00001.ARW", t), SyncFile("DSC00001.JPG", t), SyncFile("DSC00002.JPG", t))
        assertEquals(setOf("DSC00001.ARW@$t", "DSC00002.JPG@$t"), SyncPlan.shotKeys(files).toSet())
        // The phone has only the JPEG of shot 1: its mark still finds the computer's RAW.
        val pairs = SyncPlan.pairs(listOf("DSC00001.JPG@${t + hour}"), SyncPlan.shotKeys(files))
        assertEquals(mapOf("DSC00001.JPG@${t + hour}" to "DSC00001.ARW@$t"), pairs)
    }

    @Test
    fun marksMergeThreeWays() {
        val pairs = listOf("a", "b", "c", "d", "e", "f").associateWith { it.uppercase() }
        val base = mapOf("a" to "PICK", "b" to "PICK", "c" to "PICK", "f" to "PICK")
        val phone = mapOf("a" to "PICK", "b" to "REJECT", "c" to "PICK", "d" to "PICK", "f" to "REJECT")
        val computer = mapOf("A" to "PICK", "B" to "PICK", "C" to "REJECT", "E" to "REJECT")
        val (changes, agreed) = SyncPlan.marks(phone, computer, base, pairs)
        // b changed on the phone, c on the computer, d is new on the phone, e on the computer;
        // f changed on both (rejected here, cleared there) and the phone wins.
        assertEquals(mapOf("B" to "REJECT", "D" to "PICK", "F" to "REJECT"), changes.computer)
        assertEquals(mapOf("c" to "REJECT", "e" to "REJECT"), changes.phone)
        assertEquals(mapOf("a" to "PICK", "b" to "REJECT", "c" to "REJECT", "d" to "PICK", "e" to "REJECT", "f" to "REJECT"), agreed)
    }

    @Test
    fun aMarkClearedOnOneSideIsClearedOnTheOther() {
        val pairs = mapOf("a" to "A")
        val (changes, agreed) = SyncPlan.marks(emptyMap(), mapOf("A" to "PICK"), mapOf("a" to "PICK"), pairs)
        assertEquals(mapOf("A" to null), changes.computer)
        assertTrue(changes.phone.isEmpty())
        assertTrue(agreed.isEmpty())
    }

    @Test
    fun editsMergeAndTheLaterChangeWins() {
        val pairs = mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val old = "v=2\nexposure=0.5\n"
        val base = mapOf("a" to SyncPlan.hash(old), "b" to SyncPlan.hash(old), "c" to SyncPlan.hash(old))
        val phone = mapOf(
            "a" to SyncEdit("v=2\nexposure=1.0\n", 10), // changed here only
            "b" to SyncEdit(old, 5),
            "c" to SyncEdit("v=2\nexposure=-1.0\n", 30), // changed on both, later here
        )
        val computer = mapOf(
            "A" to SyncEdit(old, 5),
            "B" to SyncEdit("v=2\ncontrast=20\n", 20), // changed there only
            "C" to SyncEdit("v=2\nexposure=2.0\n", 25),
            "D" to SyncEdit("v=2\nshadows=40\n", 40), // new there
        )
        val (changes, agreed) = SyncPlan.edits(phone, computer, base, pairs)
        assertEquals(setOf("A", "C"), changes.computer.keys)
        assertEquals("v=2\nexposure=-1.0\n", changes.computer["C"]!!.text)
        assertEquals(setOf("b", "d"), changes.phone.keys)
        assertEquals(20L, changes.phone["b"]!!.modified)
        assertEquals(4, agreed.size)

        // Reset on the computer: the phone drops its edit too.
        val (reset, _) = SyncPlan.edits(mapOf("a" to SyncEdit(old, 1)), emptyMap(), mapOf("a" to SyncPlan.hash(old)), mapOf("a" to "A"))
        assertEquals(mapOf("a" to null), reset.phone)
    }

    @Test
    fun baseRoundTrips() {
        val b = Base(mapOf("a@1" to "PICK"), mapOf("a@1" to "abc"), setOf("a@1", "A@2"))
        assertEquals(b, Base.decode(b.encode()))
    }
}
