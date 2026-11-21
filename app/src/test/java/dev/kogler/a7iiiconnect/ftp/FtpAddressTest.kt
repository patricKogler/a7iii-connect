package dev.kogler.a7iiiconnect.ftp

import org.junit.Assert.assertEquals
import org.junit.Test

class FtpAddressTest {
    private val pixel8a = listOf(
        "rmnet1" to "100.79.178.181",
        "wlan0" to "192.168.0.5",
        "wlan1" to "10.192.156.219",
    )

    @Test fun pixelHotspotOnWlan1IsNotTheHomeWifi() {
        assertEquals("10.192.156.219" to "192.168.0.5", FtpService.pickAddresses(pixel8a, setOf("wlan0")))
    }

    @Test fun withoutAJoinedNetworkReadingWlan0IsStillTheClientSide() {
        assertEquals("10.192.156.219" to "192.168.0.5", FtpService.pickAddresses(pixel8a, emptySet()))
    }

    @Test fun hotspotOnly() {
        assertEquals("10.192.156.219" to null, FtpService.pickAddresses(listOf("wlan1" to "10.192.156.219"), emptySet()))
        assertEquals("192.168.43.1" to null, FtpService.pickAddresses(listOf("ap0" to "192.168.43.1"), emptySet()))
    }

    @Test fun homeWifiOnly() {
        assertEquals(null to "192.168.0.5", FtpService.pickAddresses(listOf("wlan0" to "192.168.0.5"), setOf("wlan0")))
    }

    @Test fun prefersApOverASecondWlan() {
        val all = listOf("wlan0" to "192.168.0.5", "wlan2" to "10.0.0.2", "swlan0" to "192.168.43.1")
        assertEquals("192.168.43.1" to "192.168.0.5", FtpService.pickAddresses(all, setOf("wlan0")))
    }
}
