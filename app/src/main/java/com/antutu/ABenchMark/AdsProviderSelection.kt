package com.antutu.ABenchMark

internal enum class AdsProvider {
    INMOBI,
    START_IO
}

internal object AdsProviderSelection {
    fun select(
        configuredProvider: String?,
        inmobiAccountId: String?,
        inmobiPlacementId: String?,
        startIoAppId: String?
    ): AdsProvider? {
        val hasInMobi = !inmobiAccountId.isNullOrBlank() &&
            !inmobiPlacementId.isNullOrBlank()
        val hasStartIo = !startIoAppId.isNullOrBlank()
        return when (configuredProvider) {
            "inmobi" -> when {
                hasInMobi -> AdsProvider.INMOBI
                hasStartIo -> AdsProvider.START_IO
                else -> null
            }
            "startio" -> when {
                hasStartIo -> AdsProvider.START_IO
                hasInMobi -> AdsProvider.INMOBI
                else -> null
            }
            else -> when {
                hasInMobi -> AdsProvider.INMOBI
                hasStartIo -> AdsProvider.START_IO
                else -> null
            }
        }
    }
}
