package com.antutu.ABenchMark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

class QSpoofActivationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundTask = Runnable {
        moveTaskToBack(true)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow display over other apps before activating QSpoof",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        try {
            startService(Intent(this, QSpoofOverlayService::class.java))
            handler.postDelayed(backgroundTask, BACKGROUND_DELAY_MS)
        } catch (error: SecurityException) {
            Toast.makeText(this, "Could not activate the QSpoof overlay", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(backgroundTask)
        super.onDestroy()
    }

    private companion object {
        const val BACKGROUND_DELAY_MS = 500L
    }
}
