package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncAuthTest {

    @Test
    fun disconnectUrlUsesProviderInPath() {
        assertEquals(
            "https://api.example.com/api/v1/users/u1/connections/google",
            SyncAuth.disconnectUrl("https://api.example.com/api/v1", "u1", "google"),
        )
        assertEquals(
            "https://api.example.com/api/v1/users/u1/connections/samsung",
            SyncAuth.disconnectUrl("https://api.example.com/api/v1/", "u1", "samsung"),
        )
    }

    @Test
    fun backgroundChunkIsSmallerThanForeground() {
        assertEquals(100, SyncDefaults.BACKGROUND_CHUNK_SIZE)
    }
}
