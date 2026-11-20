package com.openwearables.health.sdk

/**
 * Picks the health store to read. The SDK and [HealthSyncWorker] both use this,
 * so background sync reads the same store as the app.
 *
 * A stored provider wins. Without one: Samsung Health when it is available,
 * otherwise Health Connect, otherwise Samsung Health (reports "not available").
 */
internal fun selectProvider(
    storedId: String?,
    samsung: () -> HealthDataProvider,
    healthConnect: () -> HealthDataProvider,
): HealthDataProvider = when (storedId) {
    ProviderIds.GOOGLE -> healthConnect()
    ProviderIds.SAMSUNG -> samsung()
    else -> {
        val s = samsung()
        if (s.isAvailable()) {
            s
        } else {
            val hc = healthConnect()
            if (hc.isAvailable()) hc else s
        }
    }
}
