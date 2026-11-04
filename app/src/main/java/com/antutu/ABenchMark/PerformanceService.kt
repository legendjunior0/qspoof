package com.antutu.ABenchMark

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class PerformanceService : Service() {

    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (overlayView != null) {
            return START_NOT_STICKY
        }

        windowManager =
            getSystemService("window") as WindowManager

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        overlayView = View(this)
        overlayView?.setBackgroundColor(0x01000000)

        windowManager.addView(overlayView, params)
        startInfiniteUpdate()

        return START_NOT_STICKY
    }

    private fun startInfiniteUpdate() {
        val view = overlayView ?: return
        view.animate()
            .alpha(0.02f)
            .setDuration(10L)
            .withEndAction {
                val activeView = overlayView ?: return@withEndAction
                activeView.animate()
                    .alpha(0.01f)
                    .setDuration(10L)
                    .withEndAction {
                        startInfiniteUpdate()
                    }
                    .start()
            }
            .start()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        overlayView?.animate()?.cancel()
        overlayView?.let(windowManager::removeView)
        overlayView = null
        super.onDestroy()
    }
}
