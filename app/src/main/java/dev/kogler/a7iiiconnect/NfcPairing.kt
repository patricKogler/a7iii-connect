package dev.kogler.a7iiiconnect

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

/**
 * Reads the Wi-Fi details the camera hands out when the phone touches its N mark.
 * The camera's tag carries an NDEF record of type application/x-sony-pmm; its layout isn't
 * documented, so we pull the "DIRECT-xxxx:MODEL" network name and the 8-character password
 * out of the text and log the raw bytes to help adjust this if the camera differs.
 */
object NfcPairing {
    data class Credentials(val ssid: String, val password: String)

    private val SSID = Regex("DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}")
    private val LABELLED_PASSWORD = Regex("(?:password|pass|pwd)\\s*[:=]\\s*([A-Za-z0-9]{8,63})", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("[A-Za-z0-9]{8}")

    fun isNfcIntent(intent: Intent?) = intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED

    fun read(intent: Intent): Credentials? {
        @Suppress("DEPRECATION")
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.filterIsInstance<NdefMessage>().orEmpty()
        return read(messages)
    }

    /** Reads a tag picked up while the app is open (reader mode), before Android hands it to Sony's app. */
    fun read(tag: Tag): Credentials? {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            SyncState.log("NFC tag without NDEF data, techs=${tag.techList.joinToString()}")
            return null
        }
        val message = ndef.cachedNdefMessage ?: runCatching {
            ndef.connect()
            ndef.ndefMessage.also { ndef.close() }
        }.getOrNull()
        return read(listOfNotNull(message))
    }

    private fun read(messages: List<NdefMessage>): Credentials? {
        for (record in messages.flatMap { it.records.toList() }) {
            val payload = record.payload ?: continue
            SyncState.log("NFC record type=${String(record.type)} ${payload.size} bytes: " +
                payload.joinToString("") { "%02x".format(it) })
            val text = payload.map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else ' ' }
                .joinToString("")
            SyncState.log("NFC text: ${text.trim()}")
            parse(text)?.let { return it }
        }
        return null
    }

    fun parse(text: String): Credentials? {
        val ssid = SSID.find(text)?.value ?: return null
        LABELLED_PASSWORD.find(text)?.let { return Credentials(ssid, it.groupValues[1]) }
        // Otherwise the password is an 8-character token somewhere after the network name.
        val rest = text.substring(text.indexOf(ssid) + ssid.length)
        val password = TOKEN.findAll(rest).map { it.value }.firstOrNull()
            ?: TOKEN.findAll(text.substring(0, text.indexOf(ssid))).map { it.value }.lastOrNull()
            ?: return null
        return Credentials(ssid, password)
    }
}
