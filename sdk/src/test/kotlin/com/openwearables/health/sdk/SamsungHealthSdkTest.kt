package com.openwearables.health.sdk

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamsungHealthSdkTest {

    private val logged = mutableListOf<String>()

    @BeforeTest
    fun mockLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterTest
    fun unmockLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun presentSdkIsAvailableInAnyBuild() {
        assertTrue(SamsungHealthSdk.checkRequested(isDebuggable = true, log = { logged += it }, isPresent = true))
        assertTrue(SamsungHealthSdk.checkRequested(isDebuggable = false, log = { logged += it }, isPresent = true))
        assertTrue(logged.isEmpty())
    }

    @Test
    fun missingSdkThrowsInDebuggableApps() {
        val error = assertFailsWith<IllegalStateException> {
            SamsungHealthSdk.checkRequested(isDebuggable = true, log = { logged += it }, isPresent = false)
        }
        assertEquals(SamsungHealthSdk.MISSING_MESSAGE, error.message)
    }

    @Test
    fun missingSdkLogsAndReportsUnavailableInReleaseBuilds() {
        assertFalse(SamsungHealthSdk.checkRequested(isDebuggable = false, log = { logged += it }, isPresent = false))
        assertEquals(listOf(SamsungHealthSdk.MISSING_MESSAGE), logged)
    }
}
