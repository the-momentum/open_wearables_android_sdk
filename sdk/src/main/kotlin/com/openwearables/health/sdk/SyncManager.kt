package com.openwearables.health.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class TypeSyncProgress(
    val typeIdentifier: String,
    var sentCount: Int = 0,
    var isComplete: Boolean = false,
    var pendingAnchorTimestamp: Long? = null,
    var pendingOlderThan: Long? = null,
    var pendingChangeToken: String? = null
)

@Serializable
data class SyncState(
    val userKey: String,
    val fullExport: Boolean,
    val createdAt: Long,
    var typeProgress: MutableMap<String, TypeSyncProgress> = mutableMapOf(),
    var totalSentCount: Int = 0,
    var completedTypes: MutableSet<String> = mutableSetOf(),
    var currentTypeIndex: Int = 0
) {
    val hasProgress: Boolean
        get() = totalSentCount > 0 || completedTypes.isNotEmpty()
}

/**
 * Manages health data synchronization.
 *
 * Works exclusively through the [HealthDataProvider] interface — all
 * provider-specific reading and unified-format conversion happens inside
 * the provider. The SyncManager just orchestrates timing, chunking,
 * auth retry, and payload delivery.
 */
class SyncManager(
    private val context: Context,
    private val secureStorage: SecureStorage,
    private val healthProvider: HealthDataProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: (String) -> Unit,
    private val onAuthError: ((Int, String) -> Unit)? = null
) {
    companion object {
        // Optional mTLS configurator. Set by [MtlsConfigurator] when an mTLS
        // source is available (KeyChain alias or bundled .p12). Reassigning
        // this and calling [rebuildSharedHttpClient] swaps the shared client
        // at runtime so the user can pick a new cert without restarting.
        @Volatile
        internal var mtlsConfigurator: ((OkHttpClient.Builder) -> Unit)? = null

        @Volatile
        private var _sharedHttpClient: OkHttpClient? = null

        @get:Synchronized
        val sharedHttpClient: OkHttpClient
            get() = _sharedHttpClient ?: rebuildSharedHttpClient()

        @Synchronized
        internal fun rebuildSharedHttpClient(): OkHttpClient {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .also { builder -> mtlsConfigurator?.invoke(builder) }
                .build()
            _sharedHttpClient = client
            return client
        }

        private const val CATCHUP_TOKEN_PREFIX = "catchup:"

        /** One sync per process. Workers construct their own [SyncManager]. */
        internal val processSyncLock = AtomicBoolean(false)

        /**
         * Bumped when anchors are cleared. A sync that started earlier must not
         * write its cursors back on top of the reset.
         */
        internal val syncEpoch = AtomicInteger(0)

        /**
         * Set when a run stops because Health Connect rejected a read.
         * The worker that owns the run consumes this and delays the follow-up.
         */
        internal val quotaBackoffPending = AtomicBoolean(false)
    }

    private val syncPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(StorageKeys.SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Read live each call so cert changes via MtlsConfigurator.reload() take
    // effect on subsequent requests without recreating the SyncManager.
    private val httpClient get() = sharedHttpClient

    private val dateFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    private val isSyncing = AtomicBoolean(false)
    private var runEpoch = 0

    private fun writesAllowed(): Boolean = runEpoch == syncEpoch.get()
    private val tokenRefreshLock = ReentrantLock()
    private var isRefreshingToken = false

    private val stateMutex = Mutex()
    private var inMemoryState: SyncState? = null

    private var fullSyncStartTime: Long? = null
    private var currentLogsEndpoint: String? = null

    var syncIntervalMinutes: Long = SyncDefaults.SYNC_INTERVAL_MINUTES
        set(value) {
            field = maxOf(value, SyncDefaults.MIN_SYNC_INTERVAL_MINUTES)
        }

    // MARK: - Sync Start Timestamp

    /**
     * Computes the earliest epoch-ms timestamp to sync from, based on persisted `syncDaysBack`.
     * Returns the start of the day (midnight local time) that many days ago,
     * or `null` if full sync (no limit) is configured.
     */
    private fun syncStartTimestamp(): Long? {
        val daysBack = secureStorage.getSyncDaysBack()
        if (daysBack <= 0) return null
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysBack)
        return cal.timeInMillis
    }

    // MARK: - User Key

    private fun userKey(): String {
        val userId = secureStorage.getUserId()
        return if (userId.isNullOrEmpty()) "user.none" else "user.$userId"
    }

    // MARK: - Auth

    private fun bearerValue(token: String): String =
        if (token.startsWith("Bearer ")) token else "Bearer $token"

    private fun applyAuth(requestBuilder: Request.Builder) {
        val accessToken = secureStorage.getAccessToken()
        val apiKey = secureStorage.getApiKey()
        if (accessToken != null) {
            requestBuilder.header("Authorization", bearerValue(accessToken))
        } else if (apiKey != null) {
            requestBuilder.header("X-Open-Wearables-API-Key", apiKey)
        }
    }

    private fun applyAuth(requestBuilder: Request.Builder, credential: String) {
        if (secureStorage.isApiKeyAuth) {
            requestBuilder.header("X-Open-Wearables-API-Key", credential)
        } else {
            requestBuilder.header("Authorization", bearerValue(credential))
        }
    }

    private fun emitAuthError(statusCode: Int) {
        logger("Auth error: HTTP $statusCode - token invalid")
        onAuthError?.invoke(statusCode, "Unauthorized - please re-authenticate")
    }

    fun retryOutboxIfPossible() { /* reserved for future use */ }

    // MARK: - Background Sync

    suspend fun startBackgroundSync(host: String, customSyncUrl: String?): Boolean {
        schedulePeriodicSync(host, customSyncUrl, replace = false)
        return true
    }

    /**
     * @param replace `true` when the interval changed. Default `KEEP` so
     * `configure` / app restart does not reset the 15-minute timer (#20).
     */
    private fun schedulePeriodicSync(host: String, customSyncUrl: String?, replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            syncIntervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_HOST to host,
                HealthSyncWorker.KEY_CUSTOM_SYNC_URL to customSyncUrl
            ))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncDefaults.WORK_NAME_PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            work
        )
        logger("Scheduled periodic sync every $syncIntervalMinutes minute(s)")
    }

    fun reschedulePeriodicSync(host: String, customSyncUrl: String?) {
        schedulePeriodicSync(host, customSyncUrl, replace = true)
    }

    fun scheduleExpeditedSync(
        host: String,
        customSyncUrl: String?,
        initialDelayMs: Long = 0L,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val builder = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_HOST to host,
                HealthSyncWorker.KEY_CUSTOM_SYNC_URL to customSyncUrl
            ))
        if (initialDelayMs > 0L) {
            builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
        } else {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val expeditedWork = builder.build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncDefaults.WORK_NAME_EXPEDITED, ExistingWorkPolicy.REPLACE, expeditedWork
        )
        if (initialDelayMs > 0L) {
            logger("Scheduled sync in ${initialDelayMs / 1000}s (Health Connect rate limit)")
        } else {
            logger("Scheduled expedited sync")
        }
    }

    suspend fun stopBackgroundSync() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SyncDefaults.WORK_NAME_PERIODIC)
        wm.cancelUniqueWork(SyncDefaults.WORK_NAME_EXPEDITED)
        logger("Cancelled periodic and expedited sync")
    }

    // MARK: - Sync Now

    suspend fun syncNow(
        host: String,
        customSyncUrl: String?,
        fullExport: Boolean,
        background: Boolean = false,
    ) {
        if (!processSyncLock.compareAndSet(false, true)) {
            logger("Sync already in progress")
            return
        }
        runEpoch = syncEpoch.get()
        isSyncing.set(true)
        quotaBackoffPending.set(false)

        try {
            val userId = secureStorage.getUserId()
            if (userId == null || !secureStorage.hasAuth) {
                logger("No credentials for sync")
                return
            }

            val endpoint = buildSyncEndpoint(host, customSyncUrl, userId)

            val trackedTypes = healthProvider.getTrackedTypes().toList()
            if (trackedTypes.isEmpty()) {
                logger("No tracked types configured")
                return
            }

            val existingState = stateMutex.withLock { loadSyncStateFromDisk() }
            val isResuming = existingState != null && existingState.hasProgress

            val floor = syncStartTimestamp()
            val floorLabel = if (floor != null) "since ${java.time.Instant.ofEpochMilli(floor)}" else "full history"

            val effectiveFullExport: Boolean
            if (isResuming) {
                effectiveFullExport = existingState!!.fullExport
                logger("Sync: resuming (${existingState.totalSentCount} sent, ${existingState.completedTypes.size}/${trackedTypes.size} types done, $floorLabel)")
                stateMutex.withLock { inMemoryState = existingState }
            } else {
                effectiveFullExport = fullExport || !hasCompletedInitialSync()
                val mode = if (effectiveFullExport) "full export" else "incremental"
                logger("Sync: starting ($mode, ${trackedTypes.size} types, ${healthProvider.providerName}, $floorLabel)")
                stateMutex.withLock {
                    inMemoryState = SyncState(
                        userKey = userKey(), fullExport = effectiveFullExport,
                        createdAt = System.currentTimeMillis()
                    )
                    persistStateToDisk()
                }
            }

            val syncStartTime = System.currentTimeMillis()
            val logsEndpoint = buildLogsEndpoint(host, userId)

            if (effectiveFullExport) {
                fullSyncStartTime = syncStartTime
                currentLogsEndpoint = logsEndpoint
                try {
                    // Already-sent totals only. Paging the whole store here
                    // burns the Health Connect request quota before any payload
                    // is uploaded. A fresh session reports 0.
                    val typeCounts = if (isResuming && existingState != null) {
                        trackedTypes.associateWith { existingState.typeProgress[it]?.sentCount ?: 0 }
                    } else {
                        trackedTypes.associateWith { 0 }
                    }
                    logger("Sending sync start log to $logsEndpoint")
                    sendSyncStartLog(logsEndpoint, trackedTypes, typeCounts, floor)
                } catch (e: Exception) {
                    logger("Sync start log failed: ${e.message}")
                }
            }

            val result = processTypesRoundRobin(
                trackedTypes, effectiveFullExport, endpoint, background,
            )

            if (effectiveFullExport && !result.completed) {
                val durationMs = (System.currentTimeMillis() - syncStartTime).toInt()
                for (typeResult in result.typeResults) {
                    if (!typeResult.success && typeResult.recordCount > 0) {
                        sendTypeEndLog(logsEndpoint, typeResult.type, false, typeResult.recordCount, durationMs)
                    }
                }
            }

            fullSyncStartTime = null
            currentLogsEndpoint = null
        } finally {
            isSyncing.set(false)
            processSyncLock.set(false)
        }
    }

    // MARK: - Round-Robin Sync Orchestration (combined payloads)

    private data class TypeResult(val type: String, val success: Boolean, val recordCount: Int)
    private data class RoundRobinResult(val completed: Boolean, val totalRecords: Int, val typeResults: List<TypeResult>)

    private data class FetchResult(
        val type: String,
        val data: UnifiedHealthData = UnifiedHealthData(),
        val count: Int = 0,
        val nextCursor: Long? = null,
        val anchorTimestamp: Long? = null,
        val nextChangeToken: String? = null,
        val isDone: Boolean = false,
        val quotaExceeded: Boolean = false,
    )

    private suspend fun processTypesRoundRobin(
        types: List<String>,
        fullExport: Boolean,
        endpoint: String,
        background: Boolean,
    ): RoundRobinResult {
        val olderThanCursors = mutableMapOf<String, Long?>()
        val anchorCursors = mutableMapOf<String, Long?>()
        val changeTokens = mutableMapOf<String, String?>()
        val completedTypes = mutableSetOf<String>()

        stateMutex.withLock {
            val state = inMemoryState
            if (state != null) {
                completedTypes.addAll(state.completedTypes)
                for ((id, progress) in state.typeProgress) {
                    if (!progress.isComplete) {
                        progress.pendingOlderThan?.let { olderThanCursors[id] = it }
                        progress.pendingAnchorTimestamp?.let { anchorCursors[id] = it }
                        progress.pendingChangeToken?.let { changeTokens[id] = it }
                    }
                }
            }
        }

        if (!fullExport) {
            val anchors = loadAnchors()
            val storedTokens = loadChangeTokens()
            val floor = syncStartTimestamp()
            for (type in types) {
                if (completedTypes.contains(type)) continue
                if (!changeTokens.containsKey(type)) {
                    storedTokens[type]?.let { changeTokens[type] = it }
                }
                if (!anchorCursors.containsKey(type)) {
                    val storedAnchor = anchors[type]
                    val anchor = when {
                        storedAnchor != null && floor != null -> maxOf(storedAnchor, floor)
                        storedAnchor != null -> storedAnchor
                        else -> floor
                    }
                    anchorCursors[type] = anchor
                }
            }
        }

        var loggedPageSize = -1
        return coroutineScope syncRound@{
        // Next page is read while the current one uploads. A failed upload
        // cancels it; cursors are persisted only after a 2xx.
        var prefetched: Deferred<List<FetchResult>>? = null
        while (true) {
            val incompleteTypes = types.filter { !completedTypes.contains(it) }
            if (incompleteTypes.isEmpty()) break

            // The worker always asks for a background run. While the app is open,
            // use the large page: Health Connect charges quota per request, so a
            // 100-record page split across every type burns the limit on a handful
            // of samples.
            val smallPages = background && !isAppInForeground()
            val chunkSize = if (smallPages) SyncDefaults.BACKGROUND_CHUNK_SIZE else SyncDefaults.CHUNK_SIZE
            if (chunkSize != loggedPageSize) {
                loggedPageSize = chunkSize
                logger("Sync page size: $chunkSize${if (smallPages) " (app in background)" else ""}")
            }
            val perTypeLimit = maxOf(1, chunkSize / incompleteTypes.size)

            // Phase 1: Fetch one chunk from each type (no network yet).
            // Health Connect reads are cross-process; do not wait on them one by one.
            val roundResults = prefetched?.await() ?: fetchTypesForRound(
                incompleteTypes, fullExport, olderThanCursors, anchorCursors, changeTokens, perTypeLimit,
            )
            prefetched = null

            for (result in roundResults) {
                if (result.quotaExceeded) {
                    continue
                }
                if (result.isDone) {
                    completedTypes.add(result.type)
                } else {
                    if (fullExport) olderThanCursors[result.type] = result.nextCursor
                    else {
                        result.nextCursor?.let { anchorCursors[result.type] = it }
                        result.nextChangeToken?.let { changeTokens[result.type] = it }
                    }
                }
            }

            val hitQuota = roundResults.any { it.quotaExceeded }

            // Read the following page during the upload. Cursors above are already
            // the next page; they are written to disk only after a 2xx.
            val stillIncomplete = types.filter { !completedTypes.contains(it) }
            if (!hitQuota && stillIncomplete.isNotEmpty()) {
                val nextLimit = maxOf(1, chunkSize / stillIncomplete.size)
                prefetched = async {
                    fetchTypesForRound(
                        stillIncomplete, fullExport, olderThanCursors, anchorCursors, changeTokens, nextLimit,
                    )
                }
            }

            // Phase 2: Merge all fetched data into one combined payload
            val mergedData = UnifiedHealthData(
                records = roundResults.flatMap { it.data.records },
                workouts = roundResults.flatMap { it.data.workouts },
                sleep = roundResults.flatMap { it.data.sleep }
            )

            if (!mergedData.isEmpty) {
                logPayloadSummary(mergedData)
                val uploadStarted = System.currentTimeMillis()
                val sendResult = sendHealthData(endpoint, mergedData)
                val uploadMs = System.currentTimeMillis() - uploadStarted

                if (!sendResult.success) {
                    prefetched?.cancel()
                    prefetched = null
                    val reason = sendResult.statusCode?.let { "HTTP $it" } ?: "network error"
                    logger("Combined round failed ($reason)")
                    val (totalSent, typeResults) = stateMutex.withLock {
                        persistStateToDisk()
                        val state = inMemoryState
                        val sent = state?.totalSentCount ?: 0
                        val results = types.map { type ->
                            TypeResult(type, state?.completedTypes?.contains(type) == true, state?.typeProgress?.get(type)?.sentCount ?: 0)
                        }
                        Pair(sent, results)
                    }
                    return@syncRound RoundRobinResult(false, totalSent, typeResults)
                }

                logger("Round sent: ${mergedData.totalCount} items (${sendResult.payloadSizeKb} KB) in ${uploadMs}ms -> ${sendResult.statusCode}")
            }

            // Phase 3: Update progress for all types in this round.
            // Full-export + change tracking: do not persist isComplete until
            // Phase 4 mints a token. A failed mint leaves the type incomplete
            // so the next incremental run cannot start from nil and re-crawl history.
            val newlyCompletedTypes = mutableListOf<Pair<String, Int>>()
            val deferCompleteForToken = fullExport && healthProvider.supportsChangeTracking()
            stateMutex.withLock {
                for (result in roundResults) {
                    val persistComplete = result.isDone && !deferCompleteForToken
                    updateInMemoryProgress(
                        result.type, result.count, isComplete = persistComplete,
                        anchorTimestamp = result.anchorTimestamp,
                        changeToken = result.nextChangeToken,
                    )
                    if (fullExport && !result.isDone && !result.quotaExceeded) {
                        inMemoryState?.typeProgress?.get(result.type)?.pendingOlderThan = result.nextCursor
                    }
                    if (!fullExport && !result.isDone) {
                        result.nextChangeToken?.let {
                            inMemoryState?.typeProgress?.get(result.type)?.pendingChangeToken = it
                        }
                    }
                    if (persistComplete) {
                        newlyCompletedTypes.add(result.type to (inMemoryState?.typeProgress?.get(result.type)?.sentCount ?: 0))
                    }
                }
                persistStateToDisk()
            }

            if (hitQuota) {
                prefetched?.cancel()
                prefetched = null
                logger("Health Connect rate limit — pausing sync, cursors unchanged for the rejected read")
                quotaBackoffPending.set(true)
                val (totalSent, typeResults) = stateMutex.withLock {
                    val state = inMemoryState
                    val sent = state?.totalSentCount ?: 0
                    val results = types.map { type ->
                        TypeResult(type, state?.completedTypes?.contains(type) == true, state?.typeProgress?.get(type)?.sentCount ?: 0)
                    }
                    Pair(sent, results)
                }
                return@syncRound RoundRobinResult(false, totalSent, typeResults)
            }

            // Phase 4: mint HC change tokens after a successful upload.
            // A failed mint pauses the sync instead of pretending the type is done.
            if (deferCompleteForToken) {
                val doneThisRound = roundResults.filter { it.isDone }
                for (done in doneThisRound) {
                    if (!healthProvider.canTrackChanges(done.type)) {
                        logger("  ${done.type}: no change stream, marking complete")
                        stateMutex.withLock {
                            updateInMemoryProgress(
                                done.type, sentInChunk = 0, isComplete = true,
                                anchorTimestamp = null, changeToken = null,
                            )
                            persistStateToDisk()
                            newlyCompletedTypes.add(
                                done.type to (inMemoryState?.typeProgress?.get(done.type)?.sentCount ?: 0)
                            )
                        }
                        continue
                    }
                    val minted = healthProvider.mintChangeToken(done.type)
                    if (minted == null) {
                        prefetched?.cancel()
                        prefetched = null
                        logger("  ${done.type}: change token mint failed — leaving type incomplete, pausing sync")
                        completedTypes.remove(done.type)
                        stateMutex.withLock {
                            inMemoryState?.completedTypes?.remove(done.type)
                            inMemoryState?.typeProgress?.get(done.type)?.isComplete = false
                            persistStateToDisk()
                        }
                        val (totalSent, typeResults) = stateMutex.withLock {
                            val state = inMemoryState
                            val sent = state?.totalSentCount ?: 0
                            val results = types.map { type ->
                                TypeResult(type, state?.completedTypes?.contains(type) == true, state?.typeProgress?.get(type)?.sentCount ?: 0)
                            }
                            Pair(sent, results)
                        }
                        return@syncRound RoundRobinResult(false, totalSent, typeResults)
                    }
                    logger("  ${done.type}: captured change token after full export")
                    stateMutex.withLock {
                        updateInMemoryProgress(
                            done.type, sentInChunk = 0, isComplete = true,
                            anchorTimestamp = null, changeToken = minted,
                        )
                        persistStateToDisk()
                        newlyCompletedTypes.add(done.type to (inMemoryState?.typeProgress?.get(done.type)?.sentCount ?: 0))
                    }
                }
            }

            val startTime = fullSyncStartTime
            val logEndpoint = currentLogsEndpoint
            if (fullExport && startTime != null && logEndpoint != null && newlyCompletedTypes.isNotEmpty()) {
                for ((type, count) in newlyCompletedTypes) {
                    if (count > 0) {
                        val durationMs = (System.currentTimeMillis() - startTime).toInt()
                        logger("Sending sync end log: ${payloadTypeName(type)} ($count records, ${durationMs}ms)")
                        launch {
                            try {
                                sendTypeEndLog(logEndpoint, type, true, count, durationMs)
                            } catch (e: Exception) {
                                logger("Type end log failed for $type: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

        val (totalSent, typeResults) = stateMutex.withLock {
            val state = inMemoryState
            val sent = state?.totalSentCount ?: 0
            val results = types.map { type ->
                TypeResult(type, state?.completedTypes?.contains(type) == true, state?.typeProgress?.get(type)?.sentCount ?: 0)
            }
            if (state?.fullExport == true) markFullExportDone()
            if (state != null) {
                logger("Sync: complete ($sent items, ${state.completedTypes.size} types)")
                if (state.fullExport && sent == 0) {
                    logger("Full export found 0 records from ${healthProvider.providerName}. If the wearable writes to Health Connect, set provider to google.")
                }
            }
            clearSyncSessionInternal()
            Pair(sent, results)
        }
        RoundRobinResult(true, totalSent, typeResults)
        }
    }

    /** True when the process has a started activity. Workers still pass background=true. */
    private fun isAppInForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) {
        false
    }

    // MARK: - Fetch-Only Chunk Processors (no network)

    private suspend fun fetchTypesForRound(
        types: List<String>,
        fullExport: Boolean,
        olderThanCursors: Map<String, Long?>,
        anchorCursors: Map<String, Long?>,
        changeTokens: Map<String, String?>,
        perTypeLimit: Int,
    ): List<FetchResult> {
        suspend fun fetch(type: String): FetchResult = if (fullExport) {
            fetchOneChunkNewestFirst(type, olderThanCursors[type], perTypeLimit)
        } else {
            fetchOneChunkIncremental(type, anchorCursors[type], changeTokens[type], perTypeLimit)
        }

        if (!healthProvider.supportsParallelReads() || types.size <= 1) {
            val results = ArrayList<FetchResult>(types.size)
            for (type in types) {
                val result = fetch(type)
                results.add(result)
                if (result.quotaExceeded) break
            }
            return results
        }

        val gate = Semaphore(4)
        return coroutineScope {
            types.map { type ->
                async {
                    gate.withPermit { fetch(type) }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchOneChunkNewestFirst(
        type: String,
        olderThan: Long?,
        limit: Int
    ): FetchResult {
        val floor = syncStartTimestamp()
        val floorIso = floor?.let { UnifiedTimestamp.fromEpochMs(it) }

        logger("  $type: querying (newest first, limit=$limit${olderThan?.let { ", olderThan=${java.time.Instant.ofEpochMilli(it)}" } ?: ""})...")

        val result = healthProvider.readDataDescending(type, olderThan, limit)

        if (result.quotaExceeded) {
            logger("  $type: rate limited — leaving cursor unchanged")
            return FetchResult(type = type, quotaExceeded = true)
        }

        if (result.data.isEmpty) {
            logger("  $type: all data sent (newest first)")
            return FetchResult(type = type, isDone = true)
        }

        val reachedFloor = floor != null && result.minTimestamp != null && result.minTimestamp <= floor
        val isLastChunk = result.data.totalCount < limit || reachedFloor

        val data = if (reachedFloor && floorIso != null) result.data.filterSince(floorIso) else result.data

        if (data.isEmpty) {
            logger("  $type: all data within range sent")
            return FetchResult(type = type, isDone = true)
        }

        val anchorTs = if (olderThan == null) result.maxTimestamp else null
        val nextOlderThan = if (isLastChunk) null else result.minTimestamp

        logger("  $type: ${data.totalCount} samples (newest first)")

        return FetchResult(
            type = type, data = data, count = data.totalCount,
            nextCursor = nextOlderThan, anchorTimestamp = anchorTs, isDone = isLastChunk
        )
    }

    private suspend fun fetchOneChunkIncremental(
        type: String,
        anchor: Long?,
        changeToken: String?,
        limit: Int
    ): FetchResult {
        if (healthProvider.supportsChangeTracking()) {
            return fetchOneChunkFromChanges(type, changeToken, limit, catchupSince = anchor)
        }
        return fetchOneChunkFromTimestamp(type, anchor, limit)
    }

    /**
     * Health Connect incremental: write-order [HealthDataProvider.readChanges].
     * A missing or expired token is minted at "now" and
     * we do a one-time [SyncDefaults.CHANGE_CATCHUP_MS] time-range catch-up so upgrades
     * and token expiry do not skip recent backfills.
     *
     * [catchupSince] is the in-memory page cursor for a multi-page catch-up.
     * The first mint always looks back [SyncDefaults.CHANGE_CATCHUP_MS] — never
     * the old timestamp anchor, which is the #19 bug.
     */
    private suspend fun fetchOneChunkFromChanges(
        type: String,
        changeToken: String?,
        limit: Int,
        catchupSince: Long? = null,
    ): FetchResult {
        val token = changeToken
        if (token == null || token.startsWith(CATCHUP_TOKEN_PREFIX)) {
            val realToken = token?.removePrefix(CATCHUP_TOKEN_PREFIX)
                ?: healthProvider.mintChangeToken(type)
            if (realToken == null) {
                logger("  $type: could not mint change token, falling back to timestamp")
                return fetchOneChunkFromTimestamp(type, catchupSince ?: loadAnchors()[type], limit)
            }
            val since = if (token != null && token.startsWith(CATCHUP_TOKEN_PREFIX)) {
                catchupSince ?: (System.currentTimeMillis() - SyncDefaults.CHANGE_CATCHUP_MS)
            } else {
                System.currentTimeMillis() - SyncDefaults.CHANGE_CATCHUP_MS
            }
            logger("  $type: change-token catch-up since ${java.time.Instant.ofEpochMilli(since)}")
            val result = healthProvider.readData(type, since, limit)
            if (result.quotaExceeded) {
                logger("  $type: rate limited during catch-up — leaving cursor unchanged")
                return FetchResult(type = type, quotaExceeded = true)
            }
            val count = result.data.totalCount
            val isLastChunk = count < limit
            if (result.data.isEmpty) {
                logger("  $type: catch-up empty")
                return FetchResult(type = type, nextChangeToken = realToken, isDone = true)
            }
            logger("  $type: catch-up $count samples")
            return FetchResult(
                type = type, data = result.data, count = count,
                nextCursor = result.maxTimestamp,
                anchorTimestamp = result.maxTimestamp,
                nextChangeToken = if (isLastChunk) realToken else CATCHUP_TOKEN_PREFIX + realToken,
                isDone = isLastChunk
            )
        }

        logger("  $type: querying changes...")
        val changes = healthProvider.readChanges(type, token)
        if (changes.tokenExpired) {
            logger("  $type: change token expired — reminting + 30-day catch-up")
            return fetchOneChunkFromChanges(type, changeToken = null, limit = limit, catchupSince = null)
        }

        val deletedNote = if (changes.deletedCount > 0) ", ${changes.deletedCount} deleted" else ""
        if (changes.data.isEmpty) {
            logger("  $type: no new data$deletedNote")
            return FetchResult(type = type, nextChangeToken = changes.nextToken ?: token, isDone = true)
        }

        val count = changes.data.totalCount
        val isLastChunk = !changes.hasMore
        logger("  $type: $count samples$deletedNote")
        return FetchResult(
            type = type, data = changes.data, count = count,
            nextChangeToken = changes.nextToken ?: token,
            isDone = isLastChunk
        )
    }

    private suspend fun fetchOneChunkFromTimestamp(
        type: String,
        anchor: Long?,
        limit: Int
    ): FetchResult {
        logger("  $type: querying (limit=$limit)...")

        val result = healthProvider.readData(type, anchor, limit)

        if (result.data.isEmpty) {
            logger("  $type: no new data")
            return FetchResult(type = type, isDone = true)
        }

        val count = result.data.totalCount
        val isLastChunk = count < limit

        logger("  $type: $count samples")

        return FetchResult(
            type = type, data = result.data, count = count,
            nextCursor = result.maxTimestamp, anchorTimestamp = result.maxTimestamp,
            isDone = isLastChunk
        )
    }

    private fun updateInMemoryProgress(
        typeIdentifier: String,
        sentInChunk: Int,
        isComplete: Boolean,
        anchorTimestamp: Long?,
        changeToken: String? = null,
    ) {
        val state = inMemoryState ?: return
        val progress = state.typeProgress.getOrPut(typeIdentifier) { TypeSyncProgress(typeIdentifier) }
        progress.sentCount += sentInChunk
        progress.isComplete = isComplete
        if (anchorTimestamp != null) progress.pendingAnchorTimestamp = anchorTimestamp
        if (changeToken != null) progress.pendingChangeToken = changeToken
        state.totalSentCount += sentInChunk
        if (isComplete) {
            state.completedTypes.add(typeIdentifier)
            progress.pendingAnchorTimestamp?.let { saveAnchor(typeIdentifier, it) }
            progress.pendingChangeToken?.let { saveChangeToken(typeIdentifier, it) }
        }
    }

    // MARK: - Payload (unified)

    /**
     * Encodes one round straight to bytes. Building [UnifiedHealthData.toDataMap]
     * first copies every record into a Map tree (several MB per page) and the
     * collector pauses the upload. Workouts and sleep stay on their maps —
     * a page has few of them. The byte array sets Content-Length and can be
     * replayed after a 401.
     */
    private fun encodeHealthPayload(data: UnifiedHealthData): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val writer = android.util.JsonWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))
        writer.beginObject()
        writer.name("provider").value(healthProvider.providerId)
        writer.name("sdkVersion").value(SyncDefaults.SDK_VERSION)
        writer.name("syncTimestamp").value(UnifiedTimestamp.fromEpochMs(System.currentTimeMillis()))
        writer.name("data")
        writer.beginObject()
        writer.name("records")
        writer.beginArray()
        for (record in data.records) writeRecord(writer, record)
        writer.endArray()
        writer.name("workouts")
        writeValue(writer, data.workouts.map { it.toMap() })
        writer.name("sleep")
        writeValue(writer, data.sleep.map { it.toMap() })
        writer.endObject()
        writer.endObject()
        writer.flush()
        return out.toByteArray()
    }

    private fun writeRecord(writer: android.util.JsonWriter, record: UnifiedRecord) {
        writer.beginObject()
        writer.name("id").value(record.id)
        writer.name("type").value(record.type)
        writer.name("startDate").value(record.startDate)
        writer.name("endDate").value(record.endDate)
        writer.name("zoneOffset")
        writeNullableString(writer, record.zoneOffset)
        writer.name("source")
        writeSource(writer, record.source)
        writer.name("value").value(record.value)
        writer.name("unit")
        writeNullableString(writer, record.unit)
        writer.name("parentId")
        writeNullableString(writer, record.parentId)
        writer.name("metadata")
        writeValue(writer, record.metadata)
        writer.endObject()
    }

    private fun writeSource(writer: android.util.JsonWriter, source: UnifiedSource) {
        writer.beginObject()
        writer.name("appId"); writeNullableString(writer, source.appId)
        writer.name("deviceId"); writeNullableString(writer, source.deviceId)
        writer.name("deviceName"); writeNullableString(writer, source.deviceName)
        writer.name("deviceManufacturer"); writeNullableString(writer, source.deviceManufacturer)
        writer.name("deviceModel"); writeNullableString(writer, source.deviceModel)
        writer.name("deviceType"); writeNullableString(writer, source.deviceType)
        writer.name("recordingMethod"); writeNullableString(writer, source.recordingMethod)
        writer.endObject()
    }

    private fun writeNullableString(writer: android.util.JsonWriter, value: String?) {
        if (value == null) writer.nullValue() else writer.value(value)
    }

    private suspend fun sendHealthData(endpoint: String, data: UnifiedHealthData): SendResult {
        val bytes = withContext(dispatchers.io) { encodeHealthPayload(data) }
        val body = bytes.toRequestBody("application/json".toMediaType())
        return sendWithBody(endpoint, body, AtomicLong(bytes.size.toLong()))
    }

    // MARK: - Payload Summary Logging

    private fun logPayloadSummary(data: UnifiedHealthData) {
        val typeCounts = mutableMapOf<String, Int>()

        for (r in data.records) {
            typeCounts[r.type] = (typeCounts[r.type] ?: 0) + 1
        }
        if (data.sleep.isNotEmpty()) {
            typeCounts["sleep"] = data.sleep.size
        }
        if (data.workouts.isNotEmpty()) {
            typeCounts["workouts"] = data.workouts.size
        }

        val totalCount = typeCounts.values.sum()
        val breakdown = typeCounts.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}: ${it.value}" }

        logger("Sending $totalCount items ($breakdown)")
    }

    // MARK: - Token Refresh

    private enum class TokenRefreshResult {
        SUCCESS, AUTH_FAILURE, NETWORK_ERROR
    }

    private suspend fun attemptTokenRefresh(): TokenRefreshResult = withContext(dispatchers.io) {
        tokenRefreshLock.withLock { isRefreshingToken = true }
        try {
            val refreshToken = secureStorage.getRefreshToken()
            val apiBaseUrl = secureStorage.apiBaseUrl
            if (refreshToken == null || apiBaseUrl == null) {
                logger("Token refresh: missing credentials")
                return@withContext TokenRefreshResult.AUTH_FAILURE
            }

            val url = "$apiBaseUrl/token/refresh"
            val bodyMap = mapOf("refresh_token" to refreshToken)
            val body = json.encodeToString(bodyMap)
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.code in 401..403) {
                logger("Token refresh rejected: HTTP ${response.code}")
                return@withContext TokenRefreshResult.AUTH_FAILURE
            }

            if (response.isSuccessful && responseBody != null) {
                val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                val newAccessToken = jsonObj["access_token"]?.jsonPrimitive?.contentOrNull
                val newRefreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.contentOrNull
                if (newAccessToken != null) {
                    secureStorage.updateTokens(newAccessToken, newRefreshToken)
                    logger("Token refresh: HTTP ${response.code}")
                    return@withContext TokenRefreshResult.SUCCESS
                } else {
                    logger("Token refresh failed: HTTP ${response.code} (no access_token in response)")
                }
            } else {
                logger("Token refresh failed: HTTP ${response.code}")
            }
            TokenRefreshResult.NETWORK_ERROR
        } catch (e: Exception) {
            logger("Token refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            TokenRefreshResult.NETWORK_ERROR
        } finally {
            tokenRefreshLock.withLock { isRefreshingToken = false }
        }
    }

    // MARK: - Send with Auth Retry

    private data class SendResult(val success: Boolean, val statusCode: Int?, val payloadSizeKb: Int)

    private suspend fun sendPayload(endpoint: String, payload: Map<String, Any>): SendResult {
        val requestBytes = AtomicLong(0)
        val body = streamingJsonBody(payload, requestBytes)
        return sendWithBody(endpoint, body, requestBytes)
    }

    /**
     * Creates an OkHttp RequestBody that streams JSON directly from the Map
     * to the network via android.util.JsonWriter. No intermediate JsonElement
     * tree or full String is allocated — only the writer's small internal buffer
     * is held in heap, making memory usage O(depth) instead of O(n).
     */
    private fun streamingJsonBody(payload: Map<String, Any>, requestBytes: AtomicLong? = null): RequestBody {
        return object : okhttp3.RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) {
                val out = if (requestBytes != null) {
                    object : java.io.OutputStream() {
                        private val delegate = sink.outputStream()
                        override fun write(b: Int) { delegate.write(b); requestBytes.incrementAndGet() }
                        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); requestBytes.addAndGet(len.toLong()) }
                        override fun flush() = delegate.flush()
                    }
                } else sink.outputStream()
                val writer = android.util.JsonWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))
                writeValue(writer, payload)
                writer.flush()
            }
        }
    }

    private fun writeValue(writer: android.util.JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is Boolean -> writer.value(value)
            is Int -> writer.value(value.toLong())
            is Long -> writer.value(value)
            is Float -> writer.value(value.toDouble())
            is Double -> writer.value(value)
            is Number -> writer.value(value.toDouble())
            is String -> writer.value(value)
            is Map<*, *> -> {
                writer.beginObject()
                for ((k, v) in value) {
                    writer.name(k.toString())
                    writeValue(writer, v)
                }
                writer.endObject()
            }
            is List<*> -> {
                writer.beginArray()
                for (item in value) {
                    writeValue(writer, item)
                }
                writer.endArray()
            }
            else -> writer.value(value.toString())
        }
    }

    private suspend fun sendWithBody(endpoint: String, body: okhttp3.RequestBody, requestBytes: AtomicLong? = null): SendResult =
        withContext(dispatchers.io) {
            try {
                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Content-Type", "application/json")
                applyAuth(requestBuilder)

                val response = httpClient.newCall(requestBuilder.build()).execute()
                val sizeKb = ((requestBytes?.get() ?: 0L) / 1024).toInt()
                val code = response.code
                response.body?.close()

                if (response.isSuccessful) return@withContext SendResult(true, code, sizeKb)
                if (code == 401) {
                    logger("Got 401, refreshing token...")
                    val retryOk = handle401(endpoint, body)
                    return@withContext SendResult(retryOk, if (retryOk) 200 else 401, sizeKb)
                }

                SendResult(false, code, sizeKb)
            } catch (e: Exception) {
                logger("Upload error: ${e.javaClass.simpleName}: ${e.message}")
                SendResult(false, null, 0)
            }
        }

    private suspend fun handle401(endpoint: String, body: okhttp3.RequestBody): Boolean {
        if (secureStorage.isApiKeyAuth) {
            emitAuthError(401)
            return false
        }

        when (attemptTokenRefresh()) {
            TokenRefreshResult.SUCCESS -> {
                val newCredential = secureStorage.authCredential
                if (newCredential != null) {
                    logger("Token refreshed, retrying...")
                    return try {
                        val retryBuilder = Request.Builder()
                            .url(endpoint)
                            .post(body)
                            .header("Content-Type", "application/json")
                        applyAuth(retryBuilder, newCredential)

                        val retryResponse = httpClient.newCall(retryBuilder.build()).execute()
                        val retryCode = retryResponse.code
                        retryResponse.body?.close()
                        if (retryResponse.isSuccessful) {
                            logger("Retry: HTTP $retryCode")
                            true
                        } else {
                            logger("Retry failed: HTTP $retryCode")
                            if (retryCode in 401..403) emitAuthError(401)
                            false
                        }
                    } catch (e: Exception) {
                        logger("Retry failed: ${e.message}")
                        false
                    }
                }
                return false
            }
            TokenRefreshResult.AUTH_FAILURE -> {
                emitAuthError(401)
                return false
            }
            TokenRefreshResult.NETWORK_ERROR -> {
                logger("Token refresh failed (network) - will retry later")
                return false
            }
        }
    }

    // MARK: - Sync Logging

    private fun payloadTypeName(trackedTypeId: String): String = when (trackedTypeId) {
        "steps" -> "STEP_COUNT"
        "heartRate" -> "HEART_RATE"
        "restingHeartRate" -> "RESTING_HEART_RATE"
        "heartRateVariabilitySDNN" -> "HEART_RATE_VARIABILITY"
        "oxygenSaturation" -> "OXYGEN_SATURATION"
        "bloodPressure", "bloodPressureSystolic" -> "BLOOD_PRESSURE_SYSTOLIC"
        "bloodPressureDiastolic" -> "BLOOD_PRESSURE_DIASTOLIC"
        "bloodGlucose" -> "BLOOD_GLUCOSE"
        "activeEnergy" -> "ACTIVE_CALORIES_BURNED"
        "basalEnergy" -> "BASAL_METABOLIC_RATE"
        "bodyTemperature" -> "BODY_TEMPERATURE"
        "bodyMass" -> "WEIGHT"
        "height" -> "HEIGHT"
        "bodyFatPercentage" -> "BODY_FAT"
        "leanBodyMass" -> "LEAN_BODY_MASS"
        "flightsClimbed" -> "FLOORS_CLIMBED"
        "distanceWalkingRunning", "distanceCycling" -> "DISTANCE"
        "water", "dietaryWater" -> "HYDRATION"
        in NutritionPayload.trackedTypeIds -> "NUTRITION"
        "vo2Max" -> "VO2_MAX"
        "respiratoryRate" -> "RESPIRATORY_RATE"
        "workout" -> "WORKOUT"
        "sleep" -> "SLEEP"
        else -> trackedTypeId.uppercase()
    }

    private fun buildLogsEndpoint(host: String, userId: String): String {
        val h = if (host.endsWith("/")) host.dropLast(1) else host
        return "$h/api/v1/sdk/users/$userId/logs"
    }

    private fun collectDeviceState(): Map<String, Any> {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager

        val batteryLevel = batteryManager
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.let { it / 100f } ?: -1f

        val batteryIntent = try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) { null }
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryState = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }

        val isLowPower = powerManager?.isPowerSaveMode ?: false

        val thermalState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            when (powerManager?.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> "NONE"
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else "UNSUPPORTED"

        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        return mapOf(
            "eventType" to "device_state",
            "timestamp" to dateFormatter.format(java.time.Instant.now()),
            "batteryLevel" to batteryLevel,
            "batteryState" to batteryState,
            "isLowPowerMode" to isLowPower,
            "thermalState" to thermalState,
            "taskType" to "background",
            "availableRamBytes" to memInfo.availMem,
            "totalRamBytes" to memInfo.totalMem
        )
    }

    private suspend fun sendSyncStartLog(logsEndpoint: String, types: List<String>, typeCounts: Map<String, Int>, startTimestamp: Long?) {
        val dataTypeCounts = types.map { mapOf("type" to payloadTypeName(it), "count" to (typeCounts[it] ?: 0)) }

        val timeRange = mutableMapOf<String, String>(
            "endDate" to dateFormatter.format(java.time.Instant.now())
        )
        startTimestamp?.let {
            timeRange["startDate"] = dateFormatter.format(java.time.Instant.ofEpochMilli(it))
        }

        val startEvent: Map<String, Any> = mapOf(
            "eventType" to "historical_data_sync_start",
            "timestamp" to dateFormatter.format(java.time.Instant.now()),
            "dataTypeCounts" to dataTypeCounts,
            "timeRange" to timeRange
        )

        val body: Map<String, Any> = mapOf(
            "sdkVersion" to SyncDefaults.SDK_VERSION,
            "provider" to healthProvider.providerId,
            "events" to listOf(startEvent, collectDeviceState())
        )

        sendSyncLog(logsEndpoint, body)
    }

    private suspend fun sendTypeEndLog(logsEndpoint: String, type: String, success: Boolean, recordCount: Int, durationMs: Int) {
        val endEvent: Map<String, Any> = mapOf(
            "eventType" to "historical_data_type_sync_end",
            "timestamp" to dateFormatter.format(java.time.Instant.now()),
            "dataType" to payloadTypeName(type),
            "success" to success,
            "recordCount" to recordCount,
            "durationMs" to durationMs
        )

        val body: Map<String, Any> = mapOf(
            "sdkVersion" to SyncDefaults.SDK_VERSION,
            "provider" to healthProvider.providerId,
            "events" to listOf(endEvent, collectDeviceState())
        )

        sendSyncLog(logsEndpoint, body)
    }

    private suspend fun sendSyncLog(endpoint: String, body: Map<String, Any>) = withContext(dispatchers.io) {
        try {
            val requestBody = streamingJsonBody(body)
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .header("Content-Type", "application/json")
            applyAuth(requestBuilder)

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.body?.close()
            logger("Sync log: HTTP ${response.code}")
        } catch (e: Exception) {
            logger("Sync log error: ${e.message}")
        }
    }

    // MARK: - Sync Endpoint

    private fun buildSyncEndpoint(host: String, customSyncUrl: String?, userId: String): String {
        if (customSyncUrl != null) {
            if (customSyncUrl.contains("{user_id}") || customSyncUrl.contains("{userId}")) {
                return customSyncUrl
                    .replace("{userId}", userId)
                    .replace("{user_id}", userId)
            }
            val normalizedBase = customSyncUrl.trimEnd('/')
            return "$normalizedBase/sdk/users/$userId/sync"
        }
        val h = if (host.endsWith("/")) host.dropLast(1) else host
        return "$h/api/v1/sdk/users/$userId/sync"
    }

    // MARK: - Anchors

    private fun loadAnchors(): Map<String, Long> {
        val jsonStr = syncPrefs.getString(StorageKeys.KEY_ANCHORS, null) ?: return emptyMap()
        return try {
            val map = json.decodeFromString<Map<String, Double>>(jsonStr)
            map.mapValues { it.value.toLong() }
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveAnchor(type: String, timestamp: Long) {
        if (!writesAllowed()) return
        val current = loadAnchors().toMutableMap()
        current[type] = timestamp
        syncPrefs.edit().putString(
            StorageKeys.KEY_ANCHORS,
            json.encodeToString(current.mapValues { it.value.toDouble() })
        ).apply()
    }

    private fun loadChangeTokens(): Map<String, String> {
        val jsonStr = syncPrefs.getString(StorageKeys.KEY_CHANGE_TOKENS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveChangeToken(type: String, token: String) {
        if (!writesAllowed()) return
        if (token.startsWith(CATCHUP_TOKEN_PREFIX)) return
        val current = loadChangeTokens().toMutableMap()
        current[type] = token
        syncPrefs.edit().putString(StorageKeys.KEY_CHANGE_TOKENS, json.encodeToString(current)).apply()
    }

    fun resetAnchors() {
        runBlocking {
            stateMutex.withLock {
                syncEpoch.incrementAndGet()
                syncPrefs.edit()
                    .remove(StorageKeys.KEY_ANCHORS)
                    .remove(StorageKeys.KEY_CHANGE_TOKENS)
                    .putBoolean(fullDoneKey(), false)
                    .commit()
                clearSyncSessionInternal()
            }
        }
        logger("Anchors reset - will perform full sync on next sync")
    }

    private fun fullDoneKey(): String = "fullDone.${userKey()}"
    fun hasCompletedInitialExport(): Boolean = hasCompletedInitialSync()

    private fun hasCompletedInitialSync(): Boolean = syncPrefs.getBoolean(fullDoneKey(), false)
    private fun markFullExportDone() {
        if (!writesAllowed()) return
        syncPrefs.edit().putBoolean(fullDoneKey(), true).apply()
    }

    // MARK: - Sync State (Mutex-protected disk I/O)

    private fun syncStateDir(): File = File(context.filesDir, StorageKeys.SYNC_STATE_DIR).also { if (!it.exists()) it.mkdirs() }
    private fun syncStateFile(): File = File(syncStateDir(), StorageKeys.SYNC_STATE_FILE)

    private fun persistStateToDisk() {
        if (!writesAllowed()) return
        val state = inMemoryState ?: return
        try {
            val jsonStr = json.encodeToString(state)
            if (jsonStr.isNotBlank() && jsonStr.startsWith("{")) {
                val file = syncStateFile()
                val tempFile = File(file.parent, "${file.name}.tmp")
                tempFile.writeText(jsonStr)
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            logger("Failed to save sync state: ${e.message}")
        }
    }

    private fun loadSyncStateFromDisk(): SyncState? {
        return try {
            val file = syncStateFile()
            if (!file.exists()) return null
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) { file.delete(); return null }
            val state = json.decodeFromString<SyncState>(jsonStr)
            if (state.userKey != userKey()) { clearSyncSessionInternal(); return null }
            state
        } catch (e: Exception) {
            logger("Corrupted sync state, clearing: ${e.message}")
            try { syncStateFile().delete() } catch (_: Exception) {}
            null
        }
    }

    private fun clearSyncSessionInternal() {
        inMemoryState = null
        try { syncStateFile().delete() } catch (_: Exception) {}
    }

    fun getSyncStatus(): Map<String, Any?> {
        val state = inMemoryState ?: loadSyncStateFromDisk()
        // Whether the initial full export (whole history) has ever completed for
        // this user. While false, apps can show a "keep the app open" hint.
        val initialExportDone = hasCompletedInitialSync()
        return if (state != null) {
            mapOf(
                "hasResumableSession" to state.hasProgress,
                "sentCount" to state.totalSentCount,
                "completedTypes" to state.completedTypes.size,
                "isFullExport" to state.fullExport,
                "initialExportDone" to initialExportDone,
                "isSyncing" to isSyncing.get(),
                "createdAt" to dateFormatter.format(java.time.Instant.ofEpochMilli(state.createdAt))
            )
        } else {
            mapOf(
                "hasResumableSession" to false,
                "sentCount" to 0,
                "completedTypes" to 0,
                "isFullExport" to false,
                "initialExportDone" to initialExportDone,
                "isSyncing" to isSyncing.get(),
                "createdAt" to null
            )
        }
    }

    fun hasResumableSyncSession(): Boolean {
        return (inMemoryState ?: loadSyncStateFromDisk())?.hasProgress == true
    }

    fun clearSyncSession() {
        clearSyncSessionInternal()
        logger("Cleared sync state")
    }
}
