package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.EditStore
import dev.kogler.a7iiiconnect.develop.ExportOptions
import dev.kogler.a7iiiconnect.develop.LibraryPhoto
import dev.kogler.a7iiiconnect.develop.Mark
import dev.kogler.a7iiiconnect.develop.Photos
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Together: two phones going through the same photos. The host's phone has the photos and runs the
 * game ([DuoSession]); the guest's phone gets previews, votes, and at the end chooses whether it
 * wants the keepers, and as JPEG, RAW or both ([Want]).
 * Then an edit battle: both edit the same RAW blind, and vote on the two versions side by side.
 *
 * Lives as long as the app process, so a screen rotation doesn't drop the link. All state is
 * Compose state and only changes on the main thread.
 */
object Duo : DuoLink.Listener {
    enum class Phase { LOBBY, CULL, REVEAL, DEBATE, DONE, BATTLE_PICK, BATTLE_EDIT, BATTLE_VOTE, BATTLE_RESULT, BATTLE_OVER }

    private const val PROTOCOL = 2
    private const val PREVIEW_EDGE = 2560
    /** About what a full-size JPEG rendered from an A7III RAW takes, for the size shown before choosing. */
    private const val RENDERED_JPEG_BYTES = 10_000_000L

    enum class Format { JPEG, RAW, BOTH }

    /** The guest's answer to "Want the photos?": null [format] means no thanks. */
    data class Want(val format: Format?, val alsoMine: Boolean)

    /** What the guest can have: the shots both kept, and the ones only the guest kept. */
    data class Offer(val both: Bundle, val mine: Bundle)

    /** [count] shots, and how many bytes each format would take. */
    data class Bundle(val count: Int, val jpeg: Long, val raw: Long, val all: Long) {
        fun bytes(f: Format) = when (f) {
            Format.JPEG -> jpeg
            Format.RAW -> raw
            Format.BOTH -> all
        }
        operator fun plus(o: Bundle) = Bundle(count + o.count, jpeg + o.jpeg, raw + o.raw, all + o.all)
        fun toJson() = JSONArray(listOf(count, jpeg, raw, all))

        companion object {
            val EMPTY = Bundle(0, 0, 0, 0)
            fun fromJson(a: JSONArray?) = a?.let { Bundle(it.optInt(0), it.optLong(1), it.optLong(2), it.optLong(3)) } ?: EMPTY
        }
    }

    private lateinit var app: Context
    private var link: DuoLink? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewJob: Job? = null

    /** What this phone's NFC "tag" says while hosting; read by [DuoTag]. */
    @Volatile var tagUri: String? = null
        private set

