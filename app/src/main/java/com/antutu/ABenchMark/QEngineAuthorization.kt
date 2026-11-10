package com.antutu.ABenchMark

import android.content.Context
import android.util.Log

internal object QEngineAuthorization {
    private const val PREFERENCES = "qengine_authorization"
    private const val EXPIRES_AT_KEY = "expires_at_ms"
    internal const val AUTHORIZATION_DURATION_MILLIS = 60L * 60_000L
    internal const val ACTION_REVERIFY = "com.antutu.ABenchMark.ACTION_QENGINE_REVERIFY"

    fun grant(context: Context, result: PremiumAccess.AppAccessResult): Boolean {
        if (!shouldGrant(result)) return false
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(EXPIRES_AT_KEY, System.currentTimeMillis() + AUTHORIZATION_DURATION_MILLIS)
            .commit()
        if (!saved) Log.e("QEngineAuthorization", "Could not save the QEngine timer")
        return saved
    }

    fun revoke(context: Context) {
        val removed = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        if (!removed) Log.e("QEngineAuthorization", "Could not clear the QEngine timer")
    }

    fun isAuthorized(context: Context): Boolean =
        remainingAuthorizationMillis(context) != null

    fun remainingAuthorizationMillis(context: Context): Long? {
        val expiresAt = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(EXPIRES_AT_KEY, 0L)
        val now = System.currentTimeMillis()
        return (expiresAt - now).takeIf { expiresAt > 0L && isTimerActive(expiresAt, now) }
    }

    internal fun shouldGrant(result: PremiumAccess.AppAccessResult): Boolean =
        result.isFinalCheck &&
            result.serverVerified &&
            result.allowed &&
            !result.updateRequired &&
            result.error == null

    internal fun isTimerActive(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis > nowMillis
}
