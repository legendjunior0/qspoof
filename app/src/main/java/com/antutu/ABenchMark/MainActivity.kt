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
    private var telegramChannelUrl = "https://t.me/iqoo_neo10_channel"
    private var telegramGroupUrl = "https://t.me/iqoo_neo10"
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
        // Access is revalidated by PremiumAccess when the configured cache expires.
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

        Rms.reset()
        Rms.saveSelectedRate(this, 0)
        openChannelForInstalledVersion = markFirstLaunchForInstalledVersion()
        setContentView(SpoofUi.createAccessLoadingView(this))
        verifyAppAccess()
    }

    private fun startApp() {
        appStarted = true
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val contentView = SpoofUi.create(
            activity = this,
            channelUrl = telegramChannelUrl,
            groupUrl = telegramGroupUrl,
            remoteConfig = PremiumAccess.lastRemoteConfig(this),
            onProfileClick = ::openDeviceProfile,
            onQspoofClick = {
                startActivity(Intent(this, RefreshRateActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            },
            onLaunchAnimationComplete = {
                StartIoAds.showLaunchAdIfReady(this)
            }
        )
        setContentView(contentView)
        contentView.postOnAnimation {
            if (!isFinishing) StartIoAds.preloadLaunchAd(this)
        }
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

    override fun onDestroy() {
        mainHandler.removeCallbacks(offlineCheck)
        if (connectivityCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = false
        }
        StartIoAds.releaseAdViews()
        super.onDestroy()
    }

    private fun verifyAppAccess() {
        if (accessCheckInProgress || isFinishing) return
        accessCheckInProgress = true
        PremiumAccess.checkAppAccess(this) { result ->
            accessCheckInProgress = !result.isFinalCheck
            if (isFinishing) return@checkAppAccess
            if (result.error != null) {
                exitAndRedirect("Could not verify access online. Opening Telegram…")
                return@checkAppAccess
            }
            telegramChannelUrl = result.channelUrl.takeIf { it.startsWith("https://") } ?: telegramChannelUrl
            telegramGroupUrl = result.groupUrl.takeIf { it.startsWith("https://") } ?: telegramGroupUrl
            if (result.updateRequired) {
                exitAndRedirectTo(result.updateMessage, result.updateUrl.ifBlank { telegramChannelUrl })
                return@checkAppAccess
            }
            if (!result.allowed) {
                exitAndRedirect("Premium access required. Opening Telegram…")
                return@checkAppAccess
            }
            if (!appStarted) startApp()
            if (openChannelForInstalledVersion) {
                openChannelForInstalledVersion = false
                openTelegramChannel()
                return@checkAppAccess
            }
            if (result.latestVersionCode > BuildConfig.VERSION_CODE &&
                result.updateUrl.isNotBlank() &&
                shouldShowOptionalUpdate(result.latestVersionCode)
            ) {
                showOptionalUpdate(
                    result.latestVersionCode,
                    result.displayVersion,
                    result.updateMessage,
                    result.releaseNotes,
                    result.updateUrl
                )
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

    private fun exitAndRedirectTo(message: String, url: String) {
        if (redirectStarted || isFinishing) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSpoofingService()
        openLinkAndExit(url)
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
        openLinkAndExit(telegramChannelUrl)
    }

    private fun openLinkAndExit(url: String) {
        if (redirectStarted) return
        redirectStarted = true
        mainHandler.removeCallbacks(offlineCheck)
        val safeUrl = url.takeIf { it.startsWith("https://") } ?: telegramChannelUrl
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
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
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramChannelUrl)).apply {
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

    private fun shouldShowOptionalUpdate(versionCode: Int): Boolean {
        val preferences = getPreferences(MODE_PRIVATE)
        val lastPromptedVersion = preferences.getInt("last_prompted_update_version", 0)
        if (versionCode <= lastPromptedVersion) return false
        preferences.edit().putInt("last_prompted_update_version", versionCode).apply()
        return true
    }

    private fun showOptionalUpdate(
        versionCode: Int,
        displayVersion: String,
        message: String,
        releaseNotes: String,
        url: String
    ) {
        val titleVersion = displayVersion.ifBlank { versionCode.toString() }
        val details = listOf(message, releaseNotes.trim())
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        android.app.AlertDialog.Builder(this)
            .setTitle("Update available (v$titleVersion)")
            .setMessage(details)
            .setNegativeButton("Later", null)
            .setPositiveButton("Open update") { _, _ -> openLinkAndExit(url) }
            .show()
    }

    private fun openDeviceProfile() {
        startActivity(Intent(this, DeviceProfileActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

}
