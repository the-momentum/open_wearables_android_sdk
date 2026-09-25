package com.openwearables.health.sdk

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Unified health data models following the Unified Health Payload specification.
 * All providers (Apple Health, Samsung Health, Health Connect) convert their
 * native data into these structures before syncing.
 */

// ---------------------------------------------------------------------------
// Source
// ---------------------------------------------------------------------------

data class UnifiedSource(
    val appId: String?,
    val deviceId: String?,
    val deviceName: String?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val deviceType: String?,
    val recordingMethod: String?
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "appId" to appId,
        "deviceId" to deviceId,
        "deviceName" to deviceName,
        "deviceManufacturer" to deviceManufacturer,
        "deviceModel" to deviceModel,
        "deviceType" to deviceType,
        "recordingMethod" to recordingMethod
    )
}

// ---------------------------------------------------------------------------
// Record (10 keys)
// ---------------------------------------------------------------------------

data class UnifiedRecord(
    val id: String,
    val type: String,
    val startDate: String,
    val endDate: String,
    val zoneOffset: String?,
    val source: UnifiedSource,
    val value: Double,
    /** Null for a food parent, which has no nutrient unit. */
    val unit: String?,
    val parentId: String?,
    val metadata: Map<String, Any?>?
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "startDate" to startDate,
        "endDate" to endDate,
        "zoneOffset" to zoneOffset,
        "source" to source.toMap(),
        "value" to value,
        "unit" to unit,
        "parentId" to parentId,
        "metadata" to metadata
    )
}

// ---------------------------------------------------------------------------
// Workout (15 keys)
// ---------------------------------------------------------------------------

data class UnifiedWorkout(
    val id: String,
    val parentId: String?,
    val type: String,
    val startDate: String,
    val endDate: String,
    val zoneOffset: String?,
    val source: UnifiedSource,
    val title: String?,
    val notes: String?,
    val values: List<Map<String, Any>>?,
    val segments: List<Map<String, Any?>>?,
    val laps: List<Map<String, Any?>>?,
    val route: List<Map<String, Any?>>?,
    val samples: List<Map<String, Any?>>?,
    val metadata: Map<String, Any?>?
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "parentId" to parentId,
        "type" to type,
        "startDate" to startDate,
        "endDate" to endDate,
        "zoneOffset" to zoneOffset,
        "source" to source.toMap(),
        "title" to title,
        "notes" to notes,
        "values" to values,
        "segments" to segments,
        "laps" to laps,
        "route" to route,
        "samples" to samples,
        "metadata" to metadata
    )
}

// ---------------------------------------------------------------------------
// Sleep (9 keys)
// ---------------------------------------------------------------------------

data class UnifiedSleep(
    val id: String,
    val parentId: String?,
    val stage: String,
    val startDate: String,
    val endDate: String,
    val zoneOffset: String?,
    val source: UnifiedSource,
    val values: List<Map<String, Any>>?,
    val metadata: Map<String, Any?>?
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "parentId" to parentId,
        "stage" to stage,
        "startDate" to startDate,
        "endDate" to endDate,
        "zoneOffset" to zoneOffset,
        "source" to source.toMap(),
        "values" to values,
        "metadata" to metadata
    )
}

// ---------------------------------------------------------------------------
// Aggregated read result
// ---------------------------------------------------------------------------

data class UnifiedHealthData(
    val records: List<UnifiedRecord> = emptyList(),
    val workouts: List<UnifiedWorkout> = emptyList(),
    val sleep: List<UnifiedSleep> = emptyList()
) {
    val isEmpty: Boolean
        get() = records.isEmpty() && workouts.isEmpty() && sleep.isEmpty()

    val totalCount: Int
        get() = records.size + workouts.size + sleep.size

    fun toDataMap(): Map<String, Any> = mapOf(
        "records" to records.map { it.toMap() },
        "workouts" to workouts.map { it.toMap() },
        "sleep" to sleep.map { it.toMap() }
    )

    /**
     * Returns a copy with only records/workouts/sleep whose startDate >= [floorIso].
     * The ISO string comparison works because dates are in ISO-8601 format (lexicographic order).
     */
    fun filterSince(floorIso: String): UnifiedHealthData = UnifiedHealthData(
        records = records.filter { it.startDate >= floorIso },
        workouts = workouts.filter { it.startDate >= floorIso },
        sleep = sleep.filter { it.startDate >= floorIso },
    )
}

data class ProviderReadResult(
    val data: UnifiedHealthData,
    val maxTimestamp: Long?,
    val minTimestamp: Long? = null,
    /** Health Connect rejected the read. Empty data here is not "no more records". */
    val quotaExceeded: Boolean = false,
)

// ---------------------------------------------------------------------------
// Timestamp helpers
// ---------------------------------------------------------------------------

object UnifiedTimestamp {
    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    fun fromEpochMs(epochMs: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(epochMs))

}

// ---------------------------------------------------------------------------
// Samsung device type → unified device type mapping
// ---------------------------------------------------------------------------

object DeviceTypeMapper {
    /**
     * Maps a Samsung [DeviceGroup] name to the unified device type.
     *
     * Samsung often reports a Galaxy Watch as [MOBILE] (sleep and other
     * records are assembled on the phone). When the group is MOBILE / unknown,
     * fall back to [name] / [model] so Watch7 (`SM-L315F`) is `watch`, not
     * `phone`. See #29.
     */
    fun fromSamsungDeviceType(
        type: String?,
        name: String? = null,
        model: String? = null,
    ): String? {
        val inferred = inferSamsungWearable(name, model)
        return when (type?.uppercase()) {
            "WATCH" -> "watch"
            "RING" -> "ring"
            "BAND" -> "fitness_band"
            "MOBILE" -> inferred ?: "phone"
            "ACCESSORY" -> inferred ?: "unknown"
            else -> inferred
        }
    }

    internal fun inferSamsungWearable(name: String?, model: String?): String? {
        val n = name?.lowercase().orEmpty()
        val m = model?.uppercase()?.replace(" ", "").orEmpty()
        if (n.contains("ring")) return "ring"
        if (n.contains("fit") && !n.contains("watch")) return "fitness_band"
        if (n.contains("watch")) return "watch"
        // SM-L is the Watch7 / Ultra / Watch8 prefix. SM-R is not watches-only
        // (Fit, Buds, Gear VR) so it is never used as a fallback.
        if (m.startsWith("SM-L")) return "watch"
        return null
    }

    fun fromHealthConnectDeviceType(type: Int): String? = when (type) {
        0 -> "unknown"
        1 -> "watch"
        2 -> "phone"
        3 -> "scale"
        4 -> "ring"
        5 -> "head_mounted"
        6 -> "fitness_band"
        7 -> "chest_strap"
        8 -> "smart_display"
        else -> "unknown"
    }

    fun fromHealthConnectRecordingMethod(method: Int): String? = when (method) {
        1 -> "active"
        2 -> "automatic"
        3 -> "manual"
        else -> "unknown"
    }
}
