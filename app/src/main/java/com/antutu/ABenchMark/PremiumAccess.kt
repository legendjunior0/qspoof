package com.antutu.ABenchMark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.Executors

object PremiumAccess {
    fun lastRemoteConfig(context: Context): JSONObject =
        readSealedAccessCache(context)?.optJSONObject("remoteConfig") ?: JSONObject()
    data class SubmissionResult(
        val submitted: Boolean,
        val message: String
    )

    data class EntitlementResult(
        val premium: Boolean,
        val error: String? = null
    )

    data class AppAccessResult(
        val allowed: Boolean,
        val premium: Boolean,
        val adsEnabled: Boolean,
        val premiumAdsEnabled: Boolean = false,
        val bannerSlotLimit: Int = 6,
        val interstitialsPerDay: Int = 2,
        val latestVersionCode: Int = 0,
        val updateRequired: Boolean = false,
        val updateUrl: String = "",
        val updateFallbackUrl: String = "",
        val updateMessage: String = "An update is required to continue.",
        val channelUrl: String = "https://t.me/iqoo_neo10_channel",
        val groupUrl: String = "https://t.me/iqoo_neo10",
        val accessCheckIntervalHours: Int = 12,
        val remoteConfig: JSONObject = JSONObject(),
        val displayVersion: String = "",
        val releaseNotes: String = "",
        val apkSha256: String = "",
        val apkStoragePath: String = "",
        val isFinalCheck: Boolean = true,
        val error: String? = null
    )

    data class DeviceProfile(
        val name: String = "",
        val email: String = "",
        val referenceId: String = "",
        val emailVerified: Boolean = false,
        val pendingEmail: String = "",
        val changedAt: String? = null,
        val lockedUntil: String? = null
    )

    data class Donor(
        val displayName: String,
        val isPinned: Boolean,
        val reviewedAt: String
    )

    data class DeviceFeedback(
        val works: Boolean? = null,
        val updatedAt: String? = null
    )

    private const val PREFERENCES = "premium_access"
    private const val ADS_ENABLED_PREFERENCE = "ads_enabled"
    private const val PREMIUM_ADS_ENABLED_PREFERENCE = "premium_ads_enabled"
    private const val BANNER_SLOT_LIMIT_PREFERENCE = "banner_slot_limit"
    private const val INTERSTITIALS_PER_DAY_PREFERENCE = "interstitials_per_day"
    private const val INTERSTITIAL_DATE_PREFERENCE = "launch_ad_date"
    private const val INTERSTITIAL_COUNT_PREFERENCE = "launch_ad_count"
    private const val LAST_INTERSTITIAL_TIME_PREFERENCE = "launch_ad_last_time"
    private const val FEEDBACK_WORKS_PREFERENCE = "feedback_works"
    private const val FEEDBACK_TIME_PREFERENCE = "feedback_time"
    private const val LAST_TELEMETRY_DATE_PREFERENCE = "last_telemetry_date"
    private const val ACCESS_CACHE_SEALED_PREFERENCE = "access_cache_sealed_v2"
    private const val ACCESS_CACHE_KEY_ALIAS_PREFIX = "qspoof_access_cache_v2_"
    private const val ACCESS_CHECK_RETRY_DELAY_MS = 3_000L
    private const val CACHE_IV_SIZE = 12
    private const val ACCESS_CACHE_TAG = "PremiumAccess"
    private val telemetryTimeZone = ZoneId.of("Asia/Kolkata")

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverConfirmedPremium = false
    private var cachedDonors: List<Donor>? = null
    private var cachedDonorsAtMillis = 0L
    private var cachedAdsAllowed: Boolean? = null
    private var globalAdsEnabled = true
    private var premiumAdsEnabled = false
    private var bannerSlotLimit = 6
    private var interstitialsPerDay = 2
    private var launchInterstitialReserved = false
    private var adSettingsLoaded = false
    private var resolvingAds = false
    private var statusCheckInProgress = false
    private var lastResolutionError: String? = null
    private val adsDecisionCallbacks = mutableListOf<(Boolean, String?) -> Unit>()
    private val entitlementCallbacks = mutableListOf<(EntitlementResult) -> Unit>()

