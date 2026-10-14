package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

/** The RAW editor: a library grid and a Lightroom-style loupe editor. Always dark, like Lightroom. */
class DevelopActivity : ComponentActivity() {

    private var photos by mutableStateOf(emptyList<LibraryPhoto>())
    private var edited by mutableStateOf(emptySet<Long>())
    private var editing by mutableStateOf<Long?>(null)
    private lateinit var store: EditStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = EditStore(this)
        editing = savedInstanceState?.getLong(KEY_EDITING, -1L)?.takeIf { it >= 0 }
            ?: intent.getLongExtra(EXTRA_PHOTO_ID, -1L).takeIf { it >= 0 }

        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current)) {
                LaunchedEffect(Unit) { reload() }
                val current = editing
                val index = photos.indexOfFirst { it.id == current }
                if (current != null && index >= 0) {
                    BackHandler { editing = null; edited = store.editedIds() }
                    EditorScreen(
                        photos = photos,
                        startIndex = index,
                        store = store,
                        onBack = { editing = null; edited = store.editedIds() },
                        onPhotoChanged = { editing = it.id },
                    )
                } else {
                    LibraryScreen(
                        photos = photos,
                        edited = edited,
                        onOpen = { editing = it.id },
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_EDITING, editing ?: -1L)
    }

    private suspend fun reload() {
        photos = withContext(Dispatchers.IO) { Library.load(this@DevelopActivity) }
        edited = withContext(Dispatchers.IO) { store.editedIds() }
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "photo_id"
        private const val KEY_EDITING = "editing"

        fun intent(context: Context, photoId: Long? = null) = Intent(context, DevelopActivity::class.java).apply {
            if (photoId != null) putExtra(EXTRA_PHOTO_ID, photoId)
        }
    }
}
