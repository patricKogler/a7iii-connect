package dev.kogler.a7iiiconnect.develop

import android.content.Context
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
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photos: a library of shots grouped by day, a swipe deck for culling, and a Lightroom-style
 * loupe editor. Always dark, like Lightroom.
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
    private lateinit var store: EditStore

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            store.forget(deleting)
            culling = null
            lifecycleScope.launch { reload() }
        }
        deleting = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = EditStore(this)
        editing = savedInstanceState?.getLong(KEY_EDITING, -1L)?.takeIf { it >= 0 }
            ?: intent.getLongExtra(EXTRA_PHOTO_ID, -1L).takeIf { it >= 0 }
        culling = savedInstanceState?.getStringArrayList(KEY_CULLING)?.let { savedInstanceState.getInt(KEY_ROUND, 1) to it }

        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current)) {
                LaunchedEffect(Unit) { reload() }
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
                        onDeleteRejected = { deleteRejected() },
                        onBack = { finish() },
                    )
                }
            }
        }
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
