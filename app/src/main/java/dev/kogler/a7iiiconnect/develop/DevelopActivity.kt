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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
    /** MediaStore id of the photo open in the editor (either file of the shot). */
    private var editing by mutableStateOf<Long?>(null)
    /** The culling round and the keys of the shots dealt into it, while the deck is open. */
    private var culling by mutableStateOf<Pair<Int, List<String>>?>(null)
    private var deleting = emptyList<Shot>()
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
        GpsService.resumeIfEnabled(this)
        editing = savedInstanceState?.getLong(KEY_EDITING, -1L)?.takeIf { it >= 0 }
            ?: intent.getLongExtra(EXTRA_PHOTO_ID, -1L).takeIf { it >= 0 }
        culling = savedInstanceState?.getStringArrayList(KEY_CULLING)?.let { savedInstanceState.getInt(KEY_ROUND, 1) to it }
        if (savedInstanceState == null) CameraTouch.handleIntent(this, intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            AppTheme {
                val current = editing
                val index = shots.indexOfFirst { current != null && it.contains(current) }
                val deck = culling
                when {
                    current != null && index >= 0 -> {
                        BackHandler { closeEditor() }
                        EditorScreen(
                            photos = shots.map { it.primary },
                            startIndex = index,
                            store = store,
                            onBack = { closeEditor() },
                            onPhotoChanged = { editing = it.id },
                        )
                    }
                    deck != null -> {
                        BackHandler { culling = null }
                        val byKey = shots.associateBy { it.key }
                        CullScreen(
                            shots = deck.second.mapNotNull { byKey[it] },
                            round = deck.first,
                            marks = marks,
                            onMark = { shot, mark -> store.setMark(shot.key, mark); marks = store.marks() },
                            rejectedCount = shots.count { marks[it.key]?.rejected == true },
                            onDeleteRejected = { deleteRejected() },
                            onBack = { culling = null },
                        )
                    }
                    else -> LibraryScreen(
                        shots = shots,
                        edited = edited,
                        marks = marks,
                        exported = exported,
                        onOpen = { editing = it.primary.id },
                        onCull = { round, deck -> culling = round to deck.map { it.key } },
                        onTogether = { round, deck -> startActivity(DuoActivity.intent(this, round, deck.map { it.key })) },
                        onDeleteRejected = { deleteRejected() },
                        transfer = transfer,
                        onCamera = { startActivity(MainActivity.intent(this)) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        CameraTouch.handleIntent(this, intent)
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
        culling?.let { (round, keys) ->
            outState.putInt(KEY_ROUND, round)
            outState.putStringArrayList(KEY_CULLING, ArrayList(keys))
        }
    }

    private suspend fun reload() {
        val photos = withContext(Dispatchers.IO) { Library.load(this@DevelopActivity) }
        val all = Library.shots(photos)
        withContext(Dispatchers.IO) {
            store.migrate(photos)
            edited = store.editedKeys(all.map { it.primary })
            marks = store.marks()
            exported = Library.exportedNames(this@DevelopActivity)
        }
        shots = all
        loaded = true
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "photo_id"
        private const val KEY_EDITING = "editing"
        private const val KEY_CULLING = "culling"
        private const val KEY_ROUND = "round"

        fun intent(context: Context, photoId: Long? = null) = Intent(context, DevelopActivity::class.java).apply {
            if (photoId != null) putExtra(EXTRA_PHOTO_ID, photoId)
        }
    }
}
