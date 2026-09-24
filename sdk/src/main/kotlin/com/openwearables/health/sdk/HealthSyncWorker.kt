package com.openwearables.health.sdk

import android.content.Context
import android.os.UserManager
import androidx.work.*

/**
 * WorkManager worker for background health data synchronization.
 *
 * Scheduled as a [PeriodicWorkRequest] by [SyncManager]. The worker does NOT
 * manually schedule the next periodic run — WorkManager handles that. If this
 * run does not finish the export it enqueues an expedited follow-up.
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_HOST = "host"
        const val KEY_CUSTOM_SYNC_URL = "customSyncUrl"
    }

    override suspend fun doWork(): Result {
        if (!isUserUnlocked()) {
            android.util.Log.w("HealthSyncWorker", "Device locked — retrying after unlock")
            return Result.retry()
        }

        val secureStorage = try {
            SecureStorage(applicationContext)
        } catch (e: Exception) {
            android.util.Log.e("HealthSyncWorker", "Secure storage unavailable (device locked?)", e)
            return Result.retry()
        }

        if (!secureStorage.isSyncActive() || !secureStorage.hasAuth) {
            android.util.Log.d("HealthSyncWorker", "Sync inactive or no credentials — skipping")
            return Result.success()
        }

        if (SyncManager.processSyncLock.get()) {
            android.util.Log.d("HealthSyncWorker", "Skipping — another sync is already running")
            return Result.success()
        }

        val host = inputData.getString(KEY_HOST) ?: secureStorage.getHost()
        if (host.isNullOrEmpty()) return Result.failure()
        val customSyncUrl = inputData.getString(KEY_CUSTOM_SYNC_URL) ?: secureStorage.getCustomSyncUrl()

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            android.util.Log.w("HealthSyncWorker", "Could not promote to foreground: ${e.message}")
        }

        val dispatchers = DefaultDispatcherProvider()
        val provider = createProvider(applicationContext, secureStorage, dispatchers)
        val syncManager = SyncManager(
            applicationContext, secureStorage, provider, dispatchers,
            { android.util.Log.d("HealthSyncWorker", it) }
        )

        return try {
            val trackedTypes = secureStorage.getTrackedTypes()
            provider.setTrackedTypes(trackedTypes)
            if (!provider.connect()) {
                android.util.Log.w("HealthSyncWorker", "Provider not available, retrying")
                return Result.retry()
            }

            android.util.Log.d("HealthSyncWorker", "Background sync (provider: ${provider.providerId})")
            syncManager.syncNow(host, customSyncUrl, fullExport = false, background = true)

            val hitQuota = SyncManager.quotaBackoffPending.getAndSet(false)
            if (hitQuota) {
                syncManager.scheduleExpeditedSync(
                    host, customSyncUrl, initialDelayMs = SyncDefaults.QUOTA_BACKOFF_MS,
                )
            } else if (syncManager.hasResumableSyncSession() || !syncManager.hasCompletedInitialExport()) {
                syncManager.scheduleExpeditedSync(host, customSyncUrl)
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HealthSyncWorker", "Sync failed", e)
            Result.retry()
        }
    }

    private fun isUserUnlocked(): Boolean {
        return try {
            val um = applicationContext.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.isUserUnlocked ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun createProvider(
        context: Context,
        storage: SecureStorage,
        dispatchers: DispatcherProvider
    ): HealthDataProvider {
        val providerId = storage.getProvider()
        val log: (String) -> Unit = { android.util.Log.d("HealthSyncWorker", it) }
        return when (providerId) {
            ProviderIds.GOOGLE -> HealthConnectManager(context, null, dispatchers, log)
            else -> SamsungHealthManager(context, null, dispatchers, log)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val secureStorage = SecureStorage(applicationContext)
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, NotificationConfig.CHANNEL_ID)
            .setContentTitle(secureStorage.getNotificationTitle())
            .setContentText(secureStorage.getNotificationText())
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationConfig.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            ForegroundInfo(NotificationConfig.NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NotificationConfig.CHANNEL_ID,
                NotificationConfig.CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = NotificationConfig.CHANNEL_DESCRIPTION }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
