package com.antutu.ABenchMark

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.security.MessageDigest

class MainActivity : Activity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 0
    private val TELEGRAM_CHANNEL_URL = "https://t.me/iqoo_neo10"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var appStarted = false
    private var accessCheckInProgress = false
    private var redirectStarted = false
    private var openChannelForInstalledVersion = false
    private var overlayPermissionCheckStarted = false
    private var connectivityCallbackRegistered = false
    private val offlineCheck = Runnable {
        if (appStarted && !isFinishing && !hasValidatedInternet()) {
            exitAndRedirect("Internet connection lost. Opening Telegram…")
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != connectivityManager.activeNetwork) return
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                mainHandler.removeCallbacks(offlineCheck)
            } else {
                scheduleOfflineCheck()
            }
        }

        override fun onLost(network: Network) {
            scheduleOfflineCheck()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasOfficialReleaseSignature()) {
            finishAndRemoveTask()
            return
        }

        openChannelForInstalledVersion = markFirstLaunchForInstalledVersion()
        setContentView(SpoofUi.createAccessLoadingView(this))
        verifyAppAccess()
    }

    private fun startApp() {
        appStarted = true
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        SpoofUi.applyHighestRefreshRatePreference(this)

        val suppressLaunchAd = openChannelForInstalledVersion
        if (!suppressLaunchAd) {
            StartIoAds.preloadLaunchAd(this)
        }
        setContentView(
            SpoofUi.create(
                activity = this,
                onLaunchAnimationComplete = {
                    if (!suppressLaunchAd) {
                        StartIoAds.showLaunchAdIfReady(this)
                    }
                }
            )
        )
        startConnectivityMonitoring()
    }

    private fun markFirstLaunchForInstalledVersion(): Boolean {
        val versionCode = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0L)
        ).longVersionCode
        val preferences = getSharedPreferences("app_launch_state", MODE_PRIVATE)
        val previouslyOpenedVersion = preferences.getLong("last_opened_version_code", -1L)
        if (previouslyOpenedVersion == versionCode) return false
        preferences.edit().putLong("last_opened_version_code", versionCode).commit()
        return true
    }

    private fun hasOfficialReleaseSignature(): Boolean {
        if (BuildConfig.DEBUG) return true

        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val signers = packageInfo.signingInfo?.apkContentsSigners ?: return false
        return signers.any { signature ->
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
            fingerprint == BuildConfig.RELEASE_CERT_SHA256
        }
    }

    override fun onResume() {
        super.onResume()
        if (appStarted) {
            SpoofUi.applyHighestRefreshRatePreference(this)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(offlineCheck)
        if (connectivityCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = false
        }
        super.onDestroy()
    }

    private fun verifyAppAccess() {
        if (accessCheckInProgress || isFinishing) return
        if (!hasValidatedInternet()) {
            exitAndRedirect("Internet connection required. Opening Telegram…")
            return
        }
        accessCheckInProgress = true
        PremiumAccess.checkAppAccess(this) { result ->
            accessCheckInProgress = false
            if (isFinishing) return@checkAppAccess
            if (result.error != null) {
                exitAndRedirect("Could not verify access online. Opening Telegram…")
                return@checkAppAccess
            }
            if (!result.allowed) {
                exitAndRedirect("Premium access required. Opening Telegram…")
                return@checkAppAccess
            }
            if (!appStarted) startApp()
            if (result.premium) StartIoAds.disableAllAds()
            if (openChannelForInstalledVersion) {
                openChannelForInstalledVersion = false
                openTelegramChannel()
                return@checkAppAccess
            }
            ensureOverlayPermission()
        }
    }

    private fun startConnectivityMonitoring() {
        if (!connectivityCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = true
        }
    }

    private fun scheduleOfflineCheck() {
        mainHandler.removeCallbacks(offlineCheck)
        mainHandler.postDelayed(offlineCheck, 500L)
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun ensureOverlayPermission() {
        if (overlayPermissionCheckStarted) return
        overlayPermissionCheckStarted = true
        checkOverlayPermission()
    }

    private fun exitAndRedirect(message: String) {
        if (redirectStarted || isFinishing) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSpoofingService()
        openTelegramChannelAndExit()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                Uri.parse("package:" + packageName)
            )

            startActivityForResult(
                intent,
                OVERLAY_PERMISSION_REQ_CODE
            )

            Toast.makeText(
                this,
                "Please allow 'Display over other apps' for performance boost",
                Toast.LENGTH_LONG
            ).show()
        } else {
            startPerformanceEngine()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                startPerformanceEngine()
            } else {
                Toast.makeText(
                    this,
                    "Permission denied. Performance spoofer inactive.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun startPerformanceEngine() {
        val intent = Intent(
            this,
            PerformanceService::class.java
        )

        startForegroundService(intent)
    }

    private fun stopSpoofingService() {
        StartIoAds.disableAllAds()
        stopService(Intent(this, PerformanceService::class.java))
    }

    private fun openTelegramChannelAndExit() {
        if (redirectStarted) return
        redirectStarted = true
        mainHandler.removeCallbacks(offlineCheck)
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(linkIntent)
            finish()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No browser or Telegram app found. Install one to open the invite.",
                Toast.LENGTH_LONG
            ).show()
            finishAndRemoveTask()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android blocked the Telegram link.", Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
        }
    }

    private fun openTelegramChannel() {
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHANNEL_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(linkIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No browser or Telegram app found. Install one to open the channel.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android blocked the Telegram link.", Toast.LENGTH_LONG).show()
        }
    }

}
