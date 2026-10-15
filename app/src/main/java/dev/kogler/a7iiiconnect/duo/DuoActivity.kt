package dev.kogler.a7iiiconnect.duo

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.kogler.a7iiiconnect.develop.Library
import dev.kogler.a7iiiconnect.develop.Similar
import dev.kogler.a7iiiconnect.ui.AppTheme
import dev.kogler.a7iiiconnect.develop.dayOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Together screen. Opened from Photos it hosts the chosen shots; opened by touching another
 * phone (or from Photos with nothing to review) it joins one.
 */
class DuoActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Even with some denied, try: Nearby says what's missing.
        Duo.startSearching()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // The other phone reads our NFC tag only while the screen is on, and the link likes it awake.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Duo.init(this)

        val keys = intent.getStringArrayListExtra(EXTRA_KEYS)
        if (savedInstanceState == null || !Duo.active) {
            if (keys != null && !keys.isEmpty()) startHosting(intent.getIntExtra(EXTRA_ROUND, 1), keys.toSet())
            else Duo.join(this, codeOf(intent))
        }
        permissions.launch(neededPermissions())

        setContent {
            AppTheme {
                DuoScreen(
                    onExit = { Duo.leave(); finish() },
                    onShareApp = { shareApp() },
                    onInstall = { installUpdate(it) },
                )
            }
        }
    }

    private fun startHosting(round: Int, keys: Set<String>) {
        Duo.host(this, round)
        lifecycleScope.launch {
            val stacks = withContext(Dispatchers.IO) {
                val shots = Library.shots(Library.load(this@DuoActivity)).filter { it.key in keys }
                // Like the culling deck: newest day first, each day in the order it was shot.
                Similar.stacks(this@DuoActivity, shots) { Duo.reportPreparing(it) }
                    .sortedByDescending { dayOf(it.first().takenMillis) }
            }
            Duo.dealDeck(stacks)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeOf(intent)?.let { Duo.join(this, it) }
    }

    override fun onResume() {
        super.onResume()
        // A host must not read tags: reader mode would switch off its own tag. A guest reads the
        // host's tag here itself, so the tap works with this screen open too.
        if (!Duo.hosting) {
            NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                this,
                { tag -> codeOf(tag)?.let { c -> runOnUiThread { Duo.join(this, c) } } },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B,
                null,
            )
        }
    }

    override fun onPause() {
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) Duo.leave()
        super.onDestroy()
    }

    /** Sends this app's own APK, through the share sheet (Quick Share), to a phone that hasn't got it. */
    private fun shareApp() {
        runCatching {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val apk = File(dir, "Shootback.apk")
            File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = APK_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send Shootback"))
        }.onFailure { Duo.notice = "Couldn't share the app: ${it.message}" }
    }

    private fun installUpdate(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Duo.notice = "Allow Shootback to install updates, then tap Install again"
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            Duo.notice = "No installer found"
        }
    }

    private fun neededPermissions(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    companion object {
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_ROUND = "round"
        private const val APK_TYPE = "application/vnd.android.package-archive"

        /** Host [keys] (shots due in culling round [round]); with no keys, join another phone. */
        fun intent(context: Context, round: Int = 1, keys: List<String> = emptyList()) =
            Intent(context, DuoActivity::class.java).apply {
                if (keys.isNotEmpty()) {
                    putExtra(EXTRA_ROUND, round)
                    putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys))
                }
            }

        /** Join the phone whose Together tag carried [code]. */
        fun joinIntent(context: Context, code: String) =
            Intent(context, DuoActivity::class.java).setData(Uri.parse("shootback://duo?code=$code"))

        /** The session code on a Together tag (another phone hosting), or null for any other tag. */
        fun codeOf(tag: Tag): String? = runCatching { codeOf(Ndef.get(tag)?.cachedNdefMessage) }.getOrNull()

        private fun codeOf(intent: Intent?): String? {
            val data = intent?.data ?: run {
                if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
                @Suppress("DEPRECATION")
                val msgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                return codeOf(msgs?.firstOrNull() as? NdefMessage)
            }
            return codeOf(data)
        }

        private fun codeOf(msg: NdefMessage?): String? =
            msg?.records?.firstNotNullOfOrNull { it.toUri() }?.let { codeOf(it) }

        private fun codeOf(uri: Uri): String? =
            uri.takeIf { it.scheme == "shootback" && it.host == "duo" }?.getQueryParameter("code")
    }
}
