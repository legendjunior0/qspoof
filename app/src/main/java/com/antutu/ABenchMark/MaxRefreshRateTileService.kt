package com.antutu.ABenchMark

import android.hardware.display.DisplayManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Display
import android.widget.Toast
import kotlin.math.roundToInt

class MaxRefreshRateTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val maxRate = maxSupportedRefreshRate()
        if (maxRate <= 0 || !Rms.isAvailable()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = "Not supported"
            tile.updateTile()
            Toast.makeText(this, "Max refresh rate is unavailable on this device", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val isMaxActive = Rms.selectedRate(this) == maxRate
        val succeeded = if (isMaxActive) Rms.reset() else Rms.vote(this, maxRate)
        if (!succeeded) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = "Request failed"
            tile.updateTile()
            Toast.makeText(this, "Could not update the refresh rate", Toast.LENGTH_SHORT).show()
            return
        }

        Rms.saveSelectedRate(this, if (isMaxActive) 0 else maxRate)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val maxRate = maxSupportedRefreshRate()
        if (maxRate <= 0 || !Rms.isAvailable()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = "Not supported"
        } else {
            val isMaxActive = Rms.selectedRate(this) == maxRate
            tile.state = if (isMaxActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = if (isMaxActive) "$maxRate Hz active" else "Tap for $maxRate Hz"
        }
        tile.label = "Max Hz"
        tile.updateTile()
    }

    private fun maxSupportedRefreshRate(): Int {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        return display.supportedModes.maxOfOrNull { it.refreshRate.roundToInt() } ?: 0
    }
}
