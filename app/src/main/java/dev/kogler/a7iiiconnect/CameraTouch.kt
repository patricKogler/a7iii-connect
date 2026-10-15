package dev.kogler.a7iiiconnect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter

/**
 * Touching the phone to the camera's N mark saves its Wi-Fi details and starts copying, from
 * whichever of our screens is open.
 */
object CameraTouch {
    /** Holding the phone near the camera re-reads the tag every few seconds. Starting over
     *  makes the camera restart its Wi-Fi, which costs a minute, so repeats are ignored. */
    private const val REPEAT_MS = 30_000L

    private var lastTouch = 0L
    private var wasRunning = false

    init {
        // Count the quiet period from the end of a sync too, not just from the tap.
        SyncState.observe { s ->
            if (wasRunning && !s.running) lastTouch = System.currentTimeMillis()
            wasRunning = s.running
        }
    }

    /**
     * While one of our screens is in front it reads the camera's tag itself. Otherwise Android
     * follows the tag's pointer to Sony's Imaging Edge app and opens the Play Store. Call from
     * onResume, and [stopReading] from onPause.
     */
    fun startReading(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.enableReaderMode(
            activity,
            { tag ->
                // Other tags (bank cards, the camera's FeliCa side) carry no Wi-Fi details; skip them.
                NfcPairing.read(tag)?.let { creds -> activity.runOnUiThread { touched(activity, creds) } }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    fun stopReading(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    /** The launcher activity was opened by the tag itself (the app was in the background). */
    fun handleIntent(context: Context, intent: Intent?) {
        if (!NfcPairing.isNfcIntent(intent)) return
        NfcPairing.read(intent!!)?.let { touched(context, it) }
    }

    private fun touched(context: Context, creds: NfcPairing.Credentials) {
        val now = System.currentTimeMillis()
        if (SyncState.status.running || now - lastTouch < REPEAT_MS) {
            SyncState.log("NFC tag read again, ignored")
            return
        }
        lastTouch = now
        Store(context).apply {
            ssid = creds.ssid
            password = creds.password
        }
        SyncService.start(context)
    }
}
