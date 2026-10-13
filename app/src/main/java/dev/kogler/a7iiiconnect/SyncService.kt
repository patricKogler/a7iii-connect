package dev.kogler.a7iiiconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Joins the camera's Wi-Fi, copies every photo the phone doesn't have yet, then disconnects.
 * Runs as a foreground service so it keeps going with the screen off.
 */
class SyncService : Service() {

    private lateinit var store: Store
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var cancelled = false
    private var searchDeadline = 0L
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo transfer", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            status("Stopping ...")
            worker?.interrupt()
            if (worker == null) stopSync("Cancelled", Phase.FAILED)
            return START_NOT_STICKY
        }
        if (SyncState.status.running) return START_NOT_STICKY

        cancelled = false
        searchDeadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        startForeground(
            NOTIFICATION_ID, notification("Looking for the camera ...", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        SyncState.update { SyncStatus(running = true, phase = Phase.SEARCHING, message = "Looking for the camera ...") }
        acquireLocks()
        connect()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseNetwork()
        releaseLocks()
        super.onDestroy()
    }

    private fun connect() {
        val ssid = store.ssid
        SyncState.log("Requesting camera Wi-Fi \"$ssid\"")
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
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
                SyncState.log("Camera Wi-Fi connected")
                worker = Thread({ runSync(network) }, "sync").also { it.start() }
            }

            override fun onUnavailable() {
                // Android gives up after one scan window (~40 s). The camera can take a while to
                // bring its Wi-Fi up, so keep looking until the overall deadline.
                if (!cancelled && System.currentTimeMillis() < searchDeadline) {
                    SyncState.log("Camera Wi-Fi not seen yet, still looking")
                    releaseNetwork()
                    connect()
                } else {
                    stopSync("Camera not found. Is Send to Smartphone open on the camera?", Phase.FAILED)
                }
            }

            override fun onLost(network: Network) {
                SyncState.log("Camera Wi-Fi lost")
                worker?.interrupt()
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, CONNECT_TIMEOUT_MS)
    }

    private fun runSync(network: Network) {
        val client = CameraClient(network) { SyncState.log(it) }
        var copied = 0
        var failed = 0
        try {
            status("Reading the camera ...", Phase.READING)
            val location = runCatching { client.discover(2000) }.getOrNull()
            client.loadDescription(location ?: CameraClient.DEFAULT_DESCRIPTION_URL)
            val items = client.listAllItems()
            val have = store.copiedKeys()
            val todo = items.filter { Store.keyFor(it) !in have }
                .sortedByDescending { it.date ?: "" }
            SyncState.log("${items.size} photos on camera, ${todo.size} new")
            if (todo.isEmpty()) {
                stopSync("All ${items.size} photos already on your phone")
                return
            }

            val small = store.smallCopies
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
            progress(0, todo.size, todo.first().title)
            val workers = (1..PARALLEL_DOWNLOADS).map {
                Thread {
                    while (!cancelled) {
                        val i = next.getAndIncrement()
                        if (i >= todo.size) break
                        val item = todo[i]
                        val res = (if (small) client.smallResource(item) else client.bestResource(item)) ?: continue
                        try {
                            saveWithRetry(client, item, res.url)
                            store.markCopied(Store.keyFor(item))
                            progress(doneCount.incrementAndGet(), todo.size, item.title)
                        } catch (e: Exception) {
                            if (cancelled) break
                            failedCount.incrementAndGet()
                            SyncState.log("Failed ${item.title}: $e")
                        }
                    }
                }.also { it.start() }
            }
            try {
                workers.forEach { it.join() }
            } catch (e: InterruptedException) {
                cancelled = true
                workers.forEach { it.join() }
            }
            copied = doneCount.get()
            failed = failedCount.get()
            val left = todo.size - copied
            stopSync(
                when {
                    cancelled -> "Stopped. Copied $copied photos."
                    left == 0 -> "Copied $copied new photos"
                    else -> "Copied $copied photos, $left didn't make it. Tap Get photos to retry."
                },
                if (left == 0 || cancelled) Phase.DONE else Phase.FAILED,
                copied,
            )
        } catch (e: Exception) {
            SyncState.log("Sync error: ${e.stackTraceToString().take(1500)}")
            stopSync(if (copied > 0) "Connection dropped after $copied photos. Tap Get photos to continue." else "Couldn't read the camera: ${e.message}", Phase.FAILED, copied)
        }
    }

    private fun saveWithRetry(client: CameraClient, item: CameraItem, url: String) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                save(client, item, url)
                return
            } catch (e: Exception) {
                last = e
                if (cancelled) throw e
                SyncState.log("Retrying ${item.title} (${attempt + 1}): $e")
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun save(client: CameraClient, item: CameraItem, url: String) {
        val name = item.title.ifBlank { "A7III_${System.currentTimeMillis()}.jpg" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/A7III Connect")
            takenMillis(item.date)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Photos")
        try {
            val (input, conn) = client.openStream(url)
            try {
                contentResolver.openOutputStream(uri)!!.use { out -> input.use { it.copyTo(out, 64 * 1024) } }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun takenMillis(date: String?): Long? = date?.let {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
    }

    private fun status(message: String, phase: Phase? = null) {
        SyncState.update { it.copy(message = message, phase = phase ?: it.phase) }
        nm.notify(NOTIFICATION_ID, notification(message, 0, 0))
    }

    private fun progress(done: Int, total: Int, file: String) {
        val message = "Copied $done of $total"
        SyncState.update { it.copy(phase = Phase.COPYING, message = message, done = done, total = total, currentFile = file) }
        nm.notify(NOTIFICATION_ID, notification(message, done, total))
    }

    private fun stopSync(message: String, phase: Phase = Phase.DONE, copied: Int = 0) {
        SyncState.log(message)
        SyncState.update { SyncStatus(running = false, phase = phase, copied = copied, message = message) }
        releaseNetwork()
        releaseLocks()
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(DONE_NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("A7III Connect")
            .setContentText(message)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build())
        stopSelf()
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("A7III Connect")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(Notification.Action.Builder(null, "Stop", cancel).build())
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a7iiiconnect:sync").apply { acquire(60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a7iiiconnect:sync").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun releaseNetwork() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val CONNECT_TIMEOUT_MS = 120_000
        private const val SEARCH_TIMEOUT_MS = 3 * 60_000L
        private const val ACTION_CANCEL = "cancel"
        private const val PARALLEL_DOWNLOADS = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
