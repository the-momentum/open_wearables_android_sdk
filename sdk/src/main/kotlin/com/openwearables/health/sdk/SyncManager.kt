package com.openwearables.health.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class TypeSyncProgress(
    val typeIdentifier: String,
    var sentCount: Int = 0,
    var isComplete: Boolean = false,
    var pendingAnchorTimestamp: Long? = null
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
        private const val TAG = "SyncManager"

        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private val syncPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(StorageKeys.SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val httpClient = sharedHttpClient

    private val dateFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    private val isSyncing = AtomicBoolean(false)
    private val tokenRefreshLock = ReentrantLock()
    private var isRefreshingToken = false

    private val stateMutex = Mutex()
    private var inMemoryState: SyncState? = null

    var syncIntervalMinutes: Long = SyncDefaults.SYNC_INTERVAL_MINUTES
        set(value) {
            field = maxOf(value, SyncDefaults.MIN_SYNC_INTERVAL_MINUTES)
        }

    // MARK: - User Key

    private fun userKey(): String {
        val userId = secureStorage.getUserId()
        return if (userId.isNullOrEmpty()) "user.none" else "user.$userId"
    }

    // MARK: - Auth

    private fun applyAuth(requestBuilder: Request.Builder) {
        val accessToken = secureStorage.getAccessToken()
        val apiKey = secureStorage.getApiKey()
        if (accessToken != null) {
            requestBuilder.header("Authorization", accessToken)
        } else if (apiKey != null) {
            requestBuilder.header("X-Open-Wearables-API-Key", apiKey)
        }
    }

    private fun applyAuth(requestBuilder: Request.Builder, credential: String) {
        if (secureStorage.isApiKeyAuth) {
            requestBuilder.header("X-Open-Wearables-API-Key", credential)
        } else {
            requestBuilder.header("Authorization", credential)
        }
    }

    private fun emitAuthError(statusCode: Int) {
        logger("Auth error: HTTP $statusCode - token invalid")
        onAuthError?.invoke(statusCode, "Unauthorized - please re-authenticate")
    }

    fun retryOutboxIfPossible() { /* reserved for future use */ }

    // MARK: - Background Sync

    suspend fun startBackgroundSync(host: String, customSyncUrl: String?): Boolean {
        schedulePeriodicSync(host, customSyncUrl)
        syncNow(host, customSyncUrl, fullExport = !hasCompletedInitialSync())
        return true
    }

    private fun schedulePeriodicSync(host: String, customSyncUrl: String?) {
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
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )
        logger("Scheduled periodic sync every $syncIntervalMinutes minute(s)")
    }

    fun scheduleExpeditedSync(host: String, customSyncUrl: String?) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val expeditedWork = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_HOST to host,
                HealthSyncWorker.KEY_CUSTOM_SYNC_URL to customSyncUrl
            ))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncDefaults.WORK_NAME_EXPEDITED, ExistingWorkPolicy.REPLACE, expeditedWork
        )
        logger("Scheduled expedited sync")
    }

    suspend fun stopBackgroundSync() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncDefaults.WORK_NAME_PERIODIC)
        logger("Cancelled periodic sync")
    }

    // MARK: - Sync Now

    suspend fun syncNow(host: String, customSyncUrl: String?, fullExport: Boolean) {
        if (!isSyncing.compareAndSet(false, true)) {
            logger("Sync already in progress")
            return
        }

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

            val startIndex: Int
            if (isResuming) {
                logger("Sync: resuming (${existingState!!.totalSentCount} sent, ${existingState.completedTypes.size}/${trackedTypes.size} types done)")
                startIndex = existingState.currentTypeIndex
                stateMutex.withLock { inMemoryState = existingState }
            } else {
                val mode = if (fullExport) "full export" else "incremental"
                logger("Sync: starting ($mode, ${trackedTypes.size} types, ${healthProvider.providerName})")
                stateMutex.withLock {
                    inMemoryState = SyncState(
                        userKey = userKey(), fullExport = fullExport,
                        createdAt = System.currentTimeMillis()
                    )
                    persistStateToDisk()
                }
                startIndex = 0
            }

            processTypes(trackedTypes, startIndex, fullExport, endpoint)
        } finally {
            isSyncing.set(false)
        }
    }

    private suspend fun processTypes(
        types: List<String>,
        startIndex: Int,
        fullExport: Boolean,
        endpoint: String
    ) {
        for (i in startIndex until types.size) {
            val type = types[i]

            val alreadySynced = stateMutex.withLock {
                inMemoryState?.completedTypes?.contains(type) == true
            }
            if (alreadySynced) {
                logger("Skipping $type - already synced")
                continue
            }

            stateMutex.withLock {
                inMemoryState?.currentTypeIndex = i
            }

            val success = processType(type, fullExport, endpoint)
            if (!success) {
                logger("Sync paused at $type, will resume later")
                stateMutex.withLock { persistStateToDisk() }
                return
            }
        }

        stateMutex.withLock {
            val state = inMemoryState ?: return
            if (state.fullExport) markFullExportDone()
            logger("Sync: complete (${state.totalSentCount} items, ${state.completedTypes.size} types)")
            clearSyncSessionInternal()
        }
    }

    private suspend fun processType(type: String, fullExport: Boolean, endpoint: String): Boolean {
        if (fullExport) {
            return processTypeNewestFirst(type, endpoint)
        }

        val anchors = loadAnchors()
        val anchor = anchors[type]

        logger("  $type: querying...")

        val result = healthProvider.readData(type, anchor, SyncDefaults.CHUNK_SIZE)

        if (result.data.isEmpty) {
            logger("  $type: no new data")
            stateMutex.withLock {
                updateInMemoryProgress(type, 0, isComplete = true, anchorTimestamp = null)
            }
            return true
        }

        val count = result.data.totalCount
        val payload = buildPayload(result.data)
        val sendResult = sendPayload(endpoint, payload)

        if (sendResult.success) {
            stateMutex.withLock {
                updateInMemoryProgress(type, count, isComplete = true, anchorTimestamp = result.maxTimestamp)
                persistStateToDisk()
            }
            logger("  $type: $count items sent (${sendResult.payloadSizeKb} KB) -> ${sendResult.statusCode}")
            return true
        } else {
            val reason = sendResult.statusCode?.let { "HTTP $it" } ?: "network error"
            logger("  $type: $count items -> failed ($reason)")
            return false
        }
    }

    /**
     * Full-export: fetch data newest-first in chunks. Uses a while loop
     * instead of recursion to avoid continuation chain buildup on large datasets.
     */
    private suspend fun processTypeNewestFirst(
        type: String,
        endpoint: String
    ): Boolean {
        var olderThan: Long? = null

        while (true) {
            logger("  $type: querying (newest first${olderThan?.let { ", olderThan=${java.time.Instant.ofEpochMilli(it)}" } ?: ""})...")

            val result = healthProvider.readDataDescending(type, olderThan, SyncDefaults.CHUNK_SIZE)

            if (result.data.isEmpty) {
                logger("  $type: all data sent (newest first)")
                stateMutex.withLock {
                    updateInMemoryProgress(type, 0, isComplete = true, anchorTimestamp = null)
                    persistStateToDisk()
                }
                return true
            }

            val count = result.data.totalCount
            val payload = buildPayload(result.data)
            val sendResult = sendPayload(endpoint, payload)

            if (!sendResult.success) {
                val reason = sendResult.statusCode?.let { "HTTP $it" } ?: "network error"
                logger("  $type: $count items -> failed ($reason)")
                return false
            }

            val anchorTs = if (olderThan == null) result.maxTimestamp else null
            val isLastChunk = count < SyncDefaults.CHUNK_SIZE

            logger("  $type: $count items sent (${sendResult.payloadSizeKb} KB) -> ${sendResult.statusCode}")

            stateMutex.withLock {
                updateInMemoryProgress(type, count, isComplete = isLastChunk, anchorTimestamp = anchorTs)
                if (isLastChunk) persistStateToDisk()
            }

            if (isLastChunk) return true

            olderThan = result.minTimestamp
        }
    }

    private fun updateInMemoryProgress(typeIdentifier: String, sentInChunk: Int, isComplete: Boolean, anchorTimestamp: Long?) {
        val state = inMemoryState ?: return
        val progress = state.typeProgress.getOrPut(typeIdentifier) { TypeSyncProgress(typeIdentifier) }
        progress.sentCount += sentInChunk
        progress.isComplete = isComplete
        if (anchorTimestamp != null) progress.pendingAnchorTimestamp = anchorTimestamp
        state.totalSentCount += sentInChunk
        if (isComplete) {
            state.completedTypes.add(typeIdentifier)
            progress.pendingAnchorTimestamp?.let { saveAnchor(typeIdentifier, it) }
        }
    }

    // MARK: - Payload (unified)

    private fun buildPayload(data: UnifiedHealthData): Map<String, Any> = mapOf(
        "provider" to healthProvider.providerId,
        "sdkVersion" to SyncDefaults.SDK_VERSION,
        "syncTimestamp" to UnifiedTimestamp.fromEpochMs(System.currentTimeMillis()),
        "data" to data.toDataMap()
    )

    // MARK: - Token Refresh

    private suspend fun attemptTokenRefresh(): Boolean = withContext(dispatchers.io) {
        tokenRefreshLock.withLock { isRefreshingToken = true }
        try {
            val refreshToken = secureStorage.getRefreshToken()
            val apiBaseUrl = secureStorage.apiBaseUrl
            if (refreshToken == null || apiBaseUrl == null) {
                android.util.Log.w(TAG, "No refresh token or host - cannot refresh")
                return@withContext false
            }

            android.util.Log.d(TAG, "Attempting token refresh...")
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
            android.util.Log.d(TAG, "Token refresh response [${response.code}]: $responseBody")

            if (response.isSuccessful && responseBody != null) {
                val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                val newAccessToken = jsonObj["access_token"]?.jsonPrimitive?.contentOrNull
                if (newAccessToken != null) {
                    val newRefreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.contentOrNull
                    secureStorage.updateTokens(newAccessToken, newRefreshToken)
                    logger("Token refreshed")
                    return@withContext true
                }
            }
            logger("Token refresh failed (HTTP ${response.code})")
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Token refresh error", e)
            logger("Token refresh failed")
            false
        } finally {
            tokenRefreshLock.withLock { isRefreshingToken = false }
        }
    }

    // MARK: - Send with Auth Retry

    private data class SendResult(val success: Boolean, val statusCode: Int?, val payloadSizeKb: Int)

    private suspend fun sendPayload(endpoint: String, payload: Map<String, Any>): SendResult {
        val jsonBody = serializePayload(payload)
        return sendPayloadRaw(endpoint, jsonBody)
    }

    private fun serializePayload(payload: Map<String, Any>): String {
        val element = mapToJsonElement(payload)
        return json.encodeToString(JsonElement.serializer(), element)
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                value.entries.associate { (k, v) -> k.toString() to mapToJsonElement(v) }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    private suspend fun sendPayloadRaw(endpoint: String, jsonBody: String): SendResult =
        withContext(dispatchers.io) {
            try {
                val sizeKb = jsonBody.length / 1024
                android.util.Log.d(TAG, "REQUEST [$endpoint] (${sizeKb} KB)")

                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                applyAuth(requestBuilder)

                val response = httpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string()
                android.util.Log.d(TAG, "RESPONSE [${response.code}]:\n$responseBody")

                if (response.isSuccessful) return@withContext SendResult(true, response.code, sizeKb)
                if (response.code == 401) {
                    val retryOk = handle401(endpoint, jsonBody)
                    return@withContext SendResult(retryOk, if (retryOk) 200 else 401, sizeKb)
                }

                SendResult(false, response.code, sizeKb)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Upload error", e)
                SendResult(false, null, 0)
            }
        }

    private suspend fun handle401(endpoint: String, jsonBody: String): Boolean {
        if (secureStorage.isApiKeyAuth) {
            emitAuthError(401)
            return false
        }

        if (attemptTokenRefresh()) {
            val newCredential = secureStorage.authCredential
            if (newCredential != null) {
                android.util.Log.d(TAG, "Retrying upload with refreshed token...")
                return try {
                    val retryBuilder = Request.Builder()
                        .url(endpoint)
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .header("Content-Type", "application/json")
                    applyAuth(retryBuilder, newCredential)

                    val retryResponse = httpClient.newCall(retryBuilder.build()).execute()
                    val retryBody = retryResponse.body?.string()
                    android.util.Log.d(TAG, "Retry RESPONSE [${retryResponse.code}]:\n$retryBody")

                    if (retryResponse.isSuccessful) true
                    else { emitAuthError(401); false }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Retry failed", e)
                    emitAuthError(401); false
                }
            }
        }
        emitAuthError(401)
        return false
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
        val current = loadAnchors().toMutableMap()
        current[type] = timestamp
        val element = mapToJsonElement(current)
        syncPrefs.edit().putString(
            StorageKeys.KEY_ANCHORS,
            json.encodeToString(JsonElement.serializer(), element)
        ).apply()
    }

    fun resetAnchors() {
        syncPrefs.edit()
            .remove(StorageKeys.KEY_ANCHORS)
            .putBoolean(fullDoneKey(), false)
            .apply()
        clearSyncSession()
        logger("Anchors reset - will perform full sync on next sync")
    }

    private fun fullDoneKey(): String = "fullDone.${userKey()}"
    private fun hasCompletedInitialSync(): Boolean = syncPrefs.getBoolean(fullDoneKey(), false)
    private fun markFullExportDone() { syncPrefs.edit().putBoolean(fullDoneKey(), true).apply() }

    // MARK: - Sync State (Mutex-protected disk I/O)

    private fun syncStateDir(): File = File(context.filesDir, StorageKeys.SYNC_STATE_DIR).also { if (!it.exists()) it.mkdirs() }
    private fun syncStateFile(): File = File(syncStateDir(), StorageKeys.SYNC_STATE_FILE)

    private fun persistStateToDisk() {
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
        return if (state != null) {
            mapOf(
                "hasResumableSession" to state.hasProgress,
                "sentCount" to state.totalSentCount,
                "completedTypes" to state.completedTypes.size,
                "isFullExport" to state.fullExport,
                "createdAt" to dateFormatter.format(java.time.Instant.ofEpochMilli(state.createdAt))
            )
        } else {
            mapOf(
                "hasResumableSession" to false,
                "sentCount" to 0,
                "completedTypes" to 0,
                "isFullExport" to false,
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
