package dev.kogler.a7iiiconnect.gps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.kogler.a7iiiconnect.MainActivity
import dev.kogler.a7iiiconnect.Store
import dev.kogler.a7iiiconnect.SyncState

enum class GpsPhase { OFF, WAITING_CAMERA, WAITING_FIX, LINKED }

data class GpsStatus(
    val phase: GpsPhase = GpsPhase.OFF,
    val lastSentMillis: Long = 0,
    val accuracyMeters: Float? = null,
    /** The camera stopped taking locations; on the A7III that happens while Bluetooth Rmt Ctrl is on. */
    val offOnCamera: Boolean = false,
)

/** Shared GPS-link state for the screen, same pattern as SyncState. */
object GpsState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(GpsStatus) -> Unit>()
    @Volatile var status = GpsStatus()
        private set

    fun update(transform: (GpsStatus) -> GpsStatus) {
        val next = synchronized(this) { transform(status).also { status = it } }
        main.post { listeners.toList().forEach { it(next) } }
    }

    fun observe(listener: (GpsStatus) -> Unit) { listeners += listener; listener(status) }
    fun remove(listener: (GpsStatus) -> Unit) { listeners -= listener }
}

/**
 * Keeps the phone's position flowing to the camera while switched on, so every photo is
 * geotagged. Runs in the foreground so it survives the screen being off.
 */
@SuppressLint("MissingPermission") // The screen asks for location and Bluetooth before starting this.
class GpsService : Service(), LocationListener {

    private val main = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private var link: CameraGpsLink? = null
    private var lastLocation: Location? = null

    private val sender = object : Runnable {
        override fun run() {
            pushLocation()
            main.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store(this).gpsEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (link != null) return START_STICKY

        val address = Store(this).gpsCameraAddress
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (address.isBlank() || adapter == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera location", NotificationManager.IMPORTANCE_MIN))
        startForeground(
            NOTIFICATION_ID, notification("Waiting for the camera"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        GpsState.update { GpsStatus(GpsPhase.WAITING_CAMERA) }

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { lm.requestLocationUpdates(it, 5_000L, 0f, this, Looper.getMainLooper()) }
        lastLocation = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        link = CameraGpsLink(this, adapter.getRemoteDevice(address), SyncState::log, onReady = { ready ->
            if (ready) {
                GpsState.update { it.copy(offOnCamera = false) }
                pushLocation()
            } else {
                GpsState.update { it.copy(phase = GpsPhase.WAITING_CAMERA) }
            }
            refreshNotification()
        }, onCameraOff = {
            GpsState.update { it.copy(phase = GpsPhase.WAITING_CAMERA, offOnCamera = true) }
            refreshNotification()
        }).also { it.start() }
        main.postDelayed(sender, SEND_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(sender)
        runCatching { lm.removeUpdates(this) }
        link?.stop()
        link = null
        GpsState.update { GpsStatus(GpsPhase.OFF) }
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        // Prefer fresh fixes; ignore a coarse network fix right after a good GPS one.
        if (previous == null || location.time - previous.time > 20_000 ||
            location.accuracy <= previous.accuracy + 10
        ) {
            lastLocation = location
        }
    }

    private fun pushLocation() {
        val l = link ?: return
        if (!l.ready) return
        val loc = lastLocation
        if (loc == null || System.currentTimeMillis() - loc.time > MAX_FIX_AGE_MS) {
            GpsState.update { it.copy(phase = GpsPhase.WAITING_FIX) }
            refreshNotification()
            return
        }
        l.send(loc.latitude, loc.longitude)
        GpsState.update {
            it.copy(phase = GpsPhase.LINKED, lastSentMillis = System.currentTimeMillis(), accuracyMeters = loc.accuracy)
        }
        refreshNotification()
    }

    private fun refreshNotification() {
        val text = if (GpsState.status.offOnCamera) "Location is off on the camera (Bluetooth remote is on)" else when (GpsState.status.phase) {
            GpsPhase.LINKED -> "Geotagging photos"
            GpsPhase.WAITING_FIX -> "Connected, waiting for a GPS fix"
            else -> "Waiting for the camera"
        }
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, GpsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Camera location")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "gps"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "stop"
        private const val SEND_INTERVAL_MS = 10_000L
        private const val MAX_FIX_AGE_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GpsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GpsService::class.java))
        }
    }
}
