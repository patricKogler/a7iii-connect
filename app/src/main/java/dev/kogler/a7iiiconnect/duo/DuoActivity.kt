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
import dev.kogler.a7iiiconnect.ui.AppTheme
import java.io.File

/**
 * Together: the shared albums, and in each the review and the edit battles. While it's open this
 * phone is findable and acts as an NFC tag, so touching another phone (with Shootback open
 * anywhere) brings it in. Phones that share an album connect by themselves when both are here.
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
        Duo.start(this, codeOf(intent))
        // After start: a fresh start forgets any earlier invite.
        intent.getStringExtra(EXTRA_INVITE)?.let { AlbumSync.invite = it }
        permissions.launch(neededPermissions())

        val start = intent.getStringExtra(EXTRA_ROUTE) ?: Routes.HOME
        setContent {
            AppTheme {
                TogetherScreen(
                    start = start,
                    onExit = { finish() },
                    onShareApp = { shareApp() },
                    onInstall = { installUpdate(it) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeOf(intent)?.let { Duo.start(this, it) }
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
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_INVITE = "invite"
        private const val APK_TYPE = "application/vnd.android.package-archive"

        /** Every shared album. */
        fun intent(context: Context) = Intent(context, DuoActivity::class.java)

        /** One shared album; with [invite], the phone that connects next joins it (only its owner invites). */
        fun albumIntent(context: Context, album: String, invite: Boolean = false) =
            Intent(context, DuoActivity::class.java).putExtra(EXTRA_ROUTE, Routes.hub(album)).apply { if (invite) putExtra(EXTRA_INVITE, album) }

        /** A new edit battle in [album], over the shot with [key]. */
        fun battleIntent(context: Context, album: String, key: String) =
            Intent(context, DuoActivity::class.java).putExtra(EXTRA_ROUTE, Routes.newBattle(album, key))

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
