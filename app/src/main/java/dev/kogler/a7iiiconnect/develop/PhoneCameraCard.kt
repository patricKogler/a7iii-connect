package dev.kogler.a7iiiconnect.develop

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Turns [PhoneCamera] on and off: shots from this phone's own camera in Photos, from a chosen
 * start, read where they are. Asks for photo access the first time.
 */
@Composable
fun PhoneCameraCard() {
    val context = LocalContext.current
    // Prefs and permissions don't notify; read them again whenever the card changes them.
    var bump by remember { mutableIntStateOf(0) }
    val enabled = remember(bump) { PhoneCamera.enabled(context) }
    val full = remember(bump) { PhoneCamera.fullAccess(context) }
    val partial = remember(bump) { PhoneCamera.partialAccess(context) }
    val start = remember(bump) { PhoneCamera.start(context) }
    val hidden = remember(bump) { PhoneCamera.hidden(context).size }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val allowed = result[Manifest.permission.READ_MEDIA_IMAGES] == true || result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
            PhoneCamera.fullAccess(context) || PhoneCamera.partialAccess(context)
        if (allowed) {
            PhoneCamera.setEnabled(context, true)
        } else {
            // Asked too often before, Android no longer shows the question: the app's settings can still allow it.
            Toast.makeText(context, "Allow Photos and videos for Shootback to show your phone's photos", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        bump++
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column {
            val subtitle = when {
                !enabled -> "Shots from this phone's camera in Photos, next to the camera's. Nothing is copied."
                full -> "${start.label}. Delete and hide them in Photos like any shot."
                partial -> "Only the photos you allowed. Pick more to add them."
                else -> "Needs access to your photos"
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Rounded.Smartphone, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Phone camera", fontWeight = FontWeight.Medium) },
                supportingContent = { Text(subtitle) },
                trailingContent = {
                    Switch(
                        checked = enabled && (full || partial),
                        onCheckedChange = { on ->
                            if (!on) { PhoneCamera.setEnabled(context, false); bump++ }
                            else if (full || partial) { PhoneCamera.setEnabled(context, true); bump++ }
                            else ask.launch(PhoneCamera.permissions)
                        },
                        modifier = Modifier.testTag("phone-camera"),
                    )
                },
            )
            if (enabled && full) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 56.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in PhoneCamera.Start.entries) {
                        FilterChip(selected = s == start, onClick = { PhoneCamera.setStart(context, s); bump++ }, label = { Text(s.label) })
                    }
                }
            }
            // Shared photos can be hidden too, so this stays when the phone camera is off.
            if ((enabled && partial) || hidden > 0) {
                Row(Modifier.padding(start = 48.dp, bottom = 4.dp)) {
                    if (enabled && partial) TextButton(onClick = { ask.launch(PhoneCamera.permissions) }) { Text("Pick more photos") }
                    if (hidden > 0) {
                        TextButton(onClick = { PhoneCamera.unhideAll(context); bump++ }) {
                            Text(if (hidden == 1) "Show 1 hidden photo again" else "Show $hidden hidden photos again")
                        }
                    }
                }
            }
        }
    }
}
