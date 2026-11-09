package com.antutu.ABenchMark

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.Locale

class MainActivity : Activity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 0
    private val UPDATE_INSTALL_PERMISSION_REQ_CODE = 1
    private val UPDATE_DOWNLOAD_NONE = -1L
    private val UPDATE_DOWNLOAD_ID_KEY = "update_download_id"
    private val UPDATE_DOWNLOAD_HASH_KEY = "update_download_hash"
    private val UPDATE_FALLBACK_URL_KEY = "update_download_fallback"
    private var telegramChannelUrl = "https://t.me/iqoo_neo10_channel"
    private var telegramGroupUrl = "https://t.me/iqoo_neo10"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private var appStarted = false
    private var accessCheckInProgress = false
    private var redirectStarted = false
    private var updateDialogShowing = false
    private var updateFallbackAttempted = false
    private var updateDownloadId = UPDATE_DOWNLOAD_NONE
    private var updateFallbackUrl = ""
    private var updateSha256 = ""
    private var updateDialog: Dialog? = null
    private var updateButton: Button? = null
    private var requiredUpdate = false
    private var updateVerificationInProgress = false
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
        if (!OfficialRelease.hasValidSignature(this)) {
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
                InMobiAds.showLaunchAdIfReady()
            }
        )
        setContentView(contentView)
        contentView.postOnAnimation {
            if (!isFinishing) InMobiAds.preloadLaunchAd(this)
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

    override fun onDestroy() {
        mainHandler.removeCallbacks(offlineCheck)
        mainHandler.removeCallbacks(updateDownloadPoll)
        if (connectivityCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = false
        }
        InMobiAds.release()
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
                showRequiredUpdateDialog(
                    result.updateUrl,
                    result.updateFallbackUrl,
                    result.apkSha256,
                    result.updateMessage,
                    result.channelUrl
                )
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
                shouldShowReleaseNotice(result.latestVersionCode)
            ) {
                showLatestReleaseNotice(
                    result.latestVersionCode,
                    result.displayVersion,
                    result.updateMessage,
                    result.releaseNotes,
                    result.updateUrl,
                    result.updateFallbackUrl,
                    result.apkSha256,
                    result.channelUrl,
                    result.remoteConfig.optJSONObject("updatePopup")
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
        } else if (requestCode == UPDATE_INSTALL_PERMISSION_REQ_CODE) {
            if (packageManager.canRequestPackageInstalls()) {
                installCompletedUpdate()
            } else {
                Toast.makeText(this, "Allow installs from QSpoof to install the update.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (updateDialogShowing) refreshUpdateButton()
    }

    private fun startPerformanceEngine() {
        val intent = Intent(
            this,
            PerformanceService::class.java
        )

        startForegroundService(intent)
    }

    private fun stopSpoofingService() {
        InMobiAds.release()
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

    private fun shouldShowReleaseNotice(versionCode: Int): Boolean {
        val preferences = getPreferences(MODE_PRIVATE)
        val lastPromptedVersion = preferences.getInt("last_prompted_update_version", 0)
        if (versionCode <= lastPromptedVersion) return false
        preferences.edit().putInt("last_prompted_update_version", versionCode).apply()
        return true
    }

    private fun showLatestReleaseNotice(
        versionCode: Int,
        displayVersion: String,
        fallbackMessage: String,
        releaseNotes: String,
        updateUrl: String,
        fallbackUrl: String,
        apkSha256: String,
        redirectUrl: String,
        popup: org.json.JSONObject?
    ) {
        if (isFinishing) return
        val title = popup?.optString("title").orEmpty().ifBlank { "Update available" }
        val message = popup?.optString("message").orEmpty().ifBlank {
            fallbackMessage.ifBlank { "A newer version is available." }
        }
        val imageUrl = popup?.optString("imageUrl").orEmpty()
        val savedButtonText = popup?.optString("downloadButtonText").orEmpty()
        val updateButtonText = savedButtonText.ifBlank { "Download update" }
        val versionLabel = displayVersion.ifBlank { "latest version" }
        val details = buildString {
            appendLine("Available version: $versionLabel (build $versionCode)")
            if (releaseNotes.isNotBlank()) {
                appendLine()
                append(releaseNotes.trim())
            }
        }
        val (dialog, downloadButton) = createUpdateDialog(
            title = title,
            subtitle = message,
            details = details,
            imageUrl = imageUrl,
            downloadText = updateButtonText,
            required = false,
        )
        dialog.setOnShowListener {
            updateDialog = dialog
            updateButton = downloadButton
            updateFallbackUrl = fallbackUrl
            updateSha256 = apkSha256
            requiredUpdate = false
            updateDialogShowing = true
            downloadButton.setOnClickListener { startOrInstallUpdate(updateUrl, redirectUrl) }
            refreshUpdateButton()
        }
        dialog.setOnDismissListener {
            updateDialogShowing = false
            updateDialog = null
            updateButton = null
            mainHandler.removeCallbacks(updateDownloadPoll)
        }
        dialog.show()
        styleUpdateDialogWindow(dialog)
    }

    private fun showRequiredUpdateDialog(
        updateUrl: String,
        fallbackUrl: String,
        apkSha256: String,
        message: String,
        redirectUrl: String
    ) {
        if (isFinishing || updateDialogShowing) return
        val (dialog, downloadButton) = createUpdateDialog(
            title = "Software update required",
            subtitle = message.ifBlank { "Install the required update to continue." },
            details = "A newer version is required to continue using the app.",
            imageUrl = "",
            downloadText = "Download update",
            required = true,
        )
        dialog.setOnShowListener {
            updateDialog = dialog
            updateButton = downloadButton
            updateFallbackUrl = fallbackUrl
            updateSha256 = apkSha256
            requiredUpdate = true
            updateDialogShowing = true
            downloadButton.setOnClickListener { startOrInstallUpdate(updateUrl, redirectUrl) }
            refreshUpdateButton()
        }
        dialog.setOnDismissListener {
            updateDialogShowing = false
            updateDialog = null
            updateButton = null
            mainHandler.removeCallbacks(updateDownloadPoll)
        }
        dialog.show()
        styleUpdateDialogWindow(dialog)
    }

    private fun createUpdateDialog(
        title: String,
        subtitle: String,
        details: String,
        imageUrl: String,
        downloadText: String,
        required: Boolean,
    ): Pair<Dialog, Button> {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 19, 22))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(54, 57, 63))
            }
        }
        card.addView(TextView(this).apply {
            text = "QSPOOF UPDATE"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 142, 70))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(51, 30, 23))
                cornerRadius = dp(20).toFloat()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        card.addView(TextView(this).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(246, 247, 249))
            maxLines = 2
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(181, 185, 193))
            setLineSpacing(dp(2).toFloat(), 1.08f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7); bottomMargin = dp(14) })
        card.addView(View(this).apply {
            setBackgroundColor(Color.rgb(47, 49, 55))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        card.addView(TextView(this).apply {
            text = "WHAT'S NEW"
            textSize = 10f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 137, 71))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(13); bottomMargin = dp(8) })

        if (imageUrl.startsWith("https://")) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Update information"
                tag = imageUrl
                background = GradientDrawable().apply {
                    setColor(Color.rgb(29, 31, 35))
                    cornerRadius = dp(14).toFloat()
                }
                clipToOutline = true
            }
            card.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(108)
            ).apply { bottomMargin = dp(8) })
            SpoofUi.loadPopupImage(image, imageUrl)
        }

        val detailsScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        detailsScroll.addView(TextView(this).apply {
            text = details
            textSize = 13f
            setTextColor(Color.rgb(226, 228, 232))
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setPadding(0, dp(2), 0, dp(8))
        })
        card.addView(detailsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(if (imageUrl.startsWith("https://")) 112 else 142)
        ))
        card.addView(View(this).apply {
            setBackgroundColor(Color.rgb(47, 49, 55))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val downloadButton = Button(this).apply {
            text = downloadText
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(Color.rgb(255, 58, 48), Color.rgb(255, 105, 0))
                cornerRadius = dp(14).toFloat()
            }
            minHeight = dp(50)
            stateListAnimator = null
        }
        card.addView(downloadButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { topMargin = dp(12); bottomMargin = dp(4) })

        if (!required) {
            card.addView(View(this).apply {
                setBackgroundColor(Color.rgb(47, 49, 55))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            card.addView(Button(this).apply {
                text = "Remind me later"
                isAllCaps = false
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.rgb(218, 220, 225))
                background = null
                minHeight = dp(46)
                stateListAnimator = null
                setOnClickListener { updateDialog?.dismiss() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ))
        }

        return Dialog(this).apply {
            setContentView(card)
            setCancelable(!required)
            setCanceledOnTouchOutside(!required)
        } to downloadButton
    }

    private fun styleUpdateDialogWindow(dialog: Dialog) {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val dialogWidth = minOf(
            screenWidth - (40 * density).toInt(),
            (360 * density).toInt()
        ).coerceAtLeast(1)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
            setLayout(
                dialogWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
    }

    private fun startOrInstallUpdate(primaryUrl: String, redirectUrl: String) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (updateDownloadId == UPDATE_DOWNLOAD_NONE) {
            val preferences = getSharedPreferences("app_update_download", MODE_PRIVATE)
            val savedId = preferences.getLong(UPDATE_DOWNLOAD_ID_KEY, UPDATE_DOWNLOAD_NONE)
            val savedHash = preferences.getString(UPDATE_DOWNLOAD_HASH_KEY, "").orEmpty()
            if (updateSha256.isNotBlank() && savedHash != updateSha256) {
                manager.remove(savedId)
                preferences.edit().remove(UPDATE_DOWNLOAD_ID_KEY).remove(UPDATE_DOWNLOAD_HASH_KEY).apply()
            } else {
                updateDownloadId = savedId
                updateSha256 = updateSha256.ifBlank { savedHash }
            }
            updateFallbackUrl = updateFallbackUrl.ifBlank {
                preferences.getString(UPDATE_FALLBACK_URL_KEY, "").orEmpty()
            }
        }
        val status = queryDownloadStatus(manager, updateDownloadId)
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> installCompletedUpdate()
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                updateButton?.text = "Downloading…"
                mainHandler.removeCallbacks(updateDownloadPoll)
                mainHandler.post(updateDownloadPoll)
            }
            DownloadManager.STATUS_FAILED -> {
                if (!startFallbackDownload(manager)) {
                    manager.remove(updateDownloadId)
                    updateDownloadId = UPDATE_DOWNLOAD_NONE
                    enqueueUpdateDownload(
                        manager,
                        primaryUrl.takeIf { it.startsWith("https://") } ?: updateFallbackUrl,
                        isFallback = !primaryUrl.startsWith("https://")
                    )
                }
                if (updateDialogShowing && redirectUrl.startsWith("https://")) {
                    openPromotionChannel(redirectUrl)
                }
            }
            else -> {
                val destination = primaryUrl.takeIf { it.startsWith("https://") }
                    ?: updateFallbackUrl
                enqueueUpdateDownload(manager, destination, isFallback = !primaryUrl.startsWith("https://"))
                if (updateDialogShowing && redirectUrl.startsWith("https://")) {
                    openPromotionChannel(redirectUrl)
                }
            }
        }
    }

    private fun enqueueUpdateDownload(manager: DownloadManager, url: String, isFallback: Boolean) {
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "No valid APK download link is configured.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                .setTitle("QSpoof update")
                .setDescription("Downloading the latest APK")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    "qspoof-update-${updateSha256.take(12).ifBlank { "latest" }}.apk"
                )
            updateDownloadId = manager.enqueue(request)
            getSharedPreferences("app_update_download", MODE_PRIVATE).edit()
                .putLong(UPDATE_DOWNLOAD_ID_KEY, updateDownloadId)
                .putString(UPDATE_DOWNLOAD_HASH_KEY, updateSha256)
                .putString(UPDATE_FALLBACK_URL_KEY, updateFallbackUrl)
                .putBoolean("fallback_attempted", isFallback)
                .apply()
            updateFallbackAttempted = isFallback
            updateButton?.text = "Downloading…"
            mainHandler.removeCallbacks(updateDownloadPoll)
            mainHandler.post(updateDownloadPoll)
        } catch (error: RuntimeException) {
            Toast.makeText(this, "Could not start the APK download: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val updateDownloadPoll = object : Runnable {
        override fun run() {
            if (!updateDialogShowing || isFinishing) return
            refreshUpdateButton()
            if (queryDownloadStatus(
                    getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager,
                    updateDownloadId
                ) in setOf(
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED
                )
            ) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun refreshUpdateButton() {
        val button = updateButton ?: return
        when (queryDownloadStatus(
            getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager,
            updateDownloadId
        )) {
            DownloadManager.STATUS_SUCCESSFUL -> button.text = "Install update"
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> button.text = "Downloading…"
            DownloadManager.STATUS_FAILED -> {
                if (!startFallbackDownload(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)) {
                    button.text = "Retry download"
                }
            }
            else -> button.text = if (requiredUpdate) "Download update" else "Download update"
        }
    }

    private fun startFallbackDownload(manager: DownloadManager): Boolean {
        val preferences = getSharedPreferences("app_update_download", MODE_PRIVATE)
        val alreadyUsedFallback = preferences.getBoolean("fallback_attempted", updateFallbackAttempted)
        if (alreadyUsedFallback || !updateFallbackUrl.startsWith("https://")) return false
        manager.remove(updateDownloadId)
        updateDownloadId = UPDATE_DOWNLOAD_NONE
        enqueueUpdateDownload(manager, updateFallbackUrl, isFallback = true)
        return true
    }

    private fun queryDownloadStatus(manager: DownloadManager, id: Long): Int {
        if (id == UPDATE_DOWNLOAD_NONE) return -1
        return try {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return -1
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        } catch (_: IllegalArgumentException) {
            -1
        }
    }

    private fun installCompletedUpdate() {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(updateDownloadId)
        if (uri == null) {
            Toast.makeText(this, "The downloaded APK is no longer available. Download it again.", Toast.LENGTH_LONG).show()
            updateDownloadId = UPDATE_DOWNLOAD_NONE
            refreshUpdateButton()
            return
        }
        val expectedHash = updateSha256.ifBlank {
            getSharedPreferences("app_update_download", MODE_PRIVATE)
                .getString(UPDATE_DOWNLOAD_HASH_KEY, "").orEmpty()
        }
        if (expectedHash.isBlank()) {
            Toast.makeText(this, "The server did not provide APK verification data.", Toast.LENGTH_LONG).show()
            return
        }
        if (updateVerificationInProgress) return
        updateVerificationInProgress = true
        updateButton?.isEnabled = false
        updateButton?.text = "Verifying APK…"
        Thread({
            val actualHash = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                }
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                updateVerificationInProgress = false
                updateButton?.isEnabled = true
                if (actualHash == null || !actualHash.equals(expectedHash, ignoreCase = true)) {
                    manager.remove(updateDownloadId)
                    updateDownloadId = UPDATE_DOWNLOAD_NONE
                    getSharedPreferences("app_update_download", MODE_PRIVATE).edit()
                        .remove(UPDATE_DOWNLOAD_ID_KEY).remove(UPDATE_DOWNLOAD_HASH_KEY).apply()
                    Toast.makeText(this, "APK verification failed. The downloaded file was removed.", Toast.LENGTH_LONG).show()
                    refreshUpdateButton()
                    return@post
                }
                openVerifiedInstaller(uri)
            }
        }, "qspoof-apk-verify").start()
    }

    private fun openVerifiedInstaller(uri: Uri) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ),
                UPDATE_INSTALL_PERMISSION_REQ_CODE
            )
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No Android package installer is available.", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android blocked installation. Allow installs from QSpoof and retry.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openPromotionChannel(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Could not open the Telegram channel.", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android blocked the Telegram link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openDeviceProfile() {
        startActivity(Intent(this, DeviceProfileActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

}
