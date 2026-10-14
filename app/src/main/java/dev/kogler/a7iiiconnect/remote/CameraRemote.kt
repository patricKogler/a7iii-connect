package dev.kogler.a7iiiconnect.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import dev.kogler.a7iiiconnect.SyncState
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sony's Camera Remote API ("Ctrl w/ Smartphone" on the camera): JSON-RPC over HTTP plus a
 * Motion-JPEG-like live view stream. The endpoint is announced in the camera's device
 * description (dd.xml) as X_ScalarWebAPI_ActionList_URL.
 */
class CameraRemote(private val network: Network) {
    private val ids = AtomicInteger(1)
    private var cameraUrl = ""

    /** Finds the camera service in the device description. Returns false if the camera has none. */
    fun discover(descriptionUrl: String): Boolean {
        val xml = get(descriptionUrl)
        val services = Regex(
            "<av:X_ScalarWebAPI_Service>.*?<av:X_ScalarWebAPI_ServiceType>(.*?)</av:X_ScalarWebAPI_ServiceType>" +
                ".*?<av:X_ScalarWebAPI_ActionList_URL>(.*?)</av:X_ScalarWebAPI_ActionList_URL>.*?</av:X_ScalarWebAPI_Service>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
        SyncState.log("Remote: services $services")
        if (services.isEmpty()) SyncState.log("Remote: description without remote API:\n${xml.take(3000)}")
        val base = services["camera"] ?: return false
        cameraUrl = "$base/camera"
        return true
    }

    fun call(method: String, vararg params: Any, version: String = "1.0"): JSONArray {
        val body = JSONObject()
            .put("method", method)
            .put("params", JSONArray().apply { params.forEach { put(it) } })
            .put("id", ids.getAndIncrement())
            .put("version", version)
        val conn = network.openConnection(URL(cameraUrl)) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 40_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            reply.optJSONArray("error")?.let { throw RemoteError(method, it.optInt(0), it.optString(1)) }
            return reply.optJSONArray("result") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    fun availableApis(): Set<String> = runCatching {
        val list = call("getAvailableApiList").optJSONArray(0) ?: JSONArray()
        (0 until list.length()).map { list.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Takes a picture and returns the URL of the camera's preview copy, if it gave one.
     * If the camera refuses a plain shot (40400 "shooting fail", e.g. it couldn't focus in one go),
     * it makes sure the camera is in still mode, half-presses until focus settles, then shoots.
     */
    fun takePicture(apis: Set<String>): String? {
        try {
            return shoot()
        } catch (e: RemoteError) {
            if (e.code != 40400 && e.code != 1) throw e
            SyncState.log("Remote: plain shot refused (${e.code}), trying half press first")
        }
        if ("getShootMode" in apis) runCatching {
            val mode = call("getShootMode").optString(0)
            SyncState.log("Remote: shoot mode $mode")
            if (mode != "still" && "setShootMode" in apis) call("setShootMode", "still")
        }
        val half = "actHalfPressShutter" in apis
        if (half) {
            call("actHalfPressShutter")
            SyncState.log("Remote: focus ${waitForFocus()}")
        }
        try {
            return shoot()
        } finally {
            if (half) runCatching { call("cancelHalfPressShutter") }
        }
    }

    private fun shoot(): String? {
        val result = try {
            call("actTakePicture")
        } catch (e: RemoteError) {
            // 40403: still processing (long exposure); wait for it to finish.
            if (e.code == 40403) call("awaitTakePicture") else throw e
        }
        return result.optJSONArray(0)?.optString(0)?.takeIf { it.isNotBlank() }
    }

    /** Polls the camera's events for focusStatus until it settles; returns the last status seen. */
    private fun waitForFocus(): String {
        var status = "unknown"
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            val text = runCatching { call("getEvent", false).toString() }.getOrDefault("")
            Regex("\"focusStatus\"\\s*:\\s*\"(\\w+)\"").find(text)?.let { status = it.groupValues[1] }
            if (status == "Focused" || status == "Failed") return status
            Thread.sleep(200)
        }
        return status
    }

    fun downloadBitmap(url: String): Bitmap? = runCatching {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        conn.inputStream.use { BitmapFactory.decodeStream(it) }.also { conn.disconnect() }
    }.getOrNull()

    /**
     * Reads live view frames until [keepGoing] returns false or the stream ends.
     * Packet = 8-byte common header (0xFF, type, sequence, timestamp) + 128-byte payload header
     * (start code 24 35 68 79, 3-byte JPEG size, 1-byte padding size) + JPEG + padding.
     */
    fun streamLiveView(url: String, keepGoing: () -> Boolean, onFrame: (Bitmap) -> Unit) {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        try {
            val input = DataInputStream(conn.inputStream.buffered(64 * 1024))
            val common = ByteArray(8)
            val payload = ByteArray(128)
            var buffer = ByteArray(200_000)
            while (keepGoing()) {
                input.readFully(common)
                if (common[0] != 0xFF.toByte()) throw IOException("Live view out of sync")
                input.readFully(payload)
                if (payload[0] != 0x24.toByte() || payload[1] != 0x35.toByte() ||
                    payload[2] != 0x68.toByte() || payload[3] != 0x79.toByte()
                ) throw IOException("Bad live view header")
                val size = ((payload[4].toInt() and 0xff) shl 16) or
                    ((payload[5].toInt() and 0xff) shl 8) or (payload[6].toInt() and 0xff)
                val padding = payload[7].toInt() and 0xff
                if (buffer.size < size) buffer = ByteArray(size)
                input.readFully(buffer, 0, size)
                input.skipBytes(padding)
                if (common[1].toInt() == 0x01) {
                    BitmapFactory.decodeByteArray(buffer, 0, size)?.let(onFrame)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    class RemoteError(method: String, val code: Int, message: String) :
        IOException("$method failed: $code ${message.ifBlank { describe(code) }}")

    companion object {
        fun describe(code: Int) = when (code) {
            40400 -> "the camera couldn't take the picture (check focus, drive mode and the memory card)"
            40401 -> "camera not ready"
            40402 -> "already shooting"
            else -> ""
        }
    }
}
