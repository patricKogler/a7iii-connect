package dev.kogler.a7iiiconnect.computer

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.text.DateFormat
import java.util.Date

/**
 * Pairing with Shootback on a computer and syncing with it: scan the code its Phone screen
 * shows, then photos, marks, albums and edits go both ways over the Wi-Fi.
 */
@Composable
fun ComputerCard() {
    val context = LocalContext.current
    val prefs = remember { ComputerPrefs(context) }
    val status by ComputerSync.status.collectAsState()
    // Prefs don't notify; re-read them whenever the status moves or the card changes them.
    var bump by remember { mutableStateOf(0) }
    val computer = remember(status, bump) { prefs.computer }
    val last = remember(status, bump) { prefs.lastSync }

    fun pair() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val c = Computer.parse(code.rawValue ?: "")
                if (c == null) {
                    Toast.makeText(context, "That's not a Shootback pairing code", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.computer?.takeIf { it.id != c.id }?.let { ComputerSync.forget(context) }
                    prefs.computer = c
                    bump++
                    ComputerSync.start(context)
                }
            }
            .addOnFailureListener { Toast.makeText(context, "Couldn't open the scanner: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column {
            val subtitle = when {
                computer == null -> "Photos, picks, albums and edits, both ways over Wi-Fi with Shootback on your computer"
                status.running -> status.text
                status.error != null -> status.error!!
                last > 0 -> "Synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))}"
                else -> "Not synced yet"
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(computer?.name ?: "Sync with a computer", fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Text(subtitle, color = if (status.error != null && !status.running) MaterialTheme.colorScheme.error else Color.Unspecified)
                },
            )
            if (computer == null) {
                Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pair() }) { Text("Pair") }
                }
                Text(
                    "On the computer, open Shootback and go to Phone, then Pair a phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
                )
            } else {
                Row(
                    Modifier.padding(start = 56.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { ComputerSync.start(context) }, enabled = !status.running) { Text("Sync now") }
                    TextButton(onClick = { ComputerSync.forget(context); bump++ }, enabled = !status.running) { Text("Unpair") }
                }
                Text(
                    "Photos from the computer",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 56.dp, top = 12.dp),
                )
                Row(Modifier.padding(start = 56.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val policy = remember(bump) { prefs.policy }
                    for (p in PhotoPolicy.entries) {
                        FilterChip(selected = p == policy, onClick = { prefs.policy = p; bump++ }, label = { Text(p.label) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sync when Photos opens", modifier = Modifier.weight(1f))
                    Switch(checked = remember(bump) { prefs.auto }, onCheckedChange = { prefs.auto = it; bump++ })
                }
            }
        }
    }
}
