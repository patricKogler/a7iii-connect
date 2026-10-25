package dev.kogler.a7iiiconnect.develop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.kogler.a7iiiconnect.ui.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share to Shootback": photos shared from Google Photos, WhatsApp, Signal or Files land in the
 * library, and in an album if you pick one. Shows only a small dialog over the app you shared
 * from and goes back there when done. Nothing is copied until you choose.
 */
class ShareActivity : ComponentActivity() {
    private var step by mutableStateOf<Step>(Step.Pick)

    private sealed interface Step {
        data object Pick : Step
        data object Name : Step
        data class Copying(val done: Int, val total: Int) : Step
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val albumStore = AlbumStore(this)
        val albums = albumStore.load().filter { it.alive }.sortedByDescending { it.createdAt }
        val what = if (uris.size == 1) "photo" else "${uris.size} photos"
        setContent {
            AppTheme {
                when (val s = step) {
                    Step.Pick -> AlbumPicker(
                        albums = albums,
                        count = uris.size,
                        title = "Add $what to Shootback",
                        onLibraryOnly = { add(uris, albumStore, null, null) },
                        onPick = { add(uris, albumStore, it.id, null) },
                        onNew = { step = Step.Name },
                        onDismiss = { finish() },
                    )
                    Step.Name -> AlbumNameDialog(
                        title = "New album",
                        initial = "",
                        confirm = "Create",
                        onDone = { name, _ -> add(uris, albumStore, null, name) },
                        onDismiss = { step = Step.Pick },
                    )
                    is Step.Copying -> AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Adding $what") },
                        text = {
                            Column {
                                LinearProgressIndicator(
                                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        },
                        confirmButton = {},
                    )
                }
            }
        }
    }

    /** Copies the photos in, then puts them in album [id], or a new album called [newName]. */
    private fun add(uris: List<Uri>, albumStore: AlbumStore, id: String?, newName: String?) {
        if (step is Step.Copying) return
        step = Step.Copying(0, uris.size)
        val source = referrer?.takeIf { it.scheme == "android-app" }?.host ?: ""
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                SharedPhotos(this@ShareActivity).add(uris, source) { done -> runOnUiThread { step = Step.Copying(done, uris.size) } }
            }
            val keys = added.keys
            val now = System.currentTimeMillis()
            val albumName = withContext(Dispatchers.IO) {
                if (keys.isEmpty()) return@withContext null
                // Read again just before writing: Together or the library may have changed albums meanwhile.
                val all = albumStore.load()
                when {
                    newName != null -> Albums.create(newName, keys, now, owner = albumStore.phoneId).also { albumStore.save(all + it) }.name
                    id != null -> all.find { it.id == id }?.let { album ->
                        albumStore.save(all.map { if (it.id == id) Albums.add(it, keys, now) else it })
                        album.name
                    }
                    else -> null
                }
            }
            val ok = uris.size - added.failed
            val message = when {
                keys.isEmpty() -> "Couldn't add that"
                added.failed > 0 -> "Added $ok of ${uris.size}" + (albumName?.let { " to $it" } ?: " to your library")
                albumName != null -> "Added to $albumName"
                else -> if (ok == 1) "Added to your library" else "Added $ok photos to your library"
            }
            Toast.makeText(this@ShareActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** The images in a SEND or SEND_MULTIPLE, from its stream extra or else its clip. */
    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        if (out.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it } }
        return out.distinct()
    }
}
