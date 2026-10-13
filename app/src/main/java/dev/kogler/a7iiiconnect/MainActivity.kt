package dev.kogler.a7iiiconnect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kogler.a7iiiconnect.ui.App
import dev.kogler.a7iiiconnect.ui.AppTheme
import dev.kogler.a7iiiconnect.ui.UiActions
import dev.kogler.a7iiiconnect.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private var status by mutableStateOf(SyncStatus())
    private var hasCamera by mutableStateOf(false)
    private var cameraName by mutableStateOf("")
    private var password by mutableStateOf("")
    private var smallCopies by mutableStateOf(false)
    private var photos by mutableStateOf(emptyList<Photo>())
    private var photoCount by mutableIntStateOf(0)
    private val listener: (SyncStatus) -> Unit = { onStatus(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = Store(this)
        refreshStore()
        val nfcAvailable = NfcAdapter.getDefaultAdapter(this) != null
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

        val actions = UiActions(
            sync = { SyncService.start(this) },
            stop = { SyncService.cancel(this) },
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
            openPhoto = { openImage(it) },
            openAlbum = { photos.firstOrNull()?.let { openImage(it) } },
        )

        setContent {
            // Re-read the album whenever a photo lands or a sync ends.
            LaunchedEffect(status.done, status.phase) { reloadPhotos() }
            AppTheme {
                App(
                    UiState(
                        status = status,
                        hasCamera = hasCamera,
                        cameraName = cameraName,
                        password = password,
                        smallCopies = smallCopies,
                        photos = photos,
                        photoCount = photoCount,
                        nfcAvailable = nfcAvailable,
                        version = version,
                    ),
                    actions,
                )
            }
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleNfc(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    override fun onStart() {
        super.onStart()
        SyncState.observe(listener)
    }

    override fun onStop() {
        SyncState.remove(listener)
        super.onStop()
    }

    // While the app is on screen it reads the camera's tag itself. Otherwise Android follows the
    // tag's pointer to Sony's Imaging Edge app and opens the Play Store.
    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag ->
                // Other tags (bank cards, the camera's FeliCa side) carry no Wi-Fi details; skip them.
                NfcPairing.read(tag)?.let { creds -> runOnUiThread { onCameraTouched(creds) } }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun onPause() {
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    private fun refreshStore() {
        hasCamera = store.hasCamera
        cameraName = store.ssid
        password = store.password
        smallCopies = store.smallCopies
    }

    private suspend fun reloadPhotos() {
        val (list, count) = withContext(Dispatchers.IO) {
            RecentPhotos.load(this@MainActivity) to RecentPhotos.count(this@MainActivity)
        }
        photos = list
        photoCount = count
    }

    private fun openImage(photo: Photo) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(photo.uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private var lastTouch = 0L
    private var wasRunning = false

    private fun onStatus(s: SyncStatus) {
        // Count the NFC quiet period from the end of a sync too, not just from the tap.
        if (wasRunning && !s.running) lastTouch = System.currentTimeMillis()
        wasRunning = s.running
        status = s
    }

    private fun handleNfc(intent: Intent?) {
        if (!NfcPairing.isNfcIntent(intent)) return
        NfcPairing.read(intent!!)?.let { onCameraTouched(it) }
    }

    /** Phone touched the camera: save its Wi-Fi details and start copying straight away. */
    private fun onCameraTouched(creds: NfcPairing.Credentials) {
        // Holding the phone near the camera re-reads the tag every few seconds. Starting over
        // makes the camera restart its Wi-Fi, which costs a minute, so ignore repeats.
        val now = System.currentTimeMillis()
        if (SyncState.status.running || now - lastTouch < NFC_REPEAT_MS) {
            SyncState.log("NFC tag read again, ignored")
            return
        }
        lastTouch = now
        store.ssid = creds.ssid
        store.password = creds.password
        refreshStore()
        SyncService.start(this)
    }

    companion object {
        private const val NFC_REPEAT_MS = 30_000L
    }
}
