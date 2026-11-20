package com.openwearables.health.sdk

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertSame

class ProviderSelectionTest {

    private fun provider(available: Boolean): HealthDataProvider =
        mockk { every { isAvailable() } returns available }

    @Test
    fun `no stored provider and no Samsung Health picks Health Connect`() {
        val samsung = provider(available = false)
        val hc = provider(available = true)
        assertSame(hc, selectProvider(null, { samsung }, { hc }))
    }

    @Test
    fun `no stored provider prefers Samsung Health when available`() {
        val samsung = provider(available = true)
        val hc = provider(available = true)
        assertSame(samsung, selectProvider(null, { samsung }, { hc }))
    }

    @Test
    fun `neither available falls back to Samsung Health`() {
        val samsung = provider(available = false)
        val hc = provider(available = false)
        assertSame(samsung, selectProvider(null, { samsung }, { hc }))
    }

    @Test
    fun `stored provider wins over availability`() {
        val samsung = provider(available = true)
        val hc = provider(available = false)
        assertSame(hc, selectProvider(ProviderIds.GOOGLE, { samsung }, { hc }))
        assertSame(samsung, selectProvider(ProviderIds.SAMSUNG, { samsung }, { hc }))
    }
}
