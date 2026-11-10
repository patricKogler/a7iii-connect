package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The monthly nudge at the top of Photos: old notes to clear out. Quiet, and gone after a tap. */
@Composable
internal fun DeclutterCard(count: Int, onOpen: () -> Unit, onLater: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("declutter-card"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, tint = NoteAmber, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (count == 1) "1 note older than a month" else "$count notes older than a month", style = MaterialTheme.typography.bodyMedium)
                Text("Still need them?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onLater) { Text("Later") }
            TextButton(onClick = onOpen, modifier = Modifier.testTag("declutter-open")) { Text("Look") }
        }
    }
}

/**
 * Clearing out notes: every one starts chosen for the trash (old ones only, when [preselected]
 * says so); tapping one keeps it. One confirm from Android trashes the rest, for 30 days.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesDeclutter(
    shots: List<Shot>,
    preselected: Set<String>,
    onTrash: (List<Shot>) -> Unit,
    onDone: () -> Unit,
) {
    var chosen by remember(shots) { mutableStateOf(preselected.intersect(shots.map { it.key }.toSet())) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Declutter notes")
                        Text(
                            "Tap the ones you still need",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    val all = chosen.size == shots.size
                    TextButton(onClick = { chosen = if (all) emptySet() else shots.map { it.key }.toSet() }) {
                        Text(if (all) "Keep all" else "Choose all")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(shots, key = { it.key }) { shot ->
                    val out = shot.key in chosen
                    Thumb(
                        shot.jpeg ?: shot.primary,
                        Modifier.aspectRatio(1f).testTag("declutter-${shot.key}").clickable {
                            chosen = if (out) chosen - shot.key else chosen + shot.key
                        },
                        // Dimmed with a red cross: going. Clear: staying.
                        mark = if (out) Mark.REJECT else null,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text(if (chosen.isEmpty()) "Done" else "Not now") }
                if (chosen.isNotEmpty()) {
                    Button(
                        onClick = { onTrash(shots.filter { it.key in chosen }) },
                        colors = ButtonDefaults.buttonColors(containerColor = RejectRed),
                        modifier = Modifier.weight(1f).testTag("declutter-trash"),
                    ) { Text(if (chosen.size == 1) "Trash 1 note" else "Trash ${chosen.size} notes") }
                }
            }
        }
    }
}
