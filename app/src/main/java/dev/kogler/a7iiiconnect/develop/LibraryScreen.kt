package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.Image
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

private enum class Filter(val label: String) {
    ALL("All"), TO_REVIEW("To review"), PICKS("Picks"), EDITED("Edited"), REJECTED("Rejected")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    shots: List<Shot>,
    edited: Set<String>,
    flags: Map<String, Flag>,
    exported: Set<String>,
    onOpen: (Shot) -> Unit,
    onCull: (List<Shot>) -> Unit,
    onDeleteRejected: () -> Unit,
    onBack: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val shown = when (filter) {
        Filter.ALL -> shots
        Filter.TO_REVIEW -> shots.filter { it.key !in flags }
        Filter.PICKS -> shots.filter { flags[it.key] == Flag.PICK }
        Filter.EDITED -> shots.filter { it.key in edited }
        Filter.REJECTED -> shots.filter { flags[it.key] == Flag.REJECT }
    }
    val unreviewed = shots.filter { it.key !in flags }
    val rejected = shots.count { flags[it.key] == Flag.REJECT }
    val days = remember(shown) { shown.groupBy { dayOf(it.takenMillis) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (unreviewed.isNotEmpty()) {
                        TextButton(onClick = { onCull(cullOrder(unreviewed)) }, modifier = Modifier.testTag("review-all")) {
                            Icon(Icons.Rounded.Swipe, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Review ${unreviewed.size}")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in Filter.entries) {
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            shots.isEmpty() -> "Photos from the camera will show up here"
                            filter == Filter.TO_REVIEW -> "All reviewed"
                            else -> "Nothing here"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            val toReview = dayShots.filter { it.key !in flags }
                            DayHeader(day, dayShots.size, toReview.size) { onCull(cullOrder(toReview)) }
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
                                flag = flags[shot.key],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: Long, count: Int, toReview: Int, onReview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dayLabel(day), style = MaterialTheme.typography.titleSmall)
            Text(
                if (toReview > 0) "$count shots · $toReview to review" else "$count shots",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (toReview > 0) TextButton(onClick = onReview) { Text("Review") }
    }
}

/** Start of the local day, as millis. */
private fun dayOf(millis: Long): Long = Calendar.getInstance().apply {
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

/** Culling order: newest day first, and within a day in the order the shots were taken. */
private fun cullOrder(shots: List<Shot>): List<Shot> =
    shots.groupBy { dayOf(it.takenMillis) }.toSortedMap(compareByDescending { it })
        .values.flatMap { day -> day.sortedWith(compareBy<Shot> { it.takenMillis }.thenBy { it.primary.name }) }

@Composable
fun Thumb(
    photo: LibraryPhoto,
    modifier: Modifier,
    label: String? = null,
    edited: Boolean = false,
    exported: Boolean = false,
    flag: Flag? = null,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(Photos.cachedThumbnail(photo)?.asImageBitmap(), photo.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Photos.thumbnail(context, photo)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let {
            Image(
                it, photo.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (flag == Flag.REJECT) 0.35f else 1f),
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
        when (flag) {
            Flag.PICK -> Badge(Icons.Rounded.Flag, "Picked", Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color(0xFF3DDC84))
            Flag.REJECT -> Badge(Icons.Rounded.Close, "Rejected", Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color(0xFFFF5A5F))
            null -> {}
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
