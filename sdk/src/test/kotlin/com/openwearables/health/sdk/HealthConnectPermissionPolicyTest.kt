package com.openwearables.health.sdk

import com.openwearables.health.sdk.HealthConnectPermissionPolicy.BACKGROUND
import com.openwearables.health.sdk.HealthConnectPermissionPolicy.HISTORY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthConnectPermissionPolicyTest {

    @Test
    fun requestsBothOptionalPermissionsWhenSupportedAndMissing() {
        assertEquals(
            setOf(BACKGROUND, HISTORY),
            HealthConnectPermissionPolicy.optionalPermissionsToRequest(true, true, emptySet()),
        )
    }

    @Test
    fun skipsOptionalPermissionsThisHealthConnectDoesNotSupport() {
        assertEquals(
            emptySet(),
            HealthConnectPermissionPolicy.optionalPermissionsToRequest(false, false, emptySet()),
        )
        assertEquals(
            setOf(HISTORY),
            HealthConnectPermissionPolicy.optionalPermissionsToRequest(false, true, emptySet()),
        )
    }

    @Test
    fun skipsOptionalPermissionsAlreadyGranted() {
        assertEquals(
            setOf(HISTORY),
            HealthConnectPermissionPolicy.optionalPermissionsToRequest(true, true, setOf(BACKGROUND)),
        )
    }

    @Test
    fun failedOptionalRequestReportsNoGrantsInsteadOfThrowing() = runTest {
        val logs = mutableListOf<String>()
        val granted = HealthConnectPermissionPolicy.requestOptional(setOf(BACKGROUND, HISTORY), { logs += it }) {
            // android.content.ActivityNotFoundException is a stub in local tests; any exception must be absorbed.
            throw IllegalStateException("No Activity found to handle Intent")
        }
        assertEquals(emptySet(), granted)
        assertTrue(logs.single().contains("No Activity found"))
    }

    @Test
    fun optionalRequestReturnsWhatTheScreenGranted() = runTest {
        val granted = HealthConnectPermissionPolicy.requestOptional(setOf(BACKGROUND, HISTORY), {}) { setOf(HISTORY) }
        assertEquals(setOf(HISTORY), granted)
    }

    @Test
    fun nothingMissingSkipsTheOptionalScreen() = runTest {
        var called = false
        HealthConnectPermissionPolicy.requestOptional(emptySet(), {}) { called = true; it }
        assertFalse(called)
    }

    @Test
    fun appScreenFallbackNeedsAnInstantCancelOnAndroid14AndAResolvableScreen() {
        assertTrue(HealthConnectPermissionPolicy.shouldOpenAppScreen(true, 34, true))
        assertFalse(HealthConnectPermissionPolicy.shouldOpenAppScreen(true, 34, false))
        assertFalse(HealthConnectPermissionPolicy.shouldOpenAppScreen(true, 33, true))
        assertFalse(HealthConnectPermissionPolicy.shouldOpenAppScreen(false, 34, true))
    }

    @Test
    fun instantCancelIsAFastEmptyResult() {
        assertTrue(HealthConnectPermissionPolicy.isInstantCancel(emptySet(), 120))
        assertFalse(HealthConnectPermissionPolicy.isInstantCancel(emptySet(), 5_000))
        assertFalse(HealthConnectPermissionPolicy.isInstantCancel(setOf(HISTORY), 120))
    }
}
