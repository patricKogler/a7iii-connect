package dev.kogler.a7iiiconnect.duo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/** The review room's API, with a pretend phone behind it (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
class RoomApiTest {
    private class FakeHost : RoomHost {
        val keys = listOf("k1", "k2", "k3")
        val members = linkedMapOf("host" to "Patrick", "anna" to "Anna")
        var facts = emptyList<Fact>()
        var v = 0
        val removed = mutableListOf<String>()
        var clock = 100L
        override fun album() = RoomAlbum("a", "Trip", "host", members.toMap(), keys - removed.toSet(), setOf("k1", "k2", "k3"))
        override fun facts() = facts
        override fun add(facts: List<Fact>) { this.facts = Facts.merge(this.facts, facts); v++ }
        override fun removeFromAlbum(keys: List<String>, now: Long) { removed += keys; v++ }
        override fun join(guest: String, name: String) { members[guest] = name; v++ }
        override fun photo(key: String, edge: Int) = byteArrayOf(1, 2, 3)
        override fun version() = v
        override fun page() = "<html>".toByteArray()
        override fun now() = ++clock
    }

    private val host = FakeHost()
    private val api = RoomApi("tok", host)
    private val secret = "0123456789abcdef0123"

    private fun get(path: String) = api.handle(HttpRequest("GET", path.substringBefore('?'), RoomHttp.parseQuery(path.substringAfter('?', "")), ByteArray(0)))
    private fun post(path: String, body: JSONObject) =
        api.handle(HttpRequest("POST", path, mapOf("t" to "tok"), body.put("s", secret).toString().toByteArray()))
    private fun json(r: HttpResponse) = JSONObject(String(r.body))

    private fun phoneVote(phone: String, key: String, call: Call) {
        host.add(listOf(TogetherReview("a", (host.members.keys).sorted(), host.keys, host.facts).voteFact(phone, key, call, host.now())))
    }

    @Test
    fun needsTheToken() {
        assertEquals(403, api.handle(HttpRequest("GET", "/api/state", mapOf("t" to "nope"), ByteArray(0))).status)
        assertEquals(200, api.handle(HttpRequest("GET", "/", emptyMap(), ByteArray(0))).status)
    }

    @Test
    fun joinVoteAndTalkItOver() {
        val before = json(get("/api/state?t=tok&s=$secret"))
        assertTrue(before.isNull("me"))
        val joined = json(post("/api/join", JSONObject().put("name", "Lena")))
        val me = joined.getString("me")
        assertTrue(me.startsWith(RoomApi.GUEST_PREFIX))
        assertEquals("Lena", host.members[me])
        assertEquals(3, joined.getJSONArray("deck").length())

        // The same browser is the same guest; another browser is someone else.
        assertEquals(me, json(post("/api/join", JSONObject().put("name", "Lena B"))).getString("me"))

        for (k in host.keys) { phoneVote("host", k, Call.PICK); phoneVote("anna", k, Call.PICK) }
        post("/api/vote", JSONObject().put("k", "k1").put("c", "P"))
        post("/api/vote", JSONObject().put("k", "k2").put("c", "R"))
        val s = json(post("/api/vote", JSONObject().put("k", "k3").put("c", "P")))
        assertEquals(0, s.getJSONArray("deck").length())
        assertEquals(2, s.getInt("matches"))
        assertEquals(1, s.getJSONArray("talk").length())
        assertEquals("k2", s.getJSONArray("talk").getJSONObject(0).getString("k"))

        // Talking it over: everyone says keep, so it's kept.
        val r = TogetherReview("a", host.members.keys.sorted(), host.keys, host.facts)
        host.add(r.sayFacts("host", "k2", Call.PICK, host.now()))
        host.add(TogetherReview("a", host.members.keys.sorted(), host.keys, host.facts).sayFacts("anna", "k2", Call.PICK, host.now()))
        val after = json(post("/api/say", JSONObject().put("k", "k2").put("c", "P")))
        assertEquals(0, after.getJSONArray("talk").length())
        assertEquals(3, after.getInt("seal"))

        val sealed = json(post("/api/seal", JSONObject()))
        assertEquals(0, sealed.getInt("seal"))
        assertTrue(sealed.has("lastRound"))
        assertTrue(host.removed.isEmpty())
    }

    @Test
    fun guestsCannotVoteBeforeJoiningOrOnStrangeShots() {
        assertEquals(403, post("/api/vote", JSONObject().put("k", "k1").put("c", "P")).status)
        post("/api/join", JSONObject().put("name", "Lena"))
        assertEquals(409, post("/api/vote", JSONObject().put("k", "nope").put("c", "P")).status)
        assertEquals(409, post("/api/vote", JSONObject().put("k", "k1").put("c", "X")).status)
        assertEquals(409, post("/api/say", JSONObject().put("k", "k1").put("c", "P")).status)
    }

    @Test
    fun longPollWaitsForAChange() {
        val v = host.version()
        Thread { Thread.sleep(300); host.join("x", "X") }.start()
        val t0 = System.currentTimeMillis()
        val s = json(get("/api/state?t=tok&s=$secret&v=$v"))
        assertTrue(System.currentTimeMillis() - t0 >= 250)
        assertNotEquals(v, s.getInt("v"))
    }

    @Test
    fun readsRequests() {
        val raw = "POST /api/vote?t=a%20b&x HTTP/1.1\r\nHost: p\r\nContent-Length: 5\r\n\r\nhello"
        val req = RoomHttp.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", req.method)
        assertEquals("/api/vote", req.path)
        assertEquals("a b", req.param("t"))
        assertEquals("hello", String(req.body))
    }

    @Test
    fun servesOverHttp() {
        val server = RoomHttp(api::handle)
        val port = server.start(48850)
        try {
            val c = URL("http://127.0.0.1:$port/api/photo?t=tok&k=k1&w=800").openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("image/jpeg", c.contentType)
            assertEquals(3, c.inputStream.readBytes().size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun wifiQrEscapes() {
        assertEquals("a\\;b\\:c", qrEscape("a;b:c"))
    }
}
