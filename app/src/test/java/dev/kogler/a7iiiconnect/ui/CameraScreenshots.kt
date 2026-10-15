package dev.kogler.a7iiiconnect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kogler.a7iiiconnect.Phase
import dev.kogler.a7iiiconnect.SyncStatus
import dev.kogler.a7iiiconnect.ftp.FtpStatus
import dev.kogler.a7iiiconnect.gps.GpsPhase
import dev.kogler.a7iiiconnect.gps.GpsStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the Camera screen to PNGs (app/build/screenshots). Run by the "Screenshots" workflow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class CameraScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private val state = UiState(
        status = SyncStatus(),
        hasCamera = true,
        cameraName = "DIRECT-A9E1:ILCE-7M3",
        password = "abcd1234",
        smallCopies = false,
        nfcAvailable = true,
        version = "0.16.0",
        gps = GpsStatus(phase = GpsPhase.LINKED, accuracyMeters = 4f),
        gpsEnabled = true,
        gpsPaired = true,
        pairing = null,
        showPairIntro = false,
        ftp = FtpStatus(),
    )

    private val actions = UiActions(
        back = {}, sync = {}, stop = {}, saveCamera = { _, _ -> }, setSmallCopies = {}, shareLog = {},
        setGps = {}, startPairing = {}, repairGps = {}, dismissPairing = {}, openRemote = {}, setFtp = {},
    )

    @Test
    fun ready() = shot("camera-1-ready", state)

    @Test
    fun copying() = shot(
        "camera-2-copying",
        state.copy(
            status = SyncStatus(running = true, phase = Phase.COPYING, done = 7, total = 23, currentFile = "DSC01234.JPG"),
        ),
    )

    @Test
    fun firstRun() = shot("camera-3-first-run", state.copy(hasCamera = false, cameraName = "", password = ""))

    private fun shot(name: String, s: UiState) {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(s, actions) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }
}
