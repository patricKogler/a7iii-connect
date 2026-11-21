package dev.kogler.a7iiiconnect.duo

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/** A shared album as the review room serves it. */
data class RoomAlbum(
    val id: String,
    val name: String,
    /** The phone running the room. */
    val host: String,
    /** Everyone in the album, phones and browser guests, by id, with their names (the host too). */
    val members: Map<String, String>,
    /** The album's shots, in album order. */
    val keys: List<String>,
    /** The shots the host has a photo of (the others can't be shown yet). */
    val present: Set<String>,
)

/** A photo to save on a guest's phone: [name] and its bytes. */
class RoomFile(val name: String, val open: () -> java.io.InputStream)

/** What the review room needs from the phone; [ReviewRoom] is the real one, tests have their own. */
interface RoomHost {
    /** The album as it is now; null once it's gone. */
    fun album(): RoomAlbum?
    fun facts(): List<Fact>
    /** Facts a guest made: kept, and passed on to the other phones like the host's own. */
    fun add(facts: List<Fact>)
    /** A guest sealed the round: the shots that came out [keys] leave the album, as when a phone seals. */
    fun removeFromAlbum(keys: List<String>, now: Long)
    /** A browser guest joins the album (or says a new name). */
    fun join(guest: String, name: String)
    /** The shot as a JPEG, longer edge about [edge]; null when it can't be read. */
    fun photo(key: String, edge: Int): ByteArray?
    /** The shot to keep, as the host made it: the edited JPEG when it's edited, full size where it can be. */
    fun download(key: String): RoomFile?
    /** Goes up whenever the album or its facts change. */
    fun version(): Int
    /** The web page the guests open. */
    fun page(): ByteArray
    fun now(): Long = System.currentTimeMillis()
}

/**
 * The review room's web API: lets people without Shootback (an iPhone, a laptop) join a shared
 * album's review in their browser, on the same rules as the phones ([TogetherReview]). Each guest
 * is a member of the album like a phone is, so their votes go to the other phones too.
 *
 * Everything needs the room's [token] (`t`, from the link in the QR code). A guest proves who
 * they are with a secret their browser made (`s`); their member id comes from it, so nobody can
 * vote as somebody else, and the same browser is the same person whenever the room opens again.
 *
 * - `GET /`: the page. `GET /api/state?t&s&v`: the review as this guest sees it; with `v`, waits
 *   (up to [WAIT_MS]) until it changes from that version, so the page is live without polling hard.
 * - `GET /api/photo?t&k&w`: a shot as JPEG, `w` the longer edge.
 * - `GET /api/file?t&k`: a shot to keep (edited, big); `inline=1` to show it rather than download it.
 *   `GET /api/zip?t&k=a|b|c&name=`: those shots in one zip, written as they're made.
 * - `POST /api/join {s,name}`, `/api/vote {s,k,c}` (c P, R, M or "" to take it back),
 *   `/api/say {s,k,c}`, `/api/coin {s,k}`, `/api/seal {s}`.
 */
class RoomApi(private val token: String, private val host: RoomHost, private val random: Random = Random.Default) {

