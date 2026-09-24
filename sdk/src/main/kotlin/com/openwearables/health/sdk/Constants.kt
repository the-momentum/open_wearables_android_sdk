package com.openwearables.health.sdk

object ProviderIds {
    const val SAMSUNG = "samsung"
    const val GOOGLE = "google"
}

object ProviderDisplayNames {
    const val SAMSUNG_HEALTH = "Samsung Health"
    const val HEALTH_CONNECT = "Health Connect"
}

object SyncDefaults {
    const val SYNC_INTERVAL_MINUTES = 15L
    const val MIN_SYNC_INTERVAL_MINUTES = 15L
    const val CHUNK_SIZE = 2000
    /** Smaller pages in WorkManager / OEM background windows. */
    const val BACKGROUND_CHUNK_SIZE = 100
    /**
     * Wait before the next Health Connect read after a rate-limit.
     * An immediate retry spends the replenishing quota on the same queries.
     */
    const val QUOTA_BACKOFF_MS = 2L * 60L * 1000L
    /**
     * One-time lookback when a change token is first minted or expires (#19).
     * Health Connect tokens last ~30 days; a shorter window would miss writes
     * that happened after the previous token and before remint.
     */
    const val CHANGE_CATCHUP_MS = 30L * 24 * 60 * 60 * 1000
    const val WORK_NAME_PERIODIC = "health_sync_periodic"
    const val WORK_NAME_EXPEDITED = "health_sync_expedited"
    const val SDK_VERSION = "0.12.0"
}

object StorageKeys {
    const val SYNC_PREFS_NAME = "com.openwearables.healthsdk.sync"
    const val KEY_ANCHORS = "anchors"
    const val KEY_CHANGE_TOKENS = "change_tokens"
    const val SYNC_STATE_DIR = "health_sync_state"
    const val SYNC_STATE_FILE = "state.json"
}

object NotificationConfig {
    const val NOTIFICATION_ID = 9001
    const val CHANNEL_ID = "health_sync_channel"
    const val CHANNEL_NAME = "Health Sync"
    const val CHANNEL_DESCRIPTION = "Background health data synchronization"
    const val DEFAULT_TEXT = "Syncing health data..."
}
