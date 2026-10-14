package dev.kogler.a7iiiconnect.gps

import java.nio.ByteBuffer
import java.util.Calendar
import java.util.TimeZone

/**
 * The location record Sony cameras accept on BLE characteristic 0xDD11.
 * Layout from github.com/whc2001/ILCE7M3ExternalGps (PROTOCOL_EN.md), worked out on an A7III:
 *
 *  [0:1]   payload length excluding these two bytes (89, or 93 with time zone)
 *  [2:4]   08 02 FC
 *  [5]     03 when time zone fields follow, else 00
 *  [6:10]  00 00 10 10 10
 *  [11:14] latitude  * 1e7, big-endian int32
 *  [15:18] longitude * 1e7, big-endian int32
 *  [19:25] UTC year (2 bytes), month, day, hour, minute, second
 *  [26:90] zeros
 *  [91:92] UTC offset in minutes   (time zone variant only)
 *  [93:94] DST offset in minutes   (time zone variant only)
 */
object SonyLocationPacket {
    fun build(latitude: Double, longitude: Double, utcMillis: Long, withTimeZone: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(if (withTimeZone) 95 else 91)
        buf.putShort((if (withTimeZone) 93 else 89).toShort())
        buf.put(byteArrayOf(0x08, 0x02, 0xFC.toByte()))
        buf.put(if (withTimeZone) 0x03 else 0x00)
        buf.put(byteArrayOf(0x00, 0x00, 0x10, 0x10, 0x10))
        buf.putInt((latitude * 1e7).toInt())
        buf.putInt((longitude * 1e7).toInt())

        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        buf.putShort(utc.get(Calendar.YEAR).toShort())
        buf.put((utc.get(Calendar.MONTH) + 1).toByte())
        buf.put(utc.get(Calendar.DAY_OF_MONTH).toByte())
        buf.put(utc.get(Calendar.HOUR_OF_DAY).toByte())
        buf.put(utc.get(Calendar.MINUTE).toByte())
        buf.put(utc.get(Calendar.SECOND).toByte())

        buf.position(91)
        if (withTimeZone) {
            val tz = TimeZone.getDefault()
            buf.putShort((tz.rawOffset / 60_000).toShort())
            buf.putShort((if (tz.inDaylightTime(java.util.Date(utcMillis))) tz.dstSavings / 60_000 else 0).toShort())
        }
        return buf.array()
    }
}
