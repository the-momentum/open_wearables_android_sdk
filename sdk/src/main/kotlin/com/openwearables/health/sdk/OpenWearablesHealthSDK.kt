package com.openwearables.health.sdk

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Main entry point for the Open Wearables Health SDK.
 *
 * Provides a unified API for reading health data from Samsung Health and
 * Health Connect, and syncing it to a backend.
 *
 * Usage:
 * ```
 * val sdk = OpenWearablesHealthSDK.initialize(context)
 * sdk.configure("https://api.example.com")
 * sdk.signIn(userId, accessToken, refreshToken, null)
 * sdk.requestAuthorization(listOf("steps", "heartRate"))
 * sdk.startBackgroundSync()
 * ```
 */
class OpenWearablesHealthSDK private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OpenWearablesHealthSDK"

        @Volatile
        private var instance: OpenWearablesHealthSDK? = null

        fun initialize(context: Context): OpenWearablesHealthSDK {
            return instance ?: synchronized(this) {
                instance ?: OpenWearablesHealthSDK(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): OpenWearablesHealthSDK {
            return instance ?: throw IllegalStateException(
                "SDK not initialized. Call OpenWearablesHealthSDK.initialize(context) first."
            )
        }
    }

    // Listeners
    var logListener: ((String) -> Unit)? = null
    var authErrorListener: ((statusCode: Int, message: String) -> Unit)? = null

    // Components
    internal val secureStorage: SecureStorage by lazy { SecureStorage(context) }

    private val samsungHealthManager: SamsungHealthManager by lazy {
        SamsungHealthManager(context, activity, ::logMessage)
    }
    private val healthConnectManager: HealthConnectManager by lazy {
        HealthConnectManager(context, activity, ::logMessage)
    }

    private var activeProvider: HealthDataProvider? = null
    private var syncManager: SyncManager? = null

    // Configuration
    private var host: String? = null
    private var customSyncUrl: String? = null

    // Activity reference for permission dialogs
    private var activity: Activity? = null

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // -----------------------------------------------------------------------
    // Activity
    // -----------------------------------------------------------------------

    fun setActivity(activity: Activity?) {
        this.activity = activity
        samsungHealthManager.setActivity(activity)
        healthConnectManager.setActivity(activity)
    }

    fun unregisterPermissionLauncher() {
        healthConnectManager.unregisterPermissionLauncher()
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    /**
     * Configure the SDK with a backend host URL.
     * @return true if background sync was previously active and should be auto-restored
     */
    fun configure(host: String, customSyncUrl: String? = null): Boolean {
        secureStorage.clearIfReinstalled()
        this.host = host
        secureStorage.saveHost(host)

        this.customSyncUrl = customSyncUrl ?: secureStorage.getCustomSyncUrl()
        this.customSyncUrl?.let { secureStorage.saveCustomSyncUrl(it) }

        val provider = getOrCreateProvider()
        val storedTypes = secureStorage.getTrackedTypes()
        if (storedTypes.isNotEmpty()) {
            provider.setTrackedTypes(storedTypes)
            logMessage("Restored ${storedTypes.size} tracked types for ${provider.providerName}")
        }

        logMessage("Configured: host=$host, provider=${provider.providerName}")

        val isSyncActive = secureStorage.isSyncActive() && secureStorage.hasSession()
        if (isSyncActive && storedTypes.isNotEmpty()) {
            logMessage("Auto-restoring background sync...")
            scope.launch { autoRestoreSync() }
        }

        return isSyncActive
    }

    private suspend fun autoRestoreSync() {
        val h = host
        if (h == null || secureStorage.getUserId() == null || !secureStorage.hasAuth) {
            logMessage("Cannot auto-restore: no session or host")
            return
        }
        ensureSyncManager().startBackgroundSync(h, customSyncUrl)
        logMessage("Background sync auto-restored")
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    suspend fun signIn(userId: String, accessToken: String?, refreshToken: String?, apiKey: String?) {
        val sm = ensureSyncManager()
        sm.clearSyncSession()
        sm.resetAnchors()

        secureStorage.saveCredentials(userId, accessToken, refreshToken)
        if (apiKey != null) {
            secureStorage.saveApiKey(apiKey)
            logMessage("API key saved")
        }

        logMessage("Signed in: userId=$userId, mode=${if (accessToken != null) "token" else "apiKey"}")
    }

    suspend fun signOut() {
        logMessage("Signing out")
        val sm = ensureSyncManager()
        sm.stopBackgroundSync()
        sm.resetAnchors()
        sm.clearSyncSession()
        activeProvider?.disconnect()
        secureStorage.clearAll()
        activeProvider = null
        syncManager = null
        logMessage("Sign out complete")
    }

    fun restoreSession(): String? {
        return if (secureStorage.hasSession()) {
            val userId = secureStorage.getUserId()
            logMessage("Session restored: userId=$userId")
            userId
        } else {
            null
        }
    }

    fun isSessionValid(): Boolean = secureStorage.hasSession()

    fun isSyncActive(): Boolean = secureStorage.isSyncActive()

    fun updateTokens(accessToken: String, refreshToken: String?) {
        secureStorage.updateTokens(accessToken, refreshToken)
        logMessage("Tokens updated")
        ensureSyncManager().retryOutboxIfPossible()
    }

    fun getStoredCredentials(): Map<String, Any?> = mapOf(
        "userId" to secureStorage.getUserId(),
        "accessToken" to secureStorage.getAccessToken(),
        "refreshToken" to secureStorage.getRefreshToken(),
        "apiKey" to secureStorage.getApiKey(),
        "host" to secureStorage.getHost(),
        "customSyncUrl" to secureStorage.getCustomSyncUrl(),
        "isSyncActive" to secureStorage.isSyncActive(),
        "provider" to (activeProvider?.providerId ?: secureStorage.getProvider())
    )

    // -----------------------------------------------------------------------
    // Authorization
    // -----------------------------------------------------------------------

    suspend fun requestAuthorization(types: List<String>): Boolean {
        secureStorage.saveTrackedTypes(types)
        val provider = getOrCreateProvider()
        provider.setTrackedTypes(types)
        logMessage("Requesting auth for ${types.size} types via ${provider.providerName}")
        return provider.requestAuthorization(types)
    }

    // -----------------------------------------------------------------------
    // Sync
    // -----------------------------------------------------------------------

    suspend fun startBackgroundSync() {
        val h = host ?: throw IllegalStateException("Not configured")
        if (!secureStorage.hasSession()) throw IllegalStateException("Not signed in")

        secureStorage.setSyncActive(true)
        logMessage("Background sync started (${getOrCreateProvider().providerName})")
        ensureSyncManager().startBackgroundSync(h, customSyncUrl)
    }

    suspend fun stopBackgroundSync() {
        ensureSyncManager().stopBackgroundSync()
        secureStorage.setSyncActive(false)
        logMessage("Background sync stopped")
    }

    suspend fun syncNow() {
        val h = host ?: throw IllegalStateException("Host not configured")
        ensureSyncManager().syncNow(h, customSyncUrl, fullExport = false)
    }

    fun resetAnchors() {
        val sm = ensureSyncManager()
        sm.resetAnchors()
        sm.clearSyncSession()
        logMessage("Anchors reset")

        val h = host
        if (secureStorage.isSyncActive() && secureStorage.hasAuth && h != null) {
            logMessage("Triggering full export after reset...")
            scope.launch {
                try {
                    sm.syncNow(h, customSyncUrl, fullExport = true)
                    logMessage("Full export after reset completed")
                } catch (e: Exception) {
                    logMessage("Full export after reset failed: ${e.message}")
                }
            }
        }
    }

    fun getSyncStatus(): Map<String, Any?> = ensureSyncManager().getSyncStatus()

    suspend fun resumeSync() {
        val h = host ?: throw IllegalStateException("Host not configured")
        val sm = ensureSyncManager()
        if (!sm.hasResumableSyncSession()) throw IllegalStateException("No resumable sync session")
        sm.syncNow(h, customSyncUrl, fullExport = false)
    }

    fun clearSyncSession() {
        ensureSyncManager().clearSyncSession()
    }

    fun hasResumableSyncSession(): Boolean = ensureSyncManager().hasResumableSyncSession()

    // -----------------------------------------------------------------------
    // Provider management
    // -----------------------------------------------------------------------

    fun setProvider(providerId: String): Boolean {
        val provider = when (providerId) {
            "samsung" -> samsungHealthManager
            "google" -> healthConnectManager
            else -> {
                logMessage("Unknown provider: $providerId")
                return false
            }
        }

        if (!provider.isAvailable()) {
            logMessage("Provider $providerId is not available on this device")
            return false
        }

        activeProvider = provider
        secureStorage.saveProvider(providerId)
        rebuildSyncManager()
        logMessage("Active provider set to: ${provider.providerName}")
        return true
    }

    fun getAvailableProviders(): List<Map<String, Any>> = listOf(
        mapOf(
            "id" to "samsung",
            "displayName" to "Samsung Health",
            "isAvailable" to samsungHealthManager.isAvailable()
        ),
        mapOf(
            "id" to "google",
            "displayName" to "Health Connect",
            "isAvailable" to healthConnectManager.isAvailable()
        )
    )

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun onForeground() {
        logMessage("App came to foreground")

        if (secureStorage.isSyncActive() && secureStorage.hasSession()) {
            logMessage("Checking for pending sync...")
            scope.launch {
                val sm = ensureSyncManager()
                if (sm.hasResumableSyncSession()) {
                    logMessage("Found interrupted sync, resuming...")
                    host?.let { sm.syncNow(it, customSyncUrl, fullExport = false) }
                }
            }
        }
    }

    fun onBackground() {
        logMessage("App went to background")

        if (secureStorage.isSyncActive() && secureStorage.hasSession()) {
            host?.let { h ->
                logMessage("Scheduling background sync...")
                ensureSyncManager().scheduleExpeditedSync(h, customSyncUrl)
            }
        }
    }

    fun destroy() {
        activeProvider?.disconnect()
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    internal fun getOrCreateProvider(): HealthDataProvider {
        activeProvider?.let { return it }
        val storedId = secureStorage.getProvider()
        val provider = resolveProvider(storedId)
        activeProvider = provider
        rebuildSyncManager()
        return provider
    }

    private fun resolveProvider(providerId: String?): HealthDataProvider = when (providerId) {
        "google" -> healthConnectManager
        "samsung" -> samsungHealthManager
        else -> autoSelectProvider()
    }

    private fun autoSelectProvider(): HealthDataProvider {
        if (samsungHealthManager.isAvailable()) return samsungHealthManager
        if (healthConnectManager.isAvailable()) return healthConnectManager
        return samsungHealthManager
    }

    private fun rebuildSyncManager() {
        val provider = activeProvider ?: return
        syncManager = SyncManager(context, secureStorage, provider, ::logMessage, ::emitAuthError)
    }

    internal fun ensureSyncManager(): SyncManager {
        if (syncManager == null) {
            getOrCreateProvider()
        }
        return syncManager!!
    }

    private fun emitAuthError(statusCode: Int, message: String) {
        logMessage("Auth error: HTTP $statusCode - $message")
        authErrorListener?.invoke(statusCode, message)
    }

    internal fun logMessage(message: String) {
        Log.d(TAG, message)
        logListener?.invoke(message)
    }
}
