package com.antutu.ABenchMark

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener
import com.startapp.sdk.ads.nativead.NativeAdDetails
import com.startapp.sdk.ads.nativead.NativeAdPreferences
import com.startapp.sdk.ads.nativead.StartAppNativeAd
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener

object StartIoAds {
    private var launchAd: StartAppAd? = null
    private var launchActivity: WeakReference<Activity>? = null
    private var launchAnimationFinished = false
    private var launchAnimationFinishedAt = 0L
    private var launchAdDisplayed = false
    private var lastEntitlementError: String? = null
    private val adSlots = mutableListOf<WeakReference<FrameLayout>>()
    private val registeredNativeAds = mutableListOf<NativeAdDetails>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var adGeneration = 0
    private var screenAdSlotsUsed = 0

    fun beginAdScreen() {
        screenAdSlotsUsed = 0
    }

    private fun reserveScreenSlot(): Boolean {
        if (screenAdSlotsUsed >= PremiumAccess.bannerSlotLimit()) return false
        screenAdSlotsUsed += 1
        return true
    }

    fun createBannerSlot(
        context: Context,
        flexible: Boolean = false,
        forceDisplay: Boolean = false
    ): FrameLayout {
        val slot = FrameLayout(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (flexible) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (flexible) 1f else 0f
            ).apply {
                if (!flexible) {
                    topMargin = dp(context, 8)
                    bottomMargin = dp(context, 8)
                }
            }
        }
        // Banner placements are disabled; interstitials remain managed separately.
        adSlots.add(WeakReference(slot))
        return slot
    }

    fun createLandscapeNativeAdSlot(context: Context, forceDisplay: Boolean = false): FrameLayout {
        val slot = FrameLayout(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(AppTheme.card)
                cornerRadius = dp(context, 10).toFloat()
                setStroke(dp(context, 1), AppTheme.border)
            }
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
        }
        adSlots.add(WeakReference(slot))
        return slot
        /*
        val requestGeneration = adGeneration

        fun requestNativeAd() {
            val nativeAd = try {
                StartAppNativeAd(context)
            } catch (_: RuntimeException) {
                slot.visibility = View.GONE
                return
            }
            val preferences = NativeAdPreferences()
                .setAdsNumber(1)
                .setAutoBitmapDownload(true)
                .setImageSize(NativeAdPreferences.NativeAdBitmapSize.SIZE72X72)
            try {
                nativeAd.loadAd(preferences, object : AdEventListener {
                    override fun onReceiveAd(ad: Ad) {
                        val details = nativeAd.nativeAds?.firstOrNull() ?: return
                        details.loadImages(context) {
                            mainHandler.post {
                                if (
                                    requestGeneration != adGeneration ||
                                    slot.parent == null
                                ) {
                                    details.unregisterView()
                                    return@post
                                }
                                showLandscapeNativeAd(slot, details, context, forceDisplay)
                            }
                        }
                    }

                    override fun onFailedToReceiveAd(ad: Ad?) {
                        slot.post { slot.visibility = View.GONE }
                    }
                })
            } catch (_: RuntimeException) {
                slot.visibility = View.GONE
            }
        }
        PremiumAccess.resolveAdsAllowed(context) { allowed, error ->
            showEntitlementError(context, error)
            if (!allowed || requestGeneration != adGeneration || !reserveScreenSlot()) {
                slot.visibility = View.GONE
                return@resolveAdsAllowed
            }
            requestNativeAd()
        }
        return slot
        */
    }

    private fun showLandscapeNativeAd(
        slot: FrameLayout,
        ad: NativeAdDetails,
        context: Context,
        forceDisplay: Boolean
    ) {
        val creative = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ad.imageBitmap?.let { bitmap ->
            val image = ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            creative.addView(image, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6), 0, 0, 0)
        }
        val label = TextView(context).apply {
            text = "AD"
            textSize = 7f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.textMuted)
        }
        textColumn.addView(label)
        val title = TextView(context).apply {
            text = ad.title.orEmpty()
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.textPrimary)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textColumn.addView(title)
        val action = TextView(context).apply {
            text = ad.callToAction.orEmpty().ifBlank { "Open" }
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
            background = AppTheme.gradient(context, 6f)
        }
        textColumn.addView(action, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 3) })
        creative.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        slot.removeAllViews()
        slot.addView(
            creative,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        ad.registerViewForInteraction(creative, listOf(action))
        registeredNativeAds.add(ad)
        slot.visibility = View.VISIBLE
    }

    fun preloadLaunchAd(activity: Activity) {
        launchAd = null
        launchActivity = WeakReference(activity)
        launchAnimationFinished = false
        launchAnimationFinishedAt = 0L
        launchAdDisplayed = false
        if (!PremiumAccess.canLoadLaunchInterstitial(activity)) return
        if (activity.isFinishing) return
            val ad = try {
                StartAppAd(activity)
            } catch (_: RuntimeException) {
                launchAd = null
                return
            }
            launchAd = ad
            try {
                ad.loadAd(
                    StartAppAd.AdMode.FULLPAGE,
                    object : AdEventListener {
                        override fun onReceiveAd(receivedAd: Ad) {
                            if (
                                launchAnimationFinished &&
                                SystemClock.uptimeMillis() - launchAnimationFinishedAt <= 1_500L
                            ) {
                                showReadyLaunchAd(ad)
                            }
                        }

                        override fun onFailedToReceiveAd(failedAd: Ad?) {
                            if (launchAd === ad) launchAd = null
                        }
                    }
                )
            } catch (_: RuntimeException) {
                if (launchAd === ad) launchAd = null
            }
    }

    fun showLaunchAdIfReady(activity: Activity) {
        launchAnimationFinished = true
        launchAnimationFinishedAt = SystemClock.uptimeMillis()
        val ad = launchAd ?: return
        showReadyLaunchAd(ad)
    }

    fun showInterstitialForMenu(activity: Activity) {
        if (!PremiumAccess.canLoadLaunchInterstitial(activity)) return
        if (activity.isFinishing) return
            val ad = try {
                StartAppAd(activity)
            } catch (_: RuntimeException) {
                return
            }
            try {
                ad.loadAd(StartAppAd.AdMode.FULLPAGE, object : AdEventListener {
                    override fun onReceiveAd(receivedAd: Ad) {
                        if (!activity.isFinishing && ad.isReady && PremiumAccess.reserveLaunchInterstitial(activity)) {
                            ad.showAd(object : AdDisplayListener {
                                override fun adDisplayed(displayedAd: Ad?) = Unit
                                override fun adClicked(clickedAd: Ad?) = Unit
                                override fun adHidden(hiddenAd: Ad?) = Unit
                                override fun adNotDisplayed(notDisplayedAd: Ad?) = Unit
                            })
                        }
                    }

                    override fun onFailedToReceiveAd(failedAd: Ad?) = Unit
                })
            } catch (_: RuntimeException) {
                // Ad providers may reject a request while the activity is changing state.
            }
    }

    fun disableAllAds() {
        releaseAdViews()
    }

    fun releaseAdViews() {
        launchAd = null
        launchActivity = null
        launchAnimationFinished = false
        launchAdDisplayed = false
        PremiumAccess.resetLaunchInterstitial()
        adGeneration += 1
        registeredNativeAds.forEach(NativeAdDetails::unregisterView)
        registeredNativeAds.clear()
        adSlots.removeAll { reference ->
            val slot = reference.get()
            if (slot == null) {
                true
            } else {
                slot.removeAllViews()
                slot.visibility = View.GONE
                true
            }
        }
    }

    private fun showReadyLaunchAd(ad: StartAppAd) {
        val activity = launchActivity?.get() ?: return
        if (
            launchAdDisplayed ||
            activity.isFinishing ||
            (launchAnimationFinished &&
                SystemClock.uptimeMillis() - launchAnimationFinishedAt > 1_500L) ||
            !ad.isReady
        ) return

        if (!PremiumAccess.reserveLaunchInterstitial(activity)) {
            launchAd = null
            return
        }

        launchAdDisplayed = true
        launchAd = null
        ad.showAd(object : AdDisplayListener {
            override fun adDisplayed(displayedAd: Ad?) = Unit

            override fun adClicked(clickedAd: Ad?) = Unit

            override fun adHidden(hiddenAd: Ad?) = Unit

            override fun adNotDisplayed(notDisplayedAd: Ad?) = Unit
        })
    }

    private fun showEntitlementError(context: Context, message: String?) {
        if (
            message == null ||
            message.contains("not configured", ignoreCase = true) ||
            message == lastEntitlementError
        ) return
        lastEntitlementError = message
        Toast.makeText(
            context.applicationContext,
            "Could not verify ad-free status: $message",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
