package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.InputChip
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/** What can be done with the open album from its top bar. */
enum class AlbumAction { SLIDESHOW, SHARE, TOGETHER, INVITE, STOP_SHARING, RENAME, DELETE }

enum class QuickFilter(val label: String) {
    ALL("All"), TO_REVIEW("To review"), PICKS("Picks"), EDITED("Edited"), REJECTED("Rejected")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    shots: List<Shot>,
    edited: Set<String>,
    marks: Map<String, Mark>,
    exported: Set<String>,
    onOpen: (Shot) -> Unit,
    onCull: (title: String, shots: List<Shot>) -> Unit,
    onTogether: (shots: List<Shot>) -> Unit,
    onDeleteRejected: () -> Unit,
    transfer: SyncStatus,
    onCamera: () -> Unit,
    meta: Map<String, ShotMeta> = emptyMap(),
    places: Map<String, String> = emptyMap(),
    /** What looking at each shot found, and the people in them. */
    insights: Map<String, ShotInsight> = emptyMap(),
    people: List<Person> = emptyList(),
    onNamePerson: (ids: Set<String>, name: String) -> Unit = { _, _ -> },
    filter: QuickFilter = QuickFilter.ALL,
    onQuickFilter: (QuickFilter) -> Unit = {},
    sheetFilter: ShotFilter = ShotFilter(),
    onSheetFilter: (ShotFilter) -> Unit = {},
    saved: List<SavedFilter> = emptyList(),
    onSaveFilter: (String) -> Unit = {},
    onDeleteSaved: (SavedFilter) -> Unit = {},
    sheetOpen: Boolean = false,
    onSheetOpen: (Boolean) -> Unit = {},
    onCompare: (List<Shot>) -> Unit = {},
    /** Albums to show as chips (not the deleted ones), and the one open, if any. */
    albums: List<Album> = emptyList(),
    album: Album? = null,
    onOpenAlbum: (Album?) -> Unit = {},
    onAddToAlbum: (List<Shot>) -> Unit = {},
    /** "New album": the shots on screen, and whether a filter or a day narrowed them down. */
    onNewAlbum: (shown: List<Shot>, narrowed: Boolean) -> Unit = { _, _ -> },
    onRemoveFromAlbum: (List<Shot>) -> Unit = {},
    onAlbumAction: (AlbumAction) -> Unit = {},
    /** This phone's id, to tell my albums from ones I was invited into. */
    me: String = "",
) {
    // Days picked by long-pressing their headers; Review then covers just those.
    var selectedDays by remember { mutableStateOf(emptySet<Long>()) }
    // Shots picked by long-pressing them, to compare head to head or put in an album.
    var selectedShots by remember { mutableStateOf(emptySet<String>()) }
    var albumMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = album != null) { onOpenAlbum(null) }
    BackHandler(enabled = selectedShots.isNotEmpty() || selectedDays.isNotEmpty()) {
        selectedShots = emptySet(); selectedDays = emptySet()
    }
    var searching by remember { mutableStateOf(false) }
    val facts = remember(shots, meta, places, edited, exported, insights, people) {
        val keys = HashMap<String, MutableSet<String>>()
        val names = HashMap<String, MutableList<String>>()
        for (p in people) for (f in p.faces) {
            keys.getOrPut(f.shot) { mutableSetOf() } += p.key
            p.name?.let { names.getOrPut(f.shot) { mutableListOf() } += it }
        }
        shots.map {
            ShotFacts(
                it, meta[it.key], it.key in edited, Photos.exportName(it.primary).lowercase() in exported, places[it.key],
                insights[it.key], keys[it.key].orEmpty(), names[it.key].orEmpty(),
            )
        }
    }
    fun personName(key: String) = people.find { it.key == key }?.name ?: "Someone"
    // The sheet's metadata filter narrows everything on screen, Review included.
    val matching = if (sheetFilter.isEmpty) shots else facts.filter { sheetFilter.matches(it) }.map { it.shot }
    // An open album shows just its shots; the filters still narrow them down.
    val filtered = if (album == null) matching else matching.filter { it.key in album }
    val shown = when (filter) {
        QuickFilter.ALL -> filtered
        QuickFilter.TO_REVIEW -> filtered.filter { it.key !in marks }
        QuickFilter.PICKS -> filtered.filter { marks[it.key]?.picked == true }
        QuickFilter.EDITED -> filtered.filter { it.key in edited }
        QuickFilter.REJECTED -> filtered.filter { marks[it.key]?.rejected == true }
    }
    val rejected = shots.count { marks[it.key]?.rejected == true }
    val days = shown.groupBy { dayOf(it.takenMillis) }
    val scope = if (selectedDays.isEmpty()) shown else shown.filter { dayOf(it.takenMillis) in selectedDays }
    // Review deals what's on screen: the shots still to review, or on Picks (or Rejected) those
    // shots again, to narrow them down (or rescue some).
    val revisit = filter == QuickFilter.PICKS || filter == QuickFilter.REJECTED
    val due = if (revisit) scope else scope.filter { it.key !in marks }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val others = album?.members?.filterKeys { it != me }?.values.orEmpty()
                    if (album != null && selectedShots.isEmpty() && selectedDays.isEmpty() && others.isNotEmpty()) {
                        Column {
                            Text(album.name, maxLines = 1)
                            Text(
                                (if (album.ownedBy(me)) "With ${others.joinToString(", ")}" else "${album.members[album.owner] ?: "Their"}'s album") +
                                    (if (album.shared) "" else " · not sharing"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    } else Text(
                        when {
                            selectedShots.isNotEmpty() -> "${selectedShots.size} selected"
                            selectedDays.isNotEmpty() -> "${selectedDays.size} ${if (selectedDays.size == 1) "day" else "days"} selected"
                            album != null -> album.name
                            else -> "Photos"
                        },
                    )
                },
                navigationIcon = {
                    if (selectedDays.isNotEmpty() || selectedShots.isNotEmpty()) {
                        IconButton(onClick = { selectedDays = emptySet(); selectedShots = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    } else if (album != null) {
                        IconButton(onClick = { onOpenAlbum(null) }, modifier = Modifier.testTag("album-close")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "All photos")
                        }
                    }
                },
                actions = {
                    if (selectedShots.isNotEmpty() || selectedDays.isNotEmpty()) {
                        // The selected shots, or every shot on screen from the selected days.
                        val picked = if (selectedShots.isNotEmpty()) shots.filter { it.key in selectedShots } else scope
                        if (album != null) {
                            IconButton(
                                onClick = { onRemoveFromAlbum(picked); selectedShots = emptySet(); selectedDays = emptySet() },
                                modifier = Modifier.testTag("album-remove"),
                            ) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove from album") }
                        }
                        IconButton(
                            onClick = { onAddToAlbum(picked); selectedShots = emptySet(); selectedDays = emptySet() },
                            modifier = Modifier.testTag("album-add"),
                        ) { Icon(Icons.Rounded.LibraryAdd, "Add to album") }
                    }
                    if (selectedShots.isNotEmpty()) {
                        // Selected shots in the order they were taken, for a fair head to head.
                        TextButton(
                            onClick = {
                                onCompare(shots.filter { it.key in selectedShots }.sortedBy { it.takenMillis })
                                selectedShots = emptySet()
                            },
                            enabled = selectedShots.size >= 2,
                            modifier = Modifier.testTag("compare"),
                        ) {
                            Icon(Icons.Rounded.Compare, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (selectedShots.size >= 2) "Compare" else "Pick one more")
                        }
                    } else if (album != null) {
                        // Room is short next to the album's name: Review is just its icon here.
                        if (due.isNotEmpty()) {
                            IconButton(onClick = { onCull(album.name, due) }, modifier = Modifier.testTag("review")) {
                                Icon(Icons.Rounded.Swipe, "Review ${due.size}")
                            }
                        }
                        IconButton(onClick = { onAlbumAction(AlbumAction.TOGETHER) }, modifier = Modifier.testTag("album-together")) {
                            Icon(Icons.Rounded.People, if (album.shared && album.members.size > 1) "Swap with ${album.members.filterKeys { it != me }.values.joinToString(", ")}" else "Share this album")
                        }
                        IconButton(onClick = { onAlbumAction(AlbumAction.SLIDESHOW) }, enabled = shown.isNotEmpty(), modifier = Modifier.testTag("album-slideshow")) {
                            Icon(Icons.Rounded.PlayArrow, "Slideshow")
                        }
                        IconButton(onClick = { onAlbumAction(AlbumAction.SHARE) }, enabled = shown.isNotEmpty(), modifier = Modifier.testTag("album-share")) {
                            Icon(Icons.Rounded.IosShare, "Share album")
                        }
                        Box {
                            IconButton(onClick = { albumMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                                if (album.shared && album.members.size > 1 && album.ownedBy(me)) {
                                    DropdownMenuItem(text = { Text("Invite someone else…") }, onClick = { albumMenu = false; onAlbumAction(AlbumAction.INVITE) })
                                }
                                if (album.shared && album.members.size > 1) {
                                    DropdownMenuItem(text = { Text("Stop sharing") }, onClick = { albumMenu = false; onAlbumAction(AlbumAction.STOP_SHARING) })
                                }
                                DropdownMenuItem(text = { Text("Rename album") }, onClick = { albumMenu = false; onAlbumAction(AlbumAction.RENAME) })
                                DropdownMenuItem(text = { Text("Delete album") }, onClick = { albumMenu = false; onAlbumAction(AlbumAction.DELETE) })
                            }
                        }
                    } else {
                        // Together: the same shots as Review, for the selected days or else the newest
                        // day with shots in it. Nothing to review: join someone else's photos.
                        IconButton(onClick = {
                            val newest = due.maxOfOrNull { dayOf(it.takenMillis) }
                            onTogether(if (selectedDays.isNotEmpty()) due else due.filter { dayOf(it.takenMillis) == newest })
                        }) { Icon(Icons.Rounded.People, "Together") }
                        if (due.isNotEmpty()) {
                            TextButton(
                                onClick = { onCull(if (revisit) filter.label else "Review", due) },
                                modifier = Modifier.testTag("review"),
                            ) {
                                Icon(Icons.Rounded.Swipe, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Review ${due.size}")
                            }
                        }
                        IconButton(onClick = { searching = true }, modifier = Modifier.testTag("search")) {
                            Icon(Icons.Rounded.Search, "Search")
                        }
                        IconButton(onClick = onCamera, modifier = Modifier.testTag("camera")) {
                            Icon(Icons.Rounded.PhotoCamera, "Camera")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (transfer.running) TransferStrip(transfer, onCamera)
            if (searching || !sheetFilter.query.isNullOrBlank()) {
                SearchField(
                    query = sheetFilter.query.orEmpty(),
                    onQuery = { onSheetFilter(sheetFilter.copy(query = it.ifEmpty { null })) },
                    onClose = { searching = false; onSheetFilter(sheetFilter.copy(query = null)) },
                    looked = shots.count { it.key in insights },
                    total = shots.size,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val active = sheetFilter.chips().size
                FilterChip(
                    selected = active > 0,
                    onClick = { onSheetOpen(true) },
                    leadingIcon = { Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)) },
                    label = { Text(if (active > 0) "Filter · $active" else "Filter") },
                    modifier = Modifier.testTag("filter"),
                )
                // Saved filters: tapping one applies it, tapping it again clears it. First, so
                // they never scroll out of sight.
                for (s in saved) {
                    val on = sheetFilter == s.filter
                    FilterChip(
                        selected = on,
                        onClick = { onSheetFilter(if (on) ShotFilter() else s.filter) },
                        leadingIcon = { Icon(Icons.Rounded.Bookmark, null, Modifier.size(16.dp)) },
                        label = { Text(s.name) },
                    )
                }
                for (f in QuickFilter.entries) {
                    FilterChip(selected = filter == f, onClick = { onQuickFilter(f) }, label = { Text(f.label) })
                }
            }
            // Albums get a row of their own: saved filters fill themselves, albums are put together by hand.
            if (album == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val narrowed = filter != QuickFilter.ALL || !sheetFilter.isEmpty || selectedDays.isNotEmpty()
                    AssistChip(
                        onClick = { onNewAlbum(scope, narrowed) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                        label = { Text("New album") },
                        modifier = Modifier.testTag("album-create"),
                    )
                    for (a in albums) {
                        FilterChip(
                            selected = false,
                            onClick = { selectedShots = emptySet(); selectedDays = emptySet(); onOpenAlbum(a) },
                            leadingIcon = { Icon(if (a.shared) Icons.Rounded.People else Icons.Rounded.PhotoAlbum, if (a.shared) "Shared" else null, Modifier.size(16.dp)) },
                            label = { Text("${a.name} · ${shots.count { it.key in a }}") },
                            modifier = Modifier.testTag("album-${a.id}"),
                        )
                    }
                }
            }
            // What the sheet filters on, each removable; a saved filter shows as its own chip instead.
            // The search words are in the search field already.
            val unsearched = sheetFilter.copy(query = null)
            if (!unsearched.isEmpty && saved.none { it.filter == sheetFilter }) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((label, without) in unsearched.chips(::personName)) {
                        InputChip(
                            selected = true,
                            onClick = { onSheetFilter(without.copy(query = sheetFilter.query)) },
                            label = { Text(label) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove $label", Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            if (sheetOpen) {
                val current = saved.find { it.filter == sheetFilter }
                FilterSheet(
                    facts = facts,
                    people = People.shown(people),
                    onNamePerson = onNamePerson,
                    filter = sheetFilter,
                    onFilter = onSheetFilter,
                    onSave = onSaveFilter,
                    savedName = current?.name,
                    onDeleteSaved = { current?.let(onDeleteSaved) },
                    onDismiss = { onSheetOpen(false) },
                )
            }
            if (filter == QuickFilter.REJECTED && rejected > 0) {
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
                            album != null && album.size == 0 -> "Nothing in this album yet. Long-press shots in Photos to add them."
                            filter == QuickFilter.TO_REVIEW && sheetFilter.isEmpty -> "All reviewed"
                            !sheetFilter.query.isNullOrBlank() && unsearched.isEmpty -> "Nothing found for \u201c${sheetFilter.query.orEmpty().trim()}\u201d"
                            !sheetFilter.isEmpty -> "No photos match these filters"
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
                            val toggle = {
                                selectedShots = emptySet()
                                selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                            }
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
                            val toggle = {
                                selectedDays = emptySet()
                                selectedShots = if (shot.key in selectedShots) selectedShots - shot.key else selectedShots + shot.key
                            }
                            // The JPEG twin's system thumbnail is cached and quick; it looks like the RAW's preview.
                            Thumb(
                                shot.jpeg ?: shot.primary,
                                Modifier.aspectRatio(1f).testTag("thumb-${shot.key}").combinedClickable(
                                    onClick = { if (selectedShots.isNotEmpty()) toggle() else onOpen(shot) },
                                    onLongClick = toggle,
                                ),
                                selected = shot.key in selectedShots,
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

/** Search box above the grid; it searches what's in the photos, their text, places, lenses and people. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, looked: Int, total: Int) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Dog, beach, a name, words on a sign") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
        )
        if (looked < total) {
            Text(
                "Still looking through your photos: $looked of $total done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
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
                (if (count == 1) "1 shot" else "$count shots") + if (toReview > 0) " · $toReview to review" else "",
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
    selected: Boolean = false,
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
            else -> Badge(Icons.Rounded.Check, "Picked", Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color(0xFF3DDC84))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            Icon(
                Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
            )
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
