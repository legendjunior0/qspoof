package com.antutu.ABenchMark

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class RefreshRateActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var currentRate: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var selectionStatus: TextView
    private lateinit var rateButtons: List<TextView>

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpoofUi.applyFullScreen(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppTheme.background)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(30))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(TextView(this).apply {
            text = "‹  Back"
            textSize = 14f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(9), dp(16), dp(9))
            background = rounded(AppTheme.field, 12f, AppTheme.border)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "QSPOOF CONTROL"
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(AppTheme.accentRed)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(toolbar)

        val intro = TextView(this).apply {
            text = "Refresh rate"
            textSize = 26f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(intro, topMargin(dp(26)))
        content.addView(TextView(this).apply {
            text = "Choose a display rate for this device"
            textSize = 13f
            setTextColor(AppTheme.textSecondary)
        }, topMargin(dp(4)))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(AppTheme.card, 22f, AppTheme.border)
        }
        statusCard.addView(TextView(this).apply {
            text = "ACTIVE REFRESH RATE"
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(AppTheme.textMuted)
            typeface = Typeface.DEFAULT_BOLD
        })
        currentRate = TextView(this).apply {
            text = "-- Hz"
            textSize = 48f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusCard.addView(currentRate, topMargin(dp(2)))
        serviceStatus = TextView(this).apply {
            textSize = 12f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(AppTheme.elevated, 12f, AppTheme.border)
        }
        statusCard.addView(serviceStatus, topMargin(dp(8)))
        selectionStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(AppTheme.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        statusCard.addView(selectionStatus, topMargin(dp(12)))
        content.addView(statusCard, topMargin(dp(22)))

        content.addView(TextView(this).apply {
            text = "SELECT REFRESH RATE"
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(AppTheme.textMuted)
            typeface = Typeface.DEFAULT_BOLD
        }, topMargin(dp(24)))

        val selectedRate = Rms.selectedRate(this)
        val choices = RATE_CHOICES.map { rate ->
            createRateButton(rate, selectedRate == rate).apply {
                setOnClickListener { applyRate(rate) }
            }
        }
        rateButtons = choices
        for (index in choices.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(
                choices[index],
                LinearLayout.LayoutParams(0, dp(58), 1f).apply { rightMargin = dp(6) }
            )
            row.addView(
                choices[index + 1],
                LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(6) }
            )
            content.addView(row, topMargin(dp(10)))
        }

        val reset = TextView(this).apply {
            text = "Reset to system default"
            textSize = 14f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(AppTheme.elevated, 16f, AppTheme.border)
            isClickable = true
            isFocusable = true
            setOnClickListener { resetRate() }
        }
        content.addView(reset, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply { topMargin = dp(14) })

        content.addView(TextView(this).apply {
            text = "Only Vivo/iQOO devices with the compatible RMS service are supported. Add Max Hz from the Quick Settings tile editor. A rate is requested only when you choose it and is not reapplied after reboot."
            textSize = 11f
            setTextColor(AppTheme.textMuted)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, topMargin(dp(18)))

        setContentView(scroll)
        scroll.post { SpoofUi.applyContentInsets(this, content, 18, 12, 18, 28) }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun createRateButton(rate: Int, selected: Boolean): TextView =
        TextView(this).apply {
            text = "$rate Hz"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else AppTheme.textPrimary)
            background = if (selected) AppTheme.gradient(this@RefreshRateActivity, 16f)
            else rounded(AppTheme.card, 16f, AppTheme.border)
            isClickable = true
            isFocusable = true
        }

    private fun applyRate(rate: Int) {
        if (Rms.vote(this, rate)) {
            Rms.saveSelectedRate(this, rate)
            selectionStatus.text = "Last requested: $rate Hz"
            Toast.makeText(this, "Submitted $rate Hz request", Toast.LENGTH_SHORT).show()
        } else {
            selectionStatus.text = "Could not submit the request. Check RMS service availability."
        }
        updateRateButtons(rate)
        refreshStatus()
    }

    private fun resetRate() {
        if (Rms.reset()) {
            Rms.saveSelectedRate(this, 0)
            selectionStatus.text = "System policy can select the refresh rate."
        } else {
            selectionStatus.text = "Could not release the rate request. Check RMS service availability."
        }
        updateRateButtons(0)
        refreshStatus()
    }

    private fun refreshStatus() {
        val available = Rms.isAvailable()
        serviceStatus.text = if (available) {
            "●  Vivo RMS service connected"
        } else {
            "●  Vivo RMS service unavailable on this firmware"
        }
        serviceStatus.setTextColor(if (available) Color.rgb(52, 211, 153) else AppTheme.textMuted)
        val rate = SpoofUi.currentDisplayRefreshRate(this)
        currentRate.text = if (rate > 0f) String.format(Locale.US, "%.1f Hz", rate) else "-- Hz"
        val selected = Rms.selectedRate(this)
        if (!::selectionStatus.isInitialized || selectionStatus.text.isNullOrBlank()) {
            selectionStatus.text = if (selected > 0) {
                "Last requested: $selected Hz"
            } else {
                "No rate request saved. System default is in control."
            }
        }
        if (::rateButtons.isInitialized) updateRateButtons(selected)
    }

    private fun updateRateButtons(selectedRate: Int) {
        rateButtons.forEachIndexed { index, button ->
            val selected = RATE_CHOICES[index] == selectedRate
            button.setTextColor(if (selected) Color.WHITE else AppTheme.textPrimary)
            button.background = if (selected) AppTheme.gradient(this, 16f)
            else rounded(AppTheme.card, 16f, AppTheme.border)
        }
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun topMargin(margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = margin }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val RATE_CHOICES = listOf(60, 90, 120, 144)
        const val REFRESH_INTERVAL_MS = 500L
    }
}
