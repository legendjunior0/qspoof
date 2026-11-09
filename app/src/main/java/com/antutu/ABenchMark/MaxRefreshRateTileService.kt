package com.antutu.ABenchMark

import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
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
        val isMaxActive = maxRate > 0 && Rms.selectedRate(this) == maxRate
        tile.label = if (isMaxActive) "$maxRate Hz" else "QHz"
        tile.icon = createQHzIcon(maxRate, isMaxActive)
        tile.updateTile()
    }

    private fun createQHzIcon(maxRate: Int, isMaxActive: Boolean): Icon {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        if (!isMaxActive || maxRate <= 0) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 34f
            val baseline = 48f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText("QHz", 48f, baseline, paint)
        } else {
            val rateText = maxRate.toString()
            paint.textSize = 43f
            paint.textAlign = Paint.Align.LEFT
            val rateWidth = paint.measureText(rateText)
            val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.LEFT
                textSize = 9f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val totalWidth = rateWidth + suffixPaint.measureText("Hz") + 2f
            val startX = (96f - totalWidth) / 2f
            val baseline = 48f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(rateText, startX, baseline, paint)
            canvas.drawText("Hz", startX + rateWidth + 2f, baseline - 12f, suffixPaint)
        }
        return Icon.createWithBitmap(bitmap)
    }

    private fun maxSupportedRefreshRate(): Int {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        return display.supportedModes.maxOfOrNull { it.refreshRate.roundToInt() } ?: 0
    }
}
