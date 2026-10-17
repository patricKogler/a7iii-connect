package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Together: the link between this phone and another one in a shared album. While the Together
 * screen is open the phone can be found and looks for others; touching phones (or tapping a name)
 * connects them, and phones already sharing an album find each other on their own.
 *
 * Once connected the phones are equals. They swap their shared albums and the photos in them
 * ([AlbumSync]) and everything done together in those albums ([PlayStore]); while connected,
 * every vote and edit goes over the moment it's made, so it's live. Nothing needs the link,
 * though: what's done apart goes over the next time the phones meet.
 *
 * Lives as long as the app process, so a screen rotation doesn't drop the link. All state is
 * Compose state and only changes on the main thread.
 */
object Duo : DuoLink.Listener {
    /** Version 3: albums and facts, no host or guest. */
    private const val PROTOCOL = 3

    private lateinit var app: Context
    private var link: DuoLink? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What this phone's NFC "tag" says while Together is open; read by [DuoTag]. */
    @Volatile var tagUri: String? = null
        private set

    var active by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var reconnecting by mutableStateOf(false); private set
    var myName by mutableStateOf(""); private set
    var partnerName by mutableStateOf(""); private set
    /** The other phone's id (from its hello), empty until it said. */
    var partnerPhone by mutableStateOf(""); private set
    /** Phones nearby with Together open: endpoint id to their name. */
    val nearby = mutableStateMapOf<String, String>()
    /** Something to tell the user once (a toast-like line). */
    var notice by mutableStateOf<String?>(null)
    /** The other phone said goodbye. */
    var ended by mutableStateOf(false); private set

    private var code = ""
    private var searching = false
    private var joinCode: String? = null

