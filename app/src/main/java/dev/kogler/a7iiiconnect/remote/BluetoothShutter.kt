package dev.kogler.a7iiiconnect.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The camera's "Bluetooth Rmt Ctrl" (Bluetooth-Fernbed.): a wireless shutter button over the
 * bond made for location linking. No live view, but no Wi-Fi either.
 *
 * Service 8000FF00, write 0xFF01 with two-byte button codes; 0xFF02 notifies focus and shutter
 * state (02 3F 20 = focus acquired, 02 A0 20 = shutter released by the camera).
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before creating this.
class BluetoothShutter(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onState: (State) -> Unit,
) {
    enum class State { CONNECTING, READY, UNAVAILABLE }

    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private val writes = LinkedBlockingQueue<Int>()
    private val events = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false

    fun start() {
        onState(State.CONNECTING)
        log("Remote: Bluetooth shutter connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stop() {
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /** Half press, wait for focus (or give up after a moment, e.g. in manual focus), full press, release. Blocking. */
    fun takePicture(): Boolean {
        events.clear()
        if (!send(FOCUS_DOWN)) return false
        val focused = waitFor(1500) { it.size >= 2 && it[1] == 0x3F.toByte() }
        if (!focused) log("Remote: no focus confirmation, shooting anyway")
        val ok = send(SHUTTER_DOWN)
        Thread.sleep(100)
        send(SHUTTER_UP)
        send(FOCUS_UP)
        return ok
    }

    @Suppress("DEPRECATION")
    private fun send(code: Int): Boolean {
        val g = gatt ?: return false
        val c = command ?: return false
        writes.clear()
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = byteArrayOf((code shr 8).toByte(), code.toByte())
        if (!g.writeCharacteristic(c)) {
            log("Remote: Bluetooth write ${"%04x".format(code)} could not be started")
            return false
        }
        if (c.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            Thread.sleep(50)
            return true
        }
        val status = writes.poll(2, TimeUnit.SECONDS)
        if (status != BluetoothGatt.GATT_SUCCESS) log("Remote: Bluetooth write ${"%04x".format(code)} failed ($status)")
        return status == BluetoothGatt.GATT_SUCCESS
    }

    private fun waitFor(ms: Long, match: (ByteArray) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: return false
            if (match(e)) return true
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Remote: Bluetooth shutter disconnected ($status)")
                command = null
                onState(State.CONNECTING)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val c = service?.characteristics?.firstOrNull { it.uuid.toString().startsWith("0000ff01") }
            if (c == null) {
                log("Remote: camera has no Bluetooth remote service. Is Bluetooth Rmt Ctrl on?")
                onState(State.UNAVAILABLE)
                return
            }
            command = c
            service.characteristics.firstOrNull { it.uuid.toString().startsWith("0000ff02") }?.let { n ->
                g.setCharacteristicNotification(n, true)
                n.getDescriptor(CCCD)?.let { d ->
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
            log("Remote: Bluetooth shutter ready (ff01 props ${c.properties.toString(16)})")
            onState(State.READY)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writes.offer(status)
        }

        @Deprecated("Still called on all supported Android versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = c.value?.clone() ?: return
            log("Remote: camera says ${value.joinToString("") { "%02x".format(it) }}")
            events.offer(value)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val FOCUS_UP = 0x0106
        const val FOCUS_DOWN = 0x0107
        const val SHUTTER_UP = 0x0108
        const val SHUTTER_DOWN = 0x0109
    }
}
