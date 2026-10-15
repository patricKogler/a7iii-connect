package dev.kogler.a7iiiconnect.duo

/**
 * An NFC Forum Type 4 tag holding one URI, answered APDU by APDU. The phone pretends to be this
 * tag (host card emulation), so touching another phone to it opens the URI there, like a sticker.
 * Plain Kotlin, so it can be tested on the JVM.
 */
class NdefTag(uri: String) {
    private val ndefFile: ByteArray = run {
        val msg = uriMessage(uri)
        byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg
    }
    private var appSelected = false
    private var file: ByteArray? = null

    fun respond(apdu: ByteArray): ByteArray {
        if (apdu.size < 4 || apdu[0] != 0.toByte()) return UNKNOWN
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            0xA4 -> {
                val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
                val data = apdu.copyOfRange(5.coerceAtMost(apdu.size), (5 + lc).coerceAtMost(apdu.size))
                when {
                    p1 == 0x04 -> {
                        appSelected = data.contentEquals(AID)
                        file = null
                        if (appSelected) OK else NOT_FOUND
                    }
                    p1 == 0x00 && appSelected && data.size == 2 -> {
                        file = when ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)) {
                            0xE103 -> CC
                            0xE104 -> ndefFile
                            else -> null
                        }
                        if (file != null) OK else NOT_FOUND
                    }
                    else -> NOT_FOUND
                }
            }
            0xB0 -> {
                val f = file ?: return NOT_ALLOWED
                val offset = p1 shl 8 or p2
                val le = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                if (offset > f.size) return WRONG_OFFSET
                f.copyOfRange(offset, (offset + le).coerceAtMost(f.size)) + OK
            }
            else -> UNKNOWN
        }
    }

    companion object {
        /** The NDEF tag application. */
        val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val NOT_ALLOWED = byteArrayOf(0x69, 0x86.toByte())
        private val WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        private val UNKNOWN = byteArrayOf(0x6D, 0x00)

        /** Capability container: mapping 2.0, read size 0x3B, NDEF file E104 up to 1 KB, read-only. */
        private val CC = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34,
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x04, 0x00, 0x00, 0xFF.toByte(),
        )

        /** One short well-known "U" record, without a prefix abbreviation. */
        fun uriMessage(uri: String): ByteArray {
            val payload = byteArrayOf(0x00) + uri.toByteArray(Charsets.UTF_8)
            require(payload.size < 256) { "URI too long for a short record" }
            return byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'U'.code.toByte()) + payload
        }
    }
}
