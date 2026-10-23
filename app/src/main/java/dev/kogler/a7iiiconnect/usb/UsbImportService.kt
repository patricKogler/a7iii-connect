package dev.kogler.a7iiiconnect.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.kogler.a7iiiconnect.Phase
import dev.kogler.a7iiiconnect.RecentPhotos
import dev.kogler.a7iiiconnect.Store
import dev.kogler.a7iiiconnect.SyncState
import dev.kogler.a7iiiconnect.SyncStatus

/**
 * Copies new photos (RAW and JPEG) from the camera over a USB cable. The camera shows up as an
 * MTP device when its USB Connection setting is MTP (or Auto). Progress goes through
 * [SyncState] like the Wi-Fi copy, so Photos shows the same strip and new shots appear as they land.
 */
class UsbImportService : Service() {

    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false
    @Volatile private var unplugged = false
    @Volatile private var worker: Thread? = null
    private var camera: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.parcelableDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION -> {
                    if (worker != null || !SyncState.status.running) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) begin(device)
                    else finish("USB access to the camera wasn't allowed", Phase.FAILED)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device.deviceName == camera?.deviceName) {
                    SyncState.log("USB: camera unplugged")
                    unplugged = true
                    cancelled = true
                    if (worker == null) finish("Camera unplugged", Phase.FAILED)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB transfer", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        releaseLock()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            worker?.interrupt()
            if (worker == null && SyncState.status.usb) finish("Stopped", Phase.FAILED)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Opening the camera ...", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (SyncState.status.running) {
            // A copy (Wi-Fi or an earlier plug-in) is already going.
            if (worker == null && !SyncState.status.usb) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        cancelled = false
        unplugged = false
        SyncState.update { SyncStatus(running = true, usb = true, phase = Phase.READING, message = "Opening the camera ...") }
        acquireLock()

        val device = intent?.parcelableDevice() ?: findCamera(usb)
        camera = device
        when {
            device == null -> finish(NOT_MTP, Phase.FAILED)
            usb.hasPermission(device) -> begin(device)
            else -> {
                SyncState.log("USB: asking for access to ${device.productName ?: device.deviceName}")
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
                )
                usb.requestPermission(device, pi)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(device: UsbDevice) {
        camera = device
        worker = Thread({ run(device) }, "usb-import").also { it.start() }
    }

    private fun run(device: UsbDevice) {
        val store = Store(this)
        var copied = 0
        val connection = usb.openDevice(device)
        val mtp = MtpDevice(device)
        if (connection == null || !mtp.open(connection)) {
            connection?.close()
            SyncState.log("USB: ${device.productName} (${device.vendorId.toString(16)}:${device.productId.toString(16)}) won't open as MTP")
            finish(NOT_MTP, Phase.FAILED)
            return
        }
        try {
            SyncState.log("USB: opened ${mtp.deviceInfo?.model ?: device.productName}")
            status("Checking which photos are new", Phase.READING)
            val files = listFiles(mtp)
            val todo = UsbPlan.select(files, store.copiedKeys(), albumFiles())
            SyncState.log("USB: ${files.size} files on the card, ${todo.size} new photos")
            if (cancelled) return finish(if (unplugged) "Camera unplugged" else "Stopped", Phase.FAILED)
            if (todo.isEmpty()) return finish("All photos on the card are already on your phone")

            val started = System.currentTimeMillis()
            var bytes = 0L
            progress(0, todo.size, todo.first().name)
            for (file in todo) {
                if (cancelled) break
                if (freeBytes() < file.size + RESERVE_BYTES) {
                    val left = todo.size - copied
                    return finish("Phone storage is full. Copied $copied, $left left on the camera.", Phase.FAILED, copied)
                }
                try {
                    save(mtp, file)
                    store.markCopied(file.key)
                    copied++
                    bytes += file.size
                    progress(copied, todo.size, file.name)
                } catch (e: Exception) {
                    if (cancelled) break
                    SyncState.log("USB: failed ${file.name}: $e")
                }
            }
            val secs = (System.currentTimeMillis() - started) / 1000.0
            SyncState.log("USB: copied $copied files, ${bytes / 1_000_000} MB in ${"%.1f".format(secs)} s")
            val left = todo.size - copied
            finish(
                when {
                    unplugged -> "Camera unplugged. Copied $copied photos."
                    cancelled -> "Stopped. Copied $copied photos."
                    left == 0 -> "Copied $copied new photos over USB"
                    else -> "Copied $copied photos, $left didn't make it. Plug in again to retry."
                },
                if (left == 0 || (cancelled && !unplugged)) Phase.DONE else Phase.FAILED,
                copied,
            )
        } catch (e: Exception) {
            SyncState.log("USB error: ${e.stackTraceToString().take(1500)}")
            finish(if (copied > 0) "Stopped after $copied photos: ${e.message}" else "Couldn't read the camera: ${e.message}", Phase.FAILED, copied)
        } finally {
            mtp.close()
        }
    }

    /** Every file on every card slot, walking DCIM's folders. */
    private fun listFiles(mtp: MtpDevice): List<CardFile> {
        val out = mutableListOf<CardFile>()
        val storages = mtp.storageIds ?: IntArray(0)
        for (storage in storages) {
            walk(mtp, storage, ROOT, out)
            // Some devices answer the root query with nothing; ask for everything flat instead.
            if (out.isEmpty()) walkFlat(mtp, storage, out)
        }
        return out
    }

    private fun walk(mtp: MtpDevice, storage: Int, parent: Int, out: MutableList<CardFile>) {
        val handles = mtp.getObjectHandles(storage, 0, parent) ?: return
        for (h in handles) {
            if (cancelled) return
            val info = mtp.getObjectInfo(h) ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                if (info.name.uppercase() !in UsbPlan.SKIP_FOLDERS) walk(mtp, storage, h, out)
            } else {
                out += CardFile(h, info.name, info.compressedSizeLong, info.dateCreated)
            }
        }
    }

    private fun walkFlat(mtp: MtpDevice, storage: Int, out: MutableList<CardFile>) {
        val handles = mtp.getObjectHandles(storage, 0, 0) ?: return
        for (h in handles) {
            if (cancelled) return
            val info = mtp.getObjectInfo(h) ?: continue
            if (info.format != MtpConstants.FORMAT_ASSOCIATION) out += CardFile(h, info.name, info.compressedSizeLong, info.dateCreated)
        }
    }

    /** Name and size of what's already in the album, from any route (Wi-Fi, FTP, an earlier cable copy). */
    private fun albumFiles(): Set<Pair<String, Long>> {
        val out = mutableSetOf<Pair<String, Long>>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}%"),
            null,
        )?.use { c -> while (c.moveToNext()) out += (c.getString(0) ?: "") to c.getLong(1) }
        return out
    }

    private fun save(mtp: MtpDevice, file: CardFile) {
        val mime = when (file.ext) {
            "arw" -> "image/x-sony-arw"
            "hif", "heif", "heic" -> "image/heif"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${RecentPhotos.ALBUM}")
            if (file.created > 0) put(MediaStore.Images.Media.DATE_TAKEN, file.created)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create ${file.name} in Photos")
        val ok = runCatching {
            contentResolver.openFileDescriptor(uri, "w")!!.use { mtp.importFile(file.handle, it) }
        }.getOrDefault(false)
        if (!ok) {
            contentResolver.delete(uri, null, null)
            error("transfer of ${file.name} failed")
        }
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun freeBytes() = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun status(message: String, phase: Phase) {
        SyncState.update { it.copy(message = message, phase = phase) }
        nm.notify(NOTIFICATION_ID, notification(message, 0, 0))
    }

    private fun progress(done: Int, total: Int, file: String) {
        val message = "Copied $done of $total"
        SyncState.update { it.copy(phase = Phase.COPYING, message = message, done = done, total = total, currentFile = file) }
        nm.notify(NOTIFICATION_ID, notification(message, done, total))
    }

    private fun finish(message: String, phase: Phase = Phase.DONE, copied: Int = 0) {
        SyncState.log("USB: $message")
        SyncState.update { SyncStatus(running = false, usb = true, phase = phase, copied = copied, message = message) }
        worker = null
        releaseLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(DONE_NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Shootback")
            .setContentText(message)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build())
        stopSelf()
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, UsbImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Copying over USB")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(Notification.Action.Builder(null, "Stop", cancel).build())
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, dev.kogler.a7iiiconnect.develop.DevelopActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a7iiiconnect:usb").apply { acquire(60 * 60 * 1000L) }
    }

    private fun releaseLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "usb"
        private const val NOTIFICATION_ID = 6
        private const val DONE_NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_PERMISSION = "dev.kogler.a7iiiconnect.USB_PERMISSION"
        private const val ROOT = -1 // 0xFFFFFFFF: objects at the top of the card
        private const val RESERVE_BYTES = 1_000_000_000L
        private const val SONY = 0x054C
        const val NOT_MTP = "No camera found on the cable. On the camera: MENU > Setup > USB Connection > MTP, then plug in again."

        /** The camera on the cable: a Sony device, or anything that speaks PTP/MTP (still image class). */
        fun findCamera(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
            d.vendorId == SONY || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
        }

        /** Starts copying from the camera on the cable; [device] comes with a plug-in intent. */
        fun start(context: Context, device: UsbDevice? = null) {
            if (SyncState.status.running) return
            context.startForegroundService(
                Intent(context, UsbImportService::class.java).apply { device?.let { putExtra(UsbManager.EXTRA_DEVICE, it) } },
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UsbImportService::class.java).setAction(ACTION_CANCEL))
        }

        /** A plug-in intent's device, if it is the camera. */
        fun attachedCamera(intent: Intent?): UsbDevice? =
            intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.parcelableDevice()
    }
}

private fun Intent.parcelableDevice(): UsbDevice? =
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
