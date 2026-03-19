package com.openwearables.health.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Secure storage for credentials using EncryptedSharedPreferences.
 * Mirrors the iOS Keychain-based storage approach.
 *
 * If EncryptedSharedPreferences fails to initialize (e.g. corrupted KeyStore),
 * the class attempts to clear corrupted keys and retry before throwing. It does
 * NOT silently fall back to unencrypted storage.
 */
class SecureStorage(private val context: Context) {

    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_NAME = "com.openwearables.healthsdk.secure"
        private const val CONFIG_PREFS_NAME = "com.openwearables.healthsdk.config"

        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_REFRESH_TOKEN = "refreshToken"
        private const val KEY_USER_ID = "userId"
        private const val KEY_API_KEY = "apiKey"

        private const val KEY_HOST = "host"
        private const val KEY_CUSTOM_SYNC_URL = "customSyncUrl"
        private const val KEY_SYNC_ACTIVE = "syncActive"
        private const val KEY_TRACKED_TYPES = "trackedTypes"
        private const val KEY_HEALTH_PROVIDER = "healthProvider"
        private const val KEY_APP_INSTALLED = "appInstalled"
        private const val KEY_SYNC_DAYS_BACK = "syncDaysBack"
        private const val KEY_NOTIFICATION_TITLE = "notificationTitle"
        private const val KEY_NOTIFICATION_TEXT = "notificationText"
    }

    /**
     * Whether the encrypted storage initialized successfully.
     * If false, credentials are NOT being stored securely.
     */
    var isEncryptionActive: Boolean = true
        private set

    private val securePrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, attempting recovery", e)
            try {
                clearCorruptedKeyStoreEntry()
                createEncryptedPrefs()
            } catch (retryException: Exception) {
                Log.e(TAG, "Recovery failed — encrypted storage unavailable. " +
                    "Sensitive data will NOT be stored.", retryException)
                isEncryptionActive = false
                throw IllegalStateException(
                    "EncryptedSharedPreferences initialization failed after recovery attempt. " +
                    "This may be a device-level KeyStore issue.", retryException
                )
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedKeyStoreEntry() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            Log.d(TAG, "Cleared corrupted KeyStore entry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear KeyStore entry", e)
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            Log.d(TAG, "Cleared corrupted shared prefs file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear shared prefs file", e)
        }
    }

    private val configPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // MARK: - Fresh Install Detection

    fun clearIfReinstalled() {
        val wasInstalled = configPrefs.getBoolean(KEY_APP_INSTALLED, false)

        if (!wasInstalled) {
            if (hasSession()) {
                Log.d(TAG, "App reinstalled - clearing stale data")
                clearAll()
            }
            configPrefs.edit().putBoolean(KEY_APP_INSTALLED, true).apply()
        }
    }

    // MARK: - Credentials

    fun saveCredentials(userId: String, accessToken: String?, refreshToken: String?) {
        securePrefs.edit().apply {
            putString(KEY_USER_ID, userId)
            if (accessToken != null) putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
    }

    fun getAccessToken(): String? = securePrefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)

    fun getUserId(): String? = securePrefs.getString(KEY_USER_ID, null)

    fun hasSession(): Boolean {
        val userId = getUserId() ?: return false
        return getAccessToken() != null || getApiKey() != null
    }

    // MARK: - Update Tokens (after refresh)

    fun updateTokens(accessToken: String, refreshToken: String?) {
        securePrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
    }

    // MARK: - API Key (alternative auth mode)

    fun saveApiKey(apiKey: String) {
        securePrefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? = securePrefs.getString(KEY_API_KEY, null)

    val isApiKeyAuth: Boolean
        get() = getApiKey() != null && getAccessToken() == null

    val authCredential: String?
        get() = getAccessToken() ?: getApiKey()

    val hasAuth: Boolean
        get() = authCredential != null

    fun hasRefreshCredentials(): Boolean {
        return getRefreshToken() != null
    }

    // MARK: - Host (not sensitive, stored in config prefs)

    fun saveHost(host: String) {
        configPrefs.edit().putString(KEY_HOST, host).apply()
    }

    fun getHost(): String? = configPrefs.getString(KEY_HOST, null)

    // MARK: - Custom Sync URL

    fun saveCustomSyncUrl(url: String) {
        configPrefs.edit().putString(KEY_CUSTOM_SYNC_URL, url).apply()
    }

    fun getCustomSyncUrl(): String? = configPrefs.getString(KEY_CUSTOM_SYNC_URL, null)

    // MARK: - Sync Active State

    fun setSyncActive(active: Boolean) {
        configPrefs.edit().putBoolean(KEY_SYNC_ACTIVE, active).apply()
    }

    fun isSyncActive(): Boolean = configPrefs.getBoolean(KEY_SYNC_ACTIVE, false)

    // MARK: - Tracked Types

    fun saveTrackedTypes(types: List<String>) {
        val json = org.json.JSONArray(types).toString()
        configPrefs.edit()
            .remove(KEY_TRACKED_TYPES)
            .putString(KEY_TRACKED_TYPES, json)
            .apply()
    }

    fun getTrackedTypes(): List<String> {
        try {
            val jsonStr = configPrefs.getString(KEY_TRACKED_TYPES, null)
            if (jsonStr != null && jsonStr.startsWith("[")) {
                val arr = org.json.JSONArray(jsonStr)
                return (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (_: ClassCastException) {
            // Legacy StringSet format - fall through
        } catch (_: Exception) { }
        return try {
            configPrefs.getStringSet(KEY_TRACKED_TYPES, emptySet())?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // MARK: - Sync Days Back

    fun saveSyncDaysBack(days: Int) {
        configPrefs.edit().putInt(KEY_SYNC_DAYS_BACK, days).apply()
    }

    /** Returns stored sync days back, or 0 if not set (meaning full sync / no limit). */
    fun getSyncDaysBack(): Int = configPrefs.getInt(KEY_SYNC_DAYS_BACK, 0)

    // MARK: - Health Provider

    fun saveProvider(providerId: String) {
        configPrefs.edit().putString(KEY_HEALTH_PROVIDER, providerId).apply()
    }

    fun getProvider(): String? = configPrefs.getString(KEY_HEALTH_PROVIDER, null)

    // MARK: - Notification

    fun saveNotificationTitle(title: String) {
        configPrefs.edit().putString(KEY_NOTIFICATION_TITLE, title).apply()
    }

    fun saveNotificationText(text: String) {
        configPrefs.edit().putString(KEY_NOTIFICATION_TEXT, text).apply()
    }

    fun getNotificationTitle(): String = configPrefs.getString(KEY_NOTIFICATION_TITLE, null)
        ?: NotificationConfig.CHANNEL_NAME

    fun getNotificationText(): String = configPrefs.getString(KEY_NOTIFICATION_TEXT, null)
        ?: NotificationConfig.DEFAULT_TEXT

    // MARK: - API Base URL (derived from host)

    val apiBaseUrl: String?
        get() {
            val host = getHost() ?: return null
            val h = if (host.endsWith("/")) host.dropLast(1) else host
            return "$h/api/v1"
        }

    // MARK: - Clear

    fun clearAll() {
        // Remove credential keys individually — EncryptedSharedPreferences.clear()
        // has a known Android bug where it can leave orphan encrypted entries,
        // causing hasSession() to return stale data after sign-out.
        securePrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_API_KEY)
            .commit()

        configPrefs.edit().clear().commit()
        configPrefs.edit().putBoolean(KEY_APP_INSTALLED, true).commit()
    }
}
