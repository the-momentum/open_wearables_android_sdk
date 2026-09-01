package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimestampSanityTest {

    @Test
    fun testIsImplausibleBoundaries() {
        val nowMs = 1_790_000_000_000L
        val futureSkewMs = 300_000L

        assertFalse(TimestampSanity.isImplausible(nowMs + 300_000L, nowMs, futureSkewMs))
        assertTrue(TimestampSanity.isImplausible(nowMs + 300_001L, nowMs, futureSkewMs))
        assertFalse(TimestampSanity.isImplausible(0L, nowMs, futureSkewMs))
        assertTrue(TimestampSanity.isImplausible(-1L, nowMs, futureSkewMs))
        assertFalse(TimestampSanity.isImplausible(nowMs - 1L, nowMs, futureSkewMs))
    }

    @Test
    fun testNegativeFutureSkewThrows() {
        assertFailsWith<IllegalArgumentException> {
            TimestampSanity.isImplausible(timestampMs = 1_000L, nowMs = 1_000L, futureSkewMs = -1L)
        }
    }

    @Test
    fun testRealIncidentRegressionIssue25() {
        val nowMs = 1_790_000_000_000L
        val futureSkewMs = 300_000L

        val incidentPairs = listOf(
            118 to 1996442727L,
            126 to 2130660455L,
            127 to 2147437671L,
            128 to 2164214887L,
            141 to 2382318695L,
            142 to 2399095911L,
            143 to 2415873127L,
            0 to 4283197390L
        )

        val device = DeviceInfo(
            deviceId = null,
            manufacturer = "Samsung",
            model = "SM-R390",
            name = "Fit3",
            brand = "samsung",
            product = "SM-R390",
            osType = "Android",
            osVersion = "16",
            sdkVersion = 36,
            deviceType = "WEARABLE",
            isSourceDevice = false
        )

        fun createRecord(uid: String, startTimeMs: Long, bpm: Double, endTimeMs: Long? = null): HealthDataRecord {
            return HealthDataRecord(
                uid = uid,
                dataType = "HEART_RATE",
                startTime = startTimeMs,
                endTime = endTimeMs,
                zoneOffset = null,
                dataSource = RawDataSource(null, null),
                device = device,
                fields = mapOf("HEART_RATE" to bpm)
            )
        }

        val r0 = createRecord("r0", incidentPairs[0].second * 1000L, incidentPairs[0].first.toDouble())
        val r1 = createRecord("r1", incidentPairs[1].second * 1000L, incidentPairs[1].first.toDouble())
        val p1 = createRecord("p1", nowMs - 60_000L, 70.0)
        val r2 = createRecord("r2", incidentPairs[2].second * 1000L, incidentPairs[2].first.toDouble())
        val r3 = createRecord("r3", incidentPairs[3].second * 1000L, incidentPairs[3].first.toDouble())
        val p2 = createRecord("p2", nowMs - 1_000L, 72.0)
        val r4 = createRecord("r4", incidentPairs[4].second * 1000L, incidentPairs[4].first.toDouble())
        val r5 = createRecord("r5", incidentPairs[5].second * 1000L, incidentPairs[5].first.toDouble())
        val p3 = createRecord("p3", nowMs + 240_000L, 75.0)
        val r6 = createRecord("r6", incidentPairs[6].second * 1000L, incidentPairs[6].first.toDouble())
        val r7 = createRecord("r7", incidentPairs[7].second * 1000L, incidentPairs[7].first.toDouble())

        val interleaved = listOf(r0, r1, p1, r2, r3, p2, r4, r5, p3, r6, r7)

        val (plausible, rejected) = TimestampSanity.partition(
            records = interleaved,
            nowMs = nowMs,
            futureSkewMs = futureSkewMs
        )

        assertEquals(listOf(p1, p2, p3), plausible)
        assertEquals(listOf(r0, r1, r2, r3, r4, r5, r6, r7), rejected)
    }

    @Test
    fun testPlausibleStartTimeWithImplausibleEndTimeIsRejected() {
        val nowMs = 1_790_000_000_000L
        val futureSkewMs = 300_000L

        val device = DeviceInfo(null, "Samsung", "SM-R390", "Fit3", "samsung", "SM-R390", "Android", "16", 36, "WEARABLE", false)
        val record = HealthDataRecord(
            uid = "r_implausible_end",
            dataType = "HEART_RATE",
            startTime = nowMs - 1_000L,
            endTime = nowMs + 400_000L,
            zoneOffset = null,
            dataSource = RawDataSource(null, null),
            device = device,
            fields = mapOf("HEART_RATE" to 80.0)
        )

        val (plausible, rejected) = TimestampSanity.partition(
            records = listOf(record),
            nowMs = nowMs,
            futureSkewMs = futureSkewMs
        )

        assertTrue(plausible.isEmpty())
        assertEquals(listOf(record), rejected)
    }

    @Test
    fun testNullEndTimeJudgedByStartTimeAlone() {
        val nowMs = 1_790_000_000_000L
        val futureSkewMs = 300_000L

        val device = DeviceInfo(null, "Samsung", "SM-R390", "Fit3", "samsung", "SM-R390", "Android", "16", 36, "WEARABLE", false)
        val plausibleRecord = HealthDataRecord(
            uid = "r_plausible_null_end",
            dataType = "HEART_RATE",
            startTime = nowMs - 5_000L,
            endTime = null,
            zoneOffset = null,
            dataSource = RawDataSource(null, null),
            device = device,
            fields = mapOf("HEART_RATE" to 75.0)
        )
        val implausibleRecord = HealthDataRecord(
            uid = "r_implausible_null_end",
            dataType = "HEART_RATE",
            startTime = nowMs + 400_000L,
            endTime = null,
            zoneOffset = null,
            dataSource = RawDataSource(null, null),
            device = device,
            fields = mapOf("HEART_RATE" to 75.0)
        )

        val (plausible, rejected) = TimestampSanity.partition(
            records = listOf(plausibleRecord, implausibleRecord),
            nowMs = nowMs,
            futureSkewMs = futureSkewMs
        )

        assertEquals(listOf(plausibleRecord), plausible)
        assertEquals(listOf(implausibleRecord), rejected)
    }
}
