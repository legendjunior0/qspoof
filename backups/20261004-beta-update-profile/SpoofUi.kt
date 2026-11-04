package com.antutu.ABenchMark

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.view.WindowInsets
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

object SpoofUi {

    private val qrExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cachedQrBitmap: Bitmap? = null
    private val touchOvershootInterpolator = OvershootInterpolator()

    private val BG_DARK = AppTheme.background
    private val CARD_BG = AppTheme.card
    private val CARD_BG_ELEVATED = AppTheme.elevated
    private val ACCENT_CYAN = AppTheme.accentRed
    private val ACCENT_INDIGO = AppTheme.accentOrange
    private val ACCENT_WASH = AppTheme.accentWash

    private val TEXT_PRIMARY = AppTheme.textPrimary
    private val TEXT_SECONDARY = AppTheme.textSecondary
    private val TEXT_MUTED = AppTheme.textMuted

    private val CARD_BORDER_GLOW = AppTheme.border
    private val HEART_RED = AppTheme.heart

    private const val UPI_ID = "ag1234.ag2@okhdfcbank"
    private const val UPI_URI = "upi://pay?pa=$UPI_ID"
    private const val TELEGRAM_CHANNEL = "https://t.me/iqoo_neo10_channel"
    private const val TELEGRAM_GROUP = "https://t.me/iqoo_neo10"

