package dev.kogler.a7iiiconnect.duo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.RecentPhotos
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.EditStore
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.LibraryPhoto
import dev.kogler.a7iiiconnect.develop.Shot
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Shared albums over Together. An album only ever goes to
 * the phones its owner invited ([Album.members]), and only shots in it are served. As soon as the
 * phones know who's who ("hello"), each sends the albums it shares with the other (a few
 * kilobytes) and puts the other's together with its own ([Albums.merge]),
 * so both have the same albums at once. Then each asks for the album shots it hasn't got: every
 * missing shot comes as a JPEG first, so the albums are whole quickly, and the RAWs follow. What
 * doesn't make it before the phones part is asked for again next time.
 */
object AlbumSync {
    /** The other phone's albums have been put together with mine. */
    var merged by mutableStateOf(false); private set
    /** Album shots on this phone, after merging. */
    var albumCount by mutableStateOf(0); private set
    var shotCount by mutableStateOf(0); private set
    /** Files coming to this phone: done / total. [asked] until the other phone says how many. */
    var getting by mutableStateOf(0 to 0); private set
    var asked by mutableStateOf(false); private set
    /** Files going to the other phone: done / total. */
    var sending by mutableStateOf(0 to 0); private set

    val busy get() = asked || getting.first < getting.second || sending.first < sending.second
    val inSync get() = merged && !busy

    /** The album, by id, whose owner asked to invite the phone that connects next. */
    var invite: String? = null
    /** The other phone's id (from its hello); nothing is shared before it's known. */
    private var partner = ""
    /** Shots already asked for on this connection, so albums sent again don't ask twice. */
    private val requested = HashSet<String>()
    /** Bumped whenever albums or album shots change here, so screens look again. */
    var changes by mutableStateOf(0); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun reset() {
        merged = false; albumCount = 0; shotCount = 0
        getting = 0 to 0; asked = false; sending = 0 to 0
        partner = ""
        requested.clear()
    }

