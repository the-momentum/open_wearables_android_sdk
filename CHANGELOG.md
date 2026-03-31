# Changelog

## 0.9.0

* **Smarter token refresh error handling**: token refresh failures are now classified as either `AUTH_FAILURE` (refresh token rejected with 401/403) or `NETWORK_ERROR` (timeout, DNS, 5xx). Only genuine auth failures trigger user disconnect — transient network errors during refresh no longer force sign-out, allowing the SDK's retry mechanism to recover automatically.

## 0.8.0

* **Breaking: Foreground service type changed from `dataSync` to `health`**. Apps must update their Play Console FGS declaration from "Data Sync" to "Health" and remove any manual `<service>` declaration with `foregroundServiceType="dataSync"` from their manifest.
* Replaced `FOREGROUND_SERVICE_DATA_SYNC` permission with `FOREGROUND_SERVICE_HEALTH`.
* Added `HIGH_SAMPLING_RATE_SENSORS` permission to satisfy the `health` FGS runtime prerequisite.
* Updated `HealthSyncWorker.getForegroundInfo()` to pass `FOREGROUND_SERVICE_TYPE_HEALTH`.

## 0.7.0

* **Combined payloads**: all health data types are now merged into a single payload per sync round instead of separate requests per type.
* **Interleaved sync**: data is fetched round-robin across all types (newest to oldest) instead of sequentially type-by-type.
* **Streaming JSON serialization**: replaced in-memory `JsonElement` tree with `android.util.JsonWriter` streaming directly to OkHttp `RequestBody`, fixing `OutOfMemoryError` on large datasets.
* **Bearer prefix normalization**: access tokens returned by the refresh endpoint without the `Bearer ` prefix are now handled correctly.
* **Sign-out reliability**: `EncryptedSharedPreferences.clear()` replaced with individual key removal using `.commit()` to work around a known Android bug where `clear()` may not reliably remove all encrypted entries.
* **`setSyncNotification()`**: customize the foreground notification title and text shown during background sync via WorkManager.
* **Cleaned up logging**: removed verbose Samsung Health SDK reflection logs, per-record debug output, and all token/credential values from log output. Logs now show only essential sync lifecycle events, payload summaries, and HTTP statuses.

## 0.6.0

* Initial tracked release.
