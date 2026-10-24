package dev.kogler.a7iiiconnect.computer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pairing, kept in the app's preferences. */
class ComputerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("computer", Context.MODE_PRIVATE)

    var computer: Computer?
        get() {
            val id = prefs.getString("id", null) ?: return null
            return Computer(id, prefs.getString("name", null) ?: "Computer", prefs.getString("host", null) ?: "", prefs.getInt("port", 0), prefs.getString("key", null) ?: return null)
        }
        set(c) {
            prefs.edit().apply {
                if (c == null) listOf("id", "name", "host", "port", "key", "last").forEach { remove(it) }
                else putString("id", c.id).putString("name", c.name).putString("host", c.host).putInt("port", c.port).putString("key", c.key)
            }.apply()
        }

    var policy: PhotoPolicy
        get() = PhotoPolicy.entries.firstOrNull { it.name == prefs.getString("policy", null) } ?: PhotoPolicy.RECENT
        set(v) = prefs.edit().putString("policy", v.name).apply()

    /** Sync by itself when Photos opens. */
    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var lastSync: Long
        get() = prefs.getLong("last", 0)
        set(v) = prefs.edit().putLong("last", v).apply()
}

/** A request the computer turned down, with its answer. */
class HttpError(val status: Int, message: String) : IOException(message)

/**
 * Talks to the desktop app's sync server: plain HTTP on the LAN, every request with the pairing
 * key, this phone's id and its name (the computer lists the phone by it).
 */
class ComputerClient(var computer: Computer, private val phoneId: String, private val phoneName: String) {
    private fun open(method: String, path: String, timeout: Int = 15_000): HttpURLConnection {
        val c = URL("http://${hostPart(computer.host)}:${computer.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4_000
        c.readTimeout = timeout
        c.setRequestProperty("Authorization", "Bearer ${computer.key}")
        c.setRequestProperty("X-Shootback-Phone", phoneId)
        c.setRequestProperty("X-Shootback-Name", URLEncoder.encode(phoneName, "UTF-8"))
        return c
    }

    private fun hostPart(h: String) = if (':' in h && !h.startsWith("[")) "[$h]" else h

    private fun <T> call(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), read: (InputStream) -> T): T {
        val c = open(method, path)
        try {
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            if (status !in 200..299) throw HttpError(status, c.errorStream?.use { it.readBytes().decodeToString() } ?: "HTTP $status")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }

    fun text(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): String =
        call(method, path, body?.toByteArray(), headers) { it.readBytes().decodeToString() }

    fun bytes(method: String, path: String, body: ByteArray? = null): ByteArray = call(method, path, body) { it.readBytes() }

    /** Sends a photo; answers false when the computer already had it. */
    fun upload(name: String, taken: Long, size: Long, input: InputStream): Boolean {
        val c = open("PUT", "/v1/photo?name=${enc(name)}&taken=$taken", timeout = 120_000)
        try {
            c.doOutput = true
            if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(256 shl 10)
            c.outputStream.use { out -> input.copyTo(out, 256 shl 10) }
            val status = c.responseCode
            if (status !in 200..299) throw HttpError(status, c.errorStream?.use { it.readBytes().decodeToString() } ?: "HTTP $status")
            return status == 201
        } finally {
            c.disconnect()
        }
    }

    /** Fetches a photo into [to]. */
    fun download(key: String, to: File) {
        val c = open("GET", "/v1/photo?key=${enc(key)}", timeout = 120_000)
        try {
            val status = c.responseCode
            if (status !in 200..299) throw HttpError(status, "HTTP $status")
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 256 shl 10) } }
        } finally {
            c.disconnect()
        }
    }

    /** Whether the computer answers where it was last found. */
    fun reachable(): Boolean = runCatching { text("GET", "/v1/hello"); true }.getOrDefault(false)

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/**
 * Looks for the paired computer on the Wi-Fi by its id ([Computer.id] in the service's TXT
 * record), for when its address changed since pairing. Answers where it is now, or null.
 */
@Suppress("DEPRECATION")
fun discover(context: Context, id: String, timeoutMs: Long = 6_000): Pair<String, Int>? {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return null
    val found = CountDownLatch(1)
    var result: Pair<String, Int>? = null
    val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    val queue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val info = queue.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, error: Int) { resolving.set(false); resolveNext() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val txt = s.attributes["id"]?.decodeToString()
                val host = s.host?.hostAddress
                if (txt == id && host != null) { result = host to s.port; found.countDown() }
                resolving.set(false)
                resolveNext()
            }
        })
    }
    val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(type: String, error: Int) { found.countDown() }
        override fun onStopDiscoveryFailed(type: String, error: Int) {}
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onServiceFound(info: NsdServiceInfo) { queue += info; resolveNext() }
        override fun onServiceLost(info: NsdServiceInfo) {}
    }
    nsd.discoverServices(Computer.SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
    found.await(timeoutMs, TimeUnit.MILLISECONDS)
    runCatching { nsd.stopServiceDiscovery(listener) }
    return result
}
