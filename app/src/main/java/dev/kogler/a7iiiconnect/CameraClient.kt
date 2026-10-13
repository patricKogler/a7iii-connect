package dev.kogler.a7iiiconnect

import android.net.Network
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** One downloadable rendition of a photo (full size, 2M copy, thumbnail ...). */
data class Resource(val url: String, val protocolInfo: String, val size: Long?, val resolution: String?)

data class CameraItem(
    val id: String,
    val title: String,
    val date: String?,
    val resources: List<Resource>,
)

/**
 * Talks to the camera's "Send to Smartphone" mode. The camera runs a UPnP/DLNA media server
 * (usually at 192.168.122.1:64321) and exposes the photos through a standard ContentDirectory.
 */
class CameraClient(private val network: Network, private val log: (String) -> Unit) {

    var baseUrl = "http://192.168.122.1:64321"
        private set
    private var contentDirectoryControl = "/upnp/control/ContentDirectory"

    /** Sends an SSDP M-SEARCH and returns the LOCATION header of the first Sony reply, if any. */
    fun discover(timeoutMs: Int = 3000): String? {
        val msg = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST:239.255.255.250:1900\r\n" +
            "ST:upnp:rootdevice\r\n" +
            "MX:2\r\n" +
            "MAN:\"ssdp:discover\"\r\n\r\n").toByteArray()
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = timeoutMs
            val target = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(msg, msg.size, target, 1900)) }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length)
                log("SSDP reply from ${packet.address.hostAddress}:\n${text.trim()}")
                val location = text.lineSequence()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                if (location != null) return location
            }
        }
        return null
    }

    /** Reads the device description and finds the ContentDirectory control URL. */
    fun loadDescription(descriptionUrl: String) {
        val url = URL(descriptionUrl)
        baseUrl = "${url.protocol}://${url.host}:${url.port}"
        val xml = httpGet(descriptionUrl)
        log("Device description (${xml.length} chars):\n${xml.take(2500)}")
        Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.let { log("Camera: ${it.groupValues[1]}") }
        val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (s in services) {
            val body = s.groupValues[1]
            if (body.contains("ContentDirectory")) {
                Regex("<controlURL>(.*?)</controlURL>").find(body)?.let {
                    contentDirectoryControl = it.groupValues[1].trim()
                }
            }
        }
        log("ContentDirectory control: $contentDirectoryControl")
    }

    data class BrowsePage(val containers: List<Pair<String, String>>, val items: List<CameraItem>, val total: Int)

    fun browse(objectId: String, start: Int = 0, count: Int = 100): BrowsePage {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val conn = network.openConnection(URL(baseUrl + contentDirectoryControl)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Browse $objectId failed: HTTP $code\n${response.take(1000)}")

        var didl = ""
        var total = 0
        val p = Xml.newPullParser()
        p.setInput(StringReader(response))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.substringAfter(':')) {
                "Result" -> didl = p.nextText()
                "TotalMatches" -> total = p.nextText().trim().toIntOrNull() ?: 0
            }
        }
        return parseDidl(didl, total)
    }

    private fun parseDidl(didl: String, total: Int): BrowsePage {
        val containers = mutableListOf<Pair<String, String>>()
        val items = mutableListOf<CameraItem>()
        if (didl.isBlank()) return BrowsePage(containers, items, total)

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(didl))
        var id = ""
        var title = ""
        var date: String? = null
        var inItem = false
        var resources = mutableListOf<Resource>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "container", "item" -> {
                        id = p.getAttributeValue(null, "id") ?: ""
                        title = ""
                        date = null
                        resources = mutableListOf()
                        inItem = p.name == "item"
                    }
                    "dc:title" -> title = p.nextText()
                    "dc:date" -> date = p.nextText()
                    "res" -> {
                        val info = p.getAttributeValue(null, "protocolInfo") ?: ""
                        val size = p.getAttributeValue(null, "size")?.toLongOrNull()
                        val res = p.getAttributeValue(null, "resolution")
                        resources += Resource(p.nextText().trim(), info, size, res)
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "container" -> containers += id to title
                    "item" -> if (inItem) items += CameraItem(id, title, date, resources)
                }
            }
        }
        return BrowsePage(containers, items, total)
    }

    /** Walks the whole tree from the root and returns every item found. */
    fun listAllItems(maxDepth: Int = 6): List<CameraItem> {
        val out = mutableListOf<CameraItem>()
        fun walk(objectId: String, name: String, depth: Int) {
            var start = 0
            while (true) {
                val page = browse(objectId, start)
                if (start == 0) {
                    log("${"  ".repeat(depth)}[$name] id=$objectId total=${page.total} " +
                        "containers=${page.containers.size} items=${page.items.size}")
                }
                out += page.items
                if (depth < maxDepth) page.containers.forEach { (cid, ctitle) -> walk(cid, ctitle, depth + 1) }
                val returned = page.containers.size + page.items.size
                start += returned
                if (returned == 0 || start >= page.total) break
            }
        }
        walk("0", "root", 0)
        return out
    }

    /** Picks the full-size rendition: the largest resource that isn't a thumbnail. */
    fun bestResource(item: CameraItem): Resource? =
        item.resources.maxByOrNull { it.size ?: resolutionPixels(it.resolution) }

    private fun resolutionPixels(r: String?): Long {
        val parts = r?.split('x') ?: return 0
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0) * (parts.getOrNull(1)?.toLongOrNull() ?: 0)
    }

    fun openStream(url: String): Pair<InputStream, HttpURLConnection> {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        return conn.inputStream to conn
    }

    private fun httpGet(url: String): String {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val DEFAULT_DESCRIPTION_URL = "http://192.168.122.1:64321/dd.xml"
    }
}
