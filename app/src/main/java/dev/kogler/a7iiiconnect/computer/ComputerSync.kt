package dev.kogler.a7iiiconnect.computer

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import dev.kogler.a7iiiconnect.develop.AlbumStore
import dev.kogler.a7iiiconnect.develop.Albums
import dev.kogler.a7iiiconnect.develop.EditStore
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.LibraryPhoto
import dev.kogler.a7iiiconnect.develop.LutLibrary
import dev.kogler.a7iiiconnect.develop.Mark
import dev.kogler.a7iiiconnect.develop.engine.Settings
import dev.kogler.a7iiiconnect.duo.saveToLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Where syncing with the computer stands, for the Computer card and Photos. */
data class ComputerStatus(
    val running: Boolean = false,
    val text: String = "",
    val error: String? = null,
    /** Counts finished syncs that changed something here, so Photos knows to load again. */
    val landed: Int = 0,
)

/**
 * Syncs this phone with the paired computer, one run at a time on a thread of its own. The
 * phone leads: it asks the computer what it has, sends the photos the computer lacks, fetches
 * the ones [PhotoPolicy] asks for, then puts marks, edits and albums together both ways
 * ([SyncPlan]) and remembers what both agreed on for next time.
 */
object ComputerSync {
    private val _status = MutableStateFlow(ComputerStatus())
    val status: StateFlow<ComputerStatus> = _status

    @Volatile private var thread: Thread? = null
    @Volatile private var lastStart = 0L

    /** Whether a sync is running now. */
    val running: Boolean get() = thread?.isAlive == true

    /** Syncs now unless a sync is running. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (ComputerPrefs(app).computer == null) return
        synchronized(this) {
            if (thread?.isAlive == true) return
            lastStart = System.currentTimeMillis()
            thread = Thread({ run(app) }, "computer-sync").apply { start() }
        }
        // Keeps it going with the screen off; if Android won't allow that right now, it still
        // runs while the app is in front.
        runCatching { app.startForegroundService(Intent(app, ComputerSyncService::class.java)) }
            .onFailure { Log.w(TAG, "Couldn't start the sync service", it) }
    }

    /** Syncs when Photos opens, unless it did a moment ago or that's turned off. */
    fun startIfDue(context: Context) {
        val prefs = ComputerPrefs(context)
        if (prefs.auto && prefs.computer != null && System.currentTimeMillis() - lastStart > 2 * 60_000) start(context)
    }

    fun forget(context: Context) {
        val prefs = ComputerPrefs(context)
        prefs.computer?.let { baseFile(context, it.id).delete() }
        prefs.computer = null
        _status.value = ComputerStatus(landed = _status.value.landed)
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        _status.value = _status.value.copy(running = true, text = text, error = null)
    }

    private fun baseFile(context: Context, id: String) =
        File(File(context.filesDir, "develop").apply { mkdirs() }, "computer-${id.replace(Regex("[^A-Za-z0-9-]"), "_")}.txt")

