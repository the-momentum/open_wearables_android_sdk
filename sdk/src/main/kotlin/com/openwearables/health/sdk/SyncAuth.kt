package com.openwearables.health.sdk

/**
 * Shared policy for auth-adjacent sync decisions. Kept as pure functions so
 * the same rules can be unit-tested without Android runtime.
 */
internal object SyncAuth {
    /**
     * `DELETE {apiBaseUrl}/users/{userId}/connections/{provider}` (#24).
     * [providerId] is `samsung` or `google`.
     */
    fun disconnectUrl(apiBaseUrl: String, userId: String, providerId: String): String {
        val base = apiBaseUrl.trimEnd('/')
        return "$base/users/$userId/connections/$providerId"
    }
}
