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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import java.util.Calendar

/**
 * The filter sheet. The top half is for everyone: presets in plain words, lens, framing and
 * dates. "More filters" opens the exact controls (ranges snap to values that occur in the
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
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", 0) }
    var more by remember { mutableStateOf(startExpanded || prefs.getBoolean("more_filters", false)) }
    var naming by remember { mutableStateOf(false) }
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
                for ((label, from) in datePresets()) {
                    val on = filter.fromDay == from && filter.toDay == null
                    CountChip(label, on, count(others.copy(fromDay = from))) {
                        onFilter(if (on) others else others.copy(fromDay = from))
                    }
                }
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

    if (naming) {
        var name by remember { mutableStateOf(filter.chips().joinToString(" · ") { it.first }.take(40)) }
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