    var active by mutableStateOf(false); private set
    /** This phone has photos to share (it started from Photos with a deck). */
    var hosting by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var reconnecting by mutableStateOf(false); private set
    var phase by mutableStateOf(Phase.LOBBY); private set
    var myName by mutableStateOf(""); private set
    var partnerName by mutableStateOf(""); private set
    /** Phones nearby that share photos: endpoint id to the owner's name. */
    val nearby = mutableStateMapOf<String, String>()
    /** Something to tell the user once (a toast-like line). */
    var notice by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false); private set

    private var code = ""
    private var searching = false
    private var joinCode: String? = null

    // The deck, the same on both phones. The guest's shots point at the received previews.
    var deck by mutableStateOf<List<List<Shot>>>(emptyList()); private set
    var preparing by mutableStateOf(0f); private set
    var session by mutableStateOf(DuoSession(emptyList())); private set
    /** Bumped whenever the votes change, so screens re-read [session]. */
    var tick by mutableIntStateOf(0); private set
    /** Bumped on every match ("you both kept this one"). */
    var matchTick by mutableIntStateOf(0); private set
    private var matchCount = 0
    /** Guest: how many cards from the top have their previews. */
    var ready by mutableIntStateOf(0); private set
    var debateItem by mutableStateOf<Int?>(null); private set
    /** Bumped on every coin flip in the Debate. */
    var coinTick by mutableIntStateOf(0); private set
    /** The last coin flip: whose call won (HOST or GUEST) and what it was. Cleared by the screen. */
    var coin by mutableStateOf<Pair<Int, Choice>?>(null)

    /** At the end: what the guest can have (host computes it, guest receives it). */
    var offer by mutableStateOf<Offer?>(null); private set
    /** The guest's answer; null until they choose. */
    var want by mutableStateOf<Want?>(null); private set
    private var bothShots = emptyList<Shot>()
    private var mineShots = emptyList<Shot>()
    private var sendJob: Job? = null

    /** The files going to the guest's phone: done / total. */
    var keepers by mutableStateOf(0 to 0); private set
    /** A file on its way (label, 0..1), for the big ones: the battle RAW, the app update. */
    var transfer by mutableStateOf<Pair<String, Float>?>(null); private set

    /** A newer Shootback the other phone sent over, ready to install. */
    var update by mutableStateOf<File?>(null); private set
    var partnerVersion by mutableStateOf(0L); private set

    // Edit battle.
    var battlePhoto by mutableStateOf<LibraryPhoto?>(null); private set
    var battleEdits by mutableStateOf(listOf<String?>(null, null)); private set
    var battleVotes by mutableStateOf(listOf<Int?>(null, null)); private set
    /** The other player has finished editing (their edit stays hidden until I'm done too). */
    var partnerEdited by mutableStateOf(false); private set
    var winner by mutableStateOf<Int?>(null); private set
    /** The winner came from a coin flip (they voted differently). */
    var flipped by mutableStateOf(false); private set
    /** Shown left/first: the host's edit when true. Each phone shuffles on its own. */
    var hostFirst by mutableStateOf(true); private set
    private var battleShot: Shot? = null
    /** Just a battle over one photo, started from the editor: no culling before or after. */
    var battleOnly by mutableStateOf(false); private set
    /** Just albums, started from an album: nothing to cull, the phones only swap albums ([AlbumSync]). */
    var albumsOnly by mutableStateOf(false); private set
    /** Host, battle only: the photo, waiting for the other phone to connect. */
    private var pendingBattle: Shot? = null

    val me get() = if (hosting) DuoSession.HOST else DuoSession.GUEST
    val partner get() = 1 - me

    fun battleStore(): EditStore = EditStore(app, "duo-battle")

    // ---- Starting and stopping ------------------------------------------------------------

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (myName.isEmpty()) myName = savedName()
    }

    /** Host: cull shots together. The deck follows with [dealDeck]. */
    fun host(context: Context) {
        init(context)
        if (active) return
        reset()
        active = true
        hosting = true
        code = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
        tagUri = "shootback://duo?code=$code"
    }

    /** Host: an edit battle over one photo. The photo itself follows with [chooseBattleShot]. */
    fun hostBattle(context: Context) {
        init(context)
        // A session already running stays as it is.
        if (active) return
        host(context)
        battleOnly = true
    }

    /** Host: swap shared albums with the other phone, nothing else; [invite] it into that album first. */
    fun hostAlbums(context: Context, invite: String?) {
        init(context)
        if (active) return
        host(context)
        albumsOnly = true
        AlbumSync.invite = invite
    }

    /** Host, battle only: the photo to fight over. The battle starts as soon as the other phone is here. */
    fun chooseBattleShot(shot: Shot) {
        if (!hosting || !battleOnly) return
        pendingBattle = shot
        if (connected && phase == Phase.LOBBY) startBattle(shot)
    }

    fun reportPreparing(fraction: Float) {
        preparing = fraction
    }

    /** Host: the cards, similar shots already stacked. */
    fun dealDeck(stacks: List<List<Shot>>) {
        if (!hosting) return
        deck = stacks
        session = DuoSession(stacks.map { it.size })
        preparing = 1f
        if (connected) {
            phase = Phase.CULL
            sendDeck()
            broadcast()
        }
    }

    /** Guest: look for a host, joining the one whose tag was read straight away. */
    fun join(context: Context, code: String?) {
        init(context)
        if (hosting && active) return
        if (!active) {
            reset()
            active = true
        }
        if (code != null) {
            joinCode = code
            nearby.entries.firstOrNull { it.value.endsWith("|$code") }?.let { connectTo(it.key) }
        }
    }

    /** Once permissions are sorted: advertise (host) and look for hosts (both). */
    fun startSearching() {
        if (!active || connected || searching) return
        searching = true
        val l = link ?: DuoLink(app, this).also { link = it }
        if (hosting) l.advertise("$myName|$code")
        l.discover()
    }

    fun connectTo(endpointId: String) {
        link?.connect(endpointId, myName)
    }

    fun leave() {
        val l = link
        link = null
        if (connected && l != null) {
            l.send(JSONObject().put("t", "bye"))
            // Give the goodbye a moment to get out before hanging up.
            scope.launch { delay(500); l.close() }
        } else {
            l?.close()
        }
        reset()
    }

    private fun reset() {
        previewJob?.cancel()
        sendJob?.cancel()
        active = false; hosting = false; connected = false; reconnecting = false; ended = false; searching = false
        phase = Phase.LOBBY; partnerName = ""; nearby.clear(); notice = null
        code = ""; joinCode = null; tagUri = null
        deck = emptyList(); preparing = 0f; session = DuoSession(emptyList()); tick = 0; matchCount = 0
        ready = 0; debateItem = null; coinTick = 0; coin = null
        offer = null; want = null; bothShots = emptyList(); mineShots = emptyList(); keepers = 0 to 0; transfer = null; update = null; partnerVersion = 0
        resetBattle(); battleOnly = false; pendingBattle = null; albumsOnly = false
        AlbumSync.reset(); AlbumSync.invite = null
        if (::app.isInitialized) File(app.filesDir, "duo").deleteRecursively()
    }

    private fun resetBattle() {
        battlePhoto = null; battleShot = null; battleEdits = listOf(null, null); battleVotes = listOf(null, null)
        winner = null; flipped = false; partnerEdited = false
    }

    fun rename(name: String) {
        myName = name.trim().ifEmpty { myName }
        app.getSharedPreferences("duo", Context.MODE_PRIVATE).edit().putString("name", myName).apply()
        if (hosting && !connected) link?.let { it.stopSearching(); searching = false; startSearching() }
    }

    private fun savedName(): String {
        app.getSharedPreferences("duo", Context.MODE_PRIVATE).getString("name", null)?.let { return it }
        // "Anna's Pixel 7" -> "Anna"
        val device = AndroidSettings.Global.getString(app.contentResolver, AndroidSettings.Global.DEVICE_NAME) ?: ""
        return Regex("^(.+?)['’]s ").find(device)?.groupValues?.get(1) ?: "Me"
    }

    // ---- Playing ----------------------------------------------------------------------------

    /** My call on card [item] (null: take it back). */
    fun vote(item: Int, choice: Choice?) {
        val matched = session.vote(me, item, choice)
        tick++
        if (hosting) {
            if (matched) match()
            afterVotes()
        } else {
            link?.send(JSONObject().put("t", "vote").put("i", item).put("v", choice?.token ?: "-"))
        }
    }

    /** Reveal -> Debate (or straight to the end when there's nothing to talk about). */
    fun next() {
        if (!hosting) {
            link?.send(JSONObject().put("t", "go"))
            return
        }
        when (phase) {
            Phase.REVEAL -> {
                debateItem = session.nextDispute
                if (debateItem == null) finish() else phase = Phase.DEBATE
                broadcast()
            }
            Phase.BATTLE_RESULT -> {
                resetBattle()
                phase = if (battleOnly) Phase.BATTLE_OVER else Phase.DONE
                broadcast()
            }
            else -> {}
        }
    }

    /** Debate: my call on [item] for both of us. The card is settled once we both make the same one. */
    fun propose(item: Int, choice: Choice) {
        if (phase != Phase.DEBATE || item != debateItem) return
        session.propose(me, item, choice)
        tick++
        if (hosting) afterProposal() else link?.send(JSONObject().put("t", "propose").put("i", item).put("v", choice.token))
    }

    /** Debate, when we can't agree: a coin picks one of our two calls. */
    fun flipCoin(item: Int) {
        if (!hosting) {
            link?.send(JSONObject().put("t", "flip").put("i", item))
            return
        }
        if (phase != Phase.DEBATE || item != debateItem) return
        val winner = Random.nextInt(2)
        val c = session.flip(item, winner) ?: return
        coinTick++
        coin = winner to c
        tick++
        afterProposal()
    }

    private fun afterProposal() {
        val item = debateItem
        if (item != null && session.decision(item) != null) {
            debateItem = session.nextDispute
            if (debateItem == null) finish()
        }
        broadcast()
    }

    private fun match() {
        matchCount++
        matchTick++
    }

    private fun afterVotes() {
        if (phase == Phase.CULL && session.bothDone) phase = Phase.REVEAL
        broadcast()
    }

    /** End of culling: write the marks, then ask the guest whether they want the photos ([want]). */
    private fun finish() {
        phase = Phase.DONE
        val store = EditStore(app)
        val keep = mutableListOf<Shot>()
        val mine = mutableListOf<Shot>()
        deck.forEachIndexed { i, stack ->
            val o = session.outcome(i)
            val g = session.voteOf(DuoSession.GUEST, i)
            stack.forEachIndexed { j, shot ->
                val picked = o != null && o.vote == Choice.Vote.PICK && j == o.keep
                if (o != null) store.setMark(shot.key, if (picked) Mark.PICK else Mark.REJECT)
                if (picked) keep += shot
                else if (g != null && g.vote == Choice.Vote.PICK && g.keep == j) mine += shot
            }
        }
        bothShots = keep
        mineShots = mine
        scope.launch {
            offer = withContext(Dispatchers.IO) { Offer(bundle(keep), bundle(mine)) }
            broadcast()
        }
    }

    private fun bundle(shots: List<Shot>): Bundle = shots.fold(Bundle.EMPTY) { acc, shot ->
        val raw = shot.raw?.let { sizeOf(it) }
        val jpeg = shot.jpeg?.let { sizeOf(it) }
        acc + Bundle(
            1,
            jpeg = jpeg ?: RENDERED_JPEG_BYTES,
            raw = raw ?: jpeg ?: 0,
            all = (raw ?: 0) + (jpeg ?: RENDERED_JPEG_BYTES),
        )
    }

    private fun sizeOf(photo: LibraryPhoto): Long = runCatching {
        app.contentResolver.query(photo.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** Guest: my answer to "Want the photos?", remembered for next time. */
    fun choose(answer: Want) {
        if (hosting || phase != Phase.DONE) return
        want = answer
        app.getSharedPreferences("duo", Context.MODE_PRIVATE).edit()
            .putString("want", answer.format?.name ?: "NONE").putBoolean("want_mine", answer.alsoMine).apply()
        sendWant()
    }

    /** Guest: the answer I gave last time, as the starting point. */
    fun lastWant(): Want {
        val prefs = app.getSharedPreferences("duo", Context.MODE_PRIVATE)
        val format = prefs.getString("want", null)?.let { name -> Format.entries.firstOrNull { it.name == name } }
        return Want(format ?: Format.JPEG, prefs.getBoolean("want_mine", false))
    }

    private fun sendWant() {
        val w = want ?: return
        link?.send(JSONObject().put("t", "want").put("f", w.format?.name ?: "NONE").put("m", w.alsoMine))
    }

    /** Host: the guest answered. Send what they asked for, one file after another. */
    private fun onWant(answer: Want) {
        if (want != null) return
        want = answer
        val format = answer.format
        if (format != null) {
            val shots = bothShots + (if (answer.alsoMine) mineShots else emptyList())
            val files = shots.flatMap { filesFor(it, format) }
            keepers = 0 to files.size
            sendJob = scope.launch { for (f in files) sendKeeper(f) }
        }
        broadcast()
    }

    /** A file to send: one of the shot's own, or [render] a JPEG from its RAW. */
    private data class Outgoing(val photo: LibraryPhoto, val render: Boolean = false)

    private fun filesFor(shot: Shot, f: Format): List<Outgoing> {
        val jpeg = shot.jpeg?.let { Outgoing(it) } ?: Outgoing(shot.raw!!, render = true)
        return when (f) {
            Format.JPEG -> listOf(jpeg)
            Format.RAW -> listOf(Outgoing(shot.primary))
            Format.BOTH -> listOfNotNull(shot.raw?.let { Outgoing(it) }, jpeg)
        }
    }

    private suspend fun sendKeeper(f: Outgoing) {
        val photo = f.photo
        if (f.render) {
            // A RAW without a JPEG twin: develop it here, with my edit if I made one.
            val out = File(app.cacheDir, "duo-out/${photo.stem}.JPG")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    out.parentFile?.mkdirs()
                    val opened = Photos.open(app, photo)
                    val bmp = Photos.render(app, opened, EditStore(app).load(photo), ExportOptions())
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    bmp.recycle()
                    runCatching { Photos.copyExif(app, photo.uri, Uri.fromFile(out)) }
                }.isSuccess
            }
            if (ok) {
                link?.sendFile(
                    JSONObject().put("kind", "keeper").put("name", out.name).put("taken", photo.takenMillis)
                        .put("mime", "image/jpeg").put("tmp", out.path),
                    out,
                )
                return
            }
            // Couldn't develop it: the RAW is better than nothing.
        }
        link?.sendUri(
            JSONObject().put("kind", "keeper").put("name", photo.name).put("taken", photo.takenMillis).put("mime", photo.mime),
            photo.uri,
        )
    }

    // ---- Edit battle --------------------------------------------------------------------------

    /** Host: battle over [shot]. Its RAW (or JPEG) goes to the other phone; both start from scratch. */
    fun startBattle(shot: Shot) {
        if (!hosting) return
        resetBattle()
        battleShot = shot
        val photo = shot.primary
        battleStore().save(photo, Settings())
        battlePhoto = photo
        hostFirst = Random.nextBoolean()
        phase = Phase.BATTLE_EDIT
        link?.sendUri(
            JSONObject().put("kind", "battle").put("name", photo.name).put("taken", photo.takenMillis).put("mime", photo.mime),
            photo.uri,
        )
        broadcast()
    }

    /** Host, battle only: same photo again, fresh edits. */
    fun rematch() {
        val shot = pendingBattle ?: return
        if (hosting && phase == Phase.BATTLE_OVER) startBattle(shot)
    }

    fun pickBattle() {
        if (!hosting || phase != Phase.DONE) return
        phase = Phase.BATTLE_PICK
        broadcast()
    }

    /** I'm done editing: my settings go in (they stay hidden until both are done). */
    fun finishEdit() {
        val photo = battlePhoto ?: return
        val s = battleStore().load(photo).encode()
        if (hosting) {
            battleEdits = battleEdits.toMutableList().also { it[DuoSession.HOST] = s }
            checkEdits()
        } else {
            battleEdits = battleEdits.toMutableList().also { it[DuoSession.GUEST] = s }
            link?.send(JSONObject().put("t", "edit").put("s", s))
        }
    }

    private fun checkEdits() {
        if (phase == Phase.BATTLE_EDIT && battleEdits.all { it != null }) phase = Phase.BATTLE_VOTE
        broadcast()
    }

    /** My favourite: [owner] is whose edit (HOST or GUEST). */
    fun battleVote(owner: Int) {
        battleVotes = battleVotes.toMutableList().also { it[me] = owner }
        if (hosting) checkBattleVotes() else link?.send(JSONObject().put("t", "bvote").put("v", owner))
    }

    private fun checkBattleVotes() {
        val (a, b) = battleVotes
        if (phase == Phase.BATTLE_VOTE && a != null && b != null) {
            flipped = a != b
            winner = if (a == b) a else Random.nextInt(2)
            phase = Phase.BATTLE_RESULT
        }
        broadcast()
    }

    fun battleSettings(owner: Int): Settings? = battleEdits[owner]?.let { Settings.decode(it) }

    /** Host: make the winning edit the photo's own edit in the library. */
    fun useWinner() {
        val shot = battleShot ?: return
        val s = battleSettings(winner ?: return) ?: return
        EditStore(app).save(shot.primary, s)
        notice = "Saved as the edit of ${shot.primary.stem}"
    }

    // ---- Over the link --------------------------------------------------------------------------

    override fun onFound(endpointId: String, endpointName: String) {
        val name = endpointName.substringBeforeLast('|')
        val theirCode = endpointName.substringAfterLast('|', "")
        if (theirCode == code || name.isEmpty()) return
        nearby[endpointId] = endpointName
        if (joinCode != null && theirCode == joinCode && !connected) connectTo(endpointId)
    }

    override fun onLost(endpointId: String) {
        nearby.remove(endpointId)
    }

    override fun onConnected(endpointId: String, incoming: Boolean, endpointName: String) {
        connected = true
        searching = false
        reconnecting = false
        nearby.clear()
        if (!incoming && hosting) {
            // We joined someone else's photos: our own deck is put away.
            hosting = false
            tagUri = null
            deck = emptyList()
            session = DuoSession(emptyList())
            phase = Phase.LOBBY
        }
        if (!incoming) joinCode = endpointName.substringAfterLast('|', "").ifEmpty { null }
        partnerName = endpointName.substringBeforeLast('|')
        AlbumSync.reset()
        link?.send(
            JSONObject().put("t", "hello").put("name", myName).put("v", versionCode()).put("p", PROTOCOL)
                .put("pid", AlbumStore(app).phoneId),
        )
        if (hosting && deck.isNotEmpty()) {
            if (phase == Phase.LOBBY) phase = Phase.CULL
            sendDeck()
            broadcast()
        }
        pendingBattle?.let { if (hosting && phase == Phase.LOBBY) startBattle(it) }
        if (hosting && albumsOnly) broadcast()
    }

    override fun onDisconnected() {
        connected = false
        previewJob?.cancel()
        if (ended || !active) return
        reconnecting = true
        // Same code, so the guest finds us again on its own.
        startSearching()
    }

    override fun onError(text: String) {
        notice = text
    }

    override fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                partnerName = msg.optString("name", partnerName)
                partnerVersion = msg.optLong("v")
                AlbumSync.onHello(app, link, msg.optString("pid"), partnerName, myName)
                // Whoever has the newer app sends it over.
                if (versionCode() > partnerVersion) {
                    link?.sendFile(JSONObject().put("kind", "apk").put("v", versionCode()), File(app.applicationInfo.sourceDir))
                }
            }
            "bye" -> {
                ended = true
                connected = false
                notice = "$partnerName ended the session"
            }
            // Both sides: albums.
            "albums" -> AlbumSync.onAlbums(app, link, msg)
            "albumneed" -> AlbumSync.onNeed(app, link, msg)
            "albumcount" -> AlbumSync.onCount(msg)
            // Guest side.
            "deck" -> onDeck(msg)
            "state" -> onState(msg)
            // Host side.
            "need" -> {
                val arr = msg.getJSONArray("l")
                sendPreviews((0 until arr.length()).map { arr.getString(it) })
            }
            "vote" -> if (hosting) {
                val i = msg.getInt("i")
                if (session.vote(DuoSession.GUEST, i, Choice.parse(msg.getString("v")))) match()
                tick++
                afterVotes()
            }
            "go" -> if (hosting) next()
            "propose" -> if (hosting && phase == Phase.DEBATE && msg.getInt("i") == debateItem) {
                session.propose(DuoSession.GUEST, msg.getInt("i"), Choice.parse(msg.getString("v")))
                tick++
                afterProposal()
            }
            "flip" -> if (hosting) flipCoin(msg.getInt("i"))
            "want" -> if (hosting && phase.ordinal >= Phase.DONE.ordinal) {
                val format = Format.entries.firstOrNull { it.name == msg.optString("f") }
                onWant(Want(format, msg.optBoolean("m")))
            }
            "edit" -> if (hosting) {
                battleEdits = battleEdits.toMutableList().also { it[DuoSession.GUEST] = msg.getString("s") }
                partnerEdited = true
                checkEdits()
            }
            "bvote" -> if (hosting) {
                battleVotes = battleVotes.toMutableList().also { it[DuoSession.GUEST] = msg.getInt("v") }
                checkBattleVotes()
            }
        }
    }

    override fun onFile(meta: JSONObject, file: File) {
        when (meta.optString("kind")) {
            "preview" -> {
                val dest = previewFile(meta.getInt("i"), meta.getInt("j"))
                dest.parentFile?.mkdirs()
                if (!file.renameTo(dest)) file.delete()
                updateReady()
            }
            "keeper" -> scope.launch {
                withContext(Dispatchers.IO) { runCatching { saveKeeper(meta, file) }; file.delete() }
                keepers = (keepers.first + 1) to keepers.second.coerceAtLeast(keepers.first + 1)
            }
            "battle" -> {
                val dest = File(app.filesDir, "duo/battle/${meta.getString("name")}")
                dest.parentFile?.mkdirs()
                file.renameTo(dest)
                transfer = null
                val photo = LibraryPhoto(-7, Uri.fromFile(dest), meta.getString("name"), meta.getString("mime"), meta.getLong("taken"))
                battleStore().save(photo, Settings())
                battlePhoto = photo
            }
            "albumshot" -> AlbumSync.onFile(app, meta, file)
            "apk" -> {
                val dest = File(app.filesDir, "duo/update/Shootback.apk")
                dest.parentFile?.mkdirs()
                file.renameTo(dest)
                transfer = null
                update = dest
            }
            else -> file.delete()
        }
    }

    override fun onProgress(meta: JSONObject, fraction: Float, outgoing: Boolean) {
        when (meta.optString("kind")) {
            "preview" -> if (outgoing && fraction >= 1f) File(meta.optString("tmp")).delete()
            "albumshot" -> AlbumSync.onProgress(meta, fraction, outgoing)
            "keeper" -> if (outgoing && fraction >= 1f) {
                keepers = (keepers.first + 1) to keepers.second
                meta.optString("tmp").ifEmpty { null }?.let { File(it).delete() }
            }
            "battle" -> transfer = if (fraction >= 1f) null
            else (if (outgoing) "Sending the RAW to $partnerName" else "Getting the RAW from $partnerName") to fraction
            "apk" -> if (!outgoing) transfer = if (fraction < 1f) "Getting the new Shootback" to fraction else null
        }
    }

    // ---- Host: sending ------------------------------------------------------------------------

    private fun sendDeck() {
        val items = JSONArray()
        for (stack in deck) {
            items.put(JSONArray().apply { stack.forEach { s -> put(JSONObject().put("n", s.primary.stem).put("t", s.takenMillis)) } })
        }
        link?.send(JSONObject().put("t", "deck").put("code", code).put("items", items))
    }

    private fun sendPreviews(wanted: List<String>) {
        previewJob?.cancel()
        val out = File(app.cacheDir, "duo-out").apply { mkdirs() }
        previewJob = scope.launch {
            for (w in wanted) {
                val (i, j) = w.split('_').map { it.toInt() }
                val shot = deck.getOrNull(i)?.getOrNull(j) ?: continue
                val f = File(out, "${i}_$j.jpg")
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bmp = Photos.cullImage(app, shot, PREVIEW_EDGE) ?: return@runCatching false
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                        true
                    }.getOrDefault(false)
                }
                if (ok) link?.sendFile(JSONObject().put("kind", "preview").put("i", i).put("j", j).put("tmp", f.path), f)
            }
        }
    }

    private fun broadcast() {
        if (!hosting || !connected) return
        val msg = JSONObject()
            .put("t", "state")
            .put("phase", phase.name)
            .put("hv", session.encodeVotes(DuoSession.HOST))
            .put("dc", session.encodeDecisions())
            .put("hp", session.encodeProposals(DuoSession.HOST))
            .put("gp", session.encodeProposals(DuoSession.GUEST))
            .put("cn", coinTick)
            .put("mc", matchCount)
            .put("debate", debateItem ?: -1)
            .put("kt", keepers.second)
            .put("bo", battleOnly)
            .put("ao", albumsOnly)
        coin?.let { (w, c) -> msg.put("cw", w).put("cc", c.token) }
        offer?.let { msg.put("ob", it.both.toJson()).put("om", it.mine.toJson()) }
        want?.let { msg.put("w", it.format?.name ?: "NONE") }
        if (phase == Phase.BATTLE_VOTE || phase == Phase.BATTLE_RESULT) {
            msg.put("be", JSONArray(battleEdits.map { it ?: "" }))
        }
        if (phase == Phase.BATTLE_RESULT) {
            msg.put("bv", JSONArray(battleVotes.map { it ?: -1 })).put("win", winner ?: -1).put("flip", flipped)
        }
        if (phase == Phase.BATTLE_EDIT) msg.put("hd", battleEdits[DuoSession.HOST] != null)
        link?.send(msg)
    }

    // ---- Guest: receiving -----------------------------------------------------------------------

    private fun onDeck(msg: JSONObject) {
        val items = msg.getJSONArray("items")
        val stacks = (0 until items.length()).map { i ->
            val arr = items.getJSONArray(i)
            (0 until arr.length()).map { j ->
                val o = arr.getJSONObject(j)
                val photo = LibraryPhoto(-(i * 100L + j + 1), Uri.fromFile(previewFile(i, j)), o.getString("n") + ".JPG", "image/jpeg", o.getLong("t"))
                Shot(null, photo)
            }
        }
        // Same deck as before a dropped connection: keep my votes.
        if (stacks.map { it.size } != session.stackSizes) session = DuoSession(stacks.map { it.size })
        deck = stacks
        updateReady()
        val missing = JSONArray()
        stacks.forEachIndexed { i, s -> s.indices.forEach { j -> if (!previewFile(i, j).exists()) missing.put("${i}_$j") } }
        link?.send(JSONObject().put("t", "need").put("l", missing))
        // Votes the host may have missed while the link was down.
        for (i in 0 until session.size) session.voteOf(DuoSession.GUEST, i)?.let { c ->
            link?.send(JSONObject().put("t", "vote").put("i", i).put("v", c.token))
        }
    }

    private fun onState(msg: JSONObject) {
        val newPhase = runCatching { Phase.valueOf(msg.getString("phase")) }.getOrDefault(phase)
        battleOnly = msg.optBoolean("bo")
        albumsOnly = msg.optBoolean("ao")
        session.loadVotes(DuoSession.HOST, msg.optString("hv"))
        session.loadDecisions(msg.optString("dc"))
        session.loadProposals(DuoSession.HOST, msg.optString("hp"))
        // My own call may still be on its way to the host: keep it until the host has it.
        val hostHasMine = msg.optString("gp").split(',').getOrNull(msg.optInt("debate", -1))
        val mineHere = debateItem?.let { session.proposalOf(DuoSession.GUEST, it) }
        if (mineHere == null || hostHasMine == mineHere.token || msg.optInt("debate", -1) != debateItem) {
            session.loadProposals(DuoSession.GUEST, msg.optString("gp"))
        }
        tick++
        val cn = msg.optInt("cn")
        if (cn > coinTick) {
            val c = Choice.parse(msg.optString("cc"))
            if (c != null) coin = msg.optInt("cw") to c
        }
        coinTick = cn
        msg.optJSONArray("ob")?.let { offer = Offer(Bundle.fromJson(it), Bundle.fromJson(msg.optJSONArray("om"))) }
        // The host missed my answer (the link dropped): again.
        if (newPhase == Phase.DONE && want != null && !msg.has("w")) sendWant()
        val mc = msg.optInt("mc")
        if (mc > matchCount) matchTick++
        matchCount = mc
        debateItem = msg.optInt("debate", -1).takeIf { it >= 0 }
        keepers = keepers.first to msg.optInt("kt")
        if (newPhase == Phase.BATTLE_PICK || newPhase == Phase.DONE || newPhase == Phase.BATTLE_OVER) {
            if (phase.ordinal >= Phase.BATTLE_PICK.ordinal && newPhase != phase) resetBattle()
        }
        if (newPhase == Phase.BATTLE_EDIT && phase != Phase.BATTLE_EDIT) hostFirst = Random.nextBoolean()
        msg.optJSONArray("be")?.let { a -> battleEdits = (0 until 2).map { a.optString(it).ifEmpty { null } } }
        msg.optJSONArray("bv")?.let { a -> battleVotes = (0 until 2).map { a.optInt(it, -1).takeIf { v -> v >= 0 } } }
        if (msg.has("win")) winner = msg.getInt("win").takeIf { it >= 0 }
        flipped = msg.optBoolean("flip")
        partnerEdited = msg.optBoolean("hd")
        phase = newPhase
    }

    private fun previewFile(i: Int, j: Int) = File(app.filesDir, "duo/previews/${i}_$j.jpg")

    private fun updateReady() {
        var n = ready.coerceAtMost(deck.size)
        while (n < deck.size && deck[n].indices.all { previewFile(n, it).exists() }) n++
        ready = n
    }

    /** A keeper lands in the guest's Photos, like a shot from their own camera would. */
    private fun saveKeeper(meta: JSONObject, file: File) =
        saveToLibrary(app, meta.getString("name"), meta.getString("mime"), meta.getLong("taken"), file)

    private fun versionCode(): Long = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
}
