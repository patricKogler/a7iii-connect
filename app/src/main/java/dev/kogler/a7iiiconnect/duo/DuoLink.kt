package dev.kogler.a7iiiconnect.duo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The phone-to-phone link, on Google's Nearby Connections (what Quick Share uses): the phones find
 * each other over Bluetooth, then move to a direct Wi-Fi link, so no shared network is needed.
 *
 * Messages are small JSON objects. A file goes as a JSON note `{"t":"file","pid":…}` describing it,
 * followed by the file itself; the receiver hands both over together once the file is complete.
 * Callbacks arrive on the main thread.
 */
class DuoLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onFound(endpointId: String, endpointName: String)
        fun onLost(endpointId: String)
        fun onConnected(endpointId: String, incoming: Boolean, endpointName: String)
        fun onDisconnected()
        /** Asking [endpointId] to connect didn't work out (and nothing else connected meanwhile). */
        fun onConnectFailed(endpointId: String)
        fun onMessage(msg: JSONObject)
        fun onFile(meta: JSONObject, file: File)
        /** A file on its way, in either direction. */
        fun onProgress(meta: JSONObject, fraction: Float, outgoing: Boolean)
        /** A file didn't make it, in either direction; it's asked for again next time. */
        fun onFileFailed(meta: JSONObject, outgoing: Boolean)
        fun onError(text: String)
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val inbox = File(context.filesDir, "duo/in").apply { mkdirs() }

    /** The other phone, once connected. */
    var endpoint: String? = null
        private set
    private val pending = HashMap<String, ConnectionInfo>()

    private val incomingFiles = HashMap<Long, Payload>()
    private val metas = HashMap<Long, JSONObject>()
    private val complete = HashSet<Long>()
    private val outgoing = HashMap<Long, JSONObject>()

    fun advertise(name: String) {
        client.startAdvertising(name, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { if (code(it) != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) fail("Couldn't let other phones find this one", it) }
    }

    fun discover() {
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { if (code(it) != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) fail("Couldn't look for phones nearby", it) }
    }

    fun stopSearching() {
        client.stopAdvertising()
        client.stopDiscovery()
    }

    fun connect(endpointId: String, myName: String) {
        client.requestConnection(myName, endpointId, lifecycle)
            .addOnFailureListener {
                Log.w(TAG, "requestConnection", it)
                // The other phone asked at the same moment, or it already worked: nothing went wrong.
                if (endpoint != null || code(it) == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
                if (endpointId !in pending) listener.onConnectFailed(endpointId)
            }
    }

    fun send(msg: JSONObject) {
        val ep = endpoint ?: return
        val bytes = msg.toString().toByteArray()
        if (bytes.size <= ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            client.sendPayload(ep, Payload.fromBytes(bytes))
        } else {
            // Too big for one message (a long deck): send it as a file instead.
            val f = File(context.cacheDir, "duo-msg-${System.nanoTime()}.json").apply { writeBytes(bytes) }
            sendFile(JSONObject().put("kind", KIND_JSON), Payload.fromFile(f))
        }
    }

    fun sendFile(meta: JSONObject, file: File) = sendFile(meta, Payload.fromFile(file))

    /** Sends the file at [uri]; false if it can't be read (gone since), so nothing goes. */
    fun sendUri(meta: JSONObject, uri: Uri): Boolean {
        if (endpoint == null) return false
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return false
        sendFile(meta, Payload.fromFile(pfd))
        return true
    }

    private fun sendFile(meta: JSONObject, payload: Payload) {
        val ep = endpoint ?: return
        meta.put("t", "file").put("pid", payload.id)
        outgoing[payload.id] = meta
        client.sendPayload(ep, Payload.fromBytes(meta.toString().toByteArray()))
        client.sendPayload(ep, payload)
    }

    fun close() {
        stopSearching()
        client.stopAllEndpoints()
        endpoint = null
        pending.clear()
        incomingFiles.clear(); metas.clear(); complete.clear(); outgoing.clear()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE) listener.onFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) = listener.onLost(endpointId)
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // One partner at a time. Both phones accept on their own: the tap (or the tap on a
            // name in the list) is the consent, like pairing headphones.
            if (endpoint != null) {
                client.rejectConnection(endpointId)
                return
            }
            pending[endpointId] = info
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val info = pending.remove(endpointId)
            if (!result.status.isSuccess || info == null) {
                if (endpoint == null) listener.onConnectFailed(endpointId)
                return
            }
            // Two connections came up at once (both phones asked): keep the first.
            if (endpoint != null && endpoint != endpointId) {
                client.disconnectFromEndpoint(endpointId)
                return
            }
            endpoint = endpointId
            stopSearching()
            listener.onConnected(endpointId, info.isIncomingConnection, info.endpointName)
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != endpoint) return
            endpoint = null
            incomingFiles.clear(); metas.clear(); complete.clear(); outgoing.clear()
            listener.onDisconnected()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val msg = runCatching { JSONObject(String(payload.asBytes()!!)) }.getOrNull() ?: return
                    if (msg.optString("t") == "file") {
                        val pid = msg.getLong("pid")
                        metas[pid] = msg
                        deliver(pid)
                    } else {
                        listener.onMessage(msg)
                    }
                }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pid = update.payloadId
            val sent = outgoing[pid]
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Below 1 until SUCCESS, so "done" is reported exactly once.
                    val f = if (update.totalBytes > 0) (update.bytesTransferred / update.totalBytes.toFloat()).coerceAtMost(0.99f) else 0f
                    if (sent != null) listener.onProgress(sent, f, true)
                    else metas[pid]?.let { listener.onProgress(it, f, false) }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (sent != null) {
                        outgoing.remove(pid)
                        listener.onProgress(sent, 1f, true)
                    } else if (pid in incomingFiles) {
                        complete += pid
                        deliver(pid)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFiles.remove(pid)
                    complete -= pid
                    outgoing.remove(pid)?.let { listener.onFileFailed(it, true) }
                    metas.remove(pid)?.let { listener.onFileFailed(it, false) }
                }
                else -> {}
            }
        }
    }

    /** Once both the note and the whole file are here: move the file into our folder and hand it over. */
    private fun deliver(pid: Long) {
        if (pid !in complete) return
        val meta = metas.remove(pid) ?: return
        val payload = incomingFiles.remove(pid) ?: return
        complete -= pid
        val dest = File(inbox, "$pid")
        io.execute {
            val ok = runCatching {
                val uri = payload.asFile()!!.asUri()!!
                context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
                runCatching { context.contentResolver.delete(uri, null, null) }
            }.onFailure { Log.w(TAG, "receiving file", it) }.isSuccess
            val json = if (ok && meta.optString("kind") == KIND_JSON) {
                runCatching { JSONObject(dest.readText()) }.getOrNull().also { dest.delete() }
            } else null
            main.post {
                when {
                    !ok -> listener.onFileFailed(meta, false)
                    json != null -> listener.onMessage(json)
                    meta.optString("kind") != KIND_JSON -> listener.onFile(meta, dest)
                }
            }
        }
    }

    private fun code(e: Exception) = (e as? ApiException)?.statusCode

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, what, e)
        val hint = when (code(e)) {
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> ". Check that Bluetooth and Wi-Fi are on."
            else -> ". Close Together and open it again."
        }
        listener.onError(what + hint)
    }

    companion object {
        private const val TAG = "DuoLink"
        private const val SERVICE = "dev.kogler.a7iiiconnect.duo"
        private const val KIND_JSON = "json"
        // One partner, so the fastest strategy: Bluetooth to find, then Wi-Fi Direct or a hotspot.
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }
}