    fun isPremium(context: Context): Boolean =
        synchronized(this) { serverConfirmedPremium }

    fun loadDeviceFeedback(context: Context, callback: (DeviceFeedback) -> Unit) {
        val prefs = preferences(context)
        executor.execute {
            val result = try {
                val response = post("app-feedback-get", getAndroidId(context), null)
                val works = if (response.has("works")) response.optBoolean("works") else null
                DeviceFeedback(works, response.optString("updatedAt").takeIf(String::isNotBlank))
            } catch (_: Exception) {
                DeviceFeedback()
            }
            if (result.works != null) prefs.edit()
                .putBoolean(FEEDBACK_WORKS_PREFERENCE, result.works)
                .putLong(FEEDBACK_TIME_PREFERENCE, System.currentTimeMillis())
                .apply()
            mainHandler.post { callback(result) }
        }
    }

    fun saveDeviceFeedback(context: Context, works: Boolean, callback: (Boolean) -> Unit = {}) {
        val prefs = preferences(context)
        prefs.edit().putBoolean(FEEDBACK_WORKS_PREFERENCE, works)
            .putLong(FEEDBACK_TIME_PREFERENCE, System.currentTimeMillis()).apply()
        executor.execute {
            val saved = try {
                post("app-feedback-set", getAndroidId(context), null,
                    extraBody = JSONObject().put("works", works))
                true
            } catch (_: Exception) {
                false
            }
            mainHandler.post { callback(saved) }
        }
    }

    fun submitTransaction(
        context: Context,
        transactionId: String,
        displayName: String?,
        showNamePartially: Boolean,
        callback: (SubmissionResult) -> Unit
    ) {
        val normalizedId = transactionId.trim()
        if (!normalizedId.matches(Regex("[A-Za-z0-9-]{6,80}"))) {
            callback(
                SubmissionResult(
                    submitted = false,
                    message = "Enter a valid payment reference ID"
                )
            )
            return
        }

        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(
                SubmissionResult(
                    submitted = false,
                    message = "Could not read this app's Android device ID"
                )
            )
            return
        }

