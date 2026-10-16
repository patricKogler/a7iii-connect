package dev.kogler.a7iiiconnect

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import dev.kogler.a7iiiconnect.develop.DevelopActivity
import dev.kogler.a7iiiconnect.ftp.FtpService
import dev.kogler.a7iiiconnect.ftp.FtpState
import dev.kogler.a7iiiconnect.ftp.FtpStatus
import dev.kogler.a7iiiconnect.gps.CameraPairing
import dev.kogler.a7iiiconnect.gps.GpsService
import dev.kogler.a7iiiconnect.gps.GpsState
import dev.kogler.a7iiiconnect.gps.GpsStatus
import dev.kogler.a7iiiconnect.ui.App
import dev.kogler.a7iiiconnect.ui.AppTheme
import dev.kogler.a7iiiconnect.ui.UiActions
import dev.kogler.a7iiiconnect.ui.UiState
import dev.kogler.a7iiiconnect.usb.UsbImportService

/** The Camera screen: getting photos off the camera (Wi-Fi or USB cable), location, FTP, remote and the connection settings. */
class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private var status by mutableStateOf(SyncStatus())
    private var hasCamera by mutableStateOf(false)
    private var cameraName by mutableStateOf("")
    private var password by mutableStateOf("")
    private var smallCopies by mutableStateOf(false)
    private val listener: (SyncStatus) -> Unit = { onStatus(it) }
    private var gps by mutableStateOf(GpsStatus())
    private var gpsEnabled by mutableStateOf(false)
    private var gpsPaired by mutableStateOf(false)
    private var pairing by mutableStateOf<CameraPairing.State?>(null)
    private var pairer: CameraPairing? = null
    private val gpsListener: (GpsStatus) -> Unit = { gps = it }
    private var ftp by mutableStateOf(FtpStatus())
    private val ftpListener: (FtpStatus) -> Unit = { ftp = it }
    private var afterPermissions: (() -> Unit)? = null

    private val gpsPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val next = afterPermissions
        afterPermissions = null
        if (result.values.all { it }) next?.invoke()
        else pairing = CameraPairing.State.Failed("Location and nearby-devices permissions are needed for this")
    }

    /** Runs [block] once Bluetooth and location permissions are granted. */
    private fun withGpsPermissions(block: () -> Unit) {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            block()
        } else {
            afterPermissions = block
            gpsPermissions.launch(needed)
        }
    }

    private fun setGps(on: Boolean) {
        if (!on) {
            store.gpsEnabled = false
            GpsService.stop(this)
            refreshStore()
            return
        }
        if (store.gpsCameraAddress.isBlank()) {
            showPairIntro = true
            return
        }
        withGpsPermissions {
            store.gpsEnabled = true
            GpsService.start(this)
            refreshStore()
        }
    }

    private var showPairIntro by mutableStateOf(false)

    private fun startPairing() {
        showPairIntro = false
        withGpsPermissions {
            pairer?.cancel()
            pairer = CameraPairing(this) { state ->
                pairing = state
                if (state is CameraPairing.State.Paired) {
                    store.gpsCameraAddress = state.device.address
                    store.gpsEnabled = true
                    GpsService.stop(this)
                    GpsService.start(this)
                    refreshStore()
                }
            }.also { it.start() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        store = Store(this)
        refreshStore()
        val nfcAvailable = NfcAdapter.getDefaultAdapter(this) != null
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

        val actions = UiActions(
            back = { finish() },
            sync = { SyncService.start(this) },
            stop = { if (SyncState.status.usb) UsbImportService.cancel(this) else SyncService.cancel(this) },
            copyUsb = { UsbImportService.start(this) },
            saveCamera = { ssid, pass ->
                store.ssid = ssid
                store.password = pass
                refreshStore()
            },
            setSmallCopies = { store.smallCopies = it; refreshStore() },
            shareLog = {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, SyncState.logText())
                }, "Share log"))
            },
            setGps = { setGps(it) },
            startPairing = { startPairing() },
            repairGps = { showPairIntro = true },
            openRemote = { startActivity(Intent(this, dev.kogler.a7iiiconnect.remote.RemoteActivity::class.java)) },
            setFtp = { on -> if (on) FtpService.start(this) else FtpService.stop(this) },
            dismissPairing = { pairer?.cancel(); pairer = null; pairing = null; showPairIntro = false },
        )

        setContent {
            AppTheme {
                App(
                    UiState(
                        status = status,
                        hasCamera = hasCamera,
                        cameraName = cameraName,
                        password = password,
                        smallCopies = smallCopies,
                        nfcAvailable = nfcAvailable,
                        version = version,
                        gps = gps,
                        gpsEnabled = gpsEnabled,
                        gpsPaired = gpsPaired,
                        pairing = pairing,
                        showPairIntro = showPairIntro,
                        ftp = ftp,
                    ),
                    actions,
                )
            }
        }
        if (savedInstanceState == null) turnOnGpsIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        turnOnGpsIfAsked(intent)
    }

    /** The Quick Settings tile couldn't start geotagging on its own and sent us here to do it. */
    private fun turnOnGpsIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_TURN_ON_GPS, false) != true) return
        intent.removeExtra(EXTRA_TURN_ON_GPS)
        setGps(true)
    }

    override fun onStart() {
        super.onStart()
        SyncState.observe(listener)
        GpsState.observe(gpsListener)
        FtpState.observe(ftpListener)
        // The hotspot may have been switched on (or got a new address) since the server started.
        if (FtpState.status.running) FtpState.update { it.copy(addresses = FtpService.addresses()) }
    }

    override fun onStop() {
        SyncState.remove(listener)
        GpsState.remove(gpsListener)
        FtpState.remove(ftpListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        CameraTouch.startReading(this)
    }

    override fun onPause() {
        CameraTouch.stopReading(this)
        super.onPause()
    }

    private fun refreshStore() {
        hasCamera = store.hasCamera
        cameraName = store.ssid
        password = store.password
        smallCopies = store.smallCopies
        gpsEnabled = store.gpsEnabled
        gpsPaired = store.gpsCameraAddress.isNotBlank()
    }

    private fun onStatus(s: SyncStatus) {
        // An NFC touch saves new camera details just before the sync starts.
        if (s.running != status.running) refreshStore()
        status = s
    }

    companion object {
        const val EXTRA_TURN_ON_GPS = "turn_on_gps"

        fun intent(context: Context) = Intent(context, MainActivity::class.java)

        /** For the location tile: the Camera screen, turning geotagging on as it opens. */
        fun openTurningOnGps(context: Context): PendingIntent = PendingIntent.getActivities(
            context, 1,
            arrayOf(
                Intent(context, DevelopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                intent(context).putExtra(EXTRA_TURN_ON_GPS, true),
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** For notifications: the Camera screen, with Photos behind it so Back lands there. */
        fun open(context: Context): PendingIntent = PendingIntent.getActivities(
            context, 0,
            arrayOf(
                Intent(context, DevelopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                intent(context),
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
