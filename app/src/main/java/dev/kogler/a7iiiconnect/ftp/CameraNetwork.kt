package dev.kogler.a7iiiconnect.ftp

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import dev.kogler.a7iiiconnect.SyncState
import java.security.SecureRandom

/**
 * Shootback's own Wi-Fi network for the camera's FTP Transfer: a Wi-Fi Direct group the camera
 * joins like any access point. Unlike the phone's hotspot, whose subnet Android picks at random
 * each time it starts, the group owner is always [ADDRESS], and the name and password stay the
 * same, so the camera is set up once. The phone keeps its own Wi-Fi and mobile data.
 *
 * Android drops a group a few seconds after its last client leaves, so while running this
 * puts it back whenever it goes away.
 */
class CameraNetwork(
    private val context: Context,
    private val onChange: (up: Boolean, error: String?) -> Unit,
) {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var running = false
    private var creating = false
    private var failures = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!running) return
            @Suppress("DEPRECATION")
            val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            if (group?.isGroupOwner == true) {
                failures = 0
                val clients = group.clientList.joinToString { it.deviceAddress }
                SyncState.log("FTP network: up on ${group.`interface`}, clients: ${clients.ifEmpty { "none" }}")
                onChange(true, null)
            } else if (!creating) {
                SyncState.log("FTP network: gone, starting it again")
                onChange(false, null)
                main.postDelayed({ create() }, 2_000)
            }
        }
    }

    fun start() {
        if (running) return
        if (manager == null) return onChange(false, "This phone has no Wi-Fi Direct")
        if (!hasPermission(context)) return onChange(false, "Open Shootback and allow Nearby devices for the camera network")
        running = true
        channel = manager.initialize(context, Looper.getMainLooper()) { channel = null }
        context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        create()
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        channel?.let { ch -> manager?.removeGroup(ch, null) }
        channel?.close()
        channel = null
        onChange(false, null)
    }

    @SuppressLint("MissingPermission")
    private fun create() {
        val ch = channel ?: return
        if (!running || creating) return
        creating = true
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid(context))
            .setPassphrase(passphrase(context))
            // The A7 III only does 2.4 GHz.
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()
        // A group left over from before (ours or another app's) would make createGroup fail as busy.
        manager!!.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createNow(ch, config)
            override fun onFailure(reason: Int) = createNow(ch, config)
        })
    }

    @SuppressLint("MissingPermission")
    private fun createNow(ch: WifiP2pManager.Channel, config: WifiP2pConfig) {
        manager!!.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                creating = false
                SyncState.log("FTP network: created ${config.networkName}")
            }

            override fun onFailure(reason: Int) {
                creating = false
                failures++
                val why = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct isn't supported"
                    WifiP2pManager.BUSY -> "Wi-Fi is busy"
                    else -> "Wi-Fi Direct error $reason"
                }
                SyncState.log("FTP network: can't create ($why), attempt $failures")
                if (!running) return
                if (failures < 5) {
                    main.postDelayed({ create() }, 3_000L * failures)
                } else {
                    onChange(false, "Couldn't open the camera network: $why. Is Wi-Fi on?")
                }
            }
        })
    }

    companion object {
        /** The group owner's address on every Android phone. */
        const val ADDRESS = "192.168.49.1"

        private const val PREFS = "ftp"

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

        /** Wi-Fi Direct names must start with "DIRECT-" and two more characters. */
        fun ssid(context: Context): String = "DIRECT-SB-Shootback"

        /** Made once per install and kept, so the camera only needs it once. */
        fun passphrase(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("network_password", null)?.let { return it }
            val letters = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            val made = (1..10).map { letters[random.nextInt(letters.length)] }.joinToString("")
            prefs.edit().putString("network_password", made).apply()
            return made
        }

        /** Whether the receiver opens its own network (default) or waits on the phone's hotspot. */
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("own_network", true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("own_network", on).apply()
        }
    }
}
