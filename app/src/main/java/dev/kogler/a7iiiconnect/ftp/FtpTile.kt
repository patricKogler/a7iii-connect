package dev.kogler.a7iiiconnect.ftp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kogler.a7iiiconnect.MainActivity
import dev.kogler.a7iiiconnect.SyncState

/** Quick Settings tile that turns the FTP receiver on and off and shows what it's doing. */
class FtpTile : TileService() {

    private val listener: (FtpStatus) -> Unit = { render(it) }

    override fun onStartListening() {
        super.onStartListening()
        FtpState.observe(listener)
    }

    override fun onStopListening() {
        FtpState.remove(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (FtpState.status.running) {
            FtpService.stop(this)
            return
        }
        try {
            FtpService.start(this)
        } catch (e: Exception) {
            // Some Android versions refuse a background service start; open the app instead.
            SyncState.log("FTP tile: can't start from the tile: $e")
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(MainActivity.open(this))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(MainActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun render(s: FtpStatus) {
        val tile = qsTile ?: return
        tile.state = if (s.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Camera FTP"
        tile.subtitle = when {
            s.running && s.received == 0 -> "Waiting"
            s.running -> "${s.received} received"
            s.error != null -> "Error"
            else -> "Off"
        }
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    companion object {
        /** Asks the system to refresh the tile, e.g. after the receiver was started from the app. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, FtpTile::class.java)) }
        }
    }
}
