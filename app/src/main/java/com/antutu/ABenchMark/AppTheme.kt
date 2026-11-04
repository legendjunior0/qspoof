package com.antutu.ABenchMark

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared dark charcoal and warm red-orange palette for the app screens. */
internal object AppTheme {
    val background = Color.rgb(8, 9, 11)
    val card = Color.rgb(20, 22, 25)
    val elevated = Color.rgb(29, 31, 35)
    val field = Color.rgb(25, 27, 31)
    val accentRed = Color.rgb(255, 58, 48)
    val accentOrange = Color.rgb(255, 105, 0)
    val accentWash = Color.rgb(53, 24, 20)
    val textPrimary = Color.rgb(245, 246, 248)
    val textSecondary = Color.rgb(180, 184, 192)
    val textMuted = Color.rgb(133, 138, 148)
    val border = Color.rgb(48, 51, 57)
    val heart = Color.rgb(255, 75, 66)

    fun gradient(
        context: Context,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(accentRed, accentOrange)
        ).apply {
            cornerRadius = radiusDp * density
            if (strokeColor != null && strokeWidthDp > 0f) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    /** Matches the small Q brand tile used in the admin header. */
    fun brandMark(context: Context): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(255, 137, 71), Color.rgb(196, 73, 42))
        ).apply {
            cornerRadius = 15f * density
        }
    }
}
