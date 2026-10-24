package dev.kogler.a7iiiconnect.computer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps a computer sync alive with the screen off. Without it, Android puts the app in the
 * background when the screen times out and cuts its network sockets, so a long first sync
 * (every photo going to the computer) stopped after a couple of minutes. The sync itself runs in
 * [ComputerSync]; this only holds the foreground notification, showing its progress, until it ends.
 */
class ComputerSyncService : Service() {
    @Volatile private var watcher: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Computer sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(ComputerSync.status.value.text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher?.isAlive != true) {
            watcher = Thread({
                var shown = ""
                while (ComputerSync.running) {
                    val text = ComputerSync.status.value.text
                    if (text != shown) {
                        nm.notify(NOTIFICATION_ID, notification(text))
                        shown = text
                    }
                    try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, "computer-sync-service").apply { start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.interrupt()
        super.onDestroy()
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Syncing with your computer")
        .setContentText(text.ifEmpty { "Starting ..." })
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, dev.kogler.a7iiiconnect.develop.DevelopActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()

    private companion object {
        const val CHANNEL = "computer-sync"
        const val NOTIFICATION_ID = 4782
    }
}