    private fun run(app: Context) {
        val power = app.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shootback:computer-sync")
        @Suppress("DEPRECATION")
        val wifi = app.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "shootback:computer-sync")
        wake.acquire(30 * 60_000L)
        wifi.acquire()
        var changed = false
        try {
            changed = sync(app)
            _status.value = ComputerStatus(landed = _status.value.landed + if (changed) 1 else 0)
        } catch (e: Exception) {
            val prefs = ComputerPrefs(app)
            val message = when {
                e is HttpError && e.status == 401 -> "${prefs.computer?.name ?: "The computer"} doesn't know this phone any more. Pair again."
                e is IOException -> "Couldn't reach ${prefs.computer?.name ?: "the computer"}. Is Shootback open on it, on the same Wi-Fi?"
                else -> e.message ?: e.toString()
            }
            Log.w(TAG, "Sync failed", e)
            _status.value = ComputerStatus(error = message, landed = _status.value.landed + if (changed) 1 else 0)
        } finally {
            runCatching { wifi.release() }
            runCatching { wake.release() }
        }
    }

    /** One sync; answers whether anything on the phone changed. */
    private fun sync(app: Context): Boolean {
        val prefs = ComputerPrefs(app)
        var computer = prefs.computer ?: return false
        val albumStore = AlbumStore(app)
        val client = ComputerClient(computer, albumStore.phoneId, phoneName(albumStore))
        say("Looking for ${computer.name}")
        if (!client.reachable()) {
            val (host, port) = discover(app, computer.id) ?: throw IOException("not found")
            computer = computer.copy(host = host, port = port)
            prefs.computer = computer
            client.computer = computer
        }
        val baseFile = baseFile(app, computer.id)
        val base = runCatching { Base.decode(baseFile.readText()) }.getOrDefault(Base())
        var changed = false

        say("Comparing with ${computer.name}")
        var state = JSONObject(client.text("GET", "/v1/state"))
        var phonePhotos = Library.load(app)
        val ups = SyncPlan.toUpload(phonePhotos.map { it.file }, computerFiles(state), base)
        val byFile = phonePhotos.associateBy { it.file }
        ups.forEachIndexed { i, f ->
            val photo = byFile[f] ?: return@forEachIndexed
            say("Sending ${i + 1} of ${ups.size} to ${computer.name}")
            val size = runCatching {
                app.contentResolver.query(photo.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            }.getOrNull() ?: -1L
            app.contentResolver.openInputStream(photo.uri)?.use { client.upload(photo.name, photo.takenMillis, size, it) }
        }

        val downs = SyncPlan.toDownload(phonePhotos.map { it.file }, computerFiles(state), base, prefs.policy, System.currentTimeMillis())
        val tmp = File(app.cacheDir, "computer-sync").apply { mkdirs() }
        downs.forEachIndexed { i, f ->
            say("Fetching ${i + 1} of ${downs.size} from ${computer.name}")
            val part = File(tmp, "photo.part")
            try {
                client.download(f.key, part)
                saveToLibrary(app, f.name, if (f.isRaw) "image/x-sony-arw" else "image/jpeg", f.taken, part)
                changed = true
            } finally {
                part.delete()
            }
        }

        say("Putting marks and edits together")
        if (ups.isNotEmpty()) state = JSONObject(client.text("GET", "/v1/state"))
        if (downs.isNotEmpty()) phonePhotos = Library.load(app)
        val phoneFiles = phonePhotos.map { it.file }
        val compFiles = computerFiles(state)
        val phoneShots = SyncPlan.shotKeys(phoneFiles)
        val pairs = SyncPlan.pairs(phoneShots, SyncPlan.shotKeys(compFiles))

        val store = EditStore(app)
        val (markChanges, marks) = SyncPlan.marks(
            store.marks().mapValues { it.value.name }, strings(state.optJSONObject("marks")), base.marks, pairs,
        )
        if (markChanges.phone.isNotEmpty()) {
            store.putMarks(markChanges.phone.mapValues { Mark.parse(it.value) })
            changed = true
        }
        if (markChanges.computer.isNotEmpty()) {
            client.text("POST", "/v1/marks", markChanges.computer.entries.joinToString("") { (k, v) -> "$k\t${v ?: "-"}\n" })
        }

        val phoneEdits = pairs.keys.mapNotNull { k ->
            val (text, modified) = store.editOf(k)
            if (text.isEmpty()) null else k to SyncEdit(normalize(text), modified)
        }.toMap()
        val compEdits = HashMap<String, SyncEdit>()
        state.optJSONObject("edits")?.let { e ->
            for (k in e.keys()) e.optJSONObject(k)?.let { compEdits[k] = SyncEdit(normalize(it.optString("text")), it.optLong("modified")) }
        }
        val (editChanges, edits) = SyncPlan.edits(phoneEdits, compEdits, base.edits, pairs)
        for ((k, e) in editChanges.phone) {
            store.putEdit(k, e?.let { Settings.decode(it.text) } ?: Settings(), e?.modified ?: 0)
            changed = true
        }
        editChanges.computer.entries.forEachIndexed { i, (k, e) ->
            say("Sending edits, ${i + 1} of ${editChanges.computer.size}")
            client.text("PUT", "/v1/edit?key=${ComputerClient.enc(k)}", e?.text ?: "", if (e != null) mapOf("X-Modified" to e.modified.toString()) else emptyMap())
        }

        say("Putting albums together")
        val mine = albumStore.load()
        val merged = client.text("POST", "/v1/albums", Albums.encode(mine))
        val theirs = Albums.rekey(Albums.decode(merged), Albums.resolver(phoneShots))
        val together = Albums.merge(mine, theirs)
        if (together != mine) {
            albumStore.save(together)
            changed = true
        }

        // Presets and LUTs, when the computer's app is new enough to have them.
        var presetBase = base.presets
        var lutBase = base.luts
        if (state.has("presets") && state.has("luts")) {
            say("Putting presets and LUTs together")
            val (presetsChanged, agreedPresets) = syncPresets(client, store, state, base.presets)
            val (lutsChanged, agreedLuts) = syncLuts(app, client, state, base.luts)
            presetBase = agreedPresets
            lutBase = agreedLuts
            changed = changed || presetsChanged || lutsChanged
        }

        baseFile.writeText(Base(marks, edits, SyncPlan.known(phoneFiles, compFiles), presetBase, lutBase).encode())
        client.text("POST", "/v1/done")
        prefs.lastSync = System.currentTimeMillis()
        return changed
    }

    private fun syncPresets(client: ComputerClient, store: EditStore, state: JSONObject, base: Map<String, String>): Pair<Boolean, Map<String, String>> {
        val phone = store.presetFiles().mapValues { (_, v) -> SyncEdit(v.first.trim(), v.second) }
        val comp = HashMap<String, SyncEdit>()
        state.optJSONObject("presets")?.let { o ->
            for (k in o.keys()) o.optJSONObject(k)?.let { comp[k] = SyncEdit(it.optString("text").trim(), it.optLong("modified")) }
        }
        val (changes, agreed) = SyncPlan.presets(phone, comp, base)
        for ((name, e) in changes.phone) store.putPreset(name, e?.text?.plus("\n") ?: "", e?.modified ?: 0)
        for ((name, e) in changes.computer) {
            client.text("PUT", "/v1/preset?name=${ComputerClient.enc(name)}", e?.text?.plus("\n") ?: "", if (e != null) mapOf("X-Modified" to e.modified.toString()) else emptyMap())
        }
        return changes.phone.isNotEmpty() to agreed
    }

    private fun syncLuts(app: Context, client: ComputerClient, state: JSONObject, base: Set<String>): Pair<Boolean, Set<String>> {
        val mine = LutLibrary.ensure(app).associateBy { it.id }
        val theirs = HashMap<String, Pair<LutLibrary.Entry, Boolean>>()
        state.optJSONArray("luts")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                val e = LutLibrary.Entry(it.optString("id"), it.optString("name"), it.optString("folder"))
                if (e.id.isNotEmpty()) theirs[e.id] = e to it.optBoolean("thumbnail")
            }
        }
        val plan = SyncPlan.luts(mine.keys, theirs.keys, base)
        val failed = HashSet<String>()
        plan.download.forEachIndexed { i, id ->
            say("Fetching LUTs, ${i + 1} of ${plan.download.size}")
            val (e, hasThumb) = theirs.getValue(id)
            runCatching {
                val bytes = client.bytes("GET", "/v1/lut?id=${ComputerClient.enc(id)}")
                val thumb = if (hasThumb) runCatching { client.bytes("GET", "/v1/lut-thumb?id=${ComputerClient.enc(id)}") }.getOrNull() else null
                LutLibrary.addFromSync(app, e, bytes, thumb)
            }.onFailure { failed += id }
        }
        plan.upload.forEachIndexed { i, id ->
            say("Sending LUTs, ${i + 1} of ${plan.upload.size}")
            val e = mine.getValue(id)
            runCatching {
                val bytes = LutLibrary.bytes(app, id) ?: error("missing")
                client.bytes("PUT", "/v1/lut?id=${ComputerClient.enc(id)}&name=${ComputerClient.enc(e.name)}&folder=${ComputerClient.enc(e.folder)}", bytes)
                LutLibrary.thumbnail(app, id)?.let { t -> runCatching { client.bytes("PUT", "/v1/lut-thumb?id=${ComputerClient.enc(id)}", t.readBytes()) } }
            }.onFailure { failed += id }
        }
        for (id in plan.deleteHere) LutLibrary.delete(app, id)
        for (id in plan.deleteThere) client.bytes("PUT", "/v1/lut?id=${ComputerClient.enc(id)}", ByteArray(0))
        val changed = (plan.download - failed).isNotEmpty() || plan.deleteHere.isNotEmpty()
        return changed to (plan.agreed - failed)
    }

    private const val TAG = "ComputerSync"

    private val LibraryPhoto.file get() = SyncFile(name, takenMillis)

    private fun computerFiles(state: JSONObject): List<SyncFile> {
        val a = state.optJSONArray("photos") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { SyncFile(it.getString("name"), it.getLong("taken")) }
    }

    private fun strings(o: JSONObject?): Map<String, String> =
        o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

    /** An edit written the way this phone writes it, so the same edit compares equal from either side. */
    private fun normalize(text: String): String =
        runCatching { Settings.decode(text).let { if (it.isDefault) "" else it.encode() } }.getOrDefault(text)

    private fun phoneName(albums: AlbumStore): String =
        albums.myName.takeIf { it != "Me" }?.let { "$it's ${android.os.Build.MODEL}" } ?: android.os.Build.MODEL
}
