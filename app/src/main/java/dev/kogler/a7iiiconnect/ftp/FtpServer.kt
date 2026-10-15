package dev.kogler.a7iiiconnect.ftp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/** Where uploaded files end up. */
interface UploadSink {
    /** Starts a file called [name]; the caller writes to [Upload.out], then commits or aborts. */
    fun open(name: String): Upload
}

interface Upload {
    val out: OutputStream
    fun commit()
    fun abort()
}

/**
 * Upload-only FTP server for the camera's "FTP Transfer" function (firmware 4.00+), which pushes
 * RAW and JPEG files as they are shot. It accepts any login and stores every STOR through [sink];
 * listings are always empty and directories are pretend, since the camera only needs uploads to
 * succeed. Plain FTP only: set Secure Protocol to Off on the camera.
 */
class FtpServer(
    private val port: Int,
    private val sink: UploadSink,
    private val log: (String) -> Unit,
    private val onReceived: (name: String, bytes: Long) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = s
        thread(name = "ftp-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: SocketException) { break }
                thread(name = "ftp-session", isDaemon = true) { Session(client).run() }
            }
        }
        log("FTP: listening on port $port")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private inner class Session(private val control: Socket) {
        private val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
        private val writer = control.getOutputStream()
        private var cwd = "/"
        private var passive: ServerSocket? = null
        private var active: InetSocketAddress? = null
        private val peer = control.inetAddress.hostAddress

        fun run() {
            log("FTP: $peer connected")
            try {
                control.soTimeout = 5 * 60_000
                reply("220 Shootback ready")
                while (true) {
                    val line = reader.readLine() ?: break
                    val space = line.indexOf(' ')
                    val cmd = (if (space < 0) line else line.substring(0, space)).uppercase()
                    val arg = if (space < 0) "" else line.substring(space + 1).trim()
                    if (cmd != "PASS") log("FTP: > $line")
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {
                log("FTP: $peer session ended: $e")
            } finally {
                closeData()
                runCatching { control.close() }
                log("FTP: $peer disconnected")
            }
        }

        /** Returns false to end the session. */
        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> reply("331 Password please")
                "PASS" -> reply("230 Logged in")
                "SYST" -> reply("215 UNIX Type: L8")
                "FEAT" -> reply("211-Features:\r\n EPSV\r\n PASV\r\n UTF8\r\n211 End")
                "OPTS", "TYPE", "MODE", "STRU", "NOOP" -> reply("200 OK")
                "PWD", "XPWD" -> reply("257 \"$cwd\"")
                "CWD", "XCWD" -> { cwd = resolve(arg); reply("250 OK") }
                "CDUP", "XCUP" -> { cwd = cwd.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }; reply("250 OK") }
                "MKD", "XMKD" -> reply("257 \"${resolve(arg)}\" created")
                "RMD", "XRMD", "DELE" -> reply("250 OK")
                "ALLO" -> reply("202 Not needed")
                "PASV" -> openPassive(extended = false)
                "EPSV" -> openPassive(extended = true)
                "PORT" -> setActive(arg)
                "LIST", "NLST", "MLSD" -> sendEmptyListing()
                "STOR", "APPE" -> receive(arg)
                "SIZE", "MDTM" -> reply("550 No such file")
                "RNFR" -> reply("350 Ready")
                "RNTO" -> { log("FTP: rename to $arg ignored"); reply("250 OK") }
                "QUIT" -> { reply("221 Bye"); return false }
                else -> reply("502 Not implemented")
            }
            return true
        }

        private fun resolve(path: String): String = when {
            path.isEmpty() -> cwd
            path.startsWith("/") -> path
            else -> cwd.trimEnd('/') + "/" + path
        }

        private fun openPassive(extended: Boolean) {
            closeData()
            // Bind to the address the camera reached us on, so the reply points at the right network.
            val local = control.localAddress
            val ss = ServerSocket(0, 1, local).apply { soTimeout = 30_000 }
            passive = ss
            val p = ss.localPort
            if (extended) {
                reply("229 Entering Extended Passive Mode (|||$p|)")
            } else {
                val ip = local.hostAddress!!.replace('.', ',')
                reply("227 Entering Passive Mode ($ip,${p shr 8},${p and 0xff})")
            }
        }

        private fun setActive(arg: String) {
            val parts = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 6) return reply("501 Bad PORT")
            closeData()
            val host = InetAddress.getByName(parts.take(4).joinToString("."))
            active = InetSocketAddress(host, parts[4] * 256 + parts[5])
            reply("200 PORT OK")
        }

        private fun openData(): Socket? {
            passive?.let { ss ->
                return try {
                    ss.accept().also { log("FTP: data connection from ${it.inetAddress.hostAddress}") }
                } finally {
                    runCatching { ss.close() }
                    passive = null
                }
            }
            active?.let { addr ->
                active = null
                return Socket().apply { connect(addr, 15_000) }
            }
            return null
        }

        private fun sendEmptyListing() {
            reply("150 Listing")
            val data = runCatching { openData() }.getOrNull()
                ?: return reply("425 No data connection")
            runCatching { data.close() }
            reply("226 Done")
        }

        private fun receive(arg: String) {
            val name = arg.substringAfterLast('/').ifBlank { return reply("501 No file name") }
            reply("150 Ready for $name")
            val data = try {
                openData()
            } catch (e: Exception) {
                log("FTP: no data connection for $name: $e")
                null
            } ?: return reply("425 No data connection")
            val upload = try {
                sink.open(name)
            } catch (e: Exception) {
                log("FTP: can't store $name: $e")
                runCatching { data.close() }
                return reply("451 Can't store file")
            }
            var bytes = 0L
            try {
                data.soTimeout = 60_000
                data.getInputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        upload.out.write(buf, 0, n)
                        bytes += n
                    }
                }
                upload.out.close()
                upload.commit()
            } catch (e: Exception) {
                log("FTP: $name failed after $bytes bytes: $e")
                runCatching { upload.out.close() }
                upload.abort()
                runCatching { data.close() }
                return reply("426 Transfer aborted")
            }
            log("FTP: received $name (${bytes / 1024} KB)")
            onReceived(name, bytes)
            reply("226 Stored")
        }

        private fun closeData() {
            runCatching { passive?.close() }
            passive = null
            active = null
        }

        private fun reply(text: String) {
            log("FTP: < ${text.substringBefore('\r')}")
            writer.write((text + "\r\n").toByteArray(Charsets.UTF_8))
            writer.flush()
        }
    }
}
