package com.openwearables.health.sdk

/**
 * Sanity bounds for provider timestamps.
 *
 * Motivated by the-momentum/open_wearables_android_sdk#25: a provider can hand the SDK
 * records whose timestamps are far in the future (observed: Samsung Health heart-rate
 * samples dated 2033-2105). Without a bound, those timestamps flow into
 * [ProviderReadResult.maxTimestamp] and are persisted as per-type sync anchors,
 * silently disabling future ingestion of that data type.
 *
 * This class is deliberately dependency-free (no Android imports) so it is unit-testable
 * on the JVM.
 */
object TimestampSanity {

    /** Default tolerance for clock skew between device and reality: 5 minutes. */
    const val DEFAULT_FUTURE_SKEW_MS: Long = 5 * 60 * 1000L

    /**
     * A timestamp is implausible if it sits further in the future than [futureSkewMs]
     * from [nowMs], or if it is negative (before 1970-01-01).
     */
    fun isImplausible(timestampMs: Long, nowMs: Long = System.currentTimeMillis(), futureSkewMs: Long = DEFAULT_FUTURE_SKEW_MS): Boolean {
        if (futureSkewMs < 0) throw IllegalArgumentException("futureSkewMs must be >= 0")
        return timestampMs < 0 || timestampMs > nowMs + futureSkewMs
    }

    /**
     * Splits raw Samsung records into (plausible, rejected) using both [HealthDataRecord.startTime]
     * and [HealthDataRecord.endTime] (when present). A record with either bound implausible is
     * rejected wholesale — a malformed bound must not leak into payloads, min/max cursors or anchors.
     */
    fun partition(
        records: List<HealthDataRecord>,
        nowMs: Long = System.currentTimeMillis(),
        futureSkewMs: Long = DEFAULT_FUTURE_SKEW_MS
    ): Pair<List<HealthDataRecord>, List<HealthDataRecord>> {
        val plausible = ArrayList<HealthDataRecord>(records.size)
        val rejected = ArrayList<HealthDataRecord>()
        for (record in records) {
            val implausible = isImplausible(record.startTime, nowMs, futureSkewMs) ||
                (record.endTime != null && isImplausible(record.endTime, nowMs, futureSkewMs))
            if (implausible) rejected.add(record) else plausible.add(record)
        }
        return plausible to rejected
    }
}
