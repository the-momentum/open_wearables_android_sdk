package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeReadResultTest {

    @Test
    fun testExpiredFactory() {
        val expired = ChangeReadResult.expired()
        assertTrue(expired.tokenExpired)
        assertTrue(expired.data.isEmpty)
        assertFalse(expired.hasMore)
    }

    @Test
    fun testUnavailableFactory() {
        val empty = ChangeReadResult.unavailable()
        assertFalse(empty.tokenExpired)
        assertTrue(empty.data.isEmpty)
    }

    @Test
    fun catchupWindowMatchesHealthConnectTokenLifetime() {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(thirtyDaysMs, SyncDefaults.CHANGE_CATCHUP_MS)
    }
}
