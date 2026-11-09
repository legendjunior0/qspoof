package com.antutu.ABenchMark

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

class DonatedPageActivity : Activity() {

    private val BG_DARK = AppTheme.background
    private val CARD_BG = AppTheme.card
    private val FIELD_BG = AppTheme.field
    private val ACCENT_CYAN = AppTheme.accentRed
    private val ACCENT_INDIGO = AppTheme.accentOrange
    private val TEXT_PRIMARY = AppTheme.textPrimary
    private val TEXT_SECONDARY = AppTheme.textSecondary
    private val BORDER_GLOW = AppTheme.border
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpoofUi.applyFullScreen(this)
        window.navigationBarColor = BG_DARK

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_DARK)
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }

        // Navigation Bar
        root.addView(createHeaderBar())
        root.addView(createSpace(36))

        // Hero Icon Badge
        val heartBadge = TextView(this).apply {
            text = "♥"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = AppTheme.gradient(this@DonatedPageActivity, 28f)
        }
        root.addView(heartBadge, LinearLayout.LayoutParams(dp(76), dp(76)))

        // Title
        val title = TextView(this).apply {
            text = "Support QSpoof"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        // Subtitle
        val message = TextView(this).apply {
            text = "Your support helps keep QSpoof in active development."
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1.15f)
        }
        root.addView(
            message,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        // Credits Tag
        val credits = TextView(this).apply {
            text = "Developed by ##UNKNOWN"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedDrawable(
                AppTheme.accentWash,
                12f,
                BORDER_GLOW,
                1f
            )
        }
        root.addView(
            credits,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        root.addView(createSpace(24))

        val checkingStatus = TextView(this).apply {
            text = "Checking premium status..."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
        }
        root.addView(
            checkingStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        )

        // Payment Verification Card
        val paymentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(CARD_BG, 20f, BORDER_GLOW, 1.2f)
        }

        val paymentTitle = TextView(this).apply {
            text = "Link Premium with a Reference ID"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }
        paymentCard.addView(paymentTitle)

        val paymentSubtitle = TextView(this).apply {
            text = "Paste the reference ID given by the administrator, then tap Link Premium. No account sign-in or name is needed."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
        }
        paymentCard.addView(
            paymentSubtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        val premiumStatus = TextView(this).apply {
            text = "Your reference will be reviewed by an administrator."
            textSize = 11f
            setTextColor(ACCENT_INDIGO)
        }
        paymentCard.addView(
            premiumStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        val displayNameInput = EditText(this).apply {
            hint = "Display name (optional)"
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(AppTheme.textMuted)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setPadding(dp(14), 0, dp(14), 0)
            includeFontPadding = false
            background = roundedDrawable(FIELD_BG, 12f, BORDER_GLOW, 1f)
        }
        paymentCard.addView(
            displayNameInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(14) }
        )

        val partialNameCheck = CheckBox(this).apply {
            text = "Show partially, e.g. R****D"
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            buttonTintList = ColorStateList.valueOf(ACCENT_CYAN)
            setPadding(0, dp(4), 0, dp(4))
        }
        paymentCard.addView(
            partialNameCheck,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )
        val namePrivacyHint = TextView(this).apply {
            text = "Your name appears in the donor list only after your payment is approved."
            textSize = 10f
            setTextColor(Color.rgb(112, 124, 142))
        }
        paymentCard.addView(
            namePrivacyHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val paymentInput = EditText(this).apply {
            hint = "Paste your reference ID here"
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(AppTheme.textMuted)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14), 0, dp(14), 0)
            includeFontPadding = false
            background = roundedDrawable(FIELD_BG, 12f, BORDER_GLOW, 1f)
        }

        inputRow.addView(
            paymentInput,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val premiumCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(CARD_BG, 20f, BORDER_GLOW, 1f)
            visibility = View.GONE
        }
        val premiumActionSlot = FrameLayout(this).apply { visibility = View.GONE }

        val submitButton = Button(this).apply {
            text = "LINK PREMIUM"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            isAllCaps = true
            background = AppTheme.gradient(this@DonatedPageActivity, 12f)
            stateListAnimator = null
            minHeight = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            attachTouchScaleAnimation(this)

            setOnClickListener { button ->
                val paymentId = paymentInput.text.toString().trim()
                if (!paymentId.matches(Regex("[A-Za-z0-9-]{6,80}"))) {
                    Toast.makeText(
                        this@DonatedPageActivity,
                        "Please enter a valid payment reference ID",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    button.isEnabled = false
                    premiumStatus.text = "Submitting transaction reference..."
                    val displayName = displayNameInput.text.toString().trim()
                    if (displayName.length > 40) {
                        Toast.makeText(
                            this@DonatedPageActivity,
                            "Display name must be 40 characters or fewer",
                            Toast.LENGTH_SHORT
                        ).show()
                        button.isEnabled = true
                        return@setOnClickListener
                    }
                    PremiumAccess.submitTransaction(
                        this@DonatedPageActivity,
                        paymentId,
                        displayName.takeIf(String::isNotEmpty),
                        partialNameCheck.isChecked
                    ) { result ->
                        if (isFinishing) return@submitTransaction
                        button.isEnabled = true
                        premiumStatus.text = result.message
                        if (result.submitted) {
                            paymentInput.text.clear()
                            displayNameInput.text.clear()
                            partialNameCheck.isChecked = false
                            if (PremiumAccess.isPremium(this@DonatedPageActivity)) {
                                paymentCard.visibility = View.GONE
                                premiumCard.visibility = View.VISIBLE
                                premiumActionSlot.visibility = View.VISIBLE
                            }
                        } else {
                            Toast.makeText(
                                this@DonatedPageActivity,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        inputRow.addView(
            submitButton,
            LinearLayout.LayoutParams(dp(122), dp(48)).apply { leftMargin = dp(10) }
        )

        paymentCard.addView(
            inputRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        val privacyNotice = TextView(this).apply {
            text = "Privacy: the server stores a hashed Android ID and your latest public IP for access and device analytics. Each new IP replaces the previous one; IP history is not kept. Transaction references are reviewed manually."
            textSize = 10f
            setTextColor(Color.rgb(112, 124, 142))
            setPadding(dp(6), dp(10), dp(6), 0)
            setLineSpacing(dp(2).toFloat(), 1.1f)
        }
        paymentCard.addView(
            privacyNotice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        paymentCard.visibility = View.GONE
        root.addView(
            paymentCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val premiumTitle = TextView(this).apply {
            text = "Premium access is active"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }
        premiumCard.addView(premiumTitle)
        val premiumDetails = TextView(this).apply {
            text = "Thank you for supporting QSpoof. Your contribution helps keep the app ad-free and continuously improving."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(3).toFloat(), 1.12f)
        }
        premiumCard.addView(
            premiumDetails,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(
            premiumCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            premiumActionSlot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        val delinkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedDrawable(CARD_BG, 16f, BORDER_GLOW, 1f)
        }
        val delinkButton = Button(this).apply {
            text = "DELINK THIS DEVICE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = AppTheme.gradient(this@DonatedPageActivity, 12f)
            stateListAnimator = null
            setOnClickListener {
                AlertDialog.Builder(this@DonatedPageActivity)
                    .setTitle("Turn off premium on this device?")
                    .setMessage("You can turn it back on by entering the same reference ID again on this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delink") { _, _ ->
                        isEnabled = false
                        PremiumAccess.delinkPremium(this@DonatedPageActivity) { success, error ->
                            if (isFinishing) return@delinkPremium
                            isEnabled = true
                            if (success) {
                                premiumCard.visibility = View.GONE
                                paymentCard.visibility = View.VISIBLE
                                premiumStatus.text = "Premium is off. Enter your registered reference ID to link it again."
                                premiumActionSlot.visibility = View.GONE
                                Toast.makeText(this@DonatedPageActivity, "Premium delinked from this device", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@DonatedPageActivity, error ?: "Could not delink premium", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.show()
            }
        }
        delinkCard.addView(
            delinkButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )
        premiumActionSlot.addView(
            delinkCard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val donorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(CARD_BG, 20f, BORDER_GLOW, 1f)
        }
        val donorTitle = TextView(this).apply {
            text = "Community donors"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }
        donorCard.addView(donorTitle)
        val donorSubtitle = TextView(this).apply {
            text = "Thank you to everyone who supports QSpoof."
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }
        donorCard.addView(
            donorSubtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        val donorRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val donorScroll = ScrollView(this).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            addView(
                donorRows,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        donorCard.addView(
            donorScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
            ).apply { topMargin = dp(12) }
        )
        root.addView(
            donorCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )
        val donorStatus = TextView(this).apply {
            text = "Loading donor names..."
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        donorRows.addView(donorStatus)

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)
        scroll.post {
            SpoofUi.applyContentInsets(this, root, 18, 12, 18, 32)
        }
        SpoofUi.registerPredictiveBack(this, scroll)
        animateEntrance(root)

        checkingStatus.visibility = View.GONE
        val premium = PremiumAccess.isPremium(this)
        if (premium) {
            premiumCard.visibility = View.VISIBLE
            premiumActionSlot.visibility = View.VISIBLE
        } else {
            paymentCard.visibility = View.VISIBLE
            premiumActionSlot.visibility = View.GONE
        }
        PremiumAccess.loadDonors(this) { donors, error ->
            if (isFinishing) return@loadDonors
            donorRows.removeAllViews()
            if (donors == null) {
                donorStatus.text = error ?: "Could not load donor names."
                donorRows.addView(donorStatus)
                return@loadDonors
            }
            if (donors.isEmpty()) {
                donorStatus.text = "Be the first to appear here."
                donorRows.addView(donorStatus)
                return@loadDonors
            }
            donors.sortedBy { it.reviewedAt }.forEach { donor ->
                val donorRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundedDrawable(FIELD_BG, 10f)
                }
                val donorName = TextView(this).apply {
                    text = donor.displayName
                    textSize = 13f
                    setTextColor(TEXT_PRIMARY)
                }
                donorRow.addView(
                    donorName,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                donorRows.addView(
                    donorRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                )
            }
        }
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
                .setStartDelay((index * 40L).coerceAtMost(280L))
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
            background = roundedDrawable(FIELD_BG, 12f, BORDER_GLOW, 1f)
            attachTouchScaleAnimation(this)
            setOnClickListener {
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        row.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "Supporter Access"
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
