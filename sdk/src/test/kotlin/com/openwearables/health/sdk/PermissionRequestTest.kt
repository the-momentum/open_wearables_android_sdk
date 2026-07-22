package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionRequestTest {
    @Test
    fun `returns true when requested permission is newly granted`() {
        assertTrue(
            permissionRequestGranted(
                requested = setOf("history"),
                alreadyGranted = emptySet(),
                newlyGranted = setOf("history")
            )
        )
    }

    @Test
    fun `returns false when requested permission is denied`() {
        assertFalse(
            permissionRequestGranted(
                requested = setOf("history"),
                alreadyGranted = emptySet(),
                newlyGranted = emptySet()
            )
        )
    }

    @Test
    fun `preserves permissions granted before the prompt`() {
        assertTrue(
            permissionRequestGranted(
                requested = setOf("steps", "history"),
                alreadyGranted = setOf("steps"),
                newlyGranted = setOf("history")
            )
        )
    }
}
