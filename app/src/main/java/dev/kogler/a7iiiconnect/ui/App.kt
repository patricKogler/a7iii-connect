package dev.kogler.a7iiiconnect.ui

import android.content.Intent
import android.util.Size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kogler.a7iiiconnect.Phase
import dev.kogler.a7iiiconnect.Photo
import dev.kogler.a7iiiconnect.SyncStatus
import dev.kogler.a7iiiconnect.ftp.FtpService
import dev.kogler.a7iiiconnect.ftp.FtpStatus
import dev.kogler.a7iiiconnect.gps.CameraPairing
import dev.kogler.a7iiiconnect.gps.GpsPhase
import dev.kogler.a7iiiconnect.gps.GpsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the screen needs, gathered by MainActivity. */
data class UiState(
    val status: SyncStatus,
    val hasCamera: Boolean,
    val cameraName: String,
    val password: String,
    val smallCopies: Boolean,
    val photos: List<Photo>,
    val photoCount: Int,
    val nfcAvailable: Boolean,
    val version: String,
    val gps: GpsStatus,
    val gpsEnabled: Boolean,
    val gpsPaired: Boolean,
    val pairing: CameraPairing.State?,
    val showPairIntro: Boolean,
    val ftp: FtpStatus,
)

class UiActions(
    val sync: () -> Unit,
    val stop: () -> Unit,
    val saveCamera: (ssid: String, password: String) -> Unit,
    val setSmallCopies: (Boolean) -> Unit,
    val shareLog: () -> Unit,
    val openPhoto: (Photo) -> Unit,
    val openAlbum: () -> Unit,
    val openEditor: () -> Unit,
    val setGps: (Boolean) -> Unit,
    val startPairing: () -> Unit,
    val repairGps: () -> Unit,
    val dismissPairing: () -> Unit,
    val openRemote: () -> Unit,
    val setFtp: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(state: UiState, actions: UiActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("A7III Connect", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (!state.hasCamera) {
            Onboarding(Modifier.padding(padding), state.nfcAvailable) { showSettings = true }
        } else {
            Home(Modifier.padding(padding), state, actions)
        }
    }

    if (showSettings) {
        SettingsSheet(state, actions, onDismiss = { showSettings = false })
    }
    if (state.showPairIntro || state.pairing != null) {
        PairingDialog(state, actions)
    }
}

// ---------------------------------------------------------------------------------------------
// Home

@Composable
private fun Home(modifier: Modifier, state: UiState, actions: UiActions) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fullWidth { StatusCard(state.status, state.cameraName, actions) }
        if (state.nfcAvailable && !state.status.running) {
            fullWidth { NfcTip() }
        }
        fullWidth { GpsCard(state, actions) }
        if (!state.status.running) fullWidth { RemoteCard(actions.openRemote) }
        if (!state.status.running) fullWidth { FtpCard(state.ftp, actions.setFtp) }
        fullWidth {
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Recent photos", style = MaterialTheme.typography.titleLarge)
                    if (state.photoCount > 0) {
                        Text(
                            "${state.photoCount} in the ${dev.kogler.a7iiiconnect.RecentPhotos.ALBUM} album",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.photoCount > 0) {
                    TextButton(onClick = actions.openEditor) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit")
                    }
                    TextButton(onClick = actions.openAlbum) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Photos")
                    }
                }
            }
        }
        if (state.photos.isEmpty()) {
            fullWidth { EmptyPhotos() }
        } else {
            items(state.photos, key = { it.uri.toString() }) { photo ->
                PhotoTile(photo, Modifier.clickable { actions.openPhoto(photo) })
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun StatusCard(status: SyncStatus, cameraName: String, actions: UiActions) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (status.phase) {
            Phase.FAILED -> colors.errorContainer
            Phase.DONE -> colors.secondaryContainer
            Phase.IDLE -> colors.surfaceContainerHigh
            else -> colors.primaryContainer
        },
        label = "container",
    )
    val content = when (status.phase) {
        Phase.FAILED -> colors.onErrorContainer
        Phase.DONE -> colors.onSecondaryContainer
        Phase.IDLE -> colors.onSurface
        else -> colors.onPrimaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseBadge(status, content)
                Spacer(Modifier.width(20.dp))
                AnimatedContent(
                    targetState = headline(status, cameraName),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "headline",
                    modifier = Modifier.weight(1f),
                ) { (title, subtitle) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = content.copy(alpha = 0.8f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (status.phase == Phase.COPYING && status.total > 0) {
                val progress by animateFloatAsState(status.done.toFloat() / status.total, label = "progress")
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = colors.primary,
                    trackColor = colors.primary.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                )
            }

            Spacer(Modifier.height(20.dp))
            if (status.running) {
                FilledTonalButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            } else {
                Button(onClick = actions.sync, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Get photos", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun headline(status: SyncStatus, cameraName: String): Pair<String, String> = when (status.phase) {
    Phase.IDLE -> "Ready" to "Open Send to Smartphone on the camera, then tap Get photos.\n$cameraName"
    Phase.SEARCHING -> "Looking for your camera" to "Make sure Send to Smartphone is open on the camera."
    Phase.READING -> "Connected" to "Checking which photos are new"
    Phase.COPYING -> "Copying ${status.done} of ${status.total}" to (status.currentFile ?: "")
    Phase.DONE -> when {
        status.copied > 0 -> "Done" to status.message
        else -> "All caught up" to status.message
    }
    Phase.FAILED -> "Didn't finish" to status.message
}

@Composable
private fun PhaseBadge(status: SyncStatus, tint: Color) {
    val icon: ImageVector = when (status.phase) {
        Phase.IDLE -> Icons.Rounded.PhotoCamera
        Phase.SEARCHING -> Icons.Rounded.Wifi
        Phase.READING -> Icons.Rounded.PhotoCamera
        Phase.COPYING -> Icons.Rounded.Download
        Phase.DONE -> Icons.Rounded.CheckCircle
        Phase.FAILED -> Icons.Rounded.ErrorOutline
    }
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
        )
        when (status.phase) {
            Phase.SEARCHING, Phase.READING -> CircularProgressIndicator(
                modifier = Modifier.size(64.dp), color = tint, strokeWidth = 3.dp,
            )
            Phase.COPYING -> if (status.total > 0) {
                val progress by animateFloatAsState(status.done.toFloat() / status.total, label = "ring")
                CircularProgressIndicator(
                    progress = { progress }, modifier = Modifier.size(64.dp), color = tint,
                    strokeWidth = 3.dp, trackColor = tint.copy(alpha = 0.15f),
                )
            }
            else -> Unit
        }
        val pulse = rememberInfiniteTransition(label = "pulse")
        val alpha by pulse.animateFloat(
            initialValue = if (status.phase == Phase.SEARCHING) 0.4f else 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "alpha",
        )
        Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun NfcTip() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Nfc, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp).rotate(-15f),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "Or keep this screen open and touch the phone to the N mark on the camera.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PhotoTile(photo: Photo, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(photo.uri, Size(360, 360), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        bitmap?.let {
            Image(it, contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun EmptyPhotos() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Photos you copy will show up here",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Location linking

@Composable
private fun GpsCard(state: UiState, actions: UiActions) {
    val gps = state.gps
    val (subtitle, active) = when {
        !state.gpsEnabled -> "Add your phone's location to every photo you take" to false
        gps.phase == GpsPhase.LINKED -> {
            val acc = gps.accuracyMeters?.let { " · ±${it.toInt()} m" } ?: ""
            "Sending location to the camera$acc" to true
        }
        gps.phase == GpsPhase.WAITING_FIX -> "Camera connected, waiting for a GPS fix" to true
        gps.offOnCamera -> "Paused: the camera isn't taking locations. It can't while Bluetooth Rmt Ctrl is on, " +
            "so turn that off under MENU > Network to geotag again." to true
        else -> "Waiting for the camera. It connects when the camera is on." to true
    }
    Surface(
        color = if (gps.phase == GpsPhase.LINKED) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column {
            ListItem(
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(
                        if (active) Icons.Rounded.LocationOn else Icons.Rounded.LocationOff, null,
                        tint = if (gps.phase == GpsPhase.LINKED) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = { Text("Geotag photos", fontWeight = FontWeight.Medium) },
                supportingContent = { Text(subtitle) },
                trailingContent = { Switch(checked = state.gpsEnabled, onCheckedChange = actions.setGps) },
            )
            if (state.gpsPaired && !state.gpsEnabled) {
                TextButton(onClick = actions.repairGps, modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)) {
                    Text("Pair again")
                }
            }
        }
    }
}

@Composable
private fun RemoteCard(onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text("Remote shooting", fontWeight = FontWeight.Medium) },
            supportingContent = { Text("Live view and shutter. Turn on Ctrl w/ Smartphone on the camera first.") },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        )
    }
}

@Composable
private fun FtpCard(ftp: FtpStatus, setFtp: (Boolean) -> Unit) {
    var showSetup by rememberSaveable { mutableStateOf(false) }
    // The hotspot is a Wi-Fi interface (ap0, swlan0, wlan1 ...); mobile data ones (rmnet) can't be reached.
    val address = ftp.addresses.firstOrNull { it.contains("(ap") || it.contains("wlan") }?.substringBefore(' ')
    val subtitle = when {
        ftp.error != null -> ftp.error
        !ftp.running -> "The camera sends RAW and JPEG as you shoot, over your phone's hotspot"
        address == null -> "Turn on your phone's hotspot so the camera can reach it"
        ftp.received == 0 -> "Waiting for the camera at $address, port ${FtpService.PORT}"
        else -> "${ftp.received} files received · last ${ftp.lastFile}"
    }
    Surface(
        color = if (ftp.running && ftp.received > 0) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column {
            ListItem(
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Receive RAW over FTP", fontWeight = FontWeight.Medium) },
                supportingContent = { Text(subtitle) },
                trailingContent = { Switch(checked = ftp.running, onCheckedChange = setFtp) },
            )
            TextButton(onClick = { showSetup = true }, modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)) {
                Text("Camera setup")
            }
        }
    }
    if (showSetup) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSetup = false },
            title = { Text("Set up FTP on the camera") },
            text = {
                Text(
                    "1. Turn on your phone's hotspot and this switch.\n" +
                        "2. Camera: MENU > Network > Wi-Fi Settings > Access Point Set., join the hotspot.\n" +
                        "3. MENU > Network > FTP Transfer Func. > Server Setting > Server 1:\n" +
                        "   Hostname: ${address ?: "(the address shown on the card)"}\n" +
                        "   Secure Protocol: Off · Port: ${FtpService.PORT}\n" +
                        "   User and password: anything, e.g. a7iii / a7iii\n" +
                        "4. FTP Function: On, Auto FTP Transfer: On, RAW+J Trans. Target: RAW & JPEG.\n\n" +
                        "The hotspot's address can change when it restarts. If uploads stop, check the card " +
                        "and update the hostname on the camera.",
                )
            },
            confirmButton = { TextButton(onClick = { showSetup = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun PairingDialog(state: UiState, actions: UiActions) {
    val pairing = state.pairing
    androidx.compose.material3.AlertDialog(
        onDismissRequest = actions.dismissPairing,
        icon = { Icon(Icons.Rounded.Bluetooth, null) },
        title = {
            Text(
                when (pairing) {
                    null -> "Pair over Bluetooth"
                    CameraPairing.State.Searching -> "Looking for the camera"
                    is CameraPairing.State.Pairing -> "Pairing"
                    is CameraPairing.State.Paired -> "Paired"
                    is CameraPairing.State.Failed -> "Couldn't pair"
                },
            )
        },
        text = {
            Column {
                when (pairing) {
                    null -> {
                        Text("On the camera:")
                        Spacer(Modifier.height(8.dp))
                        Text("1. MENU > Network > Bluetooth Settings > Bluetooth Function: On")
                        Text("2. MENU > Network > Location Info. Link Set. > Location Info. Link: On")
                        Text("3. MENU > Network > Bluetooth Settings > Pairing")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Then tap Start and confirm the pairing on both the phone and the camera.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CameraPairing.State.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Keep the camera on its Pairing screen.")
                    }
                    is CameraPairing.State.Pairing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Found ${pairing.name}. Confirm the pairing on the phone and on the camera.")
                    }
                    is CameraPairing.State.Paired ->
                        Text("${pairing.name} is paired. Photos will now get your location whenever the camera is on.")
                    is CameraPairing.State.Failed -> Text(pairing.reason)
                }
            }
        },
        confirmButton = {
            when (pairing) {
                null -> Button(onClick = actions.startPairing) { Text("Start") }
                is CameraPairing.State.Failed -> Button(onClick = actions.startPairing) { Text("Try again") }
                is CameraPairing.State.Paired -> Button(onClick = actions.dismissPairing) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (pairing !is CameraPairing.State.Paired) {
                TextButton(onClick = actions.dismissPairing) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// First run

@Composable
private fun Onboarding(modifier: Modifier, nfcAvailable: Boolean, onManual: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(140.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (nfcAvailable) Icons.Rounded.Nfc else Icons.Rounded.PhotoCamera, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Connect your camera",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (nfcAvailable) {
                "Turn the A7III on, show a photo in playback, and touch the back of your phone " +
                    "to the N mark on the camera's side."
            } else {
                "On the camera open MENU > Network > Send to Smartphone, then enter the network " +
                    "name and password it shows."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onManual) { Text("Enter network details instead") }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(state: UiState, actions: UiActions, onDismiss: () -> Unit) {
    var ssid by rememberSaveable { mutableStateOf(state.cameraName) }
    var password by rememberSaveable { mutableStateOf(state.password) }
    var showPassword by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Camera", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Filled in automatically when you touch the camera. Otherwise copy them from the " +
                    "camera's Send to Smartphone screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it },
                label = { Text("Network name") },
                placeholder = { Text("DIRECT-xxxx:ILCE-7M3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { actions.saveCamera(ssid.trim(), password); onDismiss() },
                enabled = ssid.isNotBlank() && (ssid.trim() != state.cameraName || password != state.password),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save") }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            ListItem(
                headlineContent = { Text("Smaller copies") },
                supportingContent = { Text("Much faster. Good for sharing, not for editing.") },
                trailingContent = { Switch(checked = state.smallCopies, onCheckedChange = actions.setSmallCopies) },
                modifier = Modifier.clickable { actions.setSmallCopies(!state.smallCopies) },
            )
            ListItem(
                headlineContent = { Text("Share log") },
                supportingContent = { Text("Helps track down connection problems") },
                leadingContent = { Icon(Icons.Outlined.Share, null) },
                modifier = Modifier.clickable { actions.shareLog() },
            )
            ListItem(
                headlineContent = { Text("Version ${state.version}") },
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/patricKogler/a7iii-connect/releases/tag/latest")),
                    )
                },
            )
        }
    }
}