    /**
     * The other phone said who it is: if I was inviting it into an album, it's a member now, and
     * it's called what it's called now everywhere. Then the albums I share with it go over. An app too old to say who it is gets nothing.
     */
    fun onHello(app: Context, link: DuoLink?, phone: String, name: String, myName: String) {
        if (phone.isEmpty()) return
        partner = phone
        val inviting = invite
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val store = AlbumStore(app)
                val stored = store.load()
                var all = stored
                if (inviting != null) {
                    all = all.map { if (it.id == inviting && it.ownedBy(store.phoneId)) Albums.invite(it, store.phoneId, myName, phone, name) else it }
                }
                // Names as they are now: theirs as it just said, mine as I last set it.
                all = Albums.named(Albums.named(all, phone, name), store.phoneId, myName)
                if (all != stored) store.save(all)
                withContext(Dispatchers.Main) { changes++ }
                Albums.encode(Albums.sharedWith(all, phone))
            }
            link?.send(JSONObject().put("t", "albums").put("a", text))
            // Then what was done together in them: votes, battles, seals.
            Duo.sendAllFacts()
        }
    }

    /** My albums changed while connected: the ones I share with the other phone go over again. */
    fun resend(app: Context, link: DuoLink?) {
        val phone = partner.ifEmpty { return }
        scope.launch {
            val text = withContext(Dispatchers.IO) { Albums.encode(Albums.sharedWith(AlbumStore(app).load(), phone)) }
            link?.send(JSONObject().put("t", "albums").put("a", text))
        }
    }

    /** Their albums: merge them into mine, then ask for the shots I'm missing. */
    fun onAlbums(app: Context, link: DuoLink?, msg: JSONObject) {
        scope.launch {
            val (counts, need) = withContext(Dispatchers.IO) {
                val shots = Library.shots(Library.load(app))
                val resolve = Albums.resolver(shots.map { it.key })
                val store = AlbumStore(app)
                val all = Albums.merge(
                    Albums.rekey(store.load(), resolve),
                    // Shared with me, so mine to share back (unless I stop sharing one).
                    Albums.rekey(Albums.decode(msg.optString("a")).map { it.copy(shared = true) }, resolve),
                )
                store.save(all)
                val alive = Albums.sharedWith(all, partner).filter { it.alive }
                val wanted = Albums.sharedKeys(all, partner)
                // Shots I haven't got at all, or have only one file of (the other phone may have both).
                val byKey = shots.associateBy { it.key }
                (alive.size to wanted.size) to wanted.mapNotNull { k ->
                    val shot = byKey[k]
                    when {
                        shot == null -> JSONObject().put("k", k).put("has", "")
                        shot.raw == null || shot.jpeg == null -> JSONObject().put("k", k).put("has", if (shot.raw != null) "r" else "j")
                        else -> null
                    }
                }
            }
            albumCount = counts.first
            shotCount = counts.second
            merged = true
            changes++
            val fresh = need.filter { requested.add(it.getString("k")) }
            if (fresh.isNotEmpty()) {
                asked = true
                link?.send(JSONObject().put("t", "albumneed").put("l", JSONArray(fresh)))
            }
        }
    }

    /** They're missing these album shots: JPEGs (with my edits) first, then the RAWs. */
    fun onNeed(app: Context, link: DuoLink?, msg: JSONObject) {
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                val shots = Library.shots(Library.load(app))
                val resolve = Albums.resolver(shots.map { it.key })
                val byKey = shots.associateBy { it.key }
                // Only shots in albums I share with this phone, whatever is asked for.
                val allowed = Albums.sharedKeys(Albums.rekey(AlbumStore(app).load(), resolve), partner)
                val edits = EditStore(app)
                val first = mutableListOf<Pair<JSONObject, Uri>>()
                val later = mutableListOf<Pair<JSONObject, Uri>>()
                val list = msg.optJSONArray("l") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val shot = resolve(o.optString("k"))?.takeIf { it in allowed }?.let { byKey[it] } ?: continue
                    val has = o.optString("has")
                    // My edit rides along with the shot's first file, so it arrives with the picture.
                    val edit = edits.load(shot.primary).takeIf { !it.isDefault }?.encode()
                    val jpeg = shot.jpeg?.takeIf { has != "j" }
                    val raw = shot.raw?.takeIf { has != "r" }
                    if (jpeg != null) first += meta(shot, jpeg, edit, has) to jpeg.uri
                    if (raw != null) {
                        // A RAW-only shot has nothing smaller to send first.
                        val m = meta(shot, raw, edit.takeIf { jpeg == null }, has) to raw.uri
                        if (jpeg == null && has.isEmpty()) first += m else later += m
                    }
                }
                first + later
            }
            link?.send(JSONObject().put("t", "albumcount").put("n", files.size))
            sending = 0 to files.size
            for ((meta, uri) in files) link?.sendUri(meta, uri)
        }
    }

    private fun meta(shot: Shot, photo: LibraryPhoto, edit: String?, has: String) = JSONObject()
        .put("kind", "albumshot").put("name", photo.name).put("taken", photo.takenMillis).put("mime", photo.mime)
        .apply {
            // A shot the other phone is new to gets my edit, under the names it will have there.
            if (edit != null && has.isEmpty()) put("edit", edit).put("names", JSONArray(shot.files.map { it.name }))
        }

    fun onCount(msg: JSONObject) {
        asked = false
        getting = getting.first to getting.first + msg.optInt("n")
    }

    /** An album shot landed: into Photos, with the other phone's edit if it came along. */
    fun onFile(app: Context, meta: JSONObject, file: File) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val taken = meta.getLong("taken")
                    saveToLibrary(app, meta.getString("name"), meta.getString("mime"), taken, file)
                    meta.optString("edit").ifEmpty { null }?.let { text ->
                        val store = EditStore(app)
                        val settings = Settings.decode(text)
                        val names = meta.optJSONArray("names") ?: JSONArray()
                        for (i in 0 until names.length()) {
                            // Under each file's key: the JPEG shows it now, the RAW once it's here.
                            val p = LibraryPhoto(-1, Uri.EMPTY, names.getString(i), meta.getString("mime"), taken)
                            if (store.load(p).isDefault) store.save(p, settings)
                        }
                    }
                }
                file.delete()
            }
            getting = (getting.first + 1) to getting.second.coerceAtLeast(getting.first + 1)
            changes++
        }
    }

    fun onProgress(meta: JSONObject, fraction: Float, outgoing: Boolean) {
        if (outgoing && fraction >= 1f) sending = (sending.first + 1) to sending.second
    }
}

/**
 * A photo from the other phone lands in Photos, like a shot from this phone's own camera would,
 * unless it's already there (the same file name, taken around the same time).
 */
internal fun saveToLibrary(app: Context, name: String, mime: String, taken: Long, file: File) {
    val resolver = app.contentResolver
    val folder = "${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}"
    val there = runCatching {
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$folder%"), null,
        )?.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.any { abs(it - taken) <= Albums.MATCH_WINDOW_MS } }
    }.getOrNull() == true
    if (there) return
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, folder)
        put(MediaStore.Images.Media.DATE_TAKEN, taken)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    // Publishing has MediaStore read the capture time from the file, and without a time zone in it
    // (the A7III writes none) it guesses one from when the file arrived, hours off. The other
    // phone's time goes back on, so both phones know the shot by the same key.
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.DATE_TAKEN, taken) }, null, null)
}
