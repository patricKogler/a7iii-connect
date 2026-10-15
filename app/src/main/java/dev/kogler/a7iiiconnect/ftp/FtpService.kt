package dev.kogler.a7iiiconnect.ftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import dev.kogler.a7iiiconnect.MainActivity
import dev.kogler.a7iiiconnect.RecentPhotos
import dev.kogler.a7iiiconnect.SyncState
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

data class FtpStatus(
    val running: Boolean = false,
    /** The phone's IPv4 addresses with their interface, e.g. "10.12.5.1 (ap0)". */
    val addresses: List<String> = emptyList(),
    val received: Int = 0,
    val lastFile: String? = null,
    val error: String? = null,
)

object FtpState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(FtpStatus) -> Unit>()
    @Volatile var status = FtpStatus()
        private set

    fun update(transform: (FtpStatus) -> FtpStatus) {
        val next = synchronized(this) { transform(status).also { status = it } }
        main.post { listeners.toList().forEach { it(next) } }
    }

    fun observe(listener: (FtpStatus) -> Unit) { listeners += listener; listener(status) }
    fun remove(listener: (FtpStatus) -> Unit) { listeners -= listener }
}

/**
 * Runs [FtpServer] in the foreground so the camera can upload RAW and JPEG files over the
 * phone's hotspot while the screen is off. Files land in the same album as copied photos.
 */
class FtpService : Service() {

    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var server: FtpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "FTP receiver", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (server != null) return START_STICKY
        acquireLocks()
        val ftp = FtpServer(PORT, MediaStoreSink(this), SyncState::log) { name, _ ->
            FtpState.update { it.copy(received = it.received + 1, lastFile = name) }
            nm.notify(NOTIFICATION_ID, notification())
        }
        try {
            ftp.start()
            server = ftp
            FtpState.update { it.copy(running = true, addresses = addresses(), error = null) }
        } catch (e: Exception) {
            SyncState.log("FTP: can't start: $e")
            FtpState.update { it.copy(running = false, error = "Couldn't open port $PORT: ${e.message}") }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLocks()
        FtpState.update { it.copy(running = false) }
        SyncState.log("FTP: stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, FtpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = MainActivity.open(this)
        val s = FtpState.status
        val text = if (s.received == 0) "Waiting for the camera on port $PORT"
        else "${s.received} files received · last ${s.lastFile}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Receiving from the camera")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a7iiiconnect:ftp").apply { acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a7iiiconnect:ftp").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val PORT = 2121
        private const val CHANNEL = "ftp"
        private const val NOTIFICATION_ID = 5
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FtpService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FtpService::class.java))
        }

        /** Every IPv4 address the camera could reach; with the hotspot on it's usually on ap0 or swlan0. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" }
                }
        }.getOrDefault(emptyList())
    }
}

/** Stores uploads in Pictures/A7III Connect (photos) or Movies/A7III Connect (clips). */
private class MediaStoreSink(private val context: Context) : UploadSink {
    override fun open(name: String): Upload {
        val ext = name.substringAfterLast('.', "").lowercase()
        val (collection, mime, dir) = when (ext) {
            "jpg", "jpeg" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", Environment.DIRECTORY_PICTURES)
            "arw" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/x-sony-arw", Environment.DIRECTORY_PICTURES)
            "hif", "heif", "heic" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/heif", Environment.DIRECTORY_PICTURES)
            "mp4" -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", Environment.DIRECTORY_MOVIES)
            else -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "application/octet-stream", Environment.DIRECTORY_DOWNLOADS)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/${RecentPhotos.ALBUM}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(collection, values) ?: error("MediaStore refused $name")
        val stream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            error("Can't write $name")
        }
        return object : Upload {
            override val out: OutputStream = stream.buffered(256 * 1024)
            override fun commit() {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            override fun abort() {
                resolver.delete(uri, null, null)
            }
        }
    }
}