        executor.execute {
            val result = try {
                val response = post(
                    action = "submit",
                    androidId = androidId,
                    transactionId = normalizedId,
                    displayName = displayName?.trim()?.takeIf(String::isNotEmpty),
                    showNamePartially = showNamePartially
                )
                val submissionStatus = response.optString("status")
                if (submissionStatus != "pending" && submissionStatus != "approved") {
                    throw IOException("Premium server returned an unexpected submission status")
                }
                synchronized(this) {
                    if (submissionStatus == "approved") {
                        serverConfirmedPremium = true
                        cachedAdsAllowed = premiumAdsEnabled
                    } else {
                        cachedAdsAllowed = !serverConfirmedPremium && globalAdsEnabled
                    }
                    lastResolutionError = null
                }
                SubmissionResult(
                    submitted = true,
                    message = response.optString(
                        "message",
                        if (submissionStatus == "approved") {
                            "Premium activated successfully on this device"
                        } else {
                            "Payment reference submitted for admin review"
                        }
                    )
                )
            } catch (error: IOException) {
                SubmissionResult(false, error.message ?: "Could not contact premium server")
            } catch (error: JSONException) {
                SubmissionResult(false, "Premium server returned an invalid response")
            } catch (error: IllegalArgumentException) {
                SubmissionResult(false, error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result) }
        }
    }

    fun loadSupportedDeviceCatalog(callback: (JSONArray?, String?) -> Unit) {
        executor.execute {
            val result = try {
                val response = post("supported-devices-get", null, null)
                response.getJSONArray("categories") to null
            } catch (error: IOException) {
                null to (error.message ?: "Could not load supported-device list")
            } catch (error: JSONException) {
                null to "Supported-device catalog returned invalid data"
            } catch (error: IllegalArgumentException) {
                null to (error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun loadDonors(context: Context, callback: (List<Donor>?, String?) -> Unit) {
        executor.execute {
            val result = try {
                val response = post(
                    action = "donors",
                    androidId = getAndroidId(context.applicationContext),
                    transactionId = null
                )
                val donorsJson = response.getJSONArray("donors")
                val donors = List(donorsJson.length()) { index ->
                    val donor = donorsJson.getJSONObject(index)
                    Donor(
                        displayName = donor.optString("displayName")
                            .takeIf { it.isNotBlank() && it != "##UNKNOWN" }
                            ?: "Anonymous donor",
                        isPinned = donor.optBoolean("isPinned"),
                        reviewedAt = donor.getString("reviewedAt")
                    )
                }
                synchronized(this) {
                    cachedDonors = donors
                    cachedDonorsAtMillis = System.currentTimeMillis()
                }
                donors to null
            } catch (error: IOException) {
                null to (error.message ?: "Could not load donor names")
            } catch (error: JSONException) {
                null to "Donor list returned an invalid response"
            } catch (error: IllegalArgumentException) {
                null to (error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    private fun loadGlobalAdsSetting(context: Context, callback: (Boolean, String?) -> Unit) {
        executor.execute {
            val result = try {
                val response = post("public-config", null, null)
                if (!response.has("adsEnabled")) {
                    throw IOException("Premium server returned incomplete global ad settings")
                }
                val enabled = response.getBoolean("adsEnabled")
                val premiumEnabled = response.optBoolean("premiumAdsEnabled", false)
                val slotLimit = response.optInt("bannerSlotLimit", 6).coerceIn(0, 6)
                val interstitialLimit = response.optInt("interstitialsPerDay", 1).coerceIn(1, 2)
                synchronized(this) {
                    globalAdsEnabled = enabled
                    premiumAdsEnabled = premiumEnabled
                    bannerSlotLimit = slotLimit
                    interstitialsPerDay = interstitialLimit
                    adSettingsLoaded = true
                    cachedAdsAllowed = null
                }
                preferences(context).edit()
                    .putBoolean(ADS_ENABLED_PREFERENCE, enabled)
                    .putBoolean(PREMIUM_ADS_ENABLED_PREFERENCE, premiumEnabled)
                    .putInt(BANNER_SLOT_LIMIT_PREFERENCE, slotLimit)
                    .putInt(INTERSTITIALS_PER_DAY_PREFERENCE, interstitialLimit)
                    .apply()
                enabled to null
            } catch (error: IOException) {
                val enabled = preferences(context).getBoolean(ADS_ENABLED_PREFERENCE, true)
                synchronized(this) {
                    globalAdsEnabled = enabled
                    premiumAdsEnabled = preferences(context).getBoolean(PREMIUM_ADS_ENABLED_PREFERENCE, false)
                    bannerSlotLimit = preferences(context).getInt(BANNER_SLOT_LIMIT_PREFERENCE, 6).coerceIn(0, 6)
                    interstitialsPerDay = preferences(context).getInt(INTERSTITIALS_PER_DAY_PREFERENCE, 1).coerceIn(1, 2)
                    adSettingsLoaded = true
                }
                enabled to (error.message ?: "Could not load global ad settings")
            } catch (error: JSONException) {
                val enabled = preferences(context).getBoolean(ADS_ENABLED_PREFERENCE, true)
                synchronized(this) { globalAdsEnabled = enabled }
                enabled to "Global ad settings returned an invalid response"
            } catch (error: IllegalArgumentException) {
                val enabled = preferences(context).getBoolean(ADS_ENABLED_PREFERENCE, true)
                synchronized(this) { globalAdsEnabled = enabled }
                enabled to (error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun refreshEntitlement(
        context: Context,
        callback: (EntitlementResult) -> Unit
    ) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(
                EntitlementResult(
                    premium = isPremium(context),
                    error = "Could not read this app's Android device ID"
                )
            )
            return
        }

        synchronized(this) {
            entitlementCallbacks.add(callback)
            if (statusCheckInProgress) return
            statusCheckInProgress = true
        }

        executor.execute {
            val result = try {
                val response = post("status", androidId, null)
                if (!response.has("premium")) {
                    throw IOException("Premium server returned an incomplete status response")
                }
                val premium = response.getBoolean("premium")
                synchronized(this) {
                    serverConfirmedPremium = premium
                    cachedAdsAllowed = null
                    lastResolutionError = null
                }
                EntitlementResult(premium)
            } catch (error: IOException) {
                synchronized(this) {
                    cachedAdsAllowed = null
                    lastResolutionError = error.message
                }
                EntitlementResult(false, error.message ?: "Could not contact premium server")
            } catch (error: JSONException) {
                synchronized(this) {
                    cachedAdsAllowed = null
                    lastResolutionError = "Premium server returned an invalid response"
                }
                EntitlementResult(false, "Premium server returned an invalid response")
            } catch (error: IllegalArgumentException) {
                synchronized(this) {
                    cachedAdsAllowed = null
                    lastResolutionError = error.message
                }
                EntitlementResult(false, error.message ?: "Premium service is not configured")
            }
            mainHandler.post {
                val callbacks: List<(EntitlementResult) -> Unit>
                synchronized(this) {
                    statusCheckInProgress = false
                    callbacks = entitlementCallbacks.toList()
                    entitlementCallbacks.clear()
                }
                callbacks.forEach { it(result) }
            }
        }
    }

    fun delinkPremium(context: Context, callback: (Boolean, String?) -> Unit) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(false, "Could not read this app's Android device ID")
            return
        }
        executor.execute {
            val result = try {
                val response = post("self-delink", androidId, null)
                val delinked = response.optBoolean("delinked")
                if (delinked) {
                    synchronized(this) {
                        serverConfirmedPremium = false
                        cachedAdsAllowed = globalAdsEnabled
                    }
                }
                delinked to if (delinked) null else "Premium is not active on this device"
            } catch (error: IOException) {
                false to (error.message ?: "Could not turn off premium")
            } catch (error: JSONException) {
                false to "Premium server returned an invalid response"
            } catch (error: IllegalArgumentException) {
                false to (error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun checkAppAccess(context: Context, callback: (AppAccessResult) -> Unit) {
        val appContext = context.applicationContext
        val cached = cachedAppAccess(appContext)
        if (cached != null) {
            mainHandler.post { callback(cached.copy(isFinalCheck = false)) }
        }

        val androidId = getAndroidId(appContext)
        if (androidId == null) {
            val result = cached?.copy(isFinalCheck = true)
                ?: offlineAppAccessResult("Could not read this app's Android device ID")
            mainHandler.post { callback(result) }
            return
        }

        requestAppAccess(appContext, androidId, cached, attempt = 0, callback)
    }

    private fun requestAppAccess(
        context: Context,
        androidId: String,
        cached: AppAccessResult?,
        attempt: Int,
        callback: (AppAccessResult) -> Unit
    ) {
        executor.execute {
            val result = fetchAppAccess(context, androidId)
            if (result.error != null && attempt == 0) {
                mainHandler.postDelayed(
                    { requestAppAccess(context, androidId, cached, attempt = 1, callback) },
                    ACCESS_CHECK_RETRY_DELAY_MS
                )
            } else {
                val finalResult = if (result.error != null && cached != null) {
                    cached.copy(isFinalCheck = true)
                } else {
                    result.copy(isFinalCheck = true)
                }
                mainHandler.post { callback(finalResult) }
            }
        }
    }

    private fun fetchAppAccess(context: Context, androidId: String): AppAccessResult {
        return try {
            val today = LocalDate.now(telemetryTimeZone).toString()
            val telemetry = if (
                preferences(context).getString(LAST_TELEMETRY_DATE_PREFERENCE, null) != today
            ) {
                JSONObject()
                    .put("deviceModel", deviceModel())
                    .put("ramMb", ramMegabytes(context))
                    .put("storageMb", storageMegabytes())
                    .put("cpu", cpuName())
            } else {
                null
            }
            val versionCode = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            ).longVersionCode.toInt()
            val startupBody = (telemetry ?: JSONObject())
                .put("versionCode", versionCode)
                .put("packageId", context.packageName)
            val response = post("app-access", androidId, null, extraBody = startupBody)
            if (!response.has("premium") || !response.has("accessAllowed") || !response.has("adsEnabled")) {
                throw IOException("Premium server returned incomplete app access status")
            }
            if (telemetry != null && response.optBoolean("telemetryRecorded")) {
                preferences(context).edit()
                    .putString(LAST_TELEMETRY_DATE_PREFERENCE, today)
                    .apply()
            }

            val premium = response.getBoolean("premium")
            val allowed = response.getBoolean("accessAllowed")
            val adsEnabled = response.getBoolean("adsEnabled")
            val premiumAds = response.optBoolean("premiumAdsEnabled", false)
            val bannerLimit = response.optInt("bannerSlotLimit", 6).coerceIn(0, 6)
            val interstitialLimit = response.optInt("interstitialsPerDay", 1).coerceIn(0, 2)
            val minimumVersionCode = response.optInt("minimumVersionCode", 0)
            val latestVersionCode = response.optInt("latestVersionCode", minimumVersionCode)
                .coerceAtLeast(minimumVersionCode)
            val updateRequired = minimumVersionCode > versionCode
            var accessCheckIntervalHours = response.optInt("accessCheckIntervalHours", 0)
            val settings = if (accessCheckIntervalHours !in 1..168 || !response.has("popups")) {
                post("public-config", null, null)
            } else {
                JSONObject()
            }
            if (accessCheckIntervalHours !in 1..168) {
                val tierKey = if (premium) {
                    "premiumAccessCheckIntervalHours"
                } else {
                    "normalAccessCheckIntervalHours"
                }
                accessCheckIntervalHours = settings.optInt(
                    tierKey,
                    settings.optInt("accessCheckIntervalHours", 12)
                )
            }
            if (accessCheckIntervalHours !in 1..168) {
                throw IOException("Premium server returned an invalid access-check interval")
            }

            val result = AppAccessResult(
                allowed = allowed,
                premium = premium,
                adsEnabled = adsEnabled,
                premiumAdsEnabled = premiumAds,
                bannerSlotLimit = bannerLimit,
                interstitialsPerDay = interstitialLimit,
                latestVersionCode = latestVersionCode,
                updateRequired = updateRequired,
                updateUrl = response.optString("updateUrl").ifBlank {
                    settings.optString("apkDownloadUrl").ifBlank { settings.optString("updateUrl") }
                },
                updateFallbackUrl = response.optString("updateFallbackUrl"),
                updateMessage = response.optString(
                    "updateMessage",
                    settings.optString("updateMessage", "An update is required to continue.")
                ),
                channelUrl = response.optString(
                    "channelUrl",
                    settings.optString("channelUrl", "https://t.me/iqoo_neo10_channel")
                ),
                groupUrl = response.optString(
                    "groupUrl",
                    settings.optString("groupUrl", "https://t.me/iqoo_neo10")
                ),
                accessCheckIntervalHours = accessCheckIntervalHours,
                remoteConfig = (response.optJSONObject("remoteConfig")
                    ?: settings.optJSONObject("remoteConfig")
                    ?: JSONObject()).apply {
                    put("popups", response.optJSONArray("popups") ?: settings.optJSONArray("popups") ?: JSONArray())
                },
                displayVersion = response.optString("displayVersion", settings.optString("displayVersion")),
                releaseNotes = response.optString("releaseNotes", settings.optString("releaseNotes")),
                apkSha256 = response.optString("apkSha256", settings.optString("apkSha256")),
                apkStoragePath = response.optString("apkStoragePath", settings.optString("apkStoragePath"))
            )
            if (allowed) {
                applyAccessState(result)
                saveAppAccessCache(context, result)
            } else {
                clearAppAccessCache(context)
                applyAccessState(result)
            }
            preferences(context).edit()
                .putBoolean(ADS_ENABLED_PREFERENCE, adsEnabled)
                .putBoolean(PREMIUM_ADS_ENABLED_PREFERENCE, premiumAds)
                .putInt(BANNER_SLOT_LIMIT_PREFERENCE, bannerLimit)
                .putInt(INTERSTITIALS_PER_DAY_PREFERENCE, interstitialLimit)
                .apply()
            result
        } catch (error: IOException) {
            offlineAppAccessResult(error.message ?: "Could not verify app access")
        } catch (error: JSONException) {
            offlineAppAccessResult("Premium server returned invalid app access status")
        } catch (error: IllegalArgumentException) {
            offlineAppAccessResult(error.message ?: "Premium service is not configured")
        }
    }

    private fun offlineAppAccessResult(error: String): AppAccessResult {
        return AppAccessResult(
            allowed = false,
            premium = false,
            adsEnabled = false,
            error = error
        )
    }

    private fun cachedAppAccess(context: Context): AppAccessResult? {
        if (hasRootIndicators()) {
            Log.w(ACCESS_CACHE_TAG, "Root indicators found; requiring online access verification")
            return null
        }
        val cache = readSealedAccessCache(context) ?: return null
        val result = AppAccessResult(
            allowed = cache.optBoolean("allowed"),
            premium = cache.optBoolean("premium"),
            adsEnabled = cache.optBoolean("adsEnabled"),
            premiumAdsEnabled = cache.optBoolean("premiumAdsEnabled"),
            bannerSlotLimit = cache.optInt("bannerSlotLimit", 6).coerceIn(0, 6),
            interstitialsPerDay = cache.optInt("interstitialsPerDay", 1).coerceIn(0, 2),
            latestVersionCode = cache.optInt("latestVersionCode", 0),
            updateRequired = cache.optBoolean("updateRequired"),
            updateUrl = cache.optString("updateUrl"),
            updateFallbackUrl = cache.optString("updateFallbackUrl"),
            updateMessage = cache.optString("updateMessage", "An update is required to continue."),
            channelUrl = cache.optString("channelUrl", "https://t.me/iqoo_neo10_channel"),
            groupUrl = cache.optString("groupUrl", "https://t.me/iqoo_neo10"),
            displayVersion = cache.optString("displayVersion"),
            releaseNotes = cache.optString("releaseNotes"),
            apkSha256 = cache.optString("apkSha256"),
            apkStoragePath = cache.optString("apkStoragePath"),
            remoteConfig = cache.optJSONObject("remoteConfig") ?: JSONObject(),
            accessCheckIntervalHours = cache.optInt("accessCheckIntervalHours", 12)
                .coerceIn(1, 168)
        )
        return result.also(::applyAccessState)
    }

    private fun saveAppAccessCache(context: Context, result: AppAccessResult) {
        val now = System.currentTimeMillis()
        val intervalHours = result.accessCheckIntervalHours.coerceIn(1, 168)
        val cache = JSONObject()
            .put("expiresAt", now + intervalHours * 60L * 60L * 1000L)
            .put("allowed", result.allowed)
            .put("premium", result.premium)
            .put("adsEnabled", result.adsEnabled)
            .put("premiumAdsEnabled", result.premiumAdsEnabled)
            .put("bannerSlotLimit", result.bannerSlotLimit.coerceIn(0, 6))
            .put("interstitialsPerDay", result.interstitialsPerDay.coerceIn(0, 2))
            .put("latestVersionCode", result.latestVersionCode.coerceAtLeast(0))
            .put("updateRequired", result.updateRequired)
            .put("updateUrl", result.updateUrl)
            .put("updateFallbackUrl", result.updateFallbackUrl)
            .put("updateMessage", result.updateMessage)
            .put("channelUrl", result.channelUrl)
            .put("groupUrl", result.groupUrl)
            .put("displayVersion", result.displayVersion)
            .put("releaseNotes", result.releaseNotes)
            .put("apkSha256", result.apkSha256)
            .put("apkStoragePath", result.apkStoragePath)
            .put("remoteConfig", result.remoteConfig)
            .put("accessCheckIntervalHours", intervalHours)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, accessCacheKey(context))
            cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(cache.toString().toByteArray(Charsets.UTF_8))
            val sealed = ByteArray(cipher.iv.size + encrypted.size)
            System.arraycopy(cipher.iv, 0, sealed, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, sealed, cipher.iv.size, encrypted.size)
            preferences(context).edit()
                .putString(ACCESS_CACHE_SEALED_PREFERENCE, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .remove("remote_config")
                .apply()
        } catch (error: Exception) {
            Log.e(ACCESS_CACHE_TAG, "Could not seal app access cache; offline access will be unavailable", error)
            clearAppAccessCache(context)
        }
    }

    private fun readSealedAccessCache(context: Context): JSONObject? {
        val preferences = preferences(context)
        val encoded = preferences.getString(ACCESS_CACHE_SEALED_PREFERENCE, null) ?: return null
        try {
            val sealed = Base64.decode(encoded, Base64.NO_WRAP)
            if (sealed.size <= CACHE_IV_SIZE) throw IOException("Access cache is incomplete")
            val iv = sealed.copyOfRange(0, CACHE_IV_SIZE)
            val ciphertext = sealed.copyOfRange(CACHE_IV_SIZE, sealed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, accessCacheKey(context), GCMParameterSpec(128, iv))
            cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
            val cache = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            if (cache.optLong("expiresAt", 0L) <= System.currentTimeMillis() ||
                !cache.optBoolean("allowed", false)
            ) {
                clearAppAccessCache(context)
                return null
            }
            return cache
        } catch (error: Exception) {
            Log.w(ACCESS_CACHE_TAG, "Discarding unreadable or modified app access cache", error)
            clearAppAccessCache(context)
            return null
        }
    }

    private fun accessCacheKey(context: Context): SecretKey {
        val alias = ACCESS_CACHE_KEY_ALIAS_PREFIX + context.packageName
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun clearAppAccessCache(context: Context) {
        preferences(context).edit()
            .remove(ACCESS_CACHE_SEALED_PREFERENCE)
            .remove("remote_config")
            .remove("access_cache_time")
            .remove("access_cache_interval_hours")
            .remove("access_cache_allowed")
            .remove("access_cache_premium")
            .remove("access_cache_ads")
            .remove("access_cache_premium_ads")
            .remove("access_cache_banner_limit")
            .remove("access_cache_interstitial_limit")
            .apply()
    }

    private fun hasRootIndicators(): Boolean {
        val rootPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/data/adb/magisk"
        )
        return rootPaths.any { File(it).exists() }
    }

    private fun applyAccessState(result: AppAccessResult) {
        synchronized(this) {
            serverConfirmedPremium = result.premium
            globalAdsEnabled = result.adsEnabled
            premiumAdsEnabled = result.premiumAdsEnabled
            bannerSlotLimit = result.bannerSlotLimit.coerceIn(0, 6)
            interstitialsPerDay = result.interstitialsPerDay.coerceIn(0, 2)
            adSettingsLoaded = true
            cachedAdsAllowed = if (result.premium) result.premiumAdsEnabled else result.adsEnabled
            lastResolutionError = null
        }
    }

    fun loadDeviceProfile(context: Context, callback: (DeviceProfile?, String?) -> Unit) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(null, "Could not read this app's Android device ID")
            return
        }
        executor.execute {
            val result = try {
                val profile = post("app-device-profile-get", androidId, null).getJSONObject("profile")
                DeviceProfile(
                    name = profile.optString("name"),
                    email = profile.optString("email"),
                    referenceId = profile.optString("referenceId"),
                    emailVerified = profile.optBoolean("emailVerified"),
                    pendingEmail = profile.optString("pendingEmail"),
                    changedAt = profile.optString("changedAt").takeIf(String::isNotBlank),
                    lockedUntil = profile.optString("lockedUntil").takeIf(String::isNotBlank)
                ) to null
            } catch (error: Exception) {
                null to (error.message ?: "Could not load your profile")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun saveDeviceProfile(context: Context, name: String, email: String, callback: (DeviceProfile?, String?) -> Unit) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(null, "Could not read this app's Android device ID")
            return
        }
        executor.execute {
            val result = try {
                val body = JSONObject().put("name", name.trim()).put("email", email.trim())
                val response = post("app-device-profile-set", androidId, null, extraBody = body).getJSONObject("profile")
                DeviceProfile(
                    name = response.optString("name"),
                    email = response.optString("email"),
                    referenceId = response.optString("referenceId"),
                    emailVerified = response.optBoolean("emailVerified"),
                    changedAt = response.optString("changedAt").takeIf(String::isNotBlank)
                ) to null
            } catch (error: Exception) {
                null to (error.message ?: "Could not save your profile")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun sendProfileEmailCode(context: Context, email: String, callback: (String?) -> Unit) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback("Could not read this app's Android device ID")
            return
        }
        executor.execute {
            val error = try {
                post("app-email-otp-send", androidId, null, extraBody = JSONObject().put("email", email.trim()))
                null
            } catch (exception: Exception) {
                exception.message ?: "Could not send a verification code"
            }
            mainHandler.post { callback(error) }
        }
    }

    fun verifyProfileEmailCode(context: Context, email: String, code: String, callback: (Boolean, String?) -> Unit) {
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(false, "Could not read this app's Android device ID")
            return
        }
        executor.execute {
            val result = try {
                val response = post(
                    "app-email-otp-verify",
                    androidId,
                    null,
                    extraBody = JSONObject().put("email", email.trim()).put("otpCode", code.trim())
                )
                response.optBoolean("verified") to null
            } catch (exception: Exception) {
                false to (exception.message ?: "Could not verify this email")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    fun resolveAdsAllowed(
        context: Context,
        callback: (Boolean, String?) -> Unit
    ) {
        synchronized(this) {
            cachedAdsAllowed?.let { allowed ->
                val error = lastResolutionError
                mainHandler.post { callback(allowed, error) }
                return
            }
        }
        if (isPremium(context) && synchronized(this) { adSettingsLoaded }) {
            val allowed = synchronized(this) { premiumAdsEnabled }
            mainHandler.post { callback(allowed, null) }
            return
        }
        synchronized(this) {
            cachedAdsAllowed?.let { allowed ->
                val error = lastResolutionError
                mainHandler.post { callback(allowed, error) }
                return
            }
            adsDecisionCallbacks.add(callback)
            if (resolvingAds) return
            resolvingAds = true
        }

        refreshEntitlement(context) { entitlement ->
            loadGlobalAdsSetting(context) { enabled, settingsError ->
                val allowed = if (entitlement.premium) synchronized(this) { premiumAdsEnabled } else enabled
                val callbacks: List<(Boolean, String?) -> Unit>
                val error = entitlement.error ?: settingsError
                synchronized(this) {
                    cachedAdsAllowed = allowed
                    lastResolutionError = error
                    resolvingAds = false
                    callbacks = adsDecisionCallbacks.toList()
                    adsDecisionCallbacks.clear()
                }
                callbacks.forEach { it(allowed, error) }
            }
        }
    }

    fun bannerSlotLimit(): Int = synchronized(this) { bannerSlotLimit }

    fun canLoadLaunchInterstitial(context: Context): Boolean {
        return true
    }

    fun reserveLaunchInterstitial(context: Context): Boolean {
        synchronized(this) {
            launchInterstitialReserved = true
        }
        return true
    }

    fun resetLaunchInterstitial() {
        synchronized(this) { launchInterstitialReserved = false }
    }

    private fun post(
        action: String,
        androidId: String?,
        transactionId: String?,
        displayName: String? = null,
        showNamePartially: Boolean = false,
        extraBody: JSONObject? = null
    ): JSONObject {
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        require(baseUrl.startsWith("https://") && publishableKey.isNotBlank()) {
            "Premium server is not configured. See SUPABASE_SETUP.md."
        }

        val functionName = if (action == "public-config") "premium-config" else "premium"
        val connection = URL("$baseUrl/functions/v1/$functionName")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Authorization", "Bearer $publishableKey")

            val body = JSONObject()
                .put("action", action)
                // Send the installed build on every app API call. The server
                // rejects device and app requests from older or unidentified builds.
                .put("versionCode", BuildConfig.VERSION_CODE)
            if (androidId != null) body.put("androidId", androidId)
            if (transactionId != null) body.put("transactionId", transactionId)
            if (displayName != null) {
                body.put("displayName", displayName)
                    .put("showNamePartially", showNamePartially)
            }
            extraBody?.keys()?.let { keys ->
                while (keys.hasNext()) {
                    val key = keys.next()
                    body.put(key, extraBody.get(key))
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(responseText)
            } catch (error: JSONException) {
                throw IOException("Premium server returned HTTP $statusCode")
            }
            if (statusCode !in 200..299) {
                throw IOException(
                    response.optString("error").ifBlank {
                        "Premium server returned HTTP $statusCode"
                    }
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun getAndroidId(context: Context): String? =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.trim()?.takeIf { it.length in 8..128 }

    private fun deviceModel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)

    private fun ramMegabytes(context: Context): Int {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun storageMegabytes(): Int {
        val fileSystem = StatFs(Environment.getDataDirectory().absolutePath)
        val bytes = fileSystem.blockCountLong * fileSystem.blockSizeLong
        return (bytes / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun cpuName(): String = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { Build.HARDWARE }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