    /** A file on its way (label, 0..1), for the big ones: the app update. */
    var transfer by mutableStateOf<Pair<String, Float>?>(null); private set
    /** A newer Shootback the other phone sent over, ready to install. */
    var update by mutableStateOf<File?>(null); private set
    var partnerVersion by mutableStateOf(0L); private set

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (myName.isEmpty()) myName = savedName()
    }

    /**
     * Together opens: be findable (with a fresh code for the NFC tag) and, with [join], connect to
     * the phone whose tag had that code. Already open, it only takes the new [join].
     */
    fun start(context: Context, join: String? = null) {
        init(context)
        if (!active) {
            reset()
            active = true
            code = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")
            tagUri = "shootback://duo?code=$code"
        }
        if (join != null && join != code && !connected) {
            joinCode = join
            nearby.keys.firstOrNull { codeOf(it) == join }?.let { connectTo(it) }
        }
    }

    /** Once permissions are sorted: be findable and look for others. */
    fun startSearching() {
        if (!active || connected || searching) return
        searching = true
        val l = link ?: DuoLink(app, this).also { link = it }
        l.advertise(endpointName())
        l.discover()
    }

    private fun endpointName() = "${myName.replace("|", " ")}|$code|${AlbumStore(app).phoneId}"

    fun connectTo(endpointId: String) {
        link?.connect(endpointId, endpointName())
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
        active = false; connected = false; reconnecting = false; ended = false; searching = false
        partnerName = ""; partnerPhone = ""; nearby.clear(); notice = null
        code = ""; joinCode = null; tagUri = null
        transfer = null; update = null; partnerVersion = 0
        AlbumSync.reset(); AlbumSync.invite = null
        rawNames.clear()
        if (::app.isInitialized) File(app.filesDir, "duo").deleteRecursively()
    }

    fun rename(name: String) {
        myName = name.trim().replace("|", " ").ifEmpty { myName }
        app.getSharedPreferences("duo", Context.MODE_PRIVATE).edit().putString("name", myName).apply()
        if (!connected) link?.let { it.stopSearching(); searching = false; startSearching() }
        // Connected: say hello again, so the other phone's albums take the new name now.
        else link?.send(hello())
    }

    private fun hello() = JSONObject().put("t", "hello").put("name", myName).put("v", versionCode()).put("p", PROTOCOL)
        .put("pid", AlbumStore(app).phoneId)

    private fun savedName(): String {
        app.getSharedPreferences("duo", Context.MODE_PRIVATE).getString("name", null)?.let { return it }
        // "Anna's Pixel 7" -> "Anna"
        val device = AndroidSettings.Global.getString(app.contentResolver, AndroidSettings.Global.DEVICE_NAME) ?: ""
        return Regex("^(.+?)['’]s ").find(device)?.groupValues?.get(1) ?: "Me"
    }

    // ---- Facts over the link -------------------------------------------------------------------

    /** Facts just made here: to the other phone, if it's connected and in their album. */
    fun sendFacts(facts: List<Fact>) {
        if (!connected || partnerPhone.isEmpty()) return
        val l = link ?: return
        scope.launch {
            val shared = withContext(Dispatchers.IO) { sharedAlbumIds() }
            val out = facts.filter { it.album in shared }
            if (out.isNotEmpty()) l.send(JSONObject().put("t", "play").put("f", Facts.encode(out)))
        }
    }

    /** Everything done together in the albums I share with the other phone (after the albums went over). */
    fun sendAllFacts() {
        val l = link ?: return
        if (partnerPhone.isEmpty()) return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val shared = sharedAlbumIds()
                Facts.encode(PlayStore.facts(app).filter { it.album in shared })
            }
            l.send(JSONObject().put("t", "play").put("f", text))
        }
    }

    private fun sharedAlbumIds(): Set<String> =
        Albums.sharedWith(AlbumStore(app).load(), partnerPhone).map { it.id }.toSet()

    /** Their facts: under my keys, only for albums we share (or I don't know yet), then kept. */
    private fun onFacts(text: String) {
        val from = partnerPhone
        scope.launch {
            val incoming = withContext(Dispatchers.IO) {
                val albums = AlbumStore(app).load().associateBy { it.id }
                val resolve = Albums.resolver(Library.shots(Library.load(app)).map { it.key })
                Facts.rekey(Facts.decode(text).filter { f -> albums[f.album]?.let { from in it.members } ?: true }, resolve)
            }
            PlayStore.receive(app, incoming)
        }
    }

    // ---- The link --------------------------------------------------------------------------------

    /** Endpoint id to the name it advertised ("name|code|phone"), so a tag's code can be matched. */
    private val rawNames = HashMap<String, String>()

    private fun codeOf(endpointId: String) = rawNames[endpointId]?.split('|')?.getOrNull(1)

    override fun onFound(endpointId: String, endpointName: String) {
        val p = endpointName.split('|')
        val name = p.getOrNull(0).orEmpty()
        val theirCode = p.getOrNull(1).orEmpty()
        val theirPhone = p.getOrNull(2).orEmpty()
        if (theirCode == code || name.isEmpty()) return
        rawNames[endpointId] = endpointName
        nearby[endpointId] = name
        if (connected) return
        if (joinCode != null && theirCode == joinCode) {
            connectTo(endpointId)
            return
        }
        // Someone I already share an album with: connect without a tap. Only one of the two
        // phones asks (the one with the smaller id), so they don't both ask at once.
        if (theirPhone.isNotEmpty()) scope.launch {
            val me = AlbumStore(app).phoneId
            val known = withContext(Dispatchers.IO) { Albums.sharedWith(AlbumStore(app).load(), theirPhone).any { it.alive } }
            if (known && me < theirPhone && !connected) connectTo(endpointId)
        }
    }

    override fun onLost(endpointId: String) {
        nearby.remove(endpointId)
    }

    override fun onConnected(endpointId: String, incoming: Boolean, endpointName: String) {
        connected = true
        searching = false
        reconnecting = false
        ended = false
        nearby.clear()
        // My tag stays as it was, so the other phone can tap again after a drop.
        partnerName = endpointName.substringBefore('|')
        AlbumSync.reset()
        link?.send(hello())
    }

    override fun onDisconnected() {
        connected = false
        if (ended || !active) return
        reconnecting = true
        startSearching()
    }

    override fun onError(text: String) {
        notice = text
    }

    override fun onMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "hello" -> {
                partnerName = msg.optString("name", partnerName)
                partnerPhone = msg.optString("pid")
                partnerVersion = msg.optLong("v")
                if (msg.optInt("p") < PROTOCOL) notice = "$partnerName needs the new Shootback for this. It's on its way."
                AlbumSync.onHello(app, link, partnerPhone, partnerName, myName)
                // Whoever has the newer app sends it over.
                if (versionCode() > partnerVersion) {
                    link?.sendFile(JSONObject().put("kind", "apk").put("v", versionCode()), File(app.applicationInfo.sourceDir))
                }
            }
            "bye" -> {
                ended = true
                connected = false
                notice = "$partnerName closed Together"
            }
            "albums" -> AlbumSync.onAlbums(app, link, msg)
            "albumneed" -> AlbumSync.onNeed(app, link, msg)
            "albumcount" -> AlbumSync.onCount(msg)
            "play" -> onFacts(msg.optString("f"))
        }
    }

    override fun onFile(meta: JSONObject, file: File) {
        when (meta.optString("kind")) {
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
            "albumshot" -> AlbumSync.onProgress(meta, fraction, outgoing)
            "apk" -> if (!outgoing) transfer = if (fraction < 1f) "Getting the new Shootback" to fraction else null
        }
    }

    /** The albums changed here (a round sealed took shots out): to the other phone now. */
    fun albumsChanged() {
        if (connected && partnerPhone.isNotEmpty()) AlbumSync.resend(app, link)
    }

    private fun versionCode(): Long = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
}
