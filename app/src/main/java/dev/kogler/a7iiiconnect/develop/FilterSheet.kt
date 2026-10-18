package dev.kogler.a7iiiconnect.develop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * The filter sheet. The top half is for everyone: presets in plain words, lens, place, framing
 * and dates. "More filters" opens the exact controls (ranges snap to values that occur in the
 * library); the sheet remembers it was opened. Every chip shows how many shots it would leave.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    facts: List<ShotFacts>,
    filter: ShotFilter,
    onFilter: (ShotFilter) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The saved filter's name when the current filter is one; it can then be deleted here. */
    savedName: String? = null,
    onDeleteSaved: () -> Unit = {},
    startExpanded: Boolean = false,
    /** People to filter by ([People.shown]); long-press one to name them. */
    people: List<Person> = emptyList(),
    onNamePerson: (people: List<Person>, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", 0) }
    var more by remember { mutableStateOf(startExpanded || prefs.getBoolean("more_filters", false)) }
    var naming by remember { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    var namingPerson by remember { mutableStateOf<List<Person>?>(null) }
    val metas = facts.mapNotNull { it.meta }
    val matching = facts.count { filter.matches(it) }

    /** Shots left if [f] were the filter: for a chip, [f] is the filter with that chip toggled on. */
    fun count(f: ShotFilter) = facts.count { f.matches(it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!filter.isEmpty) TextButton(onClick = { onFilter(ShotFilter()) }) { Text("Clear all") }
            }

            Section("Find") {
                for (p in FilterPreset.entries) {
                    val on = p in filter.presets
                    val next = filter.copy(presets = if (on) filter.presets - p else filter.presets + p)
                    CountChip(p.label, on, if (on) null else count(next)) { onFilter(next) }
                }
            }
            if (people.isNotEmpty()) {
                val byKey = remember(facts) { facts.associateBy { it.shot.key } }
                Text(
                    "People", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // People given the same name are one person.
                    for (same in people.groupBy { it.key }.values) {
                        val key = same.first().key
                        val on = key in filter.people
                        val cover = same.flatMap { it.faces }.mapNotNull { ref -> byKey[ref.shot]?.let { f -> f.insight?.faces?.getOrNull(ref.index)?.let { Triple(f.shot, it, ref) } } }
                            .maxByOrNull { it.second.width }
                        val count = same.flatMap { p -> p.faces.map { it.shot } }.distinct().size
                        PersonAvatar(
                            name = same.first().name,
                            count = count,
                            selected = on,
                            shot = cover?.first,
                            box = cover?.second?.box,
                            onClick = { onFilter(filter.copy(people = if (on) filter.people - key else filter.people + key)) },
                            onLongClick = { namingPerson = same },
                        )
                    }
                }
                Text(
                    "Long-press someone to name them. Give two the same name if they're the same person.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val lenses = metas.mapNotNull { it.lens }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            if (lenses.isNotEmpty()) Section("Lens") {
                val others = filter.copy(lenses = emptySet())
                for (lens in lenses) {
                    val on = lens in filter.lenses
                    CountChip(lens, on, count(others.copy(lenses = setOf(lens)))) {
                        onFilter(filter.copy(lenses = if (on) filter.lenses - lens else filter.lenses + lens))
                    }
                }
            }
            val places = facts.mapNotNull { it.place }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            val located = metas.count { it.located }
            Section("Where") {
                val others = filter.copy(places = emptySet())
                for (place in places) {
                    val on = place in filter.places
                    CountChip(place, on, count(others.copy(places = setOf(place)))) {
                        onFilter(filter.copy(places = if (on) filter.places - place else filter.places + place))
                    }
                }
                if (places.isEmpty()) {
                    Text(
                        if (located > 0) "Looking up where $located photos were taken…"
                        else "No photos with a location yet. The camera only records it while the phone's location link is on and the Bluetooth remote is off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (metas.any { it.portrait != null }) Section("Framing") {
                val others = filter.copy(framing = emptySet())
                for (fr in Framing.entries) {
                    val on = fr in filter.framing
                    CountChip(fr.label, on, count(others.copy(framing = setOf(fr)))) {
                        onFilter(filter.copy(framing = if (on) filter.framing - fr else filter.framing + fr))
                    }
                }
            }
            Section("When") {
                val others = filter.copy(fromDay = null, toDay = null)
                val presets = datePresets()
                for ((label, from) in presets) {
                    val on = filter.fromDay == from && filter.toDay == null
                    CountChip(label, on, count(others.copy(fromDay = from))) {
                        onFilter(if (on) others else others.copy(fromDay = from))
                    }
                }
                val custom = (filter.fromDay != null || filter.toDay != null) && !(filter.toDay == null && presets.any { it.second == filter.fromDay })
                FilterChip(
                    selected = custom,
                    onClick = { pickingDates = true },
                    leadingIcon = { Icon(Icons.Rounded.DateRange, null, Modifier.size(18.dp)) },
                    label = { Text(if (custom) ShotFilter.dates(filter.fromDay, filter.toDay) else "Pick dates") },
                    modifier = Modifier.testTag("pick-dates"),
                )
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Row(
                Modifier.fillMaxWidth().clickable { more = !more; prefs.edit().putBoolean("more_filters", more).apply() }
                    .padding(vertical = 12.dp).testTag("more-filters"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("More filters", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(more) {
                Column {
                    StopsRange("Focal length", metas.mapNotNull { it.focal }, filter.focal?.let { it.start to it.endInclusive }, { ShotFilter.mm(it) }) {
                        onFilter(filter.copy(focal = it?.let { (a, b) -> a..b }))
                    }
                    StopsRange("Aperture", metas.mapNotNull { it.aperture }, filter.aperture?.let { it.start to it.endInclusive }, { ShotFilter.fStop(it) }) {
                        onFilter(filter.copy(aperture = it?.let { (a, b) -> a..b }))
                    }
                    StopsRange("Shutter speed", metas.mapNotNull { it.exposure }, filter.exposure?.let { it.start to it.endInclusive }, { ShotFilter.shutter(it) }) {
                        onFilter(filter.copy(exposure = it?.let { (a, b) -> a..b }))
                    }
                    StopsRange("ISO", metas.mapNotNull { it.iso }, filter.iso?.let { it.first to it.last }, { "ISO $it" }) {
                        onFilter(filter.copy(iso = it?.let { (a, b) -> a..b }))
                    }
                    StopsRange("Exposure compensation", metas.mapNotNull { it.bias }, filter.bias?.let { it.start to it.endInclusive }, { ShotFilter.ev(it) }) {
                        onFilter(filter.copy(bias = it?.let { (a, b) -> a..b }))
                    }
                    val programs = metas.mapNotNull { it.program }.toSet()
                    val modes = ShootingMode.entries.filter { it.program in programs }
                    if (modes.isNotEmpty()) Section("Mode") {
                        val others = filter.copy(modes = emptySet())
                        for (m in modes) {
                            val on = m in filter.modes
                            CountChip(m.label, on, count(others.copy(modes = setOf(m)))) {
                                onFilter(filter.copy(modes = if (on) filter.modes - m else filter.modes + m))
                            }
                        }
                    }
                    if (metas.any { it.flash != null }) Section("Flash") {
                        YesNo(filter.flash, "Fired", "Didn't fire", { count(filter.copy(flash = it)) }) { onFilter(filter.copy(flash = it)) }
                    }
                    Section("Location") {
                        YesNo(filter.located, "With location", "Without", { count(filter.copy(located = it)) }) { onFilter(filter.copy(located = it)) }
                    }
                    Section("Files") {
                        val others = filter.copy(files = emptySet())
                        for (k in FileKind.entries) {
                            val on = k in filter.files
                            CountChip(k.label, on, count(others.copy(files = setOf(k)))) {
                                onFilter(filter.copy(files = if (on) filter.files - k else filter.files + k))
                            }
                        }
                    }
                    Section("Export") {
                        YesNo(filter.exported, "Exported", "Not exported", { count(filter.copy(exported = it)) }) { onFilter(filter.copy(exported = it)) }
                    }
                    if (metas.size < facts.size) {
                        Text(
                            "Reading details of ${facts.size - metas.size} more shots…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    savedName != null -> OutlinedButton(onClick = onDeleteSaved) { Text("Delete \u201c$savedName\u201d") }
                    !filter.isEmpty -> OutlinedButton(onClick = { naming = true }, modifier = Modifier.testTag("save-filter")) { Text("Save as chip") }
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).testTag("show-results")) {
                    Text(if (matching == 1) "Show 1 photo" else "Show $matching photos")
                }
            }
        }
    }

    if (pickingDates) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.fromDay?.let(::localDayToUtc),
            initialSelectedEndDateMillis = filter.toDay?.let(::localDayToUtc),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        pickingDates = false
                        val from = state.selectedStartDateMillis?.let(::utcToLocalDay)
                        onFilter(filter.copy(fromDay = from, toDay = state.selectedEndDateMillis?.let(::utcToLocalDay) ?: from))
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pickingDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state, Modifier.weight(1f), title = { Text("Taken between", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }

    if (naming) {
        var name by remember { mutableStateOf(filter.chips { k -> people.find { it.key == k }?.name ?: "Someone" }.joinToString(" · ") { it.first }.take(40)) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save filter") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Chip name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { naming = false; onSave(name.trim()) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    namingPerson?.let { same ->
        var name by remember(same) { mutableStateOf(same.first().name.orEmpty()) }
        val done = {
            val key = same.first().key
            namingPerson = null
            onNamePerson(same, name)
            // Keep the filter on this person under their new name.
            if (key in filter.people) onFilter(filter.copy(people = filter.people - key + (name.trim().ifEmpty { same.first().id })))
        }
        AlertDialog(
            onDismissRequest = { namingPerson = null },
            title = { Text(if (same.first().name == null) "Who is this?" else "Rename") },
            text = {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.testTag("person-name"),
                )
            },
            confirmButton = { TextButton(onClick = done) { Text(if (name.isBlank()) "Forget name" else "Save") } },
            dismissButton = { TextButton(onClick = { namingPerson = null }) { Text("Cancel") } },
        )
    }
}

/** A face to filter by: round crop, name (or "Name?") and how many shots they're in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonAvatar(name: String?, count: Int, selected: Boolean, shot: Shot?, box: FloatArray?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val face by produceState<ImageBitmap?>(FaceCrops.cached(shot, box)?.asImageBitmap(), shot?.key, box) {
        if (value == null && shot != null && box != null) value = withContext(Dispatchers.IO) { FaceCrops.get(context, shot, box)?.asImageBitmap() }
    }
    Column(
        Modifier.width(72.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("person"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        ) {
            face?.let { Image(it, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            name ?: "Name?", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Round-ready face crops for the People row, kept while the app runs. */
internal object FaceCrops {
    private val cache = android.util.LruCache<String, Bitmap>(64)
    private fun key(shot: Shot, box: FloatArray) = shot.key + box.joinToString(",")

    fun cached(shot: Shot?, box: FloatArray?): Bitmap? = if (shot == null || box == null) null else cache.get(key(shot, box))

    /** A square around the face with some room, about 160 pixels across. */
    fun get(context: android.content.Context, shot: Shot, box: FloatArray): Bitmap? {
        cache.get(key(shot, box))?.let { return it }
        val src = Photos.cullImage(context, shot, 1024) ?: return null
        val cx = (box[0] + box[2]) / 2 * src.width
        val cy = (box[1] + box[3]) / 2 * src.height
        val half = maxOf((box[2] - box[0]) * src.width, (box[3] - box[1]) * src.height) * 0.8f
        val l = (cx - half).toInt().coerceIn(0, src.width - 1)
        val t = (cy - half).toInt().coerceIn(0, src.height - 1)
        val side = minOf((2 * half).toInt(), src.width - l, src.height - t).coerceAtLeast(1)
        val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, side, side), 160, 160, true)
        src.recycle()
        cache.put(key(shot, box), crop)
        return crop
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountChip(label: String, selected: Boolean, count: Int?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = selected || count != 0,
        label = { Text(if (count == null) label else "$label  $count") },
    )
}

@Composable
private fun YesNo(value: Boolean?, yes: String, no: String, count: (Boolean) -> Int, onChange: (Boolean?) -> Unit) {
    CountChip(yes, value == true, count(true)) { onChange(if (value == true) null else true) }
    CountChip(no, value == false, count(false)) { onChange(if (value == false) null else false) }
}

/** A range slider over the values that actually occur, so every stop is one you shot at. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Comparable<T>> StopsRange(
    title: String,
    values: List<T>,
    current: Pair<T, T>?,
    label: (T) -> String,
    onChange: (Pair<T, T>?) -> Unit,
) {
    val stops = remember(values) { values.distinct().sorted() }
    if (stops.size < 2) return
    val last = stops.lastIndex.toFloat()
    fun indices(c: Pair<T, T>?) = if (c == null) 0f..last else {
        val a = stops.indexOfFirst { it >= c.first }.coerceAtLeast(0)
        val b = stops.indexOfLast { it <= c.second }.coerceAtLeast(a)
        a.toFloat()..b.toFloat()
    }
    var range by remember(stops, current) { mutableStateOf(indices(current)) }
    val a = Math.round(range.start)
    val b = Math.round(range.endInclusive)
    Column(Modifier.padding(top = 12.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (a == 0 && b == stops.lastIndex) "Any" else "${label(stops[a])} – ${label(stops[b])}", style = MaterialTheme.typography.labelLarge)
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = {
                val i = Math.round(range.start)
                val j = Math.round(range.endInclusive)
                onChange(if (i == 0 && j == stops.lastIndex) null else stops[i] to stops[j])
            },
            valueRange = 0f..last,
            steps = (stops.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

private fun datePresets(): List<Pair<String, Long>> {
    val today = dayOf(System.currentTimeMillis())
    val week = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
    val month = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -29) }.timeInMillis
    val year = Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    return listOf("Today" to today, "Last 7 days" to week, "Last 30 days" to month, "This year" to year)
}

/** The date pickers work in UTC midnights; the filter in local ones. */
private fun utcToLocalDay(utc: Long): Long {
    val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
}

private fun localDayToUtc(day: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = day }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
