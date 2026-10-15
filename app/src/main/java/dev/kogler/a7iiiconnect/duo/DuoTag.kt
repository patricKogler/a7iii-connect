package dev.kogler.a7iiiconnect.duo

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * While this phone hosts a Together session it acts as an NFC tag holding the session's link
 * ([Duo.tagUri]). Touching the other phone to its back opens Shootback there and joins. Android
 * only routes to us while the screen is on, which it is on the lobby.
 */
class DuoTag : HostApduService() {
    private var tag: NdefTag? = null

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val uri = Duo.tagUri ?: return byteArrayOf(0x6A, 0x82.toByte())
        // A new reader starts with SELECT by AID; give it a fresh tag in case the link changed.
        if (tag == null || apdu.size > 2 && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte()) tag = NdefTag(uri)
        return tag!!.respond(apdu)
    }

    override fun onDeactivated(reason: Int) {
        tag = null
    }
}
