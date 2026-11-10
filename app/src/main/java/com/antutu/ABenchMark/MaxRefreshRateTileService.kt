package com.antutu.ABenchMark

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
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
        if (!isMaxActive && !QEngineAuthorization.isAuthorized(this)) {
            openQspoofForVerification()
            return
        }

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
            tile.subtitle = when {
                isMaxActive -> "$maxRate active"
                !QEngineAuthorization.isAuthorized(this) -> "Open QSpoof to verify"
                else -> "Tap for $maxRate"
            }
        }
        val isMaxActive = maxRate > 0 && Rms.selectedRate(this) == maxRate
        tile.label = "QHz"
        tile.icon = createQHzIcon(maxRate, isMaxActive)
        tile.updateTile()
    }

    private fun openQspoofForVerification() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Could not open QSpoof to verify access", Toast.LENGTH_LONG).show()
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(launchIntent)
            }
        } catch (error: SecurityException) {
            Toast.makeText(this, "Could not open QSpoof to verify access", Toast.LENGTH_LONG).show()
        }
    }

    private fun createQHzIcon(maxRate: Int, isMaxActive: Boolean): Icon {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }
        val text = if (isMaxActive && maxRate > 0) maxRate.toString() else "QHz"
        paint.textSize = if (text == "QHz") 60f else 76f
        paint.textAlign = Paint.Align.CENTER
        val textWidth = paint.measureText(text)
        if (textWidth > 116f) {
            paint.textScaleX = 116f / textWidth
        }
        val baseline = 64f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, 64f, baseline, paint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun maxSupportedRefreshRate(): Int {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        return display.supportedModes.maxOfOrNull { it.refreshRate.roundToInt() } ?: 0
    }
}
