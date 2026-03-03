package com.openwearables.health.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TypeSyncProgress(
    val typeIdentifier: String,
    var sentCount: Int = 0,
    var isComplete: Boolean = false,
    var pendingAnchorTimestamp: Long? = null
)

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
    private val logger: (String) -> Unit,
    private val onAuthError: ((Int, String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_PREFS_NAME = "com.openwearables.healthsdk.sync"
        private const val KEY_ANCHORS = "anchors"
        private const val WORK_NAME_PERIODIC = "health_sync_periodic"
        private const val CHUNK_SIZE = 2000
        private const val SYNC_INTERVAL_MINUTES = 5L
        private const val SYNC_STATE_DIR = "health_sync_state"
        private const val SYNC_STATE_FILE = "state.json"
        const val SDK_VERSION = "0.1.0"
    }

    private val syncPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val gson = Gson()
    private val prettyGson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val dateFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    private val isSyncing = AtomicBoolean(false)
    private val tokenRefreshLock = ReentrantLock()
    private var isRefreshingToken = false

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
        withContext(Dispatchers.Main) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            scheduleNextSync(host, customSyncUrl, constraints)
            logger("Scheduled sync every $SYNC_INTERVAL_MINUTES minute(s)")
        }

        syncNow(host, customSyncUrl, fullExport = !hasCompletedInitialSync())
        return true
    }

    private fun scheduleNextSync(host: String, customSyncUrl: String?, constraints: Constraints) {
        val work = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                HealthSyncWorker.KEY_HOST to host,
                HealthSyncWorker.KEY_CUSTOM_SYNC_URL to customSyncUrl
            ))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_PERIODIC, ExistingWorkPolicy.REPLACE, work
        )
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
            "health_sync_expedited", ExistingWorkPolicy.REPLACE, expeditedWork
        )
        logger("Scheduled expedited sync")
    }

    suspend fun stopBackgroundSync() {
        withContext(Dispatchers.Main) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            logger("Cancelled periodic sync")
        }
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

            val existingState = loadSyncState()
            val isResuming = existingState != null && existingState.hasProgress

            val startIndex: Int
            if (isResuming) {
                logger("Sync: resuming (${existingState!!.totalSentCount} sent, ${existingState.completedTypes.size}/${trackedTypes.size} types done)")
                startIndex = getResumeTypeIndex()
            } else {
                val mode = if (fullExport) "full export" else "incremental"
                logger("Sync: starting ($mode, ${trackedTypes.size} types, ${healthProvider.providerName})")
                startNewSyncState(fullExport)
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
            if (!shouldSyncType(type)) {
                logger("Skipping $type - already synced")
                continue
            }

            updateCurrentTypeIndex(i)
            val success = processType(type, fullExport, endpoint)
            if (!success) {
                logger("Sync paused at $type, will resume later")
                return
            }
        }
        finalizeSyncState()
    }

    private suspend fun processType(type: String, fullExport: Boolean, endpoint: String): Boolean {
        val anchors = if (fullExport) emptyMap() else loadAnchors()
        val anchor = anchors[type]

        logger("  $type: querying...")

        val result = healthProvider.readData(type, anchor, CHUNK_SIZE)

        if (result.data.isEmpty) {
            logger("  $type: no new data")
            updateTypeProgress(type, 0, isComplete = true, anchorTimestamp = null)
            return true
        }

        val count = result.data.totalCount
        val payload = buildPayload(result.data)
        val sendResult = sendPayload(endpoint, payload)

        if (sendResult.success) {
            updateTypeProgress(type, count, isComplete = true, anchorTimestamp = result.maxTimestamp)
            logger("  $type: $count items sent (${sendResult.payloadSizeKb} KB) -> ${sendResult.statusCode}")
            return true
        } else {
            val reason = sendResult.statusCode?.let { "HTTP $it" } ?: "network error"
            logger("  $type: $count items -> failed ($reason)")
            return false
        }
    }

    // MARK: - Payload (unified)

    private fun buildPayload(data: UnifiedHealthData): Map<String, Any> = mapOf(
        "provider" to healthProvider.providerId,
        "sdkVersion" to SDK_VERSION,
        "syncTimestamp" to UnifiedTimestamp.fromEpochMs(System.currentTimeMillis()),
        "data" to data.toDataMap()
    )

    // MARK: - Token Refresh

    private suspend fun attemptTokenRefresh(): Boolean = withContext(Dispatchers.IO) {
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
            val body = gson.toJson(mapOf("refresh_token" to refreshToken))
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            android.util.Log.d(TAG, "Token refresh response [${response.code}]: $responseBody")

            if (response.isSuccessful && responseBody != null) {
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(responseBody, Map::class.java) as? Map<String, Any>
                val newAccessToken = json?.get("access_token") as? String
                if (newAccessToken != null) {
                    secureStorage.updateTokens(newAccessToken, json["refresh_token"] as? String)
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

    private suspend fun sendPayload(endpoint: String, payload: Map<String, Any>): SendResult =
        withContext(Dispatchers.IO) {
            try {
                val jsonBody = gson.toJson(payload)
                val sizeKb = jsonBody.length / 1024
                android.util.Log.d(TAG, "REQUEST [$endpoint] (${sizeKb} KB):\n${prettyGson.toJson(payload)}")

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
        val json = syncPrefs.getString(KEY_ANCHORS, null) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Double>
            map?.mapValues { it.value.toLong() } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveAnchor(type: String, timestamp: Long) {
        val current = loadAnchors().toMutableMap()
        current[type] = timestamp
        syncPrefs.edit().putString(KEY_ANCHORS, gson.toJson(current)).apply()
    }

    fun resetAnchors() {
        syncPrefs.edit()
            .remove(KEY_ANCHORS)
            .putBoolean(fullDoneKey(), false)
            .apply()
        clearSyncSession()
        logger("Anchors reset - will perform full sync on next sync")
    }

    private fun fullDoneKey(): String = "fullDone.${userKey()}"
    private fun hasCompletedInitialSync(): Boolean = syncPrefs.getBoolean(fullDoneKey(), false)
    private fun markFullExportDone() { syncPrefs.edit().putBoolean(fullDoneKey(), true).apply() }

    // MARK: - Sync State

    private fun syncStateDir(): File = File(context.filesDir, SYNC_STATE_DIR).also { if (!it.exists()) it.mkdirs() }
    private fun syncStateFile(): File = File(syncStateDir(), SYNC_STATE_FILE)

    private fun saveSyncState(state: SyncState) {
        try {
            val json = gson.toJson(state)
            if (json.isNotBlank() && json.startsWith("{")) {
                val file = syncStateFile()
                val tempFile = File(file.parent, "${file.name}.tmp")
                tempFile.writeText(json)
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            logger("Failed to save sync state: ${e.message}")
        }
    }

    private fun loadSyncState(): SyncState? {
        return try {
            val file = syncStateFile()
            if (!file.exists()) return null
            val json = file.readText()
            if (json.isBlank()) { file.delete(); return null }
            val state = gson.fromJson(json, SyncState::class.java)
            if (state == null || state.userKey != userKey()) { clearSyncSession(); return null }
            state
        } catch (e: Exception) {
            logger("Corrupted sync state, clearing: ${e.message}")
            try { syncStateFile().delete() } catch (_: Exception) {}
            null
        }
    }

    private fun startNewSyncState(fullExport: Boolean): SyncState {
        val state = SyncState(
            userKey = userKey(), fullExport = fullExport,
            createdAt = System.currentTimeMillis()
        )
        saveSyncState(state)
        return state
    }

    private fun updateTypeProgress(typeIdentifier: String, sentInChunk: Int, isComplete: Boolean, anchorTimestamp: Long?) {
        val state = loadSyncState() ?: return
        var progress = state.typeProgress[typeIdentifier] ?: TypeSyncProgress(typeIdentifier)
        progress.sentCount += sentInChunk
        progress.isComplete = isComplete
        if (anchorTimestamp != null) progress.pendingAnchorTimestamp = anchorTimestamp
        state.typeProgress[typeIdentifier] = progress
        state.totalSentCount += sentInChunk
        if (isComplete) {
            state.completedTypes.add(typeIdentifier)
            progress.pendingAnchorTimestamp?.let { saveAnchor(typeIdentifier, it) }
        }
        saveSyncState(state)
    }

    private fun updateCurrentTypeIndex(index: Int) {
        val state = loadSyncState() ?: return
        state.currentTypeIndex = index
        saveSyncState(state)
    }

    private fun finalizeSyncState() {
        val state = loadSyncState() ?: return
        if (state.fullExport) markFullExportDone()
        logger("Sync: complete (${state.totalSentCount} items, ${state.completedTypes.size} types)")
        clearSyncSession()
    }

    private fun shouldSyncType(typeIdentifier: String): Boolean {
        val state = loadSyncState() ?: return true
        return !state.completedTypes.contains(typeIdentifier)
    }

    private fun getResumeTypeIndex(): Int = loadSyncState()?.currentTypeIndex ?: 0

    fun getSyncStatus(): Map<String, Any?> {
        val state = loadSyncState()
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

    fun hasResumableSyncSession(): Boolean = loadSyncState()?.hasProgress == true

    fun clearSyncSession() {
        try {
            syncStateFile().delete()
            logger("Cleared sync state")
        } catch (e: Exception) {
            logger("Failed to clear sync state: ${e.message}")
        }
    }
}

// ---------------------------------------------------------------------------
// WorkManager worker
// ---------------------------------------------------------------------------

class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_HOST = "host"
        const val KEY_CUSTOM_SYNC_URL = "customSyncUrl"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "health_sync_channel"
        private const val WORK_NAME_PERIODIC = "health_sync_periodic"
        private const val SYNC_INTERVAL_MINUTES = 5L
    }

    override suspend fun doWork(): Result {
        val host = inputData.getString(KEY_HOST) ?: return Result.failure()
        val customSyncUrl = inputData.getString(KEY_CUSTOM_SYNC_URL)

        try {
            setForeground(getForegroundInfo())
            android.util.Log.d("HealthSyncWorker", "Running as foreground service")
        } catch (e: Exception) {
            android.util.Log.w("HealthSyncWorker", "Could not promote to foreground: ${e.message}")
        }

        val secureStorage = SecureStorage(applicationContext)
        val provider = createProvider(applicationContext, secureStorage)
        val syncManager = SyncManager(applicationContext, secureStorage, provider, {
            android.util.Log.d("HealthSyncWorker", it)
        })

        return try {
            val trackedTypes = secureStorage.getTrackedTypes()
            provider.setTrackedTypes(trackedTypes)

            android.util.Log.d("HealthSyncWorker", "Background sync (provider: ${provider.providerId})")
            syncManager.syncNow(host, customSyncUrl, fullExport = false)

            scheduleNextSync(host, customSyncUrl)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HealthSyncWorker", "Sync failed", e)
            scheduleNextSync(host, customSyncUrl)
            Result.retry()
        }
    }

    private fun createProvider(context: Context, storage: SecureStorage): HealthDataProvider {
        val providerId = storage.getProvider()
        val log: (String) -> Unit = { android.util.Log.d("HealthSyncWorker", it) }
        return when (providerId) {
            "google" -> HealthConnectManager(context, null, log)
            else -> SamsungHealthManager(context, null, log)
        }
    }

    private fun scheduleNextSync(host: String, customSyncUrl: String?) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val nextWork = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                KEY_HOST to host,
                KEY_CUSTOM_SYNC_URL to customSyncUrl
            ))
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME_PERIODIC, ExistingWorkPolicy.REPLACE, nextWork
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Health Sync")
            .setContentText("Syncing health data...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Health Sync", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background health data synchronization" }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
