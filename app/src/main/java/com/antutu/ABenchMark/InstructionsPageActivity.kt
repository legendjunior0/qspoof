package com.antutu.ABenchMark

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import org.json.JSONArray

class InstructionsPageActivity : Activity() {

    private val BG_DARK = AppTheme.background
    private val CARD_BG = AppTheme.card
    private val CARD_ELEVATED = AppTheme.elevated
    private val ACCENT_CYAN = AppTheme.accentRed
    private val ACCENT_INDIGO = AppTheme.accentOrange
    private val TEXT_PRIMARY = AppTheme.textPrimary
    private val TEXT_SECONDARY = AppTheme.textSecondary
    private val TEXT_MUTED = AppTheme.textMuted
    private val BORDER_GLOW = AppTheme.border

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartIoAds.beginAdScreen()

        SpoofUi.applyFullScreen(this)
        window.navigationBarColor = BG_DARK

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DARK)
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }

        // Top Header
        content.addView(createHeaderBar())
        content.addView(createSpace(20))

        val intro = TextView(this).apply {
            text = "Quick Setup Guide"
            textSize = 24f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(intro)

        val subIntro = TextView(this).apply {
            text = "Follow these simple methods to activate performance spoofing."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
        }
        content.addView(
            subIntro,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        content.addView(createSpace(18))

        // Method 1 Card
        content.addView(
            createMethodCard(
                methodNum = "01",
                title = "Method 1 — Pop-up View",
                steps = arrayOf(
                    "Launch the target game or application.",
                    "Open QSpoof in Pop-up View mode from your system menu.",
                    "QSpoof will automatically minimize to background.",
                    "The spoofing profile will now remain active."
                )
            )
        )

        content.addView(createSpace(14))

        // Method 2 Card
        content.addView(
            createMethodCard(
                methodNum = "02",
                title = "Method 2 — Split Screen Mode",
                steps = arrayOf(
                    "Launch your target game or app.",
                    "Open QSpoof side-by-side in Split Screen mode.",
                    "QSpoof will trigger background mode automatically.",
                    "Expand your game to full screen; spoofing stays active."
                )
            )
        )

        content.addView(createSpace(14))

        content.addView(StartIoAds.createBannerSlot(this))
        content.addView(createNoteCard())
        content.addView(createSpace(14))
        content.addView(StartIoAds.createBannerSlot(this))

        // Supported Devices Card
        content.addView(createSupportedDevicesCard())

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)
        scroll.post {
            SpoofUi.applyContentInsets(this, content, 18, 12, 18, 28)
        }
        SpoofUi.registerPredictiveBack(this, scroll)
        animateEntrance(content)
    }

    private fun animateEntrance(content: ViewGroup) {
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is Space) continue
            child.alpha = 0f
            child.translationY = dp(20).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 45L).coerceAtMost(300L))
                .setDuration(380)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun createHeaderBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = TextView(this).apply {
            text = "‹ Back"
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(16), dp(8))
            background = roundedDrawable(CARD_ELEVATED, 12f, BORDER_GLOW, 1f)
            attachTouchScaleAnimation(this)
            setOnClickListener {
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        row.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "Instructions"
            textSize = 18f
            setTextColor(ACCENT_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        row.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        return row
    }

    private fun createMethodCard(
        methodNum: String,
        title: String,
        steps: Array<String>
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(CARD_BG, 20f, BORDER_GLOW, 1.2f)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = methodNum
            textSize = 11f
            setTextColor(ACCENT_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedDrawable(
                AppTheme.accentWash,
                10f,
                BORDER_GLOW,
                1f
            )
        }
        headerRow.addView(badge, LinearLayout.LayoutParams(dp(32), dp(28)))

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(
            titleView,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { leftMargin = dp(10) }
        )

        card.addView(headerRow)
        card.addView(createSpace(14))

        for (i in steps.indices) {
            val stepRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            val dot = View(this).apply {
                background = roundedDrawable(ACCENT_INDIGO, 4f)
            }
            stepRow.addView(
                dot,
                LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    topMargin = dp(6)
                    rightMargin = dp(10)
                }
            )

            val descView = TextView(this).apply {
                text = steps[i]
                textSize = 12f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(2).toFloat(), 1.12f)
            }
            stepRow.addView(
                descView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            card.addView(
                stepRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }

        return card
    }

    private fun createNoteCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(
                Color.rgb(43, 32, 20),
                16f,
                Color.rgb(91, 60, 27),
                1f
            )
        }

        val noteTitle = TextView(this).apply {
            text = "Important Note"
            textSize = 13f
            setTextColor(Color.rgb(255, 174, 66))
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(noteTitle)

        val noteText = TextView(this).apply {
            text = "QSpoof actively maintains its background spoofing while the host game/app is running. Closing the host application will end active spoofing."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(2).toFloat(), 1.1f)
        }
        card.addView(
            noteText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        return card
    }

    private fun createSupportedDevicesCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(CARD_ELEVATED, 16f, BORDER_GLOW, 1f)
        }
        val title = TextView(this).apply {
            text = "Supported Hardware Profiles"
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(title)
        val entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(entries)
        val loading = TextView(this).apply {
            text = "Loading supported devices…"
            textSize = 11f
            setTextColor(TEXT_MUTED)
        }
        entries.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        PremiumAccess.loadSupportedDeviceCatalog { categories, error ->
            entries.removeAllViews()
            if (categories == null) {
                val unavailable = TextView(this).apply {
                    text = "Supported devices are unavailable while offline. Open this page online to refresh the list."
                    textSize = 11f
                    setTextColor(TEXT_MUTED)
                }
                entries.addView(unavailable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                return@loadSupportedDeviceCatalog
            }
            if (categories.length() == 0) {
                val empty = TextView(this).apply { text = "No supported device profiles are listed yet."; textSize = 11f; setTextColor(TEXT_MUTED) }
                entries.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                return@loadSupportedDeviceCatalog
            }
            for (categoryIndex in 0 until categories.length()) {
                val category = categories.optJSONObject(categoryIndex) ?: continue
                val categoryName = TextView(this).apply {
                    text = category.optString("name")
                    textSize = 11f
                    setTextColor(ACCENT_CYAN)
                    typeface = Typeface.DEFAULT_BOLD
                }
                entries.addView(categoryName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (categoryIndex == 0) 7 else 10) })
                val items = category.optJSONArray("items") ?: JSONArray()
                for (itemIndex in 0 until items.length()) {
                    val item = items.optJSONObject(itemIndex) ?: continue
                    val status = item.optString("status", "Supported")
                    val row = TextView(this).apply {
                        text = "• ${item.optString("name")} — $status"
                        textSize = 11f
                        // Keep device entries visually uniform; only category headings use the accent color.
                        setTextColor(TEXT_PRIMARY)
                    }
                    entries.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                }
            }
        }
        return card
    }

    private fun createSpace(heightDp: Int): Space {
        return Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            )
        }
    }

    private fun attachTouchScaleAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                        .setInterpolator(OvershootInterpolator()).start()
                }
            }
            false
        }
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f
    ): Drawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            if (strokeColor != null && strokeWidthDp > 0f) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
