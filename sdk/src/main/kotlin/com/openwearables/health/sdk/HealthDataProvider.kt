package com.openwearables.health.sdk

import android.app.Activity

/**
 * Abstraction over health data sources (Samsung Health, Health Connect).
 *
 * Each implementation reads provider-specific data and converts it
 * to the [UnifiedHealthData] format before returning. The [SyncManager]
 * works exclusively through this interface and never touches raw
 * provider-specific types.
 */
interface HealthDataProvider {

    /** Identifier sent in the payload: `"samsung"` / `"google"`. */
    val providerId: String

    /** Human-readable name for UI display. */
    val providerName: String

    /** `true` when the backing app / API is installed and meets minimum requirements. */
    fun isAvailable(): Boolean

    /** Open a connection to the health store. Must be called before reading data. */
    suspend fun connect(): Boolean

    /** Release any held resources. */
    fun disconnect()

    /**
     * Provide the current Activity (needed for permission dialogs).
     * Implementations should store this as a [java.lang.ref.WeakReference]
     * to prevent Activity leaks in singleton-scoped objects.
     */
    fun setActivity(activity: Activity?)

    /** Configure which Flutter-side type IDs should be tracked. */
    fun setTrackedTypes(typeIds: List<String>)

    /** Return the current set of tracked type IDs. */
    fun getTrackedTypes(): Set<String>

    /**
     * Show the provider's native permission UI for the given [typeIds].
     * Returns `true` when at least one requested data type was granted.
     * Types the user denied are removed from the tracked set and are not synced.
     */
    suspend fun requestAuthorization(typeIds: List<String>): Boolean

    /**
     * Read data for a single type and return it in unified format.
     *
     * @param typeId       Flutter-side type identifier (e.g. `"heartRate"`)
     * @param sinceTimestamp  epoch-ms anchor; only data **after** this point is returned
     * @param limit        maximum number of raw records to fetch from the store
     */
    suspend fun readData(
        typeId: String,
        sinceTimestamp: Long? = null,
        limit: Int = 1000
    ): ProviderReadResult

    /**
     * Read data for a single type in descending order (newest first).
     * Used during full export to sync from newest to oldest.
     *
     * @param typeId              Flutter-side type identifier (e.g. `"heartRate"`)
     * @param olderThanTimestamp  epoch-ms cursor; only data **before** this point is returned.
     *                            `null` means start from the newest available data.
     * @param limit               maximum number of raw records to fetch from the store
     * @return [ProviderReadResult] with [ProviderReadResult.minTimestamp] set to the oldest
     *         record's timestamp in this chunk (used as cursor for the next chunk).
     */
    suspend fun readDataDescending(
        typeId: String,
        olderThanTimestamp: Long? = null,
        limit: Int = 1000
    ): ProviderReadResult = ProviderReadResult(UnifiedHealthData(), null, null)

    /**
     * Whether incremental sync follows writes (Health Connect `getChanges`,
     * Samsung `readChanges`) instead of the record's start time.
     */
    fun supportsChangeTracking(): Boolean = false

    /**
     * Concurrent [readDataDescending] calls are safe. Health Connect reads
     * cross process, so a round of many types should not wait on each one.
     */
    fun supportsParallelReads(): Boolean = false

    /**
     * Whether [typeId] has a change-token stream. Types the provider cannot
     * read complete without one and must not pause the sync.
     */
    fun canTrackChanges(typeId: String): Boolean = supportsChangeTracking()

    /** Mint a change token at "now" so later [readChanges] sees only subsequent writes. */
    suspend fun mintChangeToken(typeId: String): String? = null

    /**
     * Read one page of insertions since [token], in write order. Backfilled
     * records (old `startTime`, new insert) are included. See #19.
     */
    suspend fun readChanges(typeId: String, token: String): ChangeReadResult =
        ChangeReadResult.unavailable()
}

data class ChangeReadResult(
    val data: UnifiedHealthData = UnifiedHealthData(),
    val nextToken: String? = null,
    val hasMore: Boolean = false,
    val upsertCount: Int = 0,
    val deletedCount: Int = 0,
    val tokenExpired: Boolean = false,
) {
    companion object {
        fun unavailable() = ChangeReadResult()
        fun expired() = ChangeReadResult(tokenExpired = true)
    }
}
