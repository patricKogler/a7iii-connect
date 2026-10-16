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

/**
 * Albums over Together, whatever else the two phones are doing. As soon as they connect each
 * sends its albums (a few kilobytes) and puts the other's together with its own ([Albums.merge]),
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun reset() {
        merged = false; albumCount = 0; shotCount = 0
        getting = 0 to 0; asked = false; sending = 0 to 0
    }

    /** Connected: my albums go over. */
    fun start(app: Context, link: DuoLink?) {
        reset()
        scope.launch {
            val text = withContext(Dispatchers.IO) { Albums.encode(AlbumStore(app).load()) }
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
                    Albums.rekey(Albums.decode(msg.optString("a")), resolve),
                )
                store.save(all)
                val alive = all.filter { it.alive }
                val wanted = alive.flatMap { it.keys }.toSet()
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
            if (need.isNotEmpty()) {
                asked = true
                link?.send(JSONObject().put("t", "albumneed").put("l", JSONArray(need)))
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
                val edits = EditStore(app)
                val first = mutableListOf<Pair<JSONObject, Uri>>()
                val later = mutableListOf<Pair<JSONObject, Uri>>()
                val list = msg.optJSONArray("l") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val shot = resolve(o.optString("k"))?.let { byKey[it] } ?: continue
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
        }
    }

    fun onProgress(meta: JSONObject, fraction: Float, outgoing: Boolean) {
        if (outgoing && fraction >= 1f) sending = (sending.first + 1) to sending.second
    }
}

/** A photo from the other phone lands in Photos, like a shot from this phone's own camera would. */
internal fun saveToLibrary(app: Context, name: String, mime: String, taken: Long, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}")
        put(MediaStore.Images.Media.DATE_TAKEN, taken)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = app.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
}
