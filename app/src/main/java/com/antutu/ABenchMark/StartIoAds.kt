package com.antutu.ABenchMark

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import java.lang.ref.WeakReference

internal object StartIoAds {
    private const val TAG = "StartIoAds"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initializedAppId: String? = null
    private var generation = 0
    private var launchAd: StartAppAd? = null
    private var launchActivity: WeakReference<Activity>? = null
    private var launchAnimationFinished = false
    private var launchAdDisplayed = false

    fun preloadLaunchAd(activity: Activity): Boolean {
        if (!PremiumAccess.canLoadLaunchInterstitial(activity) || activity.isFinishing) return false
        val appId = PremiumAccess.lastRemoteConfig(activity).optString("startioAppId").trim()
        if (!isValidAppId(appId) || !initialize(activity, appId)) return false

        val requestGeneration = ++generation
        launchAd = null
        launchActivity = WeakReference(activity)
        launchAnimationFinished = false
        launchAdDisplayed = false
        return try {
            val ad = StartAppAd(activity)
            ad.loadAd(StartAppAd.AdMode.FULLPAGE, eventListener(requestGeneration) {
                if (activity.isFinishing) {
                    ad.close()
                    return@eventListener
                }
                launchAd = ad
                if (launchAnimationFinished) showReadyLaunchAd()
            })
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not request launch interstitial", error)
            false
        }
    }

    fun showLaunchAdIfReady() {
        launchAnimationFinished = true
        showReadyLaunchAd()
    }

    fun showInterstitialForMenu(activity: Activity): Boolean {
        if (!PremiumAccess.canLoadLaunchInterstitial(activity) || activity.isFinishing) return false
        val appId = PremiumAccess.lastRemoteConfig(activity).optString("startioAppId").trim()
        if (!isValidAppId(appId) || !initialize(activity, appId)) return false

        val requestGeneration = ++generation
        return try {
            val ad = StartAppAd(activity)
            ad.loadAd(StartAppAd.AdMode.FULLPAGE, eventListener(requestGeneration) {
                if (
                    !activity.isFinishing &&
                    ad.isReady() &&
                    PremiumAccess.reserveLaunchInterstitial(activity)
                ) {
                    show(ad)
                }
            })
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not request menu interstitial", error)
            false
        }
    }

    fun release() {
        generation += 1
        launchAd?.close()
        launchAd = null
        launchActivity = null
        launchAnimationFinished = false
        launchAdDisplayed = false
    }

    private fun initialize(activity: Activity, appId: String): Boolean {
        if (initializedAppId == appId) return true
        if (initializedAppId != null) {
            Log.w(TAG, "Start.io is already initialized with a different publisher App ID")
            return false
        }
        return try {
            StartAppAd.disableSplash()
            StartAppAd.disableAutoInterstitial()
            StartAppSDK.init(activity.applicationContext, appId)
            initializedAppId = appId
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not initialize Start.io", error)
            false
        }
    }

    private fun eventListener(
        requestGeneration: Int,
        onLoaded: () -> Unit
    ): AdEventListener = object : AdEventListener {
        override fun onReceiveAd(ad: Ad) {
            mainHandler.post {
                if (requestGeneration == generation) onLoaded()
            }
        }

        override fun onFailedToReceiveAd(ad: Ad?) {
            Log.w(TAG, "Start.io interstitial failed to load")
        }
    }

    private fun showReadyLaunchAd() {
        val activity = launchActivity?.get() ?: return
        val ad = launchAd ?: return
        if (launchAdDisplayed || activity.isFinishing || !ad.isReady()) return
        if (!PremiumAccess.reserveLaunchInterstitial(activity)) {
            ad.close()
            launchAd = null
            return
        }
        launchAdDisplayed = true
        launchAd = null
        show(ad)
    }

    private fun show(startAppAd: StartAppAd) {
        val displayed = startAppAd.showAd(object : AdDisplayListener {
            override fun adDisplayed(ad: Ad) = Unit
            override fun adHidden(ad: Ad) = startAppAd.close()
            override fun adClicked(ad: Ad) = Unit
            override fun adNotDisplayed(ad: Ad) = startAppAd.close()
        })
        if (!displayed) {
            Log.w(TAG, "Start.io interstitial was not ready to display")
            startAppAd.close()
        }
    }

    private fun isValidAppId(appId: String): Boolean =
        appId.matches(Regex("[A-Za-z0-9._-]{1,128}"))
}
