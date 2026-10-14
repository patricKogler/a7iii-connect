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

/** Live view and shutter over Wi-Fi, like Imaging Edge's remote shooting. */
class RemoteActivity : ComponentActivity() {

    private enum class Phase { CONNECTING, STARTING, LIVE, UNSUPPORTED, FAILED }

    private var phase by mutableStateOf(Phase.CONNECTING)
    private var message by mutableStateOf("Connecting to the camera ...")
    private var frame by mutableStateOf<Bitmap?>(null)
    private var lastShot by mutableStateOf<Bitmap?>(null)
    private var shooting by mutableStateOf(false)
    private var shotCount by mutableStateOf(0)

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
        connect()
    }

    override fun onDestroy() {
        running = false
        val r = remote
        Thread {
            runCatching { r?.call("stopLiveview") }
            runCatching { r?.call("stopRecMode") }
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }.start()
        super.onDestroy()
    }

    private fun connect() {
        val store = Store(this)
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
                Thread({ session(network) }, "remote").start()
            }

            override fun onUnavailable() {
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
        val r = remote ?: return
        if (shooting) return
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
                            if (phase == Phase.CONNECTING || phase == Phase.STARTING) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(24.dp))
                            }
                            Text(message, color = Color.White, textAlign = TextAlign.Center)
                        }
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
                    ShutterButton(enabled = phase == Phase.LIVE && !shooting, busy = shooting) { shoot() }
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
