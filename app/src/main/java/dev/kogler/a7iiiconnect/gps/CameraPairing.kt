package dev.kogler.a7iiiconnect.gps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dev.kogler.a7iiiconnect.SyncState

/**
 * Finds a Sony camera that is advertising over Bluetooth and bonds with it. Sony cameras put
 * manufacturer id 0x012D in their advert, followed by device type 0x0003 for cameras.
 */
@SuppressLint("MissingPermission") // The screen asks for Bluetooth permissions first.
class CameraPairing(
    private val context: Context,
    private val onUpdate: (State) -> Unit,
) {
    sealed interface State {
        data object Searching : State
        data class Pairing(val name: String) : State
        data class Paired(val device: BluetoothDevice, val name: String) : State
        data class Failed(val reason: String) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var target: BluetoothDevice? = null
    private var finished = false

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (target != null) return
            target = result.device
            stopScan()
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Sony camera"
            SyncState.log("Bluetooth: found $name (${result.device.address}), bond=${result.device.bondState}")
            if (result.device.bondState == BluetoothDevice.BOND_BONDED) {
                finish(State.Paired(result.device, name))
            } else {
                onUpdate(State.Pairing(name))
                if (!result.device.createBond()) finish(State.Failed("Android didn't start pairing"))
            }
        }

        override fun onScanFailed(errorCode: Int) = finish(State.Failed("Bluetooth scan failed ($errorCode)"))
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device?.address != target?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> finish(State.Paired(device!!, device.name ?: "Sony camera"))
                BluetoothDevice.BOND_NONE -> finish(State.Failed("Pairing was declined or timed out"))
            }
        }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            finish(State.Failed("Turn on Bluetooth first"))
            return
        }
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        onUpdate(State.Searching)
        val filter = ScanFilter.Builder()
            .setManufacturerData(SONY, byteArrayOf(0x03, 0x00), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scan)
        main.postDelayed({ if (target == null) finish(State.Failed("No camera found. Is the camera in pairing mode?")) }, 30_000)
    }

    fun cancel() = finish(null)

    private fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private fun finish(state: State?) {
        if (finished) return
        finished = true
        stopScan()
        runCatching { context.unregisterReceiver(bondReceiver) }
        main.removeCallbacksAndMessages(null)
        state?.let { onUpdate(it) }
    }

    companion object {
        private const val SONY = 0x012D
    }
}
