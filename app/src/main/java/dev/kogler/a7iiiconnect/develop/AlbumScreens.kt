package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** "Add to album": the albums to choose from, newest first, or a new one. */
@Composable
fun AlbumPicker(
    albums: List<Album>,
    count: Int,
    onPick: (Album) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Add to album" else "Add $count shots to album") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    AlbumRow(Icons.Rounded.Add, "New album", null, Modifier.clickable(onClick = onNew).testTag("album-new"))
                    HorizontalDivider()
                }
                items(albums, key = { it.id }) { a ->
                    AlbumRow(Icons.Rounded.PhotoAlbum, a.name, if (a.size == 1) "1 shot" else "${a.size} shots", Modifier.clickable { onPick(a) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlbumRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String?, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Naming an album, new or renamed. [offer] shots on screen can come along into a new album
 * (ticked when [offerByDefault]: a filter, a day or a selection narrowed them down).
 */
@Composable
fun AlbumNameDialog(
    title: String,
    initial: String,
    confirm: String,
    offer: Int = 0,
    offerByDefault: Boolean = false,
    onDone: (name: String, withShots: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var withShots by remember { mutableStateOf(offerByDefault) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Lisbon 2026") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("album-name"),
                )
                if (offer > 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { withShots = !withShots },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withShots, onCheckedChange = { withShots = it })
                        Text(if (offer == 1) "Start with the shot shown" else "Start with the $offer shots shown")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(name, withShots && offer > 0) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("album-confirm")) {
                Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Getting an album ready to share: edits are rendered to JPEGs first. */
@Composable
fun AlbumShareProgress(done: Int, total: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Getting photos ready") },
        text = {
            Column {
                Text("$done of $total")
                Spacer(Modifier.size(12.dp))
                LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        },
        confirmButton = {},
    )
}

/**
 * The album full screen, one shot after another: edited shots as developed, the rest as the
 * camera's JPEG. Swipe to move on yourself; tap pauses and resumes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumSlideshow(
    name: String,
    shots: List<Shot>,
    edited: Set<String>,
    store: EditStore,
    onBack: () -> Unit,
) {
    val pager = rememberPagerState { shots.size }
    var playing by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(playing, pager.currentPage, shots.size) {
        if (!playing || shots.size < 2) return@LaunchedEffect
        delay(SLIDE_MS)
        pager.animateScrollToPage((pager.currentPage + 1) % shots.size)
    }
    LaunchedEffect(chrome, playing) {
        // The controls fade away while it plays.
        if (chrome && playing) { delay(2500); chrome = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("slideshow")) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { shots[it].key }) { page ->
            val shot = shots[page]
            Box(Modifier.fillMaxSize().clickable { playing = !playing; chrome = true }, contentAlignment = Alignment.Center) {
                val image = rememberSlide(shot, shot.key in edited, store)
                if (image != null) Image(image, shot.primary.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
            }
        }
        if (chrome || !playing) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("${pager.currentPage + 1} of ${shots.size}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { playing = !playing; chrome = true }, modifier = Modifier.testTag("slideshow-play")) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                }
            }
            if (!playing) {
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Paused · tap to play", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A slide: the edit developed at screen size, or the camera's own picture. */
@Composable
private fun rememberSlide(shot: Shot, edited: Boolean, store: EditStore): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, shot.key, edited) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (edited) {
                    val opened = Photos.open(context, shot.primary)
                    Photos.renderPreview(opened, store.load(shot.primary), SLIDE_EDGE)
                } else {
                    Photos.cullImage(context, shot, SLIDE_EDGE)
                }
            }.getOrNull()
                // An edit that can't be developed still shows, as the camera took it.
                ?: if (edited) runCatching { Photos.cullImage(context, shot, SLIDE_EDGE) }.getOrNull() else null
        }?.asImageBitmap()
    }
    return image
}

private const val SLIDE_MS = 4000L
private const val SLIDE_EDGE = 2048
