package com.antutu.ABenchMark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

object PremiumAccess {
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
        val error: String? = null
    )

    data class Donor(
        val displayName: String,
        val isPinned: Boolean,
        val reviewedAt: String
    )

    private const val PREFERENCES = "premium_access"
    private const val ADS_ENABLED_PREFERENCE = "ads_enabled"
    private const val LAST_TELEMETRY_DATE_PREFERENCE = "last_telemetry_date"
    private val telemetryTimeZone = ZoneId.of("Asia/Kolkata")

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverConfirmedPremium = false
    private var cachedDonors: List<Donor>? = null
    private var cachedDonorsAtMillis = 0L
    private var cachedAdsAllowed: Boolean? = null
    private var globalAdsEnabled = true
    private var resolvingAds = false
    private var statusCheckInProgress = false
    private var lastResolutionError: String? = null
    private val adsDecisionCallbacks = mutableListOf<(Boolean, String?) -> Unit>()
    private val entitlementCallbacks = mutableListOf<(EntitlementResult) -> Unit>()

    fun isPremium(context: Context): Boolean =
        synchronized(this) { serverConfirmedPremium }

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
                        cachedAdsAllowed = false
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

    fun loadDonors(callback: (List<Donor>?, String?) -> Unit) {
        synchronized(this) {
            val cached = cachedDonors
            if (cached != null && System.currentTimeMillis() - cachedDonorsAtMillis < 10 * 60 * 1000L) {
                mainHandler.post { callback(cached, null) }
                return
            }
        }
        executor.execute {
            val result = try {
                val response = post(
                    action = "donors",
                    androidId = null,
                    transactionId = null
                )
                val donorsJson = response.getJSONArray("donors")
                val donors = List(donorsJson.length()) { index ->
                    val donor = donorsJson.getJSONObject(index)
                    Donor(
                        displayName = donor.getString("displayName"),
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
                synchronized(this) {
                    globalAdsEnabled = enabled
                    cachedAdsAllowed = null
                }
                preferences(context).edit().putBoolean(ADS_ENABLED_PREFERENCE, enabled).apply()
                enabled to null
            } catch (error: IOException) {
                val enabled = preferences(context).getBoolean(ADS_ENABLED_PREFERENCE, true)
                synchronized(this) { globalAdsEnabled = enabled }
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
        val androidId = getAndroidId(context)
        if (androidId == null) {
            callback(
                offlineAppAccessResult(
                    "Could not read this app's Android device ID"
                )
            )
            return
        }

        executor.execute {
            val result = try {
                val appContext = context.applicationContext
                val today = LocalDate.now(telemetryTimeZone).toString()
                val telemetry = if (
                    preferences(appContext).getString(LAST_TELEMETRY_DATE_PREFERENCE, null) != today
                ) {
                    JSONObject()
                        .put("deviceModel", deviceModel())
                        .put("ramMb", ramMegabytes(appContext))
                        .put("storageMb", storageMegabytes())
                        .put("cpu", cpuName())
                } else {
                    null
                }
                val response = post("app-access", androidId, null, extraBody = telemetry)
                if (
                    !response.has("premium") ||
                    !response.has("accessAllowed") ||
                    !response.has("adsEnabled")
                ) {
                    throw IOException("Premium server returned incomplete app access status")
                }
                if (telemetry != null && response.optBoolean("telemetryRecorded")) {
                    preferences(appContext).edit()
                        .putString(LAST_TELEMETRY_DATE_PREFERENCE, today)
                        .apply()
                }
                val premium = response.getBoolean("premium")
                val allowed = response.getBoolean("accessAllowed")
                val adsEnabled = response.getBoolean("adsEnabled")
                synchronized(this) {
                    serverConfirmedPremium = premium
                    globalAdsEnabled = adsEnabled
                    cachedAdsAllowed = !premium && adsEnabled
                    lastResolutionError = null
                }
                preferences(appContext).edit()
                    .putBoolean(ADS_ENABLED_PREFERENCE, adsEnabled)
                    .apply()
                AppAccessResult(allowed = allowed, premium = premium, adsEnabled = adsEnabled)
            } catch (error: IOException) {
                offlineAppAccessResult(error.message ?: "Could not verify app access")
            } catch (error: JSONException) {
                offlineAppAccessResult("Premium server returned invalid app access status")
            } catch (error: IllegalArgumentException) {
                offlineAppAccessResult(error.message ?: "Premium service is not configured")
            }
            mainHandler.post { callback(result) }
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

    fun resolveAdsAllowed(
        context: Context,
        callback: (Boolean, String?) -> Unit
    ) {
        if (isPremium(context)) {
            mainHandler.post { callback(false, null) }
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
                val allowed = !entitlement.premium && enabled
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

        val connection = URL("$baseUrl/functions/v1/premium")
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
        )?.takeIf { it.length in 8..128 }

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
