package dev.kogler.a7iiiconnect.develop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kogler.a7iiiconnect.develop.engine.Settings
import java.text.DateFormat
import java.util.Date

/**
 * Versions (independent edits of one photo, like Lightroom's virtual copies) and the active
 * version's checkpoints (saved states to go back to, like Lightroom's snapshots).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun VersionsPanel(
    versions: Versions,
    checkpoints: List<Checkpoint>,
    current: Settings,
    onSwitch: (Int) -> Unit,
    /** true: a copy of this version; false: start from the original. */
    onAdd: (copy: Boolean) -> Unit,
    onRename: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onSaveCheckpoint: (String) -> Unit,
    onRestore: (Checkpoint) -> Unit,
    onDeleteCheckpoint: (Checkpoint) -> Unit,
) {
    var editing by remember { mutableStateOf<Version?>(null) }
    var naming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Checkpoint?>(null) }

    Text("Versions", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    Text(
        "Each version is its own edit and exports as its own JPEG. Long-press one to rename or delete it.",
        color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in versions.list) {
            val on = v.id == versions.active
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
                    .then(if (on) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .combinedClickable(onClick = { if (!on) onSwitch(v.id) }, onLongClick = { editing = v })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(v.name, color = Color.White, fontSize = 13.sp) }
        }
        Chip("+ Copy of this") { onAdd(true) }
        Chip("+ From original") { onAdd(false) }
    }

    Text("Checkpoints", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
    Text(
        "Tap one to go back to it (undo brings you back). One is saved for you before a preset replaces your edit.",
        color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp),
    )
    Chip("+ Save checkpoint") { naming = true }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val today = day.format(Date())
    Column(Modifier.padding(top = 6.dp)) {
        for (c in checkpoints) {
            val here = c.settings == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { if (!here) onRestore(c) }, onLongClick = { removing = c })
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    c.name, color = if (c.auto) Color.LightGray else Color.White, fontSize = 13.sp,
                    fontWeight = if (here) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
                )
                val d = Date(c.time)
                Text(if (day.format(d) == today) time.format(d) else day.format(d), color = Color.Gray, fontSize = 11.sp)
            }
        }
        if (checkpoints.isEmpty()) Text("No checkpoints yet.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }

    editing?.let { v ->
        var name by remember(v.id) { mutableStateOf(v.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Version") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(v.id, name); editing = null }) { Text("Rename") } },
            dismissButton = {
                if (versions.list.size > 1) TextButton(onClick = { onDelete(v.id); editing = null }) { Text("Delete version") }
            },
        )
    }
    if (naming) {
        var name by remember { mutableStateOf("Checkpoint ${checkpoints.count { !it.auto } + 1}") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save checkpoint") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onSaveCheckpoint(name.trim().ifEmpty { "Checkpoint" }); naming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete \"${c.name}\"?") },
            confirmButton = { TextButton(onClick = { onDeleteCheckpoint(c); removing = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp) }
}