    private class ScaleToFitFrameLayout(
        context: Context,
        private val reserveSideAds: Boolean
    ) : FrameLayout(context) {
        private fun scaleFor(availableWidth: Float, contentWidth: Float, availableHeight: Float, contentHeight: Float): Float {
            val sideAdsWidth = if (reserveSideAds) dp(context, 100).toFloat() else 0f
            val widthScale = availableWidth / (contentWidth + sideAdsWidth).coerceAtLeast(1f)
            val heightScale = availableHeight / contentHeight.coerceAtLeast(1f)
            return minOf(1f, widthScale, heightScale)
        }

        private fun scaleContentToBounds() {
            val content = getChildAt(0) ?: return
            val contentWidth = content.measuredWidth.toFloat().coerceAtLeast(1f)
            val contentHeight = content.measuredHeight.toFloat().coerceAtLeast(1f)
            val animationRoom = dp(context, 18).toFloat()
            val scale = scaleFor(
                width.toFloat(),
                contentWidth,
                (height.toFloat() - animationRoom).coerceAtLeast(1f),
                contentHeight
            )
            content.pivotX = contentWidth / 2f
            content.pivotY = 0f
            content.scaleX = scale
            content.scaleY = scale
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val content = getChildAt(0)
            if (content == null) {
                setMeasuredDimension(
                    resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                    resolveSize(suggestedMinimumHeight, heightMeasureSpec)
                )
                return
            }
            val params = content.layoutParams as ViewGroup.MarginLayoutParams
            val childWidthSpec = getChildMeasureSpec(
                widthMeasureSpec,
                paddingLeft + paddingRight + params.leftMargin + params.rightMargin,
                params.width
            )
            content.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val desiredWidth = content.measuredWidth + paddingLeft + paddingRight + params.leftMargin + params.rightMargin
            val desiredHeight = content.measuredHeight + paddingTop + paddingBottom + params.topMargin + params.bottomMargin
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
            )
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec).toFloat()
            val availableHeight = (MeasureSpec.getSize(heightMeasureSpec) - dp(context, 18))
                .coerceAtLeast(1).toFloat()
            val scale = scaleFor(
                availableWidth,
                content.measuredWidth.coerceAtLeast(1).toFloat(),
                availableHeight,
                content.measuredHeight.coerceAtLeast(1).toFloat()
            )
            content.pivotX = content.measuredWidth / 2f
            content.pivotY = 0f
            content.scaleX = scale
            content.scaleY = scale
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            scaleContentToBounds()
        }
    }

    fun create(
        activity: Activity,
        onLaunchAnimationComplete: () -> Unit = {}
    ): View {
        applyFullScreen(activity)
        val rootContainer = FrameLayout(activity).apply {
            setBackgroundColor(BG_DARK)
        }

        val mainDashboard = createMainDashboard(activity)
        mainDashboard.alpha = 0f
        mainDashboard.scaleX = 0.97f
        mainDashboard.scaleY = 0.97f

        rootContainer.addView(
            mainDashboard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addPopupSideAds(activity, rootContainer, mainDashboard)

        val splashOverlay = createSplashOverlay(activity) {
            mainDashboard.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(450)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    animateDashboardEntrance(mainDashboard)
                    onLaunchAnimationComplete()
                }
                .start()
        }

        rootContainer.addView(
            splashOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        registerPredictiveBack(activity, rootContainer)
        return rootContainer
    }

    fun createAccessLoadingView(activity: Activity): View {
        applyFullScreen(activity)
        val overlay = FrameLayout(activity).apply { setBackgroundColor(BG_DARK) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(activity, 20).toFloat()
        }
        content.addView(
            OrbitLoader(activity),
            LinearLayout.LayoutParams(dp(activity, 118), dp(activity, 118))
        )
        val connectionLabel = TextView(activity).apply {
            text = "Establishing Connection"
            textSize = 14f
            typeface = Typeface.DEFAULT
            setTextColor(ACCENT_CYAN)
            gravity = Gravity.CENTER
        }
        content.addView(
            connectionLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 18) }
        )
        overlay.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        overlay.post {
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        return overlay
    }

    private class OrbitLoader(context: Context) : View(context) {
        private val orbitColor = AppTheme.accentRed
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val random = java.util.Random()
        private val ringBases = FloatArray(4) { random.nextFloat() * 360f }
        private val ringRotations = ringBases.copyOf()
        private var coreScale = 1f

        private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val rotation = it.animatedValue as Float
                ringRotations.indices.forEach { index ->
                    val direction = if (index % 2 == 0) 1f else -1f
                    ringRotations[index] = (ringBases[index] + direction * rotation) % 360f
                }
                invalidate()
            }
        }
        private val pulseAnimator = ValueAnimator.ofFloat(0.96f, 1.04f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                coreScale = it.animatedValue as Float
                invalidate()
            }
        }
        private val bounds = RectF()

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!rotationAnimator.isStarted) rotationAnimator.start()
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        }

        override fun onDetachedFromWindow() {
            rotationAnimator.cancel()
            pulseAnimator.cancel()
            super.onDetachedFromWindow()
        }

        fun stopAnimations() {
            rotationAnimator.cancel()
            pulseAnimator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val density = resources.displayMetrics.density
            val maxRadius = width.coerceAtMost(height) * 0.42f
            val ringStrokeWidths = floatArrayOf(3f, 2.5f, 2.5f, 2f)
            val ringSweepAngles = floatArrayOf(92f, 118f, 76f, 104f)
            val ringOffsets = floatArrayOf(0f, 112f, 206f, 278f)
            val ringColors = intArrayOf(
                orbitColor,
                Color.rgb(75, 78, 85),
                ACCENT_CYAN,
                ACCENT_INDIGO
            )
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (index in ringStrokeWidths.indices) {
                val radius = maxRadius * (0.52f + index * 0.16f)
                bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
                paint.color = ringColors[index]
                paint.strokeWidth = ringStrokeWidths[index] * density
                canvas.drawArc(
                    bounds,
                    ringRotations[index] + ringOffsets[index],
                    ringSweepAngles[index],
                    false,
                    paint
                )
            }

            canvas.save()
            canvas.scale(coreScale, coreScale, cx, cy)
            paint.style = Paint.Style.FILL
            paint.color = ACCENT_CYAN
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 32f * density
            val baseline = cy - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText("Q", cx, baseline, paint)
            canvas.restore()
        }
    }

    fun applyFullScreen(activity: Activity) {
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        activity.window.statusBarColor = BG_DARK
        activity.window.navigationBarColor = BG_DARK
        val decorView = activity.window.decorView
        decorView.post {
            decorView.windowInsetsController?.let { controller ->
                controller.show(WindowInsets.Type.statusBars())
                controller.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
    }

    fun applyContentInsets(
        activity: Activity,
        content: View,
        leftDp: Int,
        topDp: Int,
        rightDp: Int,
        bottomDp: Int
    ) {
        val density = activity.resources.displayMetrics.density
        val left = (leftDp * density).toInt()
        val top = (topDp * density).toInt()
        val right = (rightDp * density).toInt()
        val bottom = (bottomDp * density).toInt()

        content.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            view.setPadding(
                left + systemBars.left,
                top + systemBars.top,
                right + systemBars.right,
                bottom + systemBars.bottom
            )
            insets
        }
        content.requestApplyInsets()
    }

    fun registerPredictiveBack(activity: Activity, content: View) {
        val callback = object : OnBackAnimationCallback {
            private var direction = 1f

            override fun onBackStarted(backEvent: BackEvent) {
                direction = if (backEvent.swipeEdge == BackEvent.EDGE_LEFT) 1f else -1f
                content.animate().cancel()
                content.pivotX = if (direction > 0f) 0f else content.width.toFloat()
            }

            override fun onBackProgressed(backEvent: BackEvent) {
                val progress = backEvent.progress.coerceIn(0f, 1f)
                content.translationX = direction * dp(activity, 28) * progress
                content.scaleX = 1f - progress * 0.025f
                content.scaleY = 1f - progress * 0.025f
                content.alpha = 1f - progress * 0.12f
            }

            override fun onBackCancelled() {
                content.animate()
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(220)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }

            override fun onBackInvoked() {
                activity.finish()
                activity.overridePendingTransition(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
            }
        }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
    }

    private fun animateDashboardEntrance(dashboard: View) {
        val content = (dashboard as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is Space) continue
            val restingTranslationY = child.translationY
            child.alpha = 0f
            child.translationY = restingTranslationY + dp(child.context, 18)
            child.animate()
                .alpha(1f)
                .translationY(restingTranslationY)
                .setStartDelay((index * 50L).coerceAtMost(320L))
                .setDuration(380)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun createMainDashboard(activity: Activity): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG_DARK)
            setPadding(
                dp(activity, 18),
                dp(activity, 0),
                dp(activity, 18),
                dp(activity, 0)
            )
        }

        root.addView(createHeaderSection(activity))
        root.addView(createVerticalSpace(activity, 14))
        root.addView(createDonationCard(activity))
        root.addView(StartIoAds.createBannerSlot(activity, forceDisplay = true))
        root.addView(createVerticalSpace(activity, 14))
        root.addView(createCommunityCard(activity))
        root.addView(StartIoAds.createBannerSlot(activity, forceDisplay = true))
        root.addView(createVerticalSpace(activity, 16))
        root.addView(createActionButtonsRow(activity))

        val reserveSideAds = activity.isInMultiWindowMode ||
            activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val dashboardFrame = ScaleToFitFrameLayout(activity, reserveSideAds).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(BG_DARK)
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        }
        dashboardFrame.post {
            applyContentInsets(activity, root, 18, 18, 18, 30)
        }

        return dashboardFrame
    }

    private fun addPopupSideAds(
        activity: Activity,
        rootContainer: FrameLayout,
        mainDashboard: View
    ) {
        val scaledContent = (mainDashboard as? ViewGroup)?.getChildAt(0) ?: return
        val dashboardRoot = scaledContent as? ViewGroup ?: return
        // The popup leaves gutters on both sides of the scaled portrait dashboard.
        // Use one landscape native ad in each gutter beside the donation and spaces cards.
        val anchors = listOfNotNull(dashboardRoot.getChildAt(2), dashboardRoot.getChildAt(5))
        if (anchors.size != 2) return
        rootContainer.clipChildren = false
        rootContainer.clipToPadding = false
        val sideSlots = anchors.flatMap { anchor ->
            listOf(Gravity.START, Gravity.END).map { horizontalGravity ->
                // Use the same StartApp banner manager as the two inline menu ads.
                val slot = StartIoAds.createBannerSlot(activity, forceDisplay = true)
                rootContainer.addView(
                    slot,
                    FrameLayout.LayoutParams(dp(activity, 320), dp(activity, 50)).apply {
                        gravity = Gravity.TOP or horizontalGravity
                        if (horizontalGravity == Gravity.START) leftMargin = dp(activity, 4)
                        else rightMargin = dp(activity, 4)
                    }
                )
                Triple(slot, anchor, horizontalGravity)
            }
        }

        fun updateRails() {
            val scaleX = scaledContent.scaleX
            val scaleY = scaledContent.scaleY
            val dashboardWidth = scaledContent.width * scaleX
            val dashboardLeft = (rootContainer.width - dashboardWidth) / 2f
            val renderedAdWidth = dp(activity, 50) * scaleX
            val gap = dp(activity, 4) * scaleX
            sideSlots.forEach { (slot, anchor, horizontalGravity) ->
                val params = slot.layoutParams as FrameLayout.LayoutParams
                if (params.width != dp(activity, 320) || params.height != dp(activity, 50)) {
                    params.width = dp(activity, 320)
                    params.height = dp(activity, 50)
                    slot.layoutParams = params
                }
                slot.pivotX = dp(activity, 160).toFloat()
                slot.pivotY = dp(activity, 25).toFloat()
                slot.scaleX = scaleX
                slot.scaleY = scaleY
                slot.rotation = 90f
                params.gravity = Gravity.TOP or Gravity.LEFT
                val bannerCenterX = if (horizontalGravity == Gravity.START) {
                    dashboardLeft - renderedAdWidth / 2f - gap
                } else {
                    dashboardLeft + dashboardWidth + renderedAdWidth / 2f + gap
                }
                params.leftMargin = bannerCenterX.toInt() - dp(activity, 160)
                params.rightMargin = 0
                val anchorY = mainDashboard.top + scaledContent.top +
                    ((anchor.top + anchor.height / 2f) * scaleY).toInt()
                params.topMargin = anchorY - dp(activity, 25)
                slot.layoutParams = params
                slot.visibility = View.VISIBLE
            }
        }

        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRails() }
        mainDashboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRails() }
        scaledContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRails() }
        updateRails()
    }

    private fun createSplashOverlay(
        activity: Activity,
        onComplete: () -> Unit
    ): View {
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(BG_DARK)
        }

        val contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(activity, 20).toFloat()
        }

        val loader = OrbitLoader(activity)
        contentContainer.addView(
            loader,
            LinearLayout.LayoutParams(dp(activity, 118), dp(activity, 118))
        )

        val statusText = TextView(activity).apply {
            text = "Establishing connection"
            textSize = 10f
            setTextColor(ACCENT_CYAN)
            gravity = Gravity.CENTER
        }
        contentContainer.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
        )

        val progressBar = FrameLayout(activity).apply {
            background = roundedDrawable(Color.rgb(54, 56, 61), 3f, context = activity)
        }
        val progressFill = View(activity).apply {
            background = AppTheme.gradient(activity, 3f)
        }
        progressBar.addView(
            progressFill,
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        contentContainer.addView(
            progressBar,
            LinearLayout.LayoutParams(dp(activity, 180), dp(activity, 4)).apply {
                topMargin = dp(activity, 22)
            }
        )

        overlay.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        var isDismissed = false
        var launchAnimator: ValueAnimator? = null
        fun dismissOverlay() {
            if (isDismissed) return
            isDismissed = true
            launchAnimator?.cancel()
            loader.stopAnimations()
            overlay.animate()
                .alpha(0f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    overlay.visibility = View.GONE
                    onComplete()
                }
                .start()
        }

        launchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val fillParams = progressFill.layoutParams
                fillParams.width = (progressBar.width * fraction).toInt()
                progressFill.layoutParams = fillParams
                statusText.text = when {
                    fraction < 0.2f -> "Establishing connection"
                    fraction < 0.4f -> "Instantiating"
                    fraction < 0.6f -> "Preparing QSpoof"
                    fraction < 0.8f -> "Loading Services"
                    else -> "Almost ready"
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dismissOverlay()
                }
            })
        }

        overlay.post {
            contentContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setInterpolator(DecelerateInterpolator())
                .start()
            progressBar.post { launchAnimator?.start() }
        }

        overlay.postDelayed({ dismissOverlay() }, 1900)

        return overlay
    }

    private fun createHeaderSection(activity: Activity): View {
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
            background = roundedDrawable(
                CARD_BG,
                22f,
                CARD_BORDER_GLOW,
                1.2f,
                activity
            )
        }

        val brandMark = TextView(activity).apply {
            text = "Q"
            textSize = 22f
            setTextColor(ACCENT_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = AppTheme.gradient(activity, 16f, CARD_BORDER_GLOW, 1f)
        }
        hero.addView(brandMark, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))

        val titleStack = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12), 0, 0, 0)
        }
        val titleView = TextView(activity).apply {
            text = "QSpoof Engine"
            textSize = 20f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleStack.addView(titleView)

        val subtitleView = TextView(activity).apply {
            text = "Performance Spoofer & Optimizer"
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        titleStack.addView(subtitleView)

        hero.addView(
            titleStack,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val statusPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 10), dp(activity, 5))
            background = roundedDrawable(
                Color.argb(30, 52, 211, 153),
                14f,
                Color.argb(70, 52, 211, 153),
                1f,
                activity
            )
        }
        val dotView = View(activity).apply {
            background = roundedDrawable(Color.rgb(52, 211, 153), 8f, context = activity)
        }
        val activePulse = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 650L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val pulse = animator.animatedValue as Float
                dotView.alpha = pulse
                dotView.scaleX = 0.85f + pulse * 0.15f
                dotView.scaleY = 0.85f + pulse * 0.15f
            }
        }
        dotView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                activePulse.start()
            }

            override fun onViewDetachedFromWindow(view: View) {
                activePulse.cancel()
            }
        })
        statusPill.addView(
            dotView,
            LinearLayout.LayoutParams(dp(activity, 6), dp(activity, 6)).apply {
                rightMargin = dp(activity, 6)
            }
        )
        val statusText = TextView(activity).apply {
            text = "ACTIVE"
            textSize = 9f
            setTextColor(Color.rgb(52, 211, 153))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        statusPill.addView(statusText)

        hero.addView(statusPill)

        return hero
    }

    private fun createDonationCard(activity: Activity): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16)
            )
            background = roundedDrawable(
                CARD_BG,
                20f,
                CARD_BORDER_GLOW,
                1.2f,
                activity
            )
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val heart = TextView(activity).apply {
            text = "♥"
            textSize = 16f
            setTextColor(AppTheme.accentRed)
        }
        titleRow.addView(heart)

        val titleView = TextView(activity).apply {
            text = "Support Project Development"
            textSize = 16f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        titleRow.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(activity, 8) }
        )
        card.addView(titleRow)

        val subTitleView = TextView(activity).apply {
            text = "Scan or tap the QR code below to contribute via UPI"
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
        }
        card.addView(
            subTitleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 4) }
        )

        val qrOuterCard = FrameLayout(activity).apply {
            background = roundedDrawable(
                CARD_BG_ELEVATED,
                18f,
                CARD_BORDER_GLOW,
                1.5f,
                activity
            )
            attachTouchScaleAnimation(this)
        }

        val qrInnerSurface = FrameLayout(activity).apply {
            background = roundedDrawable(
                Color.rgb(245, 245, 245),
                14f,
                null,
                0f,
                activity
            )
        }

        val qrImage = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        loadQrBitmap(qrImage)

        qrInnerSurface.addView(
            qrImage,
            FrameLayout.LayoutParams(
                dp(activity, 124),
                dp(activity, 124),
                Gravity.CENTER
            )
        )

        qrOuterCard.addView(
            qrInnerSurface,
            FrameLayout.LayoutParams(
                dp(activity, 134),
                dp(activity, 134),
                Gravity.CENTER
            )
        )

        qrOuterCard.setOnClickListener { openUpiPayment(activity) }

        card.addView(
            qrOuterCard,
            LinearLayout.LayoutParams(
                dp(activity, 146),
                dp(activity, 146)
            ).apply { topMargin = dp(activity, 12) }
        )

        val tapHintText = TextView(activity).apply {
            text = "TAP TO PAY WITH ANY UPI APP"
            textSize = 9f
            setTextColor(ACCENT_CYAN)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        card.addView(
            tapHintText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
        )

        val upiRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(activity, 12),
                dp(activity, 8),
                dp(activity, 8),
                dp(activity, 8)
            )
            background = roundedDrawable(
                CARD_BG_ELEVATED,
                14f,
                CARD_BORDER_GLOW,
                1f,
                activity
            )
            attachTouchScaleAnimation(this)
        }

        val upiDetailsLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val upiLabelText = TextView(activity).apply {
            text = "UPI ADDRESS"
            textSize = 9f
            setTextColor(TEXT_MUTED)
            typeface = Typeface.DEFAULT_BOLD
        }

        val upiValueText = TextView(activity).apply {
            text = UPI_ID
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }

        upiDetailsLayout.addView(upiLabelText)
        upiDetailsLayout.addView(upiValueText)

        upiRow.addView(
            upiDetailsLayout,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val copyButton = TextView(activity).apply {
            text = "COPY"
            textSize = 10f
            setTextColor(ACCENT_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(
                dp(activity, 10),
                dp(activity, 6),
                dp(activity, 10),
                dp(activity, 6)
            )
            background = roundedDrawable(
                ACCENT_WASH,
                8f,
                CARD_BORDER_GLOW,
                1f,
                activity
            )
        }

        upiRow.addView(copyButton)
        upiRow.setOnClickListener { copyToClipboard(activity, "UPI ID", UPI_ID) }

        card.addView(
            upiRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 12) }
        )

        return card
    }

    private fun createCommunityCard(activity: Activity): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16)
            )
            background = roundedDrawable(
                CARD_BG,
                20f,
                CARD_BORDER_GLOW,
                1.2f,
                activity
            )
        }

        val titleView = TextView(activity).apply {
            text = "Our Telegram Spaces"
            textSize = 15f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(titleView)

        val subTitleView = TextView(activity).apply {
            text = "Stay updated on new releases and discuss features."
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        card.addView(
            subTitleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 2) }
        )

        val item1 = createCommunityRow(
            activity,
            title = "Main Channel",
            subtitle = "Official news, releases & updates",
            url = TELEGRAM_CHANNEL
        )
        card.addView(
            item1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 12) }
        )

        val item2 = createCommunityRow(
            activity,
            title = "Discussions Group",
            subtitle = "Community chat & live support",
            url = TELEGRAM_GROUP
        )
        card.addView(
            item2,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
        )

        val credits = TextView(activity).apply {
            text = "Credits: Aditya🥀 & ATOM"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        card.addView(
            credits,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 14) }
        )

        return card
    }

    private fun createCommunityRow(
        activity: Activity,
        title: String,
        subtitle: String,
        url: String
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(activity, 12),
                dp(activity, 10),
                dp(activity, 12),
                dp(activity, 10)
            )
            background = roundedDrawable(
                CARD_BG_ELEVATED,
                14f,
                CARD_BORDER_GLOW,
                1f,
                activity
            )
            attachTouchScaleAnimation(this)
        }

        val infoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitleView = TextView(activity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(TEXT_MUTED)
        }

        infoLayout.addView(titleView)
        infoLayout.addView(subtitleView)

        row.addView(
            infoLayout,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val joinButton = TextView(activity).apply {
            text = "JOIN ↗"
            textSize = 10f
            setTextColor(ACCENT_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(
                dp(activity, 10),
                dp(activity, 6),
                dp(activity, 10),
                dp(activity, 6)
            )
            background = roundedDrawable(
                ACCENT_WASH,
                8f,
                CARD_BORDER_GLOW,
                1f,
                activity
            )
        }

        row.addView(joinButton)
        joinButton.setOnClickListener { openUrl(activity, url) }
        row.setOnClickListener { openUrl(activity, url) }

        return row
    }

    private fun createActionButtonsRow(activity: Activity): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val donatedBtn = TextView(activity).apply {
            text = SpannableString("I DONATED ♥").apply {
                val heartIndex = indexOf('♥')
                setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    heartIndex,
                    heartIndex + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = AppTheme.gradient(activity, 14f)
            attachTouchScaleAnimation(this)
            setOnClickListener {
                activity.startActivity(Intent(activity, DonatedPageActivity::class.java))
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }

        row.addView(
            donatedBtn,
            LinearLayout.LayoutParams(0, dp(activity, 52), 1f).apply {
                rightMargin = dp(activity, 6)
            }
        )

        val instructionsBtn = TextView(activity).apply {
            text = "HOW TO USE →"
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(
                CARD_BG_ELEVATED,
                14f,
                CARD_BORDER_GLOW,
                1f,
                activity
            )
            attachTouchScaleAnimation(this)
            setOnClickListener {
                activity.startActivity(Intent(activity, InstructionsPageActivity::class.java))
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }

        row.addView(
            instructionsBtn,
            LinearLayout.LayoutParams(0, dp(activity, 52), 1f).apply {
                leftMargin = dp(activity, 6)
            }
        )

        return row
    }

    private fun openUpiPayment(activity: Activity) {
        val upiIntent = Intent(Intent.ACTION_VIEW, Uri.parse(UPI_URI))
        try {
            activity.startActivity(upiIntent)
        } catch (_: Exception) {
            Toast.makeText(activity, "No UPI payment application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "UPI ID copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(activity: Activity, url: String) {
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            activity.startActivity(linkIntent)
            // Keep the app task in the background if the browser or Telegram
            // immediately returns after resolving the community deep link.
            activity.moveTaskToBack(true)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No browser or supported app found to open this link", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(activity, "Android blocked opening this link", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateQr(value: String): Bitmap {
        val size = 500
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )

        val matrix = MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

        val darkPixel = Color.rgb(18, 19, 21)
        val lightPixel = Color.WHITE

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) darkPixel else lightPixel)
            }
        }

        return bitmap
    }

    private fun loadQrBitmap(imageView: ImageView) {
        cachedQrBitmap?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            return
        }
        qrExecutor.execute {
            val bitmap = cachedQrBitmap ?: runCatching { generateQr(UPI_URI) }.getOrNull()
            if (bitmap != null) {
                cachedQrBitmap = bitmap
                imageView.post {
                    if (imageView.isAttachedToWindow) imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun createVerticalSpace(context: Context, heightDp: Int): Space {
        return Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
            )
        }
    }

    private fun attachTouchScaleAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                        .setInterpolator(touchOvershootInterpolator).start()
                }
            }
            false
        }
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f,
        context: Context
    ): Drawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            if (strokeColor != null && strokeWidthDp > 0f) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
