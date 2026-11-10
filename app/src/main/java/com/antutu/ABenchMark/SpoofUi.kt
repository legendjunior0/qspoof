package com.antutu.ABenchMark

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.app.DownloadManager
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
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
import android.widget.ScrollView
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
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object SpoofUi {

    private val qrExecutor = Executors.newSingleThreadExecutor()
    private val popupImageExecutor = Executors.newSingleThreadExecutor()
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
    private const val MAX_POPUP_IMAGE_BYTES = 8L * 1024L * 1024L

    private class ScaleToFitFrameLayout(context: Context) : FrameLayout(context) {
        private fun scaleFor(availableWidth: Float, contentWidth: Float, availableHeight: Float, contentHeight: Float): Float {
            val widthScale = availableWidth / contentWidth.coerceAtLeast(1f)
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
        channelUrl: String = TELEGRAM_CHANNEL,
        groupUrl: String = TELEGRAM_GROUP,
        remoteConfig: org.json.JSONObject = org.json.JSONObject(),
        onProfileClick: () -> Unit = {},
        onQspoofClick: () -> Unit = {},
        onLaunchAnimationComplete: () -> Unit = {}
    ): View {
        applyFullScreen(activity)
        val rootContainer = FrameLayout(activity).apply {
            setBackgroundColor(BG_DARK)
        }
        addRemoteAnnouncement(activity, rootContainer, remoteConfig)

        val mainDashboard = createMainDashboard(
            activity,
            channelUrl,
            groupUrl,
            remoteConfig,
            onProfileClick,
            onQspoofClick
        )
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

    private fun addRemoteAnnouncement(
        activity: Activity,
        root: FrameLayout,
        config: org.json.JSONObject
    ) {
        showRemotePopup(activity, config)
        val announcement = config.optJSONObject("announcement") ?: return
        if (!announcement.optBoolean("enabled", false)) return
        val message = announcement.optString("message").trim()
        if (message.isBlank()) return
        val key = "remote_announcement_" + announcement.optString("id", "default")
        val preferences = activity.getSharedPreferences("remote_ui", Context.MODE_PRIVATE)
        if (announcement.optBoolean("dismissible", true) && preferences.getBoolean(key, false)) return
        val card = TextView(activity).apply {
            text = message
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                setColor(Color.rgb(65, 75, 120))
            }
            if (announcement.optBoolean("dismissible", true)) {
                setOnClickListener {
                    preferences.edit().putBoolean(key, true).apply()
                    root.removeView(this)
                }
            }
        }
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(activity, 16), dp(activity, 18), dp(activity, 16), 0)
            gravity = Gravity.TOP
        })
    }

    private fun showRemotePopup(activity: Activity, config: org.json.JSONObject) {
        val popups = config.optJSONArray("popups") ?: return
        val preferences = activity.getSharedPreferences("remote_ui", Context.MODE_PRIVATE)
        val eligible = mutableListOf<org.json.JSONObject>()
        for (index in 0 until popups.length()) {
            val popup = popups.optJSONObject(index) ?: continue
            if (!popup.optBoolean("enabled", false)) continue
            val id = popup.optString("id").takeIf(String::isNotBlank) ?: continue
            val maxDisplays = popup.optInt("maxDisplays", 1)
            val shown = preferences.getInt("popup_count_$id", 0)
            if (maxDisplays <= 0 || shown >= maxDisplays) continue
            val cooldownHours = popup.optInt("cooldownHours", 0).coerceAtLeast(0)
            val lastShown = preferences.getLong("popup_last_$id", 0L)
            if (cooldownHours > 0 && System.currentTimeMillis() - lastShown < cooldownHours * 60L * 60L * 1000L) continue
            val title = popup.optString("title").trim()
            val message = popup.optString("message").trim()
            if (title.isBlank() || message.isBlank()) continue
            eligible.add(popup)
        }
        if (eligible.isEmpty()) return

        activity.window.decorView.post {
            if (activity.isFinishing) return@post

            var currentIndex = 0
            lateinit var dialog: android.app.AlertDialog
            lateinit var popupTitle: TextView
            lateinit var popupMessage: TextView
            lateinit var popupImage: ImageView
            lateinit var pageLabel: TextView
            lateinit var actionButton: TextView
            lateinit var laterButton: TextView
            lateinit var previousButton: TextView
            lateinit var nextButton: TextView

            fun markDisplayed(popup: org.json.JSONObject) {
                val id = popup.optString("id")
                preferences.edit()
                    .putInt("popup_count_$id", preferences.getInt("popup_count_$id", 0) + 1)
                    .putLong("popup_last_$id", System.currentTimeMillis())
                    .apply()
            }

            fun bindPopup(index: Int) {
                currentIndex = index.coerceIn(0, eligible.lastIndex)
                val popup = eligible[currentIndex]
                popupTitle.text = popup.optString("title")
                popupMessage.text = popup.optString("message")
                pageLabel.text = if (eligible.size > 1) {
                    "Swipe to browse  •  ${currentIndex + 1} / ${eligible.size}"
                } else {
                    ""
                }
                previousButton.visibility = if (eligible.size > 1) View.VISIBLE else View.INVISIBLE
                nextButton.visibility = if (eligible.size > 1) View.VISIBLE else View.INVISIBLE
                previousButton.isEnabled = currentIndex > 0
                nextButton.isEnabled = currentIndex < eligible.lastIndex
                previousButton.alpha = if (previousButton.isEnabled) 1f else 0.35f
                nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
                laterButton.visibility = if (popup.optBoolean("dismissible", true)) View.VISIBLE else View.GONE

                val mode = popup.optString("buttonMode", "link")
                val buttonText = popup.optString("buttonText").trim().ifBlank {
                    if (mode == "download") "Download Now" else "Learn More"
                }
                actionButton.text = buttonText
                actionButton.visibility = View.VISIBLE
                actionButton.setOnClickListener {
                    val link = popup.optString("link").trim()
                    if (!link.startsWith("https://", ignoreCase = true)) {
                        dialog.dismiss()
                    } else if (mode == "download") {
                        dialog.dismiss()
                        downloadPopupFile(activity, link, popup.optString("title"))
                    } else {
                        dialog.dismiss()
                        openUrl(activity, link)
                    }
                }

                val imageUrl = popup.optString("imageUrl").trim()
                popupImage.tag = imageUrl
                popupImage.setImageDrawable(null)
                popupImage.visibility = if (imageUrl.startsWith("https://", ignoreCase = true)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                if (popupImage.visibility == View.VISIBLE) loadPopupImage(popupImage, imageUrl)
                markDisplayed(popup)
            }

            val carousel = PopupCarouselFrame(activity) { delta ->
                val nextIndex = currentIndex + delta
                if (nextIndex in eligible.indices) bindPopup(nextIndex)
            }.apply {
                clipToPadding = true
                background = roundedDrawable(
                    AppTheme.card,
                    22f,
                    AppTheme.border,
                    1f,
                    activity
                )
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 14))
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val page = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            popupTitle = TextView(activity).apply {
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT_PRIMARY)
                gravity = Gravity.CENTER
            }
            popupImage = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Promotion image"
                background = roundedDrawable(AppTheme.elevated, 12f, AppTheme.border, 1f, activity)
                clipToOutline = true
            }
            popupMessage = TextView(activity).apply {
                textSize = 14f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(activity, 3).toFloat(), 1.08f)
            }
            page.addView(popupTitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            page.addView(popupImage, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 178)
            ).apply { topMargin = dp(activity, 12) })
            page.addView(popupMessage, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 14) })
            scroll.addView(page)
            content.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))

            actionButton = TextView(activity).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                background = AppTheme.gradient(activity, 14f)
                isClickable = true
                isFocusable = true
            }
            content.addView(actionButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 50)
            ).apply { topMargin = dp(activity, 14) })

            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            previousButton = popupNavButton(activity, "‹").apply {
                setOnClickListener { if (currentIndex > 0) bindPopup(currentIndex - 1) }
            }
            nextButton = popupNavButton(activity, "›").apply {
                setOnClickListener {
                    if (currentIndex < eligible.lastIndex) bindPopup(currentIndex + 1)
                }
            }
            pageLabel = TextView(activity).apply {
                textSize = 10f
                setTextColor(TEXT_MUTED)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            laterButton = TextView(activity).apply {
                text = "Remind Me Later"
                textSize = 14f
                setTextColor(TEXT_PRIMARY)
                gravity = Gravity.CENTER
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
            footer.addView(previousButton, LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 40)))
            footer.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            footer.addView(nextButton, LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 40)))
            content.addView(footer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 6) })
            content.addView(laterButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 44)
            ).apply { topMargin = dp(activity, 2) })
            carousel.addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            dialog = android.app.AlertDialog.Builder(activity).create().apply {
                setView(carousel)
                setCancelable(true)
                setCanceledOnTouchOutside(true)
                setOnShowListener {
                    window?.setBackgroundDrawableResource(android.R.color.transparent)
                    val width = (activity.resources.displayMetrics.widthPixels - dp(activity, 36))
                        .coerceAtMost(dp(activity, 440))
                    window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            bindPopup(0)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (activity.resources.displayMetrics.widthPixels - dp(activity, 36))
                .coerceAtMost(dp(activity, 440))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private class PopupCarouselFrame(
        context: Context,
        private val onSwipe: (Int) -> Unit
    ) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swipeDirection = 0

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    swipeDirection = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > dp(context, 48) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                    ) {
                        swipeDirection = if (dx < 0f) 1 else -1
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && swipeDirection != 0) {
                onSwipe(swipeDirection)
                swipeDirection = 0
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) swipeDirection = 0
            return true
        }
    }

    private fun popupNavButton(activity: Activity, label: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

    fun loadPopupImage(imageView: ImageView, url: String) {
        popupImageExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                if (!url.startsWith("https://", ignoreCase = true) ||
                    connection.responseCode !in 200..299 ||
                    (connection.contentLengthLong > 0L &&
                        connection.contentLengthLong > MAX_POPUP_IMAGE_BYTES)
                ) {
                    throw IOException("Promotion image URL or response is invalid")
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_POPUP_IMAGE_BYTES) {
                            throw IOException("Promotion image is too large")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Promotion image could not be decoded")
                imageView.post {
                    if (imageView.isAttachedToWindow && imageView.tag == url) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (error: Exception) {
                Log.w("QSpoofPopup", "Could not load promotional image", error)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun downloadPopupFile(activity: Activity, url: String, title: String): Boolean {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https") throw IllegalArgumentException("Only HTTPS downloads are allowed")
            val fileName = uri.lastPathSegment
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.take(100)
                ?.takeIf(String::isNotBlank)
                ?: "qspoof-download"
            val request = DownloadManager.Request(uri)
                .setTitle(title.take(80).ifBlank { "QSpoof download" })
                .setDescription("Downloading from QSpoof")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            if (fileName.endsWith(".apk", ignoreCase = true)) {
                request.setMimeType("application/vnd.android.package-archive")
            }
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show()
            return true
        } catch (error: Exception) {
            Toast.makeText(
                activity,
                error.message ?: "Could not start the download",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
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
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
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

    fun currentDisplayRefreshRate(activity: Activity): Float =
        activity.getSystemService(DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.mode
            ?.refreshRate
            ?: 0f

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

    private fun createMainDashboard(
        activity: Activity,
        channelUrl: String,
        groupUrl: String,
        remoteConfig: org.json.JSONObject,
        onProfileClick: () -> Unit,
        onQspoofClick: () -> Unit
    ): View {
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

        root.addView(createHeaderSection(activity, remoteConfig, onProfileClick, onQspoofClick))
        root.addView(createVerticalSpace(activity, 14))
        root.addView(createDonationCard(activity))
        root.addView(createRefreshRateCard(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 10) })
        root.addView(createVerticalSpace(activity, 14))
        root.addView(createCommunityCard(activity, channelUrl, groupUrl))
        root.addView(createVerticalSpace(activity, 16))
        root.addView(createActionButtonsRow(activity))

        val dashboardFrame = ScaleToFitFrameLayout(activity).apply {
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

    private fun createHeaderSection(
        activity: Activity,
        remoteConfig: org.json.JSONObject,
        onProfileClick: () -> Unit,
        onQspoofClick: () -> Unit
    ): View {
        fun createCard(): LinearLayout = LinearLayout(activity).apply {
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

        fun addBrandMark(card: LinearLayout, sizeDp: Int) {
            val brandMark = ImageView(activity).apply {
                setImageResource(R.drawable.original_app_icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedDrawable(Color.TRANSPARENT, 12f, context = activity)
                clipToOutline = true
                contentDescription = "QSpoof logo"
            }
            card.addView(brandMark, LinearLayout.LayoutParams(dp(activity, sizeDp), dp(activity, sizeDp)))
        }

        fun addTitle(
            card: LinearLayout,
            title: String,
            subtitle: String,
            hint: String,
            version: String? = null
        ) {
            val titleStack = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 12), 0, 0, 0)
            }
            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(activity).apply {
                text = title
                textSize = 20f
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            if (version != null) {
                titleRow.addView(TextView(activity).apply {
                    text = version
                    textSize = 10f
                    setTextColor(AppTheme.accentOrange)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(activity, 5), 0, 0, 0)
                })
            }
            titleStack.addView(titleRow)
            titleStack.addView(TextView(activity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(TEXT_SECONDARY)
            })
            titleStack.addView(TextView(activity).apply {
                text = hint
                textSize = 9f
                setTextColor(TEXT_MUTED)
            })
            card.addView(titleStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val cardHeight = dp(activity, 112)
        val front = createCard()
        addBrandMark(front, 55)
        val appBrand = if (BuildConfig.FLAVOR == "ludashi") "Qspoof" else "QSpoof"
        val engineName = "$appBrand Engine"
        addTitle(
            front,
            engineName,
            "Performance Spoofer & Optimizer",
            "Tap controls • Swipe to switch",
            "v5.0"
        )
        front.addView(createActiveStatus(activity))

        val back = createCard().apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            rotationX = -85f
            contentDescription = "Your Profile. Swipe down to return to $engineName."
        }
        val profileHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addBrandMark(profileHeader, 46)
        addTitle(profileHeader, "Your Profile", "Name and verified email", "Tap profile • Swipe to switch")
        profileHeader.addView(TextView(activity).apply {
            text = "›"
            textSize = 26f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 48)))
        back.addView(profileHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val referencePreview = TextView(activity).apply {
            text = "Reference ID: Open profile to load"
            textSize = 10f
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(activity, 60), dp(activity, 8), dp(activity, 4), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        back.addView(referencePreview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cardFlipper = FrameLayout(activity).apply {
            cameraDistance = 9000f * activity.resources.displayMetrics.density
            clipChildren = false
            addView(front, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight))
            addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight))
            contentDescription = "$engineName and Your Profile card. Tap the $appBrand card for controls. Swipe up or down to switch cards."
        }
        var showingProfile = false
        var cardAnimationRunning = false
        fun showCard(profile: Boolean, swipeDirection: Int) {
            if (showingProfile == profile || cardAnimationRunning) return
            cardAnimationRunning = true
            showingProfile = profile
            val outgoing = if (profile) front else back
            val incoming = if (profile) back else front
            val outgoingRotation = if (swipeDirection > 0) -90f else 90f
            val incomingRotation = -outgoingRotation
            outgoing.animate()
                .rotationX(outgoingRotation)
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    outgoing.visibility = View.GONE
                    outgoing.rotationX = 0f
                    outgoing.alpha = 1f
                    incoming.rotationX = incomingRotation
                    incoming.alpha = 0f
                    incoming.visibility = View.VISIBLE
                    incoming.animate()
                        .rotationX(0f)
                        .alpha(1f)
                        .setDuration(220L)
                        .withEndAction { cardAnimationRunning = false }
                        .start()
                }
                .start()
        }
        val swipeThreshold = dp(activity, 24)
        fun attachCardGesture(view: View) {
            var downX = 0f
            var downY = 0f
            var hasSwiped = false
            var movedBeyondTapSlop = false
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        hasSwiped = false
                        movedBeyondTapSlop = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - downX
                        val deltaY = event.y - downY
                        if (kotlin.math.abs(deltaX) >= swipeThreshold ||
                            kotlin.math.abs(deltaY) >= swipeThreshold
                        ) {
                            movedBeyondTapSlop = true
                        }
                        if (kotlin.math.abs(deltaY) >= swipeThreshold &&
                            kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 1.2f
                        ) {
                            hasSwiped = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val deltaY = event.y - downY
                        if (hasSwiped &&
                            kotlin.math.abs(deltaY) >= swipeThreshold &&
                            kotlin.math.abs(deltaY) > kotlin.math.abs(event.x - downX) * 1.2f
                        ) {
                            showCard(!showingProfile, if (deltaY > 0f) 1 else -1)
                        } else if (!movedBeyondTapSlop) {
                            if (showingProfile) onProfileClick() else onQspoofClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hasSwiped = false
                        movedBeyondTapSlop = true
                    }
                }
                true
            }
        }
        listOf(front, back, profileHeader).forEach(::attachCardGesture)
        PremiumAccess.loadDeviceProfile(activity) { profile, _ ->
            if (!activity.isFinishing) {
                val ref = profile?.referenceId?.takeIf(String::isNotBlank)
                referencePreview.text = if (ref == null) "Reference ID: Not linked" else "Reference ID: $ref"
            }
        }
        return cardFlipper
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

    private fun createRefreshRateCard(activity: Activity): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = roundedDrawable(CARD_BG, 18f, CARD_BORDER_GLOW, 1f, activity)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = "Force Refresh Rate"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rateStatus = TextView(activity).apply {
            text = "--Hz"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
            setTextColor(Color.rgb(52, 211, 153))
            background = roundedDrawable(
                Color.argb(30, 52, 211, 153),
                5f,
                Color.argb(70, 52, 211, 153),
                1f,
                activity
            )
            contentDescription = "Current display refresh rate"
        }
        header.addView(rateStatus)
        card.addView(header)

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rateButtons = mutableMapOf<Int, TextView>()
        var resetControl: TextView? = null
        fun updateButtons() {
            val selectedRate = Rms.selectedRate(activity)
            rateButtons.forEach { (rate, button) ->
                val selected = selectedRate == rate
                button.setTextColor(if (selected) Color.WHITE else TEXT_SECONDARY)
                button.background = if (selected) AppTheme.gradient(activity, 10f)
                else roundedDrawable(AppTheme.elevated, 10f, CARD_BORDER_GLOW, 1f, activity)
            }
            resetControl?.apply {
                setTextColor(AppTheme.accentRed)
                background = roundedDrawable(
                    AppTheme.accentWash,
                    10f,
                    AppTheme.accentRed,
                    1f,
                    activity
                )
            }
        }
        val rates = listOf(90, 120, 144)
        rates.forEachIndexed { index, rate ->
            val button = TextView(activity).apply {
                text = "${rate}Hz"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = 0
                setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Request $rate hertz"
                setOnClickListener {
                    if (Rms.vote(activity, rate)) {
                        Rms.saveSelectedRate(activity, rate)
                        updateButtons()
                    } else {
                        Toast.makeText(
                            activity,
                            "Could not request $rate Hz on this device",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            rateButtons[rate] = button
            buttonRow.addView(button, LinearLayout.LayoutParams(0, dp(activity, 36), 1f).apply {
                if (index > 0) leftMargin = dp(activity, 4)
                rightMargin = dp(activity, 4)
            })
        }

        val resetButton = TextView(activity).apply {
            text = "Reset"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = 0
            setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
            contentDescription = "Reset refresh rate to system default"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (Rms.reset()) {
                    Rms.saveSelectedRate(activity, 0)
                    updateButtons()
                } else {
                    Toast.makeText(
                        activity,
                        "Could not reset the refresh rate on this device",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        resetControl = resetButton
        buttonRow.addView(resetButton, LinearLayout.LayoutParams(0, dp(activity, 36), 1f).apply {
            leftMargin = dp(activity, 4)
        })
        card.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 36)
        ).apply { topMargin = dp(activity, 8) })

        updateButtons()

        val refreshRateUpdater = object : Runnable {
            override fun run() {
                if (!card.isAttachedToWindow || activity.isFinishing) return
                val rate = currentDisplayRefreshRate(activity)
                if (rate > 0f) {
                    rateStatus.text = String.format(Locale.US, "%.0fHz", rate)
                    rateStatus.setTextColor(Color.rgb(52, 211, 153))
                } else {
                    rateStatus.text = "--Hz"
                    rateStatus.setTextColor(TEXT_SECONDARY)
                }
                updateButtons()
                card.postDelayed(this, 1_000L)
            }
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeCallbacks(refreshRateUpdater)
                view.post(refreshRateUpdater)
            }

            override fun onViewDetachedFromWindow(view: View) {
                view.removeCallbacks(refreshRateUpdater)
            }
        })
        return card
    }

    private fun createActiveStatus(context: Context): LinearLayout {
        val green = Color.rgb(52, 211, 153)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(10), dp(5))
            background = roundedDrawable(
                Color.argb(30, 52, 211, 153),
                14f,
                Color.argb(70, 52, 211, 153),
                1f,
                context
            )
            contentDescription = "Status: Active"
        }

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(green)
            }
        }

        val pulse = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 650L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                dot.alpha = value
                dot.scaleX = 0.85f + value * 0.15f
                dot.scaleY = 0.85f + value * 0.15f
            }
        }

        dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = pulse.start()
            override fun onViewDetachedFromWindow(view: View) = pulse.cancel()
        })

        status.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            marginEnd = dp(6)
        })
        status.addView(TextView(context).apply {
            text = "Active"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            letterSpacing = 0.08f
        }, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return status
    }

    private fun createCommunityCard(activity: Activity, channelUrl: String, groupUrl: String): View {
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
            url = channelUrl
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
            url = groupUrl
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