    fun handle(req: HttpRequest): HttpResponse {
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            return HttpResponse(200, "text/html; charset=utf-8", host.page())
        }
        if (req.method == "GET" && req.path == "/favicon.ico") return HttpResponse.text(404, "")
        if (!req.path.startsWith("/api/")) return HttpResponse.text(404, "Not here")
        if (!sameToken(req.param("t"))) return HttpResponse.text(403, "This room has closed. Ask for a new link.")
        val body = if (req.method == "POST") runCatching { JSONObject(String(req.body)) }.getOrNull() ?: return bad("Bad request") else null
        val secret = body?.optString("s") ?: req.param("s")
        return when ("${req.method} ${req.path}") {
            "GET /api/state" -> {
                req.query["v"]?.toIntOrNull()?.let { waitForChange(it) }
                state(guestId(secret))
            }
            "GET /api/photo" -> photo(req.param("k"), req.param("w").toIntOrNull() ?: 1600)
            "GET /api/file" -> file(req.param("k"), inline = req.param("inline") == "1")
            "GET /api/zip" -> zip(req.param("k").split('|').filter { it.isNotEmpty() }, req.param("name"))
            "POST /api/join" -> join(secret, body!!.optString("name"))
            "POST /api/vote" -> act(secret) { g, a, r ->
                val key = body!!.optString("k")
                if (key !in r.open || key !in a.present) return@act null
                val call = body.optString("c").let { if (it.isEmpty()) null else Call.of(it) ?: return@act null }
                listOf(r.voteFact(g, key, call, host.now()))
            }
            "POST /api/say" -> act(secret) { g, _, r ->
                val key = body!!.optString("k")
                val call = Call.of(body.optString("c"))?.takeIf { it != Call.MAYBE } ?: return@act null
                if (r.state(key) != ReviewState.DISPUTED) return@act null
                r.sayFacts(g, key, call, host.now())
            }
            "POST /api/coin" -> act(secret) { g, a, r ->
                r.coinFact(g, body!!.optString("k"), random.nextInt(members(a).size), host.now())?.let { listOf(it) }
            }
            "POST /api/seal" -> act(secret) { g, _, r ->
                val now = host.now()
                val keys = r.toSeal(g)
                r.seal(g, now).ifEmpty { null }?.also { host.removeFromAlbum(keys.filter { r.outcome(it) == Call.REJECT }, now) }
            }
            else -> HttpResponse.text(404, "Not here")
        }
    }

    private fun sameToken(t: String) = MessageDigest.isEqual(t.toByteArray(), token.toByteArray())

    private fun bad(text: String) = HttpResponse.text(400, text)

    private fun members(a: RoomAlbum) = (a.members.keys + a.host).distinct().sorted()

    private fun review(a: RoomAlbum) = TogetherReview(a.id, members(a), a.keys, host.facts())

    /** A guest's member id, from the secret their browser keeps: the same for the same browser and album. */
    private fun guestId(secret: String): String? {
        if (secret.length < 16) return null
        val a = host.album() ?: return null
        val d = MessageDigest.getInstance("SHA-256").digest("${a.id}\u0000$secret".toByteArray())
        return GUEST_PREFIX + d.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun join(secret: String, name: String): HttpResponse {
        val g = guestId(secret) ?: return bad("Bad request")
        val clean = name.replace(Regex("[\\t\\r\\n|]"), " ").trim().take(30)
        if (clean.isEmpty()) return bad("Say your name")
        host.join(g, clean)
        return state(g)
    }

    /** A guest's move: [make] gives the facts it makes, or null when it doesn't fit the review as it is. */
    private fun act(secret: String, make: (String, RoomAlbum, TogetherReview) -> List<Fact>?): HttpResponse {
        val g = guestId(secret) ?: return bad("Bad request")
        val a = host.album() ?: return HttpResponse.text(404, "The album is gone")
        if (g !in a.members) return HttpResponse.text(403, "Join first")
        val facts = make(g, a, review(a)) ?: return HttpResponse.text(409, "That changed meanwhile")
        host.add(facts)
        return state(g)
    }

    private fun waitForChange(seen: Int) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (host.version() == seen && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    private fun photo(key: String, edge: Int): HttpResponse {
        val a = host.album() ?: return HttpResponse.text(404, "The album is gone")
        if (key !in a.keys || key !in a.present) return HttpResponse.text(404, "No such photo")
        val bytes = host.photo(key, edge.coerceIn(320, 2400)) ?: return HttpResponse.text(404, "Can't read that photo")
        return HttpResponse(200, "image/jpeg", bytes, cache = true)
    }

    private fun file(key: String, inline: Boolean): HttpResponse {
        val a = host.album() ?: return HttpResponse.text(404, "The album is gone")
        if (key !in a.keys || key !in a.present) return HttpResponse.text(404, "No such photo")
        val f = host.download(key) ?: return HttpResponse.text(404, "Can't read that photo")
        return HttpResponse(200, "image/jpeg", ByteArray(0), filename = f.name.takeIf { !inline }, stream = { out -> f.open().use { it.copyTo(out) } })
    }

    private fun zip(keys: List<String>, name: String): HttpResponse {
        val a = host.album() ?: return HttpResponse.text(404, "The album is gone")
        val wanted = keys.distinct().filter { it in a.keys && it in a.present }
        if (wanted.isEmpty()) return HttpResponse.text(404, "No photos picked")
        val zipName = listOf(a.name, name.trim()).filter { it.isNotEmpty() }.joinToString(" - ").take(60) + ".zip"
        return HttpResponse(200, "application/zip", ByteArray(0), filename = zipName, stream = { out ->
            val zip = java.util.zip.ZipOutputStream(out)
            // Photos don't get smaller in a zip; storing them is quicker.
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            val used = HashSet<String>()
            for (k in wanted) {
                val f = host.download(k) ?: continue
                var entry = f.name
                var n = 2
                while (!used.add(entry)) entry = f.name.substringBeforeLast('.') + "-${n++}." + f.name.substringAfterLast('.', "jpg")
                zip.putNextEntry(java.util.zip.ZipEntry(entry))
                f.open().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        })
    }

    /** The review as guest [g] sees it (not joined yet: null), as JSON. */
    fun state(g: String?): HttpResponse {
        val version = host.version()
        val a = host.album() ?: return HttpResponse.json(JSONObject().put("gone", true).put("v", version).toString())
        val r = review(a)
        val members = members(a)
        val joined = g != null && g in a.members
        val me = g.takeIf { joined }
        fun name(id: String) = a.members[id] ?: "Someone"
        val o = JSONObject()
        o.put("v", version)
        o.put("album", a.name)
        o.put("hostName", name(a.host))
        o.put("me", me ?: JSONObject.NULL)
        o.put("total", r.open.size)
        o.put("members", JSONArray(members.map { m ->
            JSONObject().put("id", m).put("name", name(m)).put("done", r.progress(m)).put("host", m == a.host).put("guest", m.startsWith(GUEST_PREFIX))
        }))
        if (me != null) {
            o.put("deck", JSONArray(r.toVote(me).filter { it in a.present }))
            o.put("waiting", r.toVote(me).count { it !in a.present })
            o.put("voted", JSONArray(r.open.filter { r.vote(me, it) != null }.takeLast(UNDO_DEPTH)))
            o.put("talk", JSONArray(r.disputes.filter { it in a.present }.map { k ->
                JSONObject().put("k", k)
                    .put("votes", JSONObject(members.associateWith { r.vote(it, k)?.token ?: "" }))
                    .put("says", JSONObject(members.associateWith { r.say(it, k)?.token ?: "" }))
                    .put("stuck", r.isStuck(k))
            }))
            o.put("seal", r.toSeal(me).size)
            val open = r.open
            o.put("kept", open.count { r.outcome(it) == Call.PICK })
            o.put("out", open.count { r.outcome(it) == Call.REJECT })
            o.put("matches", open.count { r.isMatch(it) })
            // For saving to their phone: what they kept, what everyone kept, and the whole album.
            val shown = a.keys.filter { it in a.present }
            o.put("picks", JSONArray(shown.filter { r.vote(me, it) == Call.PICK || r.say(me, it) == Call.PICK }))
            o.put("keepers", JSONArray(shown.filter { r.outcome(it) == Call.PICK }))
            o.put("all", JSONArray(shown))
            r.rounds.lastOrNull { it.first == me }?.let { (_, at, t) ->
                o.put("lastRound", JSONObject().put("at", at).put("kept", t.kept).put("out", t.out).put("score", t.score))
            }
        }
        return HttpResponse.json(o.toString())
    }

    companion object {
        /** Member ids of browser guests start with this; phone ids never do. */
        const val GUEST_PREFIX = "web-"
        const val WAIT_MS = 20_000L
        private const val UNDO_DEPTH = 20

        /** A fresh room token: long enough that nobody guesses it. */
        fun newToken(random: Random = Random.Default): String {
            val letters = "abcdefghijkmnpqrstuvwxyz23456789"
            return (1..16).map { letters[random.nextInt(letters.length)] }.joinToString("")
        }
    }
}

/** Escapes the characters the Wi-Fi QR format (`WIFI:S:…;P:…;;`) reserves. */
internal fun qrEscape(s: String) = s.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
