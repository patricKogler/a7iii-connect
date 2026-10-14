package dev.kogler.a7iiiconnect.gps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Bluetooth LE link that feeds the phone's position to the camera, like Imaging Edge's
 * "Location Information Linkage". Uses autoConnect, so it reconnects by itself whenever the
 * camera is switched back on.
 *
 * Setup after each connect: MTU 158 (the record is 95 bytes) > discover > subscribe 0xDD01 >
 * 0xDD30 = 1 (lock) > 0xDD31 = 1 (enable) > read 0xDD21 (does it want time zone fields?).
 * 0xDD30/0xDD31 only exist on newer camera firmware and are skipped when missing.
 * Every discovery also logs all services and characteristics, to see whether this body has the
 * Wi-Fi handoff (0xCC06-0xCC08) and remote (0xFF01) characteristics newer Sony apps use.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before creating this.
class CameraGpsLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onReady: (Boolean) -> Unit,
    /** The camera turned location linking off, e.g. because its Bluetooth remote was switched on. */
    private val onCameraOff: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<Pair<String, () -> Boolean>>()
    private var busy = false
    private var withTimeZone = true

    @Volatile var ready = false
        private set

    fun start() {
        log("Bluetooth: connecting to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun send(latitude: Double, longitude: Double) {
        if (!ready) return
        val packet = SonyLocationPacket.build(latitude, longitude, System.currentTimeMillis(), withTimeZone)
        enqueue("location") { write(CHAR_LOCATION, packet) }
    }

    fun stop() {
        if (ready) {
            enqueue("disable") { write(CHAR_ENABLE, byteArrayOf(0)) }
            enqueue("unlock") { write(CHAR_LOCK, byteArrayOf(0)) }
        }
        // Give the two writes a moment, then drop the link.
        main.postDelayed({
            ready = false
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        }, 1500)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Bluetooth: connected, negotiating")
                if (!g.requestMtu(158)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Bluetooth: camera disconnected ($status), waiting for it to come back")
                setReady(false)
                queue.clear()
                busy = false
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = post {
            log("Bluetooth: MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = post {
            // Survey what this camera exposes (CC = Wi-Fi handoff on newer bodies, FF = remote).
            g.services.forEach { s ->
                val chars = s.characteristics.joinToString(" ") { "${it.uuid.short()}/${it.properties.toString(16)}" }
                log("Bluetooth: service ${s.uuid.toString().substring(0, 8)}: $chars")
            }
            if (g.getService(SERVICE) == null) {
                log("Bluetooth: camera has no location service. Is Location Info. Link Set. on?")
                onCameraOff()
                return@post
            }
            char(CHAR_STATUS)?.let { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(CCCD)?.let { d ->
                    enqueue("subscribe") {
                        @Suppress("DEPRECATION")
                        d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                }
            }
            if (char(CHAR_LOCK) != null) enqueue("lock") { write(CHAR_LOCK, byteArrayOf(1)) }
            if (char(CHAR_ENABLE) != null) enqueue("enable") { write(CHAR_ENABLE, byteArrayOf(1)) }
            enqueue("config") { char(CHAR_CONFIG)?.let { g.readCharacteristic(it) } ?: false }
        }

        @Deprecated("Still called on all supported Android versions")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val value = c.value?.clone()
            post {
                if (c.isA(CHAR_CONFIG)) {
                    if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.size > 4) {
                        withTimeZone = (value[4].toInt() and 0x02) != 0
                    }
                    log("Bluetooth: location link ready (config ${value?.toHex()}, time zone=$withTimeZone)")
                    setReady(status == BluetoothGatt.GATT_SUCCESS)
                }
                next()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = post {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Bluetooth: write ${c.uuid.short()} failed ($status)")
            if (c.isA(CHAR_LOCK) && status != BluetoothGatt.GATT_SUCCESS) {
                log("Bluetooth: camera refused the location lock. Close other apps linked to it.")
            }
            next()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int,
        ) = post { next() }

        @Deprecated("Still called on all supported Android versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = c.value?.clone()
            post {
                if (c.isA(CHAR_STATUS) && value?.contentEquals(byteArrayOf(3, 1, 2, 0)) == true) {
                    log("Bluetooth: location linking was switched off on the camera")
                    setReady(false)
                    onCameraOff()
                } else if (c.isA(CHAR_STATUS) && value?.contentEquals(byteArrayOf(3, 1, 2, 1)) == true) {
                    log("Bluetooth: location linking switched on on the camera")
                    g.discoverServices()
                }
            }
        }
    }

    private fun setReady(value: Boolean) {
        if (ready == value) return
        ready = value
        onReady(value)
    }

    // Sony's characteristic UUIDs share the 16-bit short form but not always the standard base,
    // so match on the first 8 hex digits like other clients of this protocol do.
    private fun char(short: String): BluetoothGattCharacteristic? =
        gatt?.getService(SERVICE)?.characteristics?.firstOrNull { it.uuid.toString().startsWith("0000$short") }

    private fun BluetoothGattCharacteristic.isA(short: String) = uuid.toString().startsWith("0000$short")

    @Suppress("DEPRECATION")
    private fun write(short: String, value: ByteArray): Boolean {
        val c = char(short) ?: return false
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = value
        return gatt?.writeCharacteristic(c) == true
    }

    private fun enqueue(name: String, op: () -> Boolean) = post {
        queue.addLast(name to op)
        if (!busy) next()
    }

    /** Runs queued GATT operations one at a time; Android drops overlapping ones. */
    private fun next() {
        busy = false
        while (queue.isNotEmpty()) {
            val (name, op) = queue.removeFirst()
            if (op()) {
                busy = true
                return
            }
            log("Bluetooth: $name could not be started")
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff")
        const val CHAR_STATUS = "dd01"
        const val CHAR_LOCATION = "dd11"
        const val CHAR_CONFIG = "dd21"
        const val CHAR_LOCK = "dd30"
        const val CHAR_ENABLE = "dd31"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun UUID.short() = toString().substring(4, 8)
    }
}
