package com.antutu.ABenchMark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QEngineAuthorizationTest {
    @Test
    fun authorizationDurationIsOneHour() {
        assertEquals(3_600_000L, QEngineAuthorization.AUTHORIZATION_DURATION_MILLIS)
    }

    @Test
    fun timerAllowsQEngineBeforeExpiry() {
        assertTrue(QEngineAuthorization.isTimerActive(1_030_000L, 1_029_999L))
    }

    @Test
    fun timerExpiresAtAndAfterDeadline() {
        assertFalse(QEngineAuthorization.isTimerActive(1_030_000L, 1_030_000L))
        assertFalse(QEngineAuthorization.isTimerActive(1_030_000L, 1_030_001L))
    }

    @Test
    fun rejectsMissingExpiry() {
        assertFalse(QEngineAuthorization.isTimerActive(0L, 1_000L))
    }

    @Test
    fun onlyFinalAllowedAccessWithoutRequiredUpdateGrantsQEngine() {
        assertTrue(QEngineAuthorization.shouldGrant(accessResult(isFinalCheck = true)))
        assertFalse(QEngineAuthorization.shouldGrant(accessResult(isFinalCheck = false)))
        assertFalse(QEngineAuthorization.shouldGrant(accessResult(allowed = false)))
        assertFalse(QEngineAuthorization.shouldGrant(accessResult(updateRequired = true)))
        assertFalse(QEngineAuthorization.shouldGrant(accessResult(error = "offline")))
        assertFalse(QEngineAuthorization.shouldGrant(accessResult(serverVerified = false)))
    }

    private fun accessResult(
        allowed: Boolean = true,
        updateRequired: Boolean = false,
        isFinalCheck: Boolean = true,
        serverVerified: Boolean = true,
        error: String? = null
    ) = PremiumAccess.AppAccessResult(
        allowed = allowed,
        premium = false,
        adsEnabled = true,
        updateRequired = updateRequired,
        serverVerified = serverVerified,
        isFinalCheck = isFinalCheck,
        error = error
    )
}
