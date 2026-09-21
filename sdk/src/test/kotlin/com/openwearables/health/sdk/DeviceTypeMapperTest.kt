package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceTypeMapperTest {

    @Test
    fun testIssue29Watch7ReportedAsMobileBecomesWatch() {
        assertEquals(
            "watch",
            DeviceTypeMapper.fromSamsungDeviceType(
                type = "MOBILE",
                name = "Galaxy Watch7",
                model = "SM-L315F",
            )
        )
    }

    @Test
    fun testMobilePhoneWithoutWearableIdentityStaysPhone() {
        assertEquals(
            "phone",
            DeviceTypeMapper.fromSamsungDeviceType(
                type = "MOBILE",
                name = "Galaxy S24",
                model = "SM-S921B",
            )
        )
    }

    @Test
    fun testExplicitSamsungWatchGroupUnchanged() {
        assertEquals("watch", DeviceTypeMapper.fromSamsungDeviceType("WATCH", "Galaxy Watch7", "SM-L315F"))
    }

    @Test
    fun testFitNameMapsToFitnessBand() {
        assertEquals(
            "fitness_band",
            DeviceTypeMapper.fromSamsungDeviceType("MOBILE", "Galaxy Fit3", "SM-R390")
        )
    }

    @Test
    fun testSmLModelWithoutNameIsWatch() {
        assertEquals("watch", DeviceTypeMapper.fromSamsungDeviceType("MOBILE", null, "SM-L315F"))
    }

    @Test
    fun testSmRModelWithoutNameIsNotGuessedAsWatch() {
        assertEquals("phone", DeviceTypeMapper.fromSamsungDeviceType("MOBILE", null, "SM-R860"))
        assertEquals("phone", DeviceTypeMapper.fromSamsungDeviceType("MOBILE", null, "SM-R530"))
    }

    @Test
    fun testUnknownGroupInfersFromName() {
        assertEquals("watch", DeviceTypeMapper.fromSamsungDeviceType("UNKNOWN", "Galaxy Watch7", null))
        assertNull(DeviceTypeMapper.fromSamsungDeviceType("UNKNOWN", "Galaxy S24", "SM-S921B"))
    }
}
