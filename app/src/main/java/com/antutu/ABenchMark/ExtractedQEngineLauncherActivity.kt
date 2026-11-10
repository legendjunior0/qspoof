package com.antutu.ABenchMark

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ExtractedQEngineLauncherActivity : ComponentActivity() {
    private val verificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && QEngineAuthorization.isAuthorized(this)) {
            launchExtractedQEngine()
        } else {
            openQspoof()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (QEngineAuthorization.isAuthorized(this)) {
            launchExtractedQEngine()
        } else {
            verificationLauncher.launch(
                Intent(this, MainActivity::class.java)
                    .setAction(QEngineAuthorization.ACTION_REVERIFY)
            )
        }
    }

    private fun launchExtractedQEngine() {
        val intent = Intent().setComponent(
            ComponentName(QENGINE_PACKAGE, "$QENGINE_PACKAGE.MainActivity")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Unable to open QEngine. Confirm it is installed.", Toast.LENGTH_LONG)
                .show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Unable to open QEngine. Confirm it is installed.", Toast.LENGTH_LONG)
                .show()
        } finally {
            finish()
        }
    }

    private fun openQspoof() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private companion object {
        const val QENGINE_PACKAGE = "com.antutu.ABenchMark"
    }
}
