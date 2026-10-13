package dev.kogler.a7iiiconnect

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Link test: joins the camera's Wi-Fi, lists what the camera exposes and downloads the newest
 * photo, logging every step so we can see exactly how the A7III behaves.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val logText = StringBuilder()
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var ssidInput: EditText
    private lateinit var passInput: EditText
    private lateinit var connectButton: Button

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var testRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "On the camera: MENU > Network > Send to Smartphone Func. > Send to Smartphone > " +
                "Select on Smartphone. Type the SSID and password the camera shows, then tap Run test."
        })
        ssidInput = EditText(this).apply {
            hint = "Camera SSID (DIRECT-xxxx:ILCE-7M3)"
            setSingleLine()
            setText(prefs.getString("ssid", ""))
        }
        passInput = EditText(this).apply {
            hint = "Camera Wi-Fi password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        root.addView(ssidInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(passInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = Button(this).apply {
            text = "Run test"
            setOnClickListener {
                prefs.edit()
                    .putString("ssid", ssidInput.text.toString().trim())
                    .putString("pass", passInput.text.toString())
                    .apply()
                startTest()
            }
        }
        buttons.addView(connectButton)
        buttons.addView(Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLog() }
        })
        root.addView(buttons)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        log("A7III Connect link test ${BuildConfigInfo.version(this)} on Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})")
    }

    override fun onDestroy() {
        releaseNetwork()
        super.onDestroy()
    }

    private fun startTest() {
        if (testRunning) return
        val ssid = ssidInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (ssid.isEmpty()) {
            log("Enter the camera SSID first.")
            return
        }
        releaseNetwork()
        testRunning = true
        connectButton.isEnabled = false
        log("Requesting Wi-Fi connection to \"$ssid\" ... (accept the system popup)")

        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (pass.isNotEmpty()) setWpa2Passphrase(pass)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val started = System.currentTimeMillis()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var handled = false
            override fun onAvailable(network: Network) {
                if (handled) return
                handled = true
                log("Connected to camera Wi-Fi after ${System.currentTimeMillis() - started} ms")
                Thread { runTest(network) }.start()
            }

            override fun onUnavailable() {
                log("Could not join the camera Wi-Fi (cancelled, wrong password, or camera not broadcasting).")
                finishTest()
            }

            override fun onLost(network: Network) {
                log("Camera Wi-Fi connection lost.")
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, 60_000)
    }

    private fun runTest(network: Network) {
        val client = CameraClient(network) { log(it) }
        try {
            log("\n== 1. Discovery (SSDP) ==")
            val location = try {
                client.discover()
            } catch (e: Exception) {
                log("SSDP failed: $e")
                null
            }
            val descUrl = location ?: CameraClient.DEFAULT_DESCRIPTION_URL.also {
                log("No SSDP reply, falling back to $it")
            }

            log("\n== 2. Device description ==")
            client.loadDescription(descUrl)

            log("\n== 3. Browsing photos ==")
            val t0 = System.currentTimeMillis()
            val items = client.listAllItems()
            log("Found ${items.size} items in ${System.currentTimeMillis() - t0} ms")
            if (items.isEmpty()) {
                log("Nothing to download. Is the camera in 'Select on Smartphone' mode?")
                return
            }
            items.take(3).forEach { item ->
                log("Item ${item.id} '${item.title}' date=${item.date}")
                item.resources.forEach { r ->
                    log("   res size=${r.size} resolution=${r.resolution}\n   info=${r.protocolInfo}\n   url=${r.url}")
                }
            }

            log("\n== 4. Downloading newest photo ==")
            val newest = items.maxByOrNull { it.date ?: "" } ?: items.last()
            val res = client.bestResource(newest)
            if (res == null) {
                log("Newest item has no downloadable resource.")
                return
            }
            log("Downloading '${newest.title}' (${res.size ?: "?"} bytes) from ${res.url}")
            val bytes = download(client, res.url, newest.title.ifBlank { "A7III_${System.currentTimeMillis()}.jpg" })
            log("Done. Look in Photos for the 'A7III Connect' album.")
            log("Received $bytes bytes")
            log("\nTest finished. Tap 'Share log' and paste it into the chat.")
        } catch (e: Exception) {
            log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            log(e.stackTraceToString().take(1500))
        } finally {
            finishTest()
        }
    }

    private fun download(client: CameraClient, url: String, name: String): Long {
        val mime = when {
            name.endsWith(".arw", true) -> "image/x-sony-arw"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/A7III Connect")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Photos")
        val start = System.currentTimeMillis()
        var total = 0L
        val (input, conn) = client.openStream(url)
        try {
            contentResolver.openOutputStream(uri)!!.use { out ->
                input.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        } finally {
            conn.disconnect()
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
        log("Speed: %.1f MB/s (%d ms)".format(total / 1_000_000.0 / (ms / 1000.0), ms))
        return total
    }

    private fun finishTest() {
        testRunning = false
        main.post { connectButton.isEnabled = true }
    }

    private fun releaseNetwork() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun shareLog() {
        val text = synchronized(logText) { logText.toString() }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share log"))
    }

    private fun log(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $msg\n"
        synchronized(logText) { logText.append(line) }
        main.post {
            logView.append(line)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}

private object BuildConfigInfo {
    fun version(activity: Activity): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }
            .getOrNull() ?: "?"
}
