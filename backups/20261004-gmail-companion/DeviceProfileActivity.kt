package com.antutu.ABenchMark

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DeviceProfileActivity : Activity() {

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpoofUi.applyFullScreen(this)

        val root = ScrollView(this).apply {
            setBackgroundColor(AppTheme.background)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        root.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹  Back"
            textSize = 14f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(9), dp(16), dp(9))
            background = roundedDrawable(AppTheme.field, 12f, AppTheme.border, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        toolbar.addView(back)
        toolbar.addView(TextView(this).apply {
            text = "YOUR PROFILE"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(AppTheme.accentRed)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(toolbar)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(28), dp(22), dp(28))
            background = roundedDrawable(AppTheme.card, 22f, AppTheme.border, 1f)
        }
        val logo = TextView(this).apply {
            text = "Q"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = AppTheme.gradient(this@DeviceProfileActivity, 20f)
        }
        hero.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)))
        hero.addView(TextView(this).apply {
            text = "Your Profile"
            textSize = 23f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        hero.addView(TextView(this).apply {
            text = "Your linked payment reference and profile details"
            textSize = 12f
            setTextColor(AppTheme.textSecondary)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedDrawable(AppTheme.card, 20f, AppTheme.border, 1f)
        }
        val referenceTitle = TextView(this).apply {
            text = "LINKED REFERENCE"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(AppTheme.textMuted)
            typeface = Typeface.DEFAULT_BOLD
        }
        profileCard.addView(referenceTitle)
        val referenceValue = TextView(this).apply {
            text = "Loading…"
            textSize = 16f
            setTextColor(AppTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        profileCard.addView(referenceValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(18)
        })

        fun addFieldLabel(label: String) {
            profileCard.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(AppTheme.textSecondary)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(5)
            })
        }
        fun newInput(hintText: String, inputType: Int): EditText = EditText(this).apply {
            hint = hintText
            setTextColor(AppTheme.textPrimary)
            setHintTextColor(AppTheme.textMuted)
            this.inputType = inputType
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(AppTheme.field, 12f, AppTheme.border, 1f)
        }

        addFieldLabel("NAME")
        val nameInput = newInput("Your name", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        profileCard.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addFieldLabel("EMAIL")
        val emailInput = newInput("Email address", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        profileCard.addView(emailInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val emailStatus = TextView(this).apply {
            text = "Verify your email to use it for premium access."
            textSize = 11f
            setTextColor(AppTheme.textMuted)
        }
        profileCard.addView(emailStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        val sendCode = Button(this).apply {
            text = "Send verification code"
            isAllCaps = false
            setTextColor(AppTheme.textPrimary)
            background = roundedDrawable(AppTheme.field, 12f, AppTheme.border, 1f)
        }
        profileCard.addView(sendCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })
        val codeInput = newInput("Six-digit code", android.text.InputType.TYPE_CLASS_NUMBER).apply {
            visibility = View.GONE
        }
        profileCard.addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        val verifyCode = Button(this).apply {
            text = "Verify email"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = AppTheme.gradient(this@DeviceProfileActivity, 12f)
            visibility = View.GONE
        }
        profileCard.addView(verifyCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })
        val changeNote = TextView(this).apply {
            text = "Profile changes are limited to once every 24 hours."
            textSize = 11f
            setTextColor(AppTheme.textMuted)
        }
        profileCard.addView(changeNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(AppTheme.textSecondary)
        }
        profileCard.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        val save = Button(this).apply {
            text = "Save profile"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = AppTheme.gradient(this@DeviceProfileActivity, 14f)
        }
        profileCard.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(16)
        })
        content.addView(profileCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        var verifiedEmail = ""
        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val candidate = s?.toString()?.trim()?.lowercase().orEmpty()
                if (candidate.isNotEmpty() && candidate != verifiedEmail.lowercase()) {
                    emailStatus.text = "Verify this email before linking it to the device."
                }
            }
        })
        sendCode.setOnClickListener {
            val candidateEmail = emailInput.text.toString().trim()
            if (!candidateEmail.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
                emailStatus.text = "Enter a valid email address first."
                return@setOnClickListener
            }
            sendCode.isEnabled = false
            emailStatus.text = "Sending code…"
            PremiumAccess.sendProfileEmailCode(this, candidateEmail) { error ->
                if (isFinishing) return@sendProfileEmailCode
                sendCode.isEnabled = true
                if (error != null) {
                    emailStatus.text = profileErrorMessage(error)
                } else {
                    codeInput.visibility = View.VISIBLE
                    verifyCode.visibility = View.VISIBLE
                    emailStatus.text = "Code sent. It expires in 10 minutes."
                }
            }
        }
        verifyCode.setOnClickListener {
            verifyCode.isEnabled = false
            emailStatus.text = "Verifying email…"
            PremiumAccess.verifyProfileEmailCode(
                this,
                emailInput.text.toString().trim(),
                codeInput.text.toString()
            ) { verified, error ->
                if (isFinishing) return@verifyProfileEmailCode
                verifyCode.isEnabled = true
                if (!verified) {
                    emailStatus.text = error?.let(::profileErrorMessage) ?: "Email verification failed."
                } else {
                    verifiedEmail = emailInput.text.toString().trim().lowercase()
                    emailStatus.text = "Email verified. Save the profile to link it to this device."
                }
            }
        }
        save.setOnClickListener {
            val candidateEmail = emailInput.text.toString().trim()
            if (candidateEmail.isNotEmpty() && candidateEmail.lowercase() != verifiedEmail.lowercase()) {
                emailStatus.text = "Verify this email address before saving it."
                return@setOnClickListener
            }
            save.isEnabled = false
            status.text = "Saving profile…"
            PremiumAccess.saveDeviceProfile(this, nameInput.text.toString(), emailInput.text.toString()) { saved, error ->
                if (isFinishing) return@saveDeviceProfile
                save.isEnabled = true
                if (error != null) {
                    status.text = profileErrorMessage(error)
                } else {
                    nameInput.setText(saved?.name.orEmpty())
                    emailInput.setText(saved?.email.orEmpty())
                    emailStatus.text = if (saved?.emailVerified == true) "Email verified and linked to this device." else "Email address not verified."
                    verifiedEmail = if (saved?.emailVerified == true) saved.email.lowercase() else ""
                    status.text = "Profile saved."
                }
            }
        }

        setContentView(root)
        SpoofUi.applyContentInsets(this, content, 18, 14, 18, 28)
        SpoofUi.registerPredictiveBack(this, root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(220L).start()

        PremiumAccess.loadDeviceProfile(this) { profile, error ->
            if (isFinishing) return@loadDeviceProfile
            if (error != null) {
                referenceValue.text = "Unavailable"
                status.text = profileErrorMessage(error)
            } else {
                referenceValue.text = profile?.referenceId?.takeIf(String::isNotBlank) ?: "Not linked"
                nameInput.setText(profile?.name.orEmpty())
                emailInput.setText(profile?.pendingEmail?.takeIf(String::isNotBlank) ?: profile?.email.orEmpty())
                verifiedEmail = when {
                    profile?.emailVerified == true -> profile.email.lowercase()
                    profile?.pendingEmail?.isNotBlank() == true -> profile.pendingEmail.lowercase()
                    else -> ""
                }
                emailStatus.text = when {
                    profile?.emailVerified == true -> "Email verified."
                    profile?.pendingEmail?.isNotBlank() == true -> "Email verified. Save the profile to link it to this device."
                    profile?.email?.isNotBlank() == true -> "Saved email is not verified. Verify it to use email-based premium access."
                    else -> "Verify your email to use it for premium access."
                }
                status.text = profile?.lockedUntil?.takeIf(String::isNotBlank)?.let { "Changes locked until $it" }.orEmpty()
            }
        }
    }

    private fun roundedDrawable(
        fill: Int,
        radiusDp: Float,
        stroke: Int,
        strokeWidthDp: Float
    ) = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(strokeWidthDp.toInt().coerceAtLeast(1)), stroke)
    }

    private fun profileErrorMessage(error: String): String = when {
        error.contains("unknown action", ignoreCase = true) ||
            error.contains("invalid device identifier", ignoreCase = true) ->
            "Profile service needs its latest update. Please retry after the service is updated."
        else -> error
    }
}
