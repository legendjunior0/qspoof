package com.antutu.ABenchMark

import org.junit.Assert.assertEquals
import org.junit.Test

class AdsProviderSelectionTest {
    @Test
    fun usesConfiguredProviderWhenItsIdsArePresent() {
        assertEquals(
            AdsProvider.START_IO,
            AdsProviderSelection.select("startio", "account", "123", "publisher")
        )
        assertEquals(
            AdsProvider.INMOBI,
            AdsProviderSelection.select("inmobi", "account", "123", "publisher")
        )
    }

    @Test
    fun fallsBackOnlyWhenSelectedProviderIsMissingItsIds() {
        assertEquals(
            AdsProvider.INMOBI,
            AdsProviderSelection.select("startio", "account", "123", "")
        )
        assertEquals(
            AdsProvider.START_IO,
            AdsProviderSelection.select("inmobi", "", "123", "publisher")
        )
        assertEquals(
            null,
            AdsProviderSelection.select("startio", "", "", "")
        )
    }
}
