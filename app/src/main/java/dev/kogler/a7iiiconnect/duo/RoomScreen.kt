package dev.kogler.a7iiiconnect.duo

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dev.kogler.a7iiiconnect.ftp.CameraNetwork
import dev.kogler.a7iiiconnect.ftp.FtpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** On the album's page: open the review to people with any phone, by a link in a QR code. */
@Composable
internal fun RoomCard(room: Room, onOpen: () -> Unit) {
    val open = ReviewRoom.album == room.album.id
    val guests = room.members.count { it.startsWith(RoomApi.GUEST_PREFIX) }
    Surface(
        color = Panel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen).testTag("room-card"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Language, null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (open) "Review room is open" else "Invite by link", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        open && guests > 0 -> "${plural(guests, "guest")} in the review"
                        open -> "Show the QR code to let others in"
                        else -> "Others review in their browser: iPhones too, no app needed"
                    },
                    color = Dim, fontSize = 13.sp,
                )
            }
            if (open) Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF3DDC84)))
        }
    }
}

/**
 * The review room for [room]'s album: opens it, and shows the QR codes to join. With the phone on
 * Wi-Fi, guests on the same Wi-Fi scan one code; away from Wi-Fi, they first join Shootback's own
 * network with a second one.
 */
@Composable
internal fun RoomScreen(room: Room, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(room.album.id) { if (ReviewRoom.album != room.album.id) ReviewRoom.open(context, room.album.id) }

    // The phone's addresses, looked at again now and then: Wi-Fi comes and goes.
    var addresses by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    LaunchedEffect(Unit) {
        while (true) {
            addresses = withContext(Dispatchers.IO) { FtpService.addresses(context) }
            delay(5_000)
        }
    }
    val (hotspot, wifi) = addresses
    LaunchedEffect(wifi, hotspot) {
        // Nowhere to reach this phone but its own network: use that.
        if (wifi == null && hotspot == null && !ReviewRoom.ownNetwork && ReviewRoom.album != null) ReviewRoom.useOwnNetwork(context, true)
    }
    val own = ReviewRoom.ownNetwork
    val address = if (own) CameraNetwork.ADDRESS else wifi ?: hotspot
    val link = address?.let { ReviewRoom.link(it) }

    TopBar("Invite by link", room.album.name, onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ReviewRoom.error?.let { Text(it, color = Pink, modifier = Modifier.padding(vertical = 8.dp)) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(4.dp)) {
            Segment("This Wi-Fi", !own, Modifier.weight(1f)) { ReviewRoom.useOwnNetwork(context, false) }
            Segment("Shootback network", own, Modifier.weight(1f)) { ReviewRoom.useOwnNetwork(context, true) }
        }
        Spacer(Modifier.height(20.dp))
        if (own) {
            val ssid = CameraNetwork.ssid(context)
            val pass = remember { CameraNetwork.passphrase(context) }
            Step(1, "Join the network", "Scan with the camera app, or join $ssid with the password $pass.")
            Qr("WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(pass)};;")
            Text(
                when {
                    ReviewRoom.networkError != null -> ReviewRoom.networkError!!
                    ReviewRoom.networkUp -> "The network is up."
                    else -> "Starting the network…"
                },
                color = if (ReviewRoom.networkError != null) Pink else Dim, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Step(2, "Open the review", "Then scan this one and open the link.")
        } else {
            Step(null, "Scan to join", "Everyone needs to be on the same Wi-Fi as this phone${if (wifi == null && hotspot != null) " (its hotspot)" else ""}.")
        }
        if (link != null) {
            Qr(link)
            Text(link, color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, "Send the link"))
                }) { Text("Send the link") }
            }
        } else {
            Text("This phone isn't on Wi-Fi. Use Shootback's network instead.", color = Dim, modifier = Modifier.padding(vertical = 24.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("In the review", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        val r = room.review
        for (m in room.members) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(room.name(m), room.color(m), 28)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (m == room.me) "You" else room.name(m), color = Color.White)
                    Text(if (m.startsWith(RoomApi.GUEST_PREFIX)) "Browser" else "Shootback", color = Dim, fontSize = 12.sp)
                }
                Text("${r.progress(m)}/${r.open.size}", color = Dim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { ReviewRoom.close(context); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Close the room") }
        Text(
            "Guests stay in the album: their votes count in this review and the next ones, and go to the other phones like yours do.",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun Segment(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(9.dp)).background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) Color.White else Dim, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun Step(n: Int?, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (n != null) {
            Box(Modifier.size(22.dp).border(1.dp, Dim, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Dim, fontSize = 13.sp)
        }
    }
}

/** A QR code for [text], dark on white so every camera reads it. */
@Composable
private fun Qr(text: String) {
    val matrix = remember(text) { qrMatrix(text) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.width / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
