package com.antutu.ABenchMark

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

class QSpoofOverlayService : Service() {

    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
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

        windowManager.addView(
            overlayView,
            params as ViewGroup.LayoutParams
        )

        startInfiniteUpdate()

        return START_STICKY
    }

    private fun startInfiniteUpdate() {
        overlayView?.animate()
            ?.alpha(0.02f)
            ?.setDuration(10)
            ?.withEndAction {
                overlayView?.animate()
                    ?.alpha(0.01f)
                    ?.setDuration(10)
                    ?.withEndAction {
                        startInfiniteUpdate()
                    }
                    ?.start()
            }
            ?.start()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()

        if (overlayView != null) {
            windowManager.removeView(overlayView)
        }

        overlayView = null
    }
}
