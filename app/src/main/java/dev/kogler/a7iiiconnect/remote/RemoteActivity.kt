package dev.kogler.a7iiiconnect.remote

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kogler.a7iiiconnect.CameraClient
import dev.kogler.a7iiiconnect.Store
import dev.kogler.a7iiiconnect.SyncState
import dev.kogler.a7iiiconnect.ui.AppTheme

/**
 * Live view and shutter over Wi-Fi, like Imaging Edge's remote shooting. While the camera's Wi-Fi
 * isn't up, the shutter works over Bluetooth instead (Bluetooth Rmt Ctrl), without live view.
 */
class RemoteActivity : ComponentActivity() {

    private enum class Phase { CONNECTING, STARTING, LIVE, UNSUPPORTED, FAILED }

    private var phase by mutableStateOf(Phase.CONNECTING)
    private var message by mutableStateOf("Connecting to the camera ...")
    private var frame by mutableStateOf<Bitmap?>(null)
    private var lastShot by mutableStateOf<Bitmap?>(null)
    private var shooting by mutableStateOf(false)
    private var shotCount by mutableStateOf(0)
    private var btState by mutableStateOf<BluetoothShutter.State?>(null)
    private var shutter: BluetoothShutter? = null

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var remote: CameraRemote? = null
    @Volatile private var running = true
    private var deadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { RemoteScreen() } }
        deadline = System.currentTimeMillis() + 3 * 60_000
        startBluetooth()
        connect()
    }

    private fun startBluetooth() {
        val address = Store(this).gpsCameraAddress
        if (address.isBlank() ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            SyncState.log("Remote: no paired camera, Bluetooth shutter off")
            return
        }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        shutter = BluetoothShutter(this, adapter.getRemoteDevice(address), { SyncState.log(it) }) { state ->
            runOnUiThread { btState = state }
        }.also { it.start() }
    }

    override fun onDestroy() {
        running = false
        shutter?.stop()
        val r = remote
        Thread {
            runCatching { r?.call("stopLiveview") }
            runCatching { r?.call("stopRecMode") }
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }.start()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Per Sony's guide, touching the N mark while the camera is in shooting mode starts its
        // remote-control Wi-Fi. Read the tag here too, so a tap both wakes the camera and gives us
        // the right network details.
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag ->
                dev.kogler.a7iiiconnect.NfcPairing.read(tag)?.let { creds ->
                    runOnUiThread {
                        if (phase == Phase.CONNECTING || phase == Phase.FAILED) {
                            SyncState.log("Remote: NFC tap, network ${creds.ssid}")
                            Store(this).apply { ssid = creds.ssid; password = creds.password }
                            phase = Phase.CONNECTING
                            message = "Connecting to the camera ..."
                            deadline = System.currentTimeMillis() + 3 * 60_000
                            connect()
                        }
                    }
                }
            },
            android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
                android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun onPause() {
        android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    private fun connect() {
        val store = Store(this)
        SyncState.log("Remote: requesting camera Wi-Fi \"${store.ssid}\"")
        // If nothing shows up quickly, the camera's remote mode is probably off; say how to start it.
        window.decorView.postDelayed({
            if (phase == Phase.CONNECTING) {
                message = "Waiting for the camera's Wi-Fi.\n\nOn the camera: MENU > Network > Ctrl w/ Smartphone > On, " +
                    "then go back to shooting mode. Or touch the phone to the N mark while the camera is in shooting mode."
            }
        }, 15_000)
        val spec = WifiNetworkSpecifier.Builder().setSsid(store.ssid).apply {
            if (store.password.isNotEmpty()) setWpa2Passphrase(store.password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var started = false
            override fun onAvailable(network: Network) {
                if (started) return
                started = true
                SyncState.log("Remote: camera Wi-Fi connected")
                Thread({ session(network) }, "remote").start()
            }

            override fun onUnavailable() {
                SyncState.log("Remote: camera Wi-Fi not seen yet")
                if (running && System.currentTimeMillis() < deadline) {
                    runOnUiThread { connect() }
                } else {
                    fail("Camera not found. Turn on MENU > Network > Ctrl w/ Smartphone.")
                }
            }

            override fun onLost(network: Network) {
                if (running) fail("Lost the connection to the camera.")
            }
        }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = cb
        cm.requestNetwork(request, cb, 60_000)
    }

    private fun session(network: Network) {
        try {
            message = "Starting remote control ..."
            phase = Phase.STARTING
            val location = runCatching { CameraClient(network) { SyncState.log(it) }.discover(2000) }.getOrNull()
            val r = CameraRemote(network)
            if (!r.discover(location ?: CameraClient.DEFAULT_DESCRIPTION_URL)) {
                phase = Phase.UNSUPPORTED
                message = "The camera is in transfer mode. Turn on MENU > Network > Ctrl w/ Smartphone, then try again."
                return
            }
            remote = r
            var apis = r.availableApis()
            SyncState.log("Remote: APIs $apis")
            if ("startRecMode" in apis) {
                r.call("startRecMode")
                Thread.sleep(1500)
                apis = r.availableApis()
            }
            val url = r.call("startLiveview").getString(0)
            SyncState.log("Remote: live view at $url")
            phase = Phase.LIVE
            message = ""
            while (running) {
                try {
                    r.streamLiveView(url, { running }) { frame = it }
                } catch (e: Exception) {
                    if (!running) break
                    SyncState.log("Remote: live view dropped ($e), reconnecting")
                    Thread.sleep(500)
                }
            }
        } catch (e: Exception) {
            SyncState.log("Remote: ${e.stackTraceToString().take(1500)}")
            fail("Remote control stopped: ${e.message}")
        }
    }

    private fun shoot() {
        if (shooting) return
        val r = remote
        if (phase != Phase.LIVE || r == null) {
            val bt = shutter?.takeIf { btState == BluetoothShutter.State.READY } ?: return
            shooting = true
            Thread {
                try {
                    if (bt.takePicture()) shotCount++ else message = "The camera didn't take the picture."
                } finally {
                    shooting = false
                }
            }.start()
            return
        }
        shooting = true
        Thread {
            try {
                val preview = r.takePicture()
                shotCount++
                preview?.let { url -> r.downloadBitmap(url)?.let { lastShot = it } }
            } catch (e: Exception) {
                SyncState.log("Remote: shutter failed: $e")
                message = "Couldn't take the picture: ${e.message}"
            } finally {
                shooting = false
            }
        }.start()
    }

    private fun fail(text: String) {
        if (phase == Phase.LIVE && !running) return
        phase = Phase.FAILED
        message = text
    }

    @Composable
    private fun RemoteScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                    Text("Remote shooting", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val bmp = frame
                    if (bmp != null && phase == Phase.LIVE) {
                        Image(
                            bmp.asImageBitmap(), "Live view",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
                        )
                        CaptureFlash(shotCount)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            if (btState == BluetoothShutter.State.READY) {
                                Text(
                                    "Bluetooth shutter ready",
                                    color = Color.White, style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Shots taken: $shotCount",
                                    color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(32.dp))
                                Text(
                                    "For live view on the phone, turn on MENU > Network > Ctrl w/ Smartphone.",
                                    color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                if (btState == BluetoothShutter.State.CONNECTING) {
                                    Text(
                                        "Connecting over Bluetooth ...",
                                        color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                                if (phase == Phase.CONNECTING || phase == Phase.STARTING) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(Modifier.height(24.dp))
                                }
                                Text(message, color = Color.White, textAlign = TextAlign.Center)
                            }
                        }
                        if (btState == BluetoothShutter.State.READY) CaptureFlash(shotCount)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)),
                    ) {
                        lastShot?.let {
                            Image(it.asImageBitmap(), "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    ShutterButton(
                        enabled = (phase == Phase.LIVE || btState == BluetoothShutter.State.READY) && !shooting,
                        busy = shooting,
                    ) { shoot() }
                    Spacer(Modifier.width(56.dp))
                }
            }
        }
    }

    @Composable
    private fun ShutterButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .size(84.dp)
                .border(4.dp, Color.White.copy(alpha = if (enabled || busy) 1f else 0.4f), CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.4f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
        }
    }

    /** Brief white blink when a picture is taken. */
    @Composable
    private fun CaptureFlash(trigger: Int) {
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(trigger) {
            if (trigger == 0) return@LaunchedEffect
            alpha.snapTo(0.7f)
            alpha.animateTo(0f, tween(250))
        }
        Box(Modifier.fillMaxSize().alpha(alpha.value).background(Color.White))
    }
}
