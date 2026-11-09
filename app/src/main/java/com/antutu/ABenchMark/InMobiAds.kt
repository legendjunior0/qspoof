package com.antutu.ABenchMark

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.sdk.InMobiSdk
import com.inmobi.sdk.SdkInitializationListener
import org.json.JSONObject
import java.lang.ref.WeakReference

object InMobiAds {
    private const val TAG = "InMobiAds"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var launchAd: InMobiInterstitial? = null
    private var launchActivity: WeakReference<Activity>? = null
    private var launchAnimationFinished = false
    private var launchAdDisplayed = false
    private var generation = 0
    private var initializedAccountId: String? = null
    private var initializingAccountId: String? = null
    private val initializationCallbacks = mutableListOf<(Boolean) -> Unit>()

    fun preloadLaunchAd(activity: Activity): Boolean {
        if (!PremiumAccess.canLoadLaunchInterstitial(activity) || activity.isFinishing) return false
        val config = PremiumAccess.lastRemoteConfig(activity)
        val accountId = config.optString("inmobiAccountId").trim()
        val placementId = config.optString("inmobiInterstitialPlacementId").toLongOrNull()
            ?: return false
        if (!accountId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) return false
        if (initializedAccountId != null && initializedAccountId != accountId) return false

        generation += 1
        val requestGeneration = generation
        launchAd = null
        launchActivity = WeakReference(activity)
        launchAnimationFinished = false
        launchAdDisplayed = false
        initialize(activity, accountId) { initialized ->
            if (!initialized || requestGeneration != generation || activity.isFinishing) return@initialize
            loadLaunchInterstitial(activity, placementId, requestGeneration)
        }
        return true
    }

    fun showLaunchAdIfReady() {
        launchAnimationFinished = true
        showReadyLaunchAd()
    }

    fun showInterstitialForMenu(activity: Activity): Boolean {
        if (!PremiumAccess.canLoadLaunchInterstitial(activity) || activity.isFinishing) return false
        val config = PremiumAccess.lastRemoteConfig(activity)
        val accountId = config.optString("inmobiAccountId").trim()
        val placementId = config.optString("inmobiInterstitialPlacementId").toLongOrNull()
            ?: return false
        if (!accountId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) return false

        val requestGeneration = ++generation
        initialize(activity, accountId) { initialized ->
            if (!initialized || requestGeneration != generation || activity.isFinishing) return@initialize
            val ad = createInterstitial(activity, placementId, requestGeneration) ?: return@initialize
            ad.setListener(object : InterstitialAdEventListener() {
                override fun onAdLoadSucceeded(ad: InMobiInterstitial, info: com.inmobi.ads.AdMetaInfo) {
                    if (
                        requestGeneration == generation &&
                        !activity.isFinishing &&
                        ad.isReady() &&
                        PremiumAccess.reserveLaunchInterstitial(activity)
                    ) {
                        ad.show()
                    }
                }

                override fun onAdLoadFailed(
                    ad: InMobiInterstitial,
                    status: InMobiAdRequestStatus
                ) {
                    Log.w(TAG, "Menu interstitial failed to load: ${status.message}")
                }
            })
            try {
                ad.load()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not request menu interstitial", error)
            }
        }
        return true
    }

    fun release() {
        generation += 1
        launchAd = null
        launchActivity = null
        launchAnimationFinished = false
        launchAdDisplayed = false
    }

    private fun initialize(activity: Activity, accountId: String, callback: (Boolean) -> Unit) {
        val initializedFor = initializedAccountId
        if (InMobiSdk.isSDKInitialized()) {
            callback(initializedFor == null || initializedFor == accountId)
            return
        }
        if (initializingAccountId != null) {
            if (initializingAccountId == accountId) {
                initializationCallbacks += callback
            } else {
                callback(false)
            }
            return
        }

        initializingAccountId = accountId
        initializationCallbacks += callback
        try {
            InMobiSdk.init(
                activity.applicationContext,
                accountId,
                JSONObject(),
                object : SdkInitializationListener {
                    override fun onInitializationComplete(error: Error?) {
                        mainHandler.post {
                            val succeeded = error == null
                            if (succeeded) initializedAccountId = accountId
                            initializingAccountId = null
                            val callbacks = initializationCallbacks.toList()
                            initializationCallbacks.clear()
                            callbacks.forEach { it(succeeded) }
                            if (error != null) Log.w(TAG, "InMobi initialization failed", error)
                        }
                    }
                }
            )
        } catch (error: RuntimeException) {
            initializingAccountId = null
            val callbacks = initializationCallbacks.toList()
            initializationCallbacks.clear()
            callbacks.forEach { it(false) }
            Log.w(TAG, "Could not initialize InMobi", error)
        }
    }

    private fun loadLaunchInterstitial(
        activity: Activity,
        placementId: Long,
        requestGeneration: Int,
    ) {
        val ad = createInterstitial(activity, placementId, requestGeneration) ?: return
        launchAd = ad
        ad.setListener(object : InterstitialAdEventListener() {
            override fun onAdLoadSucceeded(ad: InMobiInterstitial, info: com.inmobi.ads.AdMetaInfo) {
                if (requestGeneration == generation && launchAnimationFinished) showReadyLaunchAd()
            }

            override fun onAdLoadFailed(
                ad: InMobiInterstitial,
                status: InMobiAdRequestStatus
            ) {
                if (launchAd === ad) launchAd = null
                Log.w(TAG, "Launch interstitial failed to load: ${status.message}")
            }
        })
        try {
            ad.load()
        } catch (error: RuntimeException) {
            if (launchAd === ad) launchAd = null
            Log.w(TAG, "Could not request launch interstitial", error)
        }
    }

    private fun createInterstitial(
        activity: Activity,
        placementId: Long,
        requestGeneration: Int,
    ): InMobiInterstitial? = try {
        InMobiInterstitial(activity, placementId, object : InterstitialAdEventListener() {
            override fun onAdLoadFailed(
                ad: InMobiInterstitial,
                status: InMobiAdRequestStatus
            ) {
                Log.w(TAG, "Interstitial failed to load: ${status.message}")
            }
        })
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not create InMobi interstitial (generation $requestGeneration)", error)
        null
    }

    private fun showReadyLaunchAd() {
        val activity = launchActivity?.get() ?: return
        val ad = launchAd ?: return
        if (
            launchAdDisplayed ||
            activity.isFinishing ||
            !ad.isReady()
        ) return
        if (!PremiumAccess.reserveLaunchInterstitial(activity)) {
            launchAd = null
            return
        }
        launchAdDisplayed = true
        launchAd = null
        ad.show()
    }
}
