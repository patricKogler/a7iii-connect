package dev.kogler.a7iiiconnect.duo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.kogler.a7iiiconnect.ftp.CameraNetwork

/**
 * Keeps the [ReviewRoom] open in the foreground, so guests can go on voting while this phone's
 * screen is off or Shootback is in the background, and opens Shootback's own network when the
 * room uses it.
 */
class RoomService : Service() {
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var network = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Review room", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReviewRoom.close(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (!ReviewRoom.startServer(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) acquireLocks()
        if (ReviewRoom.ownNetwork && !network) {
            network = true
            CameraNetwork.acquire(this, NETWORK_USER) { up, error ->
                ReviewRoom.networkUp = up
                ReviewRoom.networkError = error
            }
        } else if (!ReviewRoom.ownNetwork && network) {
            network = false
            CameraNetwork.release(NETWORK_USER)
            ReviewRoom.networkUp = false
            ReviewRoom.networkError = null
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (network) CameraNetwork.release(NETWORK_USER)
        network = false
        ReviewRoom.networkUp = false
        ReviewRoom.stopServer()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a7iiiconnect:room").apply { acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a7iiiconnect:room").apply { acquire() }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 4, Intent(this, RoomService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = ReviewRoom.album?.let { id ->
            PendingIntent.getActivity(this, 5, DuoActivity.albumIntent(this, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Review room is open")
            .setContentText("Others can vote from their browser")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Close", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "room"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"
        private const val NETWORK_USER = "room"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RoomService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RoomService::class.java))
        }
    }
}
