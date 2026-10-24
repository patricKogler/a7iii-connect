package dev.kogler.a7iiiconnect.computer

import java.net.URLDecoder

/** The computer this phone is paired with, as its pairing code gave it. [host] is where it was last found. */
data class Computer(val id: String, val name: String, val host: String, val port: Int, val key: String) {
    companion object {
        /** Reads the desktop app's pairing code: `shootback://pair?v=1&id=…&name=…&host=…&port=…&key=…`. */
        fun parse(text: String): Computer? {
            val t = text.trim()
            if (!t.startsWith("shootback://pair?")) return null
            val q = t.substringAfter('?').split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
            if ((q["v"]?.toIntOrNull() ?: 0) != PROTOCOL) return null
            val id = q["id"]?.takeIf { it.isNotBlank() } ?: return null
            val key = q["key"]?.takeIf { it.length >= 16 } ?: return null
            val port = q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return Computer(id, q["name"]?.ifBlank { null } ?: "Computer", q["host"] ?: "", port, key)
        }

        const val PROTOCOL = 1
        const val SERVICE = "_shootback._tcp."
    }
}
