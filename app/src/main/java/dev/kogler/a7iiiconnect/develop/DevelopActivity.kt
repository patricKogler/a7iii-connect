package dev.kogler.a7iiiconnect.develop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.CameraTouch
import dev.kogler.a7iiiconnect.MainActivity
import dev.kogler.a7iiiconnect.SyncState
import dev.kogler.a7iiiconnect.SyncStatus
import dev.kogler.a7iiiconnect.ftp.FtpState
import dev.kogler.a7iiiconnect.ftp.FtpStatus
import dev.kogler.a7iiiconnect.gps.GpsService
import dev.kogler.a7iiiconnect.ui.AppTheme
import dev.kogler.a7iiiconnect.duo.DuoActivity
import dev.kogler.a7iiiconnect.usb.UsbImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photos, the app's start screen: a library of shots grouped by day, a swipe deck for culling,
 * and a Lightroom-style loupe editor. The camera button opens the Camera screen
 * ([MainActivity]); transfers show as a strip on top. Always dark, like Lightroom.
 */
class DevelopActivity : ComponentActivity() {

    private var shots by mutableStateOf(emptyList<Shot>())
    private var edited by mutableStateOf(emptySet<String>())
    private var marks by mutableStateOf(emptyMap<String, Mark>())
    private var exported by mutableStateOf(emptySet<String>())
    private var meta by mutableStateOf(emptyMap<String, ShotMeta>())
    private var places by mutableStateOf(emptyMap<String, String>())
    private var insights by mutableStateOf(emptyMap<String, ShotInsight>())
    private var people by mutableStateOf(emptyList<Person>())
    private var indexing: kotlinx.coroutines.Job? = null
    // Filters live here so they survive opening a photo and coming back.
    private var quickFilter by mutableStateOf(QuickFilter.ALL)
    private var sheetFilter by mutableStateOf(ShotFilter())
    private var sheetOpen by mutableStateOf(false)
    private var saved by mutableStateOf(emptyList<SavedFilter>())
    /** MediaStore id of the photo open in the editor (either file of the shot). */
    private var editing by mutableStateOf<Long?>(null)
    /** The deck's title and the keys of the shots dealt into it, while it is open. */
    private var culling by mutableStateOf<Pair<String, List<String>>?>(null)
    /** The keys of the shots picked in the library to compare head to head, while that's open. */
    private var comparing by mutableStateOf<List<String>?>(null)
    private var deleting = emptyList<Shot>()
    private lateinit var albumStore: AlbumStore
    /** Every album, deleted ones included (kept for comparing with another phone). */
    private var albums by mutableStateOf(emptyList<Album>())
    /** The album open in the library, by id. */
    private var openAlbum by mutableStateOf<String?>(null)
    private var albumDialog by mutableStateOf<AlbumDialog?>(null)
    /** The album playing as a slideshow, by id. */
    private var slideshow by mutableStateOf<String?>(null)
    /** The album the deck was dealt from: rejecting there takes shots out of it. */
    private var cullAlbum by mutableStateOf<String?>(null)
    /** What each shot taken out in the deck had in the album, for undo. */
    private val takenOut = HashMap<String, AlbumEntry?>()
    /** Shots done and in all while an album is got ready to share. */
    private var sharing by mutableStateOf<Pair<Int, Int>?>(null)
    private var loaded = false
    private lateinit var store: EditStore
    private var transfer by mutableStateOf(SyncStatus())
    private val transferListener: (SyncStatus) -> Unit = { s ->
        // New shots land as they're copied; show them in the library straight away.
        val landed = s.done != transfer.done || (transfer.running && !s.running)
        transfer = s
        if (landed) reloadUnlessEditing()
    }
    private var ftpReceived = -1
    private val ftpListener: (FtpStatus) -> Unit = { s ->
        if (ftpReceived >= 0 && s.received != ftpReceived) reloadUnlessEditing()
        ftpReceived = s.received
    }

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            store.forget(deleting)
            culling = null
            lifecycleScope.launch { reload() }
        }
        deleting = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        store = EditStore(this)
        albumStore = AlbumStore(this)
        albums = albumStore.load()
        openAlbum = savedInstanceState?.getString(KEY_ALBUM)
        slideshow = savedInstanceState?.getString(KEY_SLIDESHOW)
        cullAlbum = savedInstanceState?.getString(KEY_CULL_ALBUM)
        saved = SavedFilters.load(this)
        GpsService.resumeIfEnabled(this)
        editing = savedInstanceState?.getLong(KEY_EDITING, -1L)?.takeIf { it >= 0 }
            ?: intent.getLongExtra(EXTRA_PHOTO_ID, -1L).takeIf { it >= 0 }
        culling = savedInstanceState?.getStringArrayList(KEY_CULLING)?.let { (savedInstanceState.getString(KEY_DECK_TITLE) ?: "Review") to it }
        comparing = savedInstanceState?.getStringArrayList(KEY_COMPARING)
        if (savedInstanceState == null) handleIntent(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            AppTheme {
                val current = editing
                val index = shots.indexOfFirst { current != null && it.contains(current) }
                val deck = culling
                val compare = comparing
                val playing = slideshow?.let { id -> albums.find { it.id == id && it.alive } }
                val showAlbum = openAlbum?.let { id -> albums.find { it.id == id && it.alive } }
                when {
                    current != null && index >= 0 -> {
                        BackHandler { closeEditor() }
                        EditorScreen(
                            photos = shots.map { it.primary },
                            startIndex = index,
                            store = store,
                            onBack = { closeEditor() },
                            onPhotoChanged = { editing = it.id },
                            onBattle = { p -> shots.firstOrNull { it.contains(p.id) }?.let { startActivity(DuoActivity.battleIntent(this, it.key)) } },
                            onAddToAlbum = { p -> shots.firstOrNull { it.contains(p.id) }?.let { albumDialog = AlbumDialog.Pick(listOf(it.key)) } },
                        )
                    }
                    playing != null -> {
                        BackHandler { slideshow = null }
                        AlbumSlideshow(
                            name = playing.name,
                            shots = albumShots(playing),
                            edited = edited,
                            store = store,
                            onBack = { slideshow = null },
                        )
                    }
                    deck != null -> {
                        BackHandler { culling = null }
                        val byKey = shots.associateBy { it.key }
                        CullScreen(
                            shots = deck.second.mapNotNull { byKey[it] },
                            title = deck.first,
                            marks = marks,
                            onMark = { shot, mark -> store.setMark(shot.key, mark); marks = store.marks() },
                            rejectedCount = shots.count { marks[it.key]?.rejected == true },
                            onDeleteRejected = { deleteRejected() },
                            onBack = { culling = null },
                            onAddToAlbum = { picked -> albumDialog = AlbumDialog.Pick(picked.map { it.key }) },
                            takeOut = cullAlbum?.takeIf { id -> albums.any { it.id == id && it.alive } }?.let { id ->
                                { shot, out -> takeOut(id, shot.key, out) }
                            },
                        )
                    }
                    compare != null -> {
                        BackHandler { comparing = null }
                        val byKey = shots.associateBy { it.key }
                        CompareScreen(
                            shots = compare.mapNotNull { byKey[it] },
                            marks = marks,
                            onMark = { shot, mark -> store.setMark(shot.key, mark); marks = store.marks() },
                            onBack = { comparing = null },
                        )
                    }
                    else -> LibraryScreen(
                        shots = shots,
                        edited = edited,
                        marks = marks,
                        exported = exported,
                        onOpen = { editing = it.primary.id },
                        onCull = { title, deck -> cullAlbum = openAlbum; takenOut.clear(); culling = title to deck.map { it.key } },
                        onTogether = { deck -> startActivity(DuoActivity.intent(this, deck.map { it.key })) },
                        onDeleteRejected = { deleteRejected() },
                        transfer = transfer,
                        onCamera = { startActivity(MainActivity.intent(this)) },
                        meta = meta,
                        places = places,
                        insights = insights,
                        people = people,
                        onNamePerson = { ids, name ->
                            people = People.rename(people, ids, name)
                            val now = people
                            val found = insights
                            lifecycleScope.launch(Dispatchers.IO) {
                                synchronized(People) { People.save(this@DevelopActivity, now) }
                                regroup(found)
                            }
                        },
                        filter = quickFilter,
                        onQuickFilter = { quickFilter = it },
                        sheetFilter = sheetFilter,
                        onSheetFilter = { sheetFilter = it },
                        saved = saved,
                        onSaveFilter = { name ->
                            saved = saved.filter { it.name != name } + SavedFilter(name, sheetFilter)
                            SavedFilters.save(this, saved)
                        },
                        onDeleteSaved = { s ->
                            saved = saved - s
                            SavedFilters.save(this, saved)
                        },
                        sheetOpen = sheetOpen,
                        onSheetOpen = { sheetOpen = it },
                        onCompare = { picked -> comparing = picked.map { it.key } },
                        albums = albums.filter { it.alive }.sortedByDescending { it.createdAt },
                        album = showAlbum,
                        onOpenAlbum = { openAlbum = it?.id },
                        onAddToAlbum = { picked -> albumDialog = AlbumDialog.Pick(picked.map { it.key }) },
                        onNewAlbum = { shown, narrowed -> albumDialog = AlbumDialog.Create(shown.map { it.key }, offer = true, narrowed = narrowed) },
                        onRemoveFromAlbum = { picked ->
                            showAlbum?.let { a -> changeAlbum(a.id) { Albums.remove(it, picked.map { s -> s.key }, System.currentTimeMillis()) } }
                        },
                        me = albumStore.phoneId,
                        onAlbumAction = { action ->
                            val a = showAlbum
                            if (a != null) when (action) {
                                AlbumAction.SLIDESHOW -> slideshow = a.id
                                AlbumAction.SHARE -> shareAlbum(a)
                                // A private album is shared only once you say so.
                                AlbumAction.TOGETHER ->
                                    if (a.shared && a.members.size > 1) startActivity(DuoActivity.albumsIntent(this))
                                    else albumDialog = AlbumDialog.Share(a.id)
                                AlbumAction.INVITE -> albumDialog = AlbumDialog.Share(a.id)
                                AlbumAction.STOP_SHARING -> changeAlbum(a.id) { Albums.share(it, false) }
                                AlbumAction.RENAME -> albumDialog = AlbumDialog.Rename(a.id)
                                AlbumAction.DELETE -> albumDialog = AlbumDialog.Delete(a.id)
                            }
                        },
                    )
                }
                AlbumDialogs()
                sharing?.let { (done, total) -> AlbumShareProgress(done, total) }
            }
        }
    }

    /** The album's shots that are on this phone, oldest first, like a story. */
    private fun albumShots(album: Album): List<Shot> = shots.filter { it.key in album }.sortedBy { it.takenMillis }

    private fun changeAlbums(change: (List<Album>) -> List<Album>) {
        albums = change(albums)
        albumStore.save(albums)
    }

    private fun changeAlbum(id: String, change: (Album) -> Album) = changeAlbums { all -> all.map { if (it.id == id) change(it) else it } }

    /** A reject in an album's deck takes the shot out of that album only; undo puts it back where it was. */
    private fun takeOut(id: String, key: String, out: Boolean) = changeAlbum(id) { album ->
        if (out) {
            takenOut[key] = album.entries[key]
            Albums.remove(album, listOf(key), System.currentTimeMillis())
        } else if (key in takenOut) {
            Albums.restore(album, key, takenOut.remove(key))
        } else {
            // Undone after the app was away (and forgot its place): back in, at the end.
            Albums.add(album, listOf(key), System.currentTimeMillis())
        }
    }

    @androidx.compose.runtime.Composable
    private fun AlbumDialogs() {
        fun now() = System.currentTimeMillis()
        when (val d = albumDialog) {
            null -> {}
            is AlbumDialog.Pick -> AlbumPicker(
                albums = albums.filter { it.alive }.sortedByDescending { it.createdAt },
                count = d.keys.size,
                onPick = { a -> changeAlbum(a.id) { Albums.add(it, d.keys, now()) }; albumDialog = null },
                onNew = { albumDialog = AlbumDialog.Create(d.keys, offer = false, narrowed = false) },
                onDismiss = { albumDialog = null },
            )
            is AlbumDialog.Create -> AlbumNameDialog(
                title = "New album",
                initial = "",
                confirm = "Create",
                offer = if (d.offer) d.keys.size else 0,
                offerByDefault = d.narrowed,
                onDone = { name, withShots ->
                    val keys = if (!d.offer || withShots) d.keys else emptyList()
                    val album = Albums.create(name, keys, now(), owner = albumStore.phoneId)
                    changeAlbums { it + album }
                    albumDialog = null
                    // A new album opens straight away, unless it was made while adding from the editor or the deck.
                    if (d.offer) openAlbum = album.id
                },
                onDismiss = { albumDialog = null },
            )
            is AlbumDialog.Rename -> AlbumNameDialog(
                title = "Rename album",
                initial = albums.find { it.id == d.id }?.name ?: "",
                confirm = "Rename",
                onDone = { name, _ -> changeAlbum(d.id) { Albums.rename(it, name, now()) }; albumDialog = null },
                onDismiss = { albumDialog = null },
            )
            is AlbumDialog.Share -> {
                val album = albums.find { it.id == d.id }
                // The owner invites someone; a member who stopped sharing only starts again.
                val inviting = album?.ownedBy(albumStore.phoneId) == true
                AlertDialog(
                onDismissRequest = { albumDialog = null },
                title = { Text(if (inviting) "Invite someone into “${album?.name}”?" else "Share “${album?.name}” again?") },
                text = {
                    Text(
                        if (inviting) "Connect with their phone in Together. They get this album with its photos and your edits, " +
                            "and can add photos of their own, which you get too. Your other albums stay private."
                        else "Your changes and photos you add go to the others in it again.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        changeAlbum(d.id) { Albums.share(it, true) }
                        albumDialog = null
                        startActivity(DuoActivity.albumsIntent(this, invite = if (inviting) d.id else null))
                    }) { Text(if (inviting) "Invite" else "Share") }
                },
                dismissButton = { TextButton(onClick = { albumDialog = null }) { Text("Cancel") } },
                )
            }
            is AlbumDialog.Delete -> AlertDialog(
                onDismissRequest = { albumDialog = null },
                title = { Text("Delete “${albums.find { it.id == d.id }?.name}”?") },
                text = { Text("The photos stay in your library; only the album goes.") },
                confirmButton = {
                    TextButton(onClick = {
                        changeAlbum(d.id) { Albums.delete(it, now()) }
                        if (openAlbum == d.id) openAlbum = null
                        albumDialog = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { albumDialog = null }) { Text("Cancel") } },
            )
        }
    }

    /**
     * Shares the album's shots as JPEGs: edited ones developed and saved to the edits folder
     * first (replacing an earlier export), the rest as the camera's JPEG.
     */
    private fun shareAlbum(album: Album) {
        if (sharing != null) return
        val list = albumShots(album)
        if (list.isEmpty()) return
        sharing = 0 to list.size
        lifecycleScope.launch {
            val options = store.exportOptions
            val uris = ArrayList<android.net.Uri>()
            for ((i, shot) in list.withIndex()) {
                val uri = withContext(Dispatchers.Default) {
                    runCatching {
                        val jpeg = shot.jpeg
                        if (shot.key !in edited && jpeg != null) jpeg.uri
                        else Photos.export(this@DevelopActivity, Photos.open(this@DevelopActivity, shot.primary), store.load(shot.primary), options, store.versions(shot.primary).active) {}
                    }.getOrNull()
                }
                if (uri != null) uris += uri
                sharing = i + 1 to list.size
            }
            sharing = null
            if (uris.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share ${album.name}"))
            }
            reload()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Touching the camera's N mark, or plugging the camera in, starts a copy. */
    private fun handleIntent(intent: Intent?) {
        CameraTouch.handleIntent(this, intent)
        UsbImportService.attachedCamera(intent)?.let { UsbImportService.start(this, it) }
    }

    override fun onStart() {
        super.onStart()
        SyncState.observe(transferListener)
        FtpState.observe(ftpListener)
        // Photos may have arrived while we were in the background.
        reloadUnlessEditing()
    }

    override fun onStop() {
        SyncState.remove(transferListener)
        FtpState.remove(ftpListener)
        ftpReceived = -1
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        CameraTouch.startReading(this)
    }

    override fun onPause() {
        CameraTouch.stopReading(this)
        super.onPause()
    }

    /** The editor keeps its own place in the list, so new arrivals wait until it closes. */
    private fun reloadUnlessEditing() {
        if (editing == null || !loaded) lifecycleScope.launch { reload() }
    }

    private fun closeEditor() {
        editing = null
        lifecycleScope.launch { reload() }
    }

    /**
     * Moves every rejected shot (RAW and JPEG) to the system trash, after Android's one
     * confirmation for the whole batch. Trashed files can be restored for 30 days.
     */
    private fun deleteRejected() {
        val rejected = shots.filter { marks[it.key]?.rejected == true }
        if (rejected.isEmpty()) return
        deleting = rejected
        val uris = rejected.flatMap { shot -> shot.files.map { it.uri } }
        val pending = MediaStore.createTrashRequest(contentResolver, uris, true)
        deleteRequest.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_EDITING, editing ?: -1L)
        culling?.let { (title, keys) ->
            outState.putString(KEY_DECK_TITLE, title)
            outState.putStringArrayList(KEY_CULLING, ArrayList(keys))
        }
        comparing?.let { outState.putStringArrayList(KEY_COMPARING, ArrayList(it)) }
        openAlbum?.let { outState.putString(KEY_ALBUM, it) }
        slideshow?.let { outState.putString(KEY_SLIDESHOW, it) }
        cullAlbum?.let { outState.putString(KEY_CULL_ALBUM, it) }
    }

    private suspend fun reload() {
        val photos = withContext(Dispatchers.IO) { Library.load(this@DevelopActivity) }
        val all = Library.shots(photos)
        withContext(Dispatchers.IO) {
            store.migrate(photos)
            edited = store.editedKeys(all.map { it.primary })
            marks = store.marks()
            exported = Library.exportedNames(this@DevelopActivity)
            // Albums may have changed in Together; shots that came from the other phone get my keys.
            val stored = albumStore.load()
            val rekeyed = Albums.rekey(stored, Albums.resolver(all.map { it.key }))
            if (rekeyed != stored) albumStore.save(rekeyed)
            withContext(Dispatchers.Main) { albums = rekeyed }
        }
        shots = all
        loaded = true
        indexMeta(all)
    }

    /** Reads the EXIF of shots not indexed yet, in the background, so filters fill in as it goes. */
    private fun indexMeta(all: List<Shot>) {
        indexing?.cancel()
        indexing = lifecycleScope.launch(Dispatchers.IO) {
            val known = MetaIndex.known(this@DevelopActivity)
            val named = Places.known(this@DevelopActivity, known)
            withContext(Dispatchers.Main) { meta = known; places = named }
            MetaIndex.index(this@DevelopActivity, all, keepGoing = { isActive }) { m -> lifecycleScope.launch { meta = m } }
            // Then name the places, which needs the network; cells already named are skipped.
            val latest = MetaIndex.known(this@DevelopActivity)
            if (Places.name(this@DevelopActivity, latest, keepGoing = { isActive })) {
                val now = Places.known(this@DevelopActivity, latest)
                withContext(Dispatchers.Main) { places = now }
            }
            // Then look at the photos themselves: faces, focus, what's in them. Newest first; it
            // pauses while the editor is open or the app is in the background (coming back reloads).
            regroup(Insights.known(this@DevelopActivity))
            Insights.index(this@DevelopActivity, all, keepGoing = {
                isActive && editing == null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            }) { found -> regroup(found) }
        }
    }

    /** Shows [found] and groups its faces into people again (call off the main thread). */
    private fun regroup(found: Map<String, ShotInsight>) {
        val grouped = synchronized(People) {
            People.group(found, People.load(this)).also { People.save(this, it) }
        }
        runOnUiThread { insights = found; people = grouped }
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "photo_id"
        private const val KEY_EDITING = "editing"
        private const val KEY_CULLING = "culling"
        private const val KEY_DECK_TITLE = "deck_title"
        private const val KEY_COMPARING = "comparing"
        private const val KEY_ALBUM = "album"
        private const val KEY_SLIDESHOW = "slideshow"
        private const val KEY_CULL_ALBUM = "cull_album"

        fun intent(context: Context, photoId: Long? = null) = Intent(context, DevelopActivity::class.java).apply {
            if (photoId != null) putExtra(EXTRA_PHOTO_ID, photoId)
        }
    }
}

/** The album dialog open over Photos, the editor or the deck. */
private sealed interface AlbumDialog {
    /** Choosing an album for [keys]. */
    data class Pick(val keys: List<String>) : AlbumDialog
    /** Naming a new album: with [keys], or offering them ([offer]) ticked when [narrowed]. */
    data class Create(val keys: List<String>, val offer: Boolean, val narrowed: Boolean) : AlbumDialog
    data class Rename(val id: String) : AlbumDialog
    /** Asking before a private album is first shared in Together. */
    data class Share(val id: String) : AlbumDialog
    data class Delete(val id: String) : AlbumDialog
}
