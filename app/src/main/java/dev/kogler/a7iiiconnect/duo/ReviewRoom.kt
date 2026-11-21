package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.SyncState
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.Photos
import dev.kogler.a7iiiconnect.develop.Shot
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.asKotlinRandom

/**
 * The review room: a shared album's review opened to browsers, so people without Shootback (an
 * iPhone, a laptop) can join it from a QR code. This phone serves the page and the photos
 * ([RoomApi] over [RoomHttp]); guests become members of the album and their votes are facts like
 * any phone's, so they reach the other phones the usual way.
 *
 * One room at a time. [RoomService] keeps it running with the screen off; Compose state here
 * is only changed on the main thread.
 */
object ReviewRoom {
    const val PORT = 47850

    /** The album whose room is open, or null. */
    var album by mutableStateOf<String?>(null); private set
    var port by mutableStateOf(PORT); private set
    var token by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)

    /** Guests join Shootback's own network ([CameraNetwork]) instead of the Wi-Fi this phone is on. */
    var ownNetwork by mutableStateOf(false); private set
    var networkUp by mutableStateOf(false); internal set
    var networkError by mutableStateOf<String?>(null); internal set

    private lateinit var app: Context
    private var http: RoomHttp? = null
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom().asKotlinRandom()

    /** Opens the room for [albumId] (closing another one first): starts [RoomService]. */
    fun open(context: Context, albumId: String) {
        app = context.applicationContext
        Duo.init(app)
        if (album != albumId) {
            stopServer()
            album = albumId
            token = RoomApi.newToken(random)
        }
        error = null
        RoomService.start(app)
    }

    /** Switches between the phone's Wi-Fi and Shootback's own network for the guests. */
    fun useOwnNetwork(context: Context, on: Boolean) {
        ownNetwork = on
        if (album != null) RoomService.start(context.applicationContext)
    }

    fun close(context: Context) {
        RoomService.stop(context)
        stopServer()
        album = null
    }

    fun link(address: String) = "http://$address:$port/?t=$token"

    /** Called by [RoomService]: listens, unless it already does. False when it couldn't. */
    internal fun startServer(context: Context): Boolean {
        app = context.applicationContext
        if (http != null) return true
        if (album == null) return false
        val server = RoomHttp(RoomApi(token, Host, random)::handle)
        return try {
            port = server.start(PORT)
            http = server
            SyncState.log("Review room: listening on port $port")
            true
        } catch (e: Exception) {
            error = "Couldn't open the room: ${e.message}"
            SyncState.log("Review room: can't start: $e")
            false
        }
    }

    internal fun stopServer() {
        http?.stop()
        http = null
        Host.forget()
    }

    /** Runs [block] on the main thread and waits for it (the stores and Compose state live there). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("The phone is busy")
        return result!!.getOrThrow()
    }

    private object Host : RoomHost {
        private val photos = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray) = value.size
        }
        @Volatile private var shots: Pair<Long, Map<String, Shot>>? = null
        @Volatile private var cached: Pair<Int, RoomAlbum?>? = null

        fun forget() {
            shots = null
            cached = null
            photos.evictAll()
        }

        /** The library, read again at most every few seconds: a long poll asks often. */
        private fun shots(): Map<String, Shot> {
            val now = System.currentTimeMillis()
            shots?.takeIf { now - it.first < 15_000 }?.let { return it.second }
            return Library.shots(Library.load(app)).associateBy { it.key }.also { shots = now to it }
        }

        override fun version(): Int = PlayStore.version * 7919 + AlbumSync.changes

        override fun album(): RoomAlbum? {
            val v = version()
            cached?.takeIf { it.first == v }?.let { return it.second }
            val id = album ?: return null
            val store = AlbumStore(app)
            val a = store.load().firstOrNull { it.id == id && it.alive }
            val made = a?.let {
                val me = store.phoneId
                val present = shots().keys
                RoomAlbum(it.id, it.name, me, it.members + (me to Duo.myName.ifEmpty { "Host" }), it.keys, it.keys.filter { k -> k in present }.toSet())
            }
            cached = v to made
            return made
        }

        override fun facts(): List<Fact> = PlayStore.facts(app)

        override fun add(facts: List<Fact>) = onMain { PlayStore.add(app, facts) }

        override fun join(guest: String, name: String) = onMain {
            val store = AlbumStore(app)
            val me = store.phoneId
            store.save(store.load().map { if (it.id == album) Albums.invite(it, me, Duo.myName, guest, name) else it })
            SyncState.log("Review room: $name joined")
            AlbumSync.touch()
            Duo.albumsChanged()
        }

        override fun removeFromAlbum(keys: List<String>, now: Long) {
            if (keys.isEmpty()) return
            onMain {
                val store = AlbumStore(app)
                store.save(store.load().map { if (it.id == album) Albums.remove(it, keys, now) else it })
                AlbumSync.touch()
                Duo.albumsChanged()
            }
        }

        override fun photo(key: String, edge: Int): ByteArray? {
            val id = "$key@$edge"
            photos.get(id)?.let { return it }
            val shot = shots()[key] ?: return null
            val bmp = Photos.cullImage(app, shot, edge) ?: return null
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 84, it) }.toByteArray()
            bmp.recycle()
            photos.put(id, bytes)
            return bytes
        }

        override fun page(): ByteArray = app.assets.open("room/index.html").use { it.readBytes() }
    }
}
