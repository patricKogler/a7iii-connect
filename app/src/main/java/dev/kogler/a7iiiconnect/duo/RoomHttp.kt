package dev.kogler.a7iiiconnect.duo

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One HTTP request, as much of it as the review room needs. */
class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val body: ByteArray) {
    fun param(name: String) = query[name].orEmpty()
}

class HttpResponse(val status: Int, val type: String, val body: ByteArray, val cache: Boolean = false) {
    companion object {
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray())
        fun json(text: String) = HttpResponse(200, "application/json; charset=utf-8", text.toByteArray())
    }
}

/**
 * A small HTTP/1.1 server for the review room: one request per connection (no keep-alive), a
 * thread per request so a waiting long poll doesn't hold up the photos. Free of Android, so the
 * tests run it on the JVM. Listens on every interface: the phone's Wi-Fi, its hotspot and
 * Shootback's own network alike.
 */
class RoomHttp(private val handler: (HttpRequest) -> HttpResponse) {
    @Volatile private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "room-http").apply { isDaemon = true } }
    private val open = AtomicInteger()

    /** Starts on [port], or the next free one above it; returns the port it got. */
    fun start(port: Int, tries: Int = 10): Int {
        var last: Exception? = null
        for (p in port until port + tries) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
            } catch (e: Exception) {
                runCatching { s.close() }
                last = e
                continue
            }
            server = s
            Thread({ accept(s) }, "room-accept").apply { isDaemon = true }.start()
            return p
        }
        throw last ?: IllegalStateException("No free port")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (_: SocketException) { break }
            // Browsers open a few connections each; a cap keeps a stuck one from piling up threads.
            if (open.incrementAndGet() > MAX_OPEN) { open.decrementAndGet(); runCatching { client.close() }; continue }
            workers.execute {
                try { serve(client) } finally { open.decrementAndGet() }
            }
        }
    }

    private fun serve(client: Socket) = client.use { c ->
        runCatching {
            c.soTimeout = 15_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val req = read(input) ?: return@runCatching write(out, HttpResponse.text(400, "Bad request"))
            val res = try { handler(req) } catch (e: Exception) { HttpResponse.text(500, "Something went wrong: ${e.message}") }
            write(out, res)
        }
    }

    private fun write(out: OutputStream, res: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n")
            append("Content-Type: ").append(res.type).append("\r\n")
            append("Content-Length: ").append(res.body.size).append("\r\n")
            append("Cache-Control: ").append(if (res.cache) "private, max-age=86400" else "no-store").append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(res.body)
        out.flush()
    }

    companion object {
        private const val MAX_OPEN = 48
        private const val MAX_BODY = 64 * 1024

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            else -> if (status >= 500) "Server Error" else "Status"
        }

        /** Reads a request off [input]: the request line, the headers, and a body by Content-Length. */
        fun read(input: InputStream): HttpRequest? {
            val line = readLine(input) ?: return null
            val parts = line.split(' ')
            if (parts.size < 2) return null
            var length = 0
            while (true) {
                val h = readLine(input) ?: return null
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0 && h.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                    length = h.substring(colon + 1).trim().toIntOrNull() ?: return null
                }
            }
            if (length < 0 || length > MAX_BODY) return null
            val body = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(body, got, length - got)
                if (n < 0) return null
                got += n
            }
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q < 0) target else target.substring(0, q)
            return HttpRequest(parts[0].uppercase(), decode(path), if (q < 0) emptyMap() else parseQuery(target.substring(q + 1)), body)
        }

        fun parseQuery(text: String): Map<String, String> = text.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 8192) return null
            }
            return buf.toString("UTF-8")
        }
    }
}
