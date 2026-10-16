package dev.kogler.a7iiiconnect.gps

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kogler.a7iiiconnect.MainActivity
import dev.kogler.a7iiiconnect.Store
import dev.kogler.a7iiiconnect.SyncState

/** Quick Settings tile that turns geotagging (phone location to the camera) on and off. */
class GpsTile : TileService() {

    private val listener: (GpsStatus) -> Unit = { render(it) }

    override fun onStartListening() {
        super.onStartListening()
        GpsState.observe(listener)
    }

    override fun onStopListening() {
        GpsState.remove(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val store = Store(this)
        if (GpsState.status.phase != GpsPhase.OFF) {
            store.gpsEnabled = false
            GpsService.stop(this)
            return
        }
        // Not paired yet, a permission is missing, or the last start from here was refused:
        // the Camera screen sorts that out and turns geotagging on.
        val permitted = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (store.gpsCameraAddress.isBlank() || !permitted || GpsState.status.startRefused) {
            openApp()
            return
        }
        try {
            store.gpsEnabled = true
            GpsService.start(this)
        } catch (e: Exception) {
            SyncState.log("Location tile: can't start from the tile: $e")
            openApp()
        }
    }

    private fun openApp() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(MainActivity.openTurningOnGps(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                MainActivity.intent(this).putExtra(MainActivity.EXTRA_TURN_ON_GPS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun render(s: GpsStatus) {
        val tile = qsTile ?: return
        tile.state = if (s.phase == GpsPhase.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Geotag photos"
        tile.subtitle = when {
            s.offOnCamera -> "Off on camera"
            s.phase == GpsPhase.LINKED -> "Sending location"
            s.phase == GpsPhase.WAITING_FIX -> "Waiting for GPS"
            s.phase == GpsPhase.WAITING_CAMERA -> "Waiting for camera"
            s.startRefused -> "Tap to open app"
            else -> "Off"
        }
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    companion object {
        private val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        /** Asks the system to refresh the tile, e.g. after geotagging was switched from the app. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, GpsTile::class.java)) }
        }
    }
}
