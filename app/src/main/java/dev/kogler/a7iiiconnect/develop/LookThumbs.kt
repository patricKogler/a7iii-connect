package dev.kogler.a7iiiconnect.develop

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.engine.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small renders of the open photo with a look applied, for the Looks panel's tiles. One renders
 * at a time (each is a GPU pass), only for the tiles on screen, and the last [KEEP] are kept so
 * scrolling back is instant.
 */
internal class LookThumbs(private val opened: OpenedPhoto) {
    private val cache = object : LinkedHashMap<Settings, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Settings, ImageBitmap>?) = size > KEEP
    }
    private val turn = Mutex()

    fun cached(s: Settings): ImageBitmap? = synchronized(cache) { cache[s] }

    suspend fun get(s: Settings): ImageBitmap? {
        cached(s)?.let { return it }
        return turn.withLock {
            cached(s) ?: withContext(Dispatchers.Default) {
                runCatching { Photos.renderPreview(opened, s, EDGE).asImageBitmap() }.getOrNull()
            }?.also { synchronized(cache) { cache[s] = it } }
        }
    }

    private companion object {
        /** Longer edge of a render; tiles crop it square. */
        const val EDGE = 160
        const val KEEP = 120
    }
}

/**
 * One look: its picture and name, outlined when applied. [settings] is the photo with the look
 * on, rendered by [thumbs]; without a photo, [file] (a saved sample) stands in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LookTile(
    name: String, settings: Settings?, thumbs: LookThumbs?, on: Boolean,
    onClick: () -> Unit, onLong: () -> Unit = {},
    file: File? = null, size: Dp = 64.dp, modifier: Modifier = Modifier,
) {
    val picture by produceState(settings?.let { thumbs?.cached(it) }, settings, thumbs) {
        value = when {
            settings != null && thumbs != null -> thumbs.get(settings)
            file != null -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
            else -> null
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.width(size).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(
            Modifier.size(size).clip(shape).background(Color(0xFF1E1E1E))
                .then(if (on) Modifier.border(2.dp, primary, shape) else Modifier),
        ) {
            picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(
            name, color = if (on) primary else Color(0xFFE0E0E0), fontSize = 11.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A group of looks: its title and count, actions (See all, Import…), and the tiles in a strip that scrolls sideways. */
@Composable
internal fun LookRow(title: String, count: Int, actions: @Composable () -> Unit = {}, tiles: LazyListScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(" · $count", color = Color(0xFF6E6E6E), fontSize = 12.sp, modifier = Modifier.weight(1f))
        actions()
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp), content = tiles)
}

/** The small text button a [LookRow] header carries. */
@Composable
internal fun RowAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label, fontSize = 12.sp) }
}
