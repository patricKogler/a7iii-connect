package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import dev.kogler.a7iiiconnect.Phase
import dev.kogler.a7iiiconnect.SyncStatus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A copy from the camera in progress; tapping it opens the Camera screen. */
@Composable
private fun TransferStrip(status: SyncStatus, onClick: () -> Unit) {
    val text = when (status.phase) {
        Phase.SEARCHING -> "Looking for the camera"
        Phase.READING -> "Checking which photos are new"
        Phase.COPYING -> if (status.total > 0) "Copying ${status.done} of ${status.total}" else "Copying"
        else -> status.message
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).testTag("transfer"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
            if (status.phase == Phase.COPYING && status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private enum class Filter(val label: String) {
    ALL("All"), TO_REVIEW("To review"), PICKS("Picks"), BEST("Best"), EDITED("Edited"), REJECTED("Rejected")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    shots: List<Shot>,
    edited: Set<String>,
    marks: Map<String, Mark>,
    exported: Set<String>,
    onOpen: (Shot) -> Unit,
    onCull: (round: Int, shots: List<Shot>) -> Unit,
    onTogether: (round: Int, shots: List<Shot>) -> Unit,
    onDeleteRejected: () -> Unit,
    transfer: SyncStatus,
    onCamera: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    // Days picked by long-pressing their headers; Review then covers just those.
    var selectedDays by remember { mutableStateOf(emptySet<Long>()) }
    val bestLevel = shots.maxOfOrNull { marks[it.key]?.level ?: 0 } ?: 0
    val shown = when (filter) {
        Filter.ALL -> shots
        Filter.TO_REVIEW -> shots.filter { it.key !in marks }
        Filter.PICKS -> shots.filter { marks[it.key]?.picked == true }
        Filter.BEST -> if (bestLevel < 1) emptyList() else shots.filter { marks[it.key]?.level == bestLevel }
        Filter.EDITED -> shots.filter { it.key in edited }
        Filter.REJECTED -> shots.filter { marks[it.key]?.rejected == true }
    }
    val rejected = shots.count { marks[it.key]?.rejected == true }
    val days = shown.groupBy { dayOf(it.takenMillis) }
    val scope = if (selectedDays.isEmpty()) shots else shots.filter { dayOf(it.takenMillis) in selectedDays }
    // Every round that still has shots to show, with how many.
    val rounds = (1..(bestLevel + 1).coerceAtLeast(1)).mapNotNull { r ->
        scope.filter { marks[it.key].dueIn(r) }.takeIf { it.isNotEmpty() }?.let { r to it }
    }
    var roundMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedDays.isEmpty()) "Photos" else "${selectedDays.size} ${if (selectedDays.size == 1) "day" else "days"} selected") },
                navigationIcon = {
                    if (selectedDays.isNotEmpty()) {
                        IconButton(onClick = { selectedDays = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    }
                },
                actions = {
                    // Together: the first round still open, for the selected days or else the newest
                    // day with shots in it. Nothing to review: join someone else's photos.
                    IconButton(onClick = {
                        val (r, due) = rounds.firstOrNull() ?: (1 to emptyList())
                        val newest = due.maxOfOrNull { dayOf(it.takenMillis) }
                        onTogether(r, if (selectedDays.isNotEmpty()) due else due.filter { dayOf(it.takenMillis) == newest })
                    }) { Icon(Icons.Rounded.People, "Together") }
                    if (rounds.isNotEmpty()) {
                        Box {
                            TextButton(
                                onClick = { if (rounds.size == 1) onCull(rounds[0].first, rounds[0].second) else roundMenu = true },
                                modifier = Modifier.testTag("review"),
                            ) {
                                Icon(Icons.Rounded.Swipe, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (rounds.size == 1) "Review ${rounds[0].second.size}" else "Review")
                            }
                            DropdownMenu(expanded = roundMenu, onDismissRequest = { roundMenu = false }) {
                                for ((r, due) in rounds) {
                                    DropdownMenuItem(
                                        text = { Text(if (r == 1) "Round 1 · ${due.size} to sort" else "Round $r · ${due.size} picks") },
                                        onClick = { roundMenu = false; onCull(r, due) },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onCamera, modifier = Modifier.testTag("camera")) {
                        Icon(Icons.Rounded.PhotoCamera, "Camera")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (transfer.running) TransferStrip(transfer, onCamera)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in Filter.entries) {
                    if (f == Filter.BEST && bestLevel < 2) continue
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (filter == Filter.REJECTED && rejected > 0) {
                Button(
                    onClick = onDeleteRejected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Delete $rejected rejected (RAW and JPEG)") }
            }
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        when {
                            shots.isEmpty() -> "Photos from the camera will show up here"
                            filter == Filter.TO_REVIEW -> "All reviewed"
                            else -> "Nothing here"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (shots.isEmpty() && !transfer.running) {
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = onCamera) {
                            Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Get photos from the camera")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    for ((day, dayShots) in days) {
                        item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                            val toggle = { selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day }
                            DayHeader(
                                day, dayShots.size, dayShots.count { it.key !in marks },
                                selected = day in selectedDays,
                                modifier = Modifier.combinedClickable(
                                    onClick = { if (selectedDays.isNotEmpty()) toggle() },
                                    onLongClick = toggle,
                                ),
                            )
                        }
                        items(dayShots, key = { it.key }) { shot ->
                            // The JPEG twin's system thumbnail is cached and quick; it looks like the RAW's preview.
                            Thumb(
                                shot.jpeg ?: shot.primary,
                                Modifier.aspectRatio(1f).clickable { onOpen(shot) },
                                label = when {
                                    shot.raw != null && shot.jpeg != null -> "RAW+J"
                                    shot.raw != null -> "RAW"
                                    else -> null
                                },
                                edited = shot.key in edited,
                                exported = Photos.exportName(shot.primary).lowercase() in exported,
                                mark = marks[shot.key],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: Long, count: Int, toReview: Int, selected: Boolean, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(dayLabel(day), style = MaterialTheme.typography.titleSmall)
            Text(
                if (toReview > 0) "$count shots · $toReview to review" else "$count shots",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Start of the local day, as millis. */
internal fun dayOf(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(day: Long): String {
    val today = dayOf(System.currentTimeMillis())
    return when (day) {
        today -> "Today"
        dayOf(today - 12 * 3600_000L) -> "Yesterday"
        else -> {
            val sameYear = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.YEAR) ==
                Calendar.getInstance().get(Calendar.YEAR)
            SimpleDateFormat(if (sameYear) "EEEE, d MMMM" else "EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(day))
        }
    }
}

@Composable
fun Thumb(
    photo: LibraryPhoto,
    modifier: Modifier,
    label: String? = null,
    edited: Boolean = false,
    exported: Boolean = false,
    mark: Mark? = null,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(Photos.cachedThumbnail(photo)?.asImageBitmap(), photo.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Photos.thumbnail(context, photo)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let {
            Image(
                it, photo.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (mark?.rejected == true) 0.35f else 1f),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) {
                Text(
                    label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (edited) Badge(Icons.Rounded.Tune, "Edited")
            if (exported) Badge(Icons.Rounded.IosShare, "Exported")
        }
        when {
            mark == null -> {}
            mark.rejected -> Badge(Icons.Rounded.Close, "Rejected", Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color(0xFFFF5A5F))
            else -> Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Flag, "Picked", tint = Color(0xFF3DDC84), modifier = Modifier.size(14.dp))
                // Rounds survived beyond the first.
                if (mark.level > 1) Text("${mark.level}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3DDC84))
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Icon(
        icon, label, tint = tint,
        modifier = modifier.size(16.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(2.dp),
    )
}
