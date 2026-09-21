# Changelog

## 0.13.0 (unreleased)

* **Optional mTLS client certificates** (#34, #16): the shared OkHttp client can present a client cert from the Android KeyChain (`pickClientCertificate` / `clearClientCertificate`) or a bundled `.p12` in assets. No cert configured = plain TLS, so existing backends keep working. The HTTP client reloads at runtime when the alias changes.
* **`redeemInvitationCode(host, code)`**: POST `{host}/api/v1/invitation-code/redeem` through the same OkHttp client, so redeem also sends the client cert when mTLS is configured.
* **Upload metrics**: streamed sync payloads now count the actual bytes sent instead of the response `Content-Length`.
* **Repo cleanup**: stop tracking accidentally committed `sdk/build/` artifacts (already in `.gitignore`).

## 0.12.0

* **Health Connect: power / speed / cadence / total calories** (#12): the SDK now reads `PowerRecord`, `SpeedRecord`, `CyclingPedalingCadenceRecord`, and `TotalCaloriesBurnedRecord` when the host app requests them. Previously those type IDs were silently dropped even when Health Connect had the data (Peloton, Strava, Zwift, Garmin). Emitted as `POWER` / `SPEED` / `CYCLING_PEDALING_CADENCE` / `TOTAL_CALORIES_BURNED`.
* **Health Connect workouts: session aggregates** (#13): `readWorkouts` no longer sends only `duration`. Each exercise session now side-queries HR / power / speed / distance / total calories / cadence in the session window and attaches min/avg/max stats (`averageHeartRate`, `averageRunningPower`, `distance`, `totalCalories`, …) so `workout_details` can populate. Time-series `samples` on the workout stay empty.
* **Health Connect pagination** (#14): descending full-export cursor now uses `startTime - 1ms` so range records (steps, distance, workouts, …) are not re-included at page boundaries. `countRecordsForTypes` page size capped at Health Connect’s 5000 limit (was 10000), so sync-start counts no longer under-report.

## 0.11.2

* **New `getSyncStatus()` fields**: `initialExportDone` (Bool) and `isSyncing` (Bool) — allows apps to show progress UI during the initial historical export.

## 0.11.1

* **Fixed JVM signature clash**: removed the redundant `setLogLevel` setter that clashed with the `logLevel` property's generated JVM signature.

## 0.11.0

* **Public `setLogLevel(level)` method** added for parity with iOS. Convenience wrapper around the existing `logLevel` property, intended for cross-platform bridges (React Native, Flutter) and Java callers. The `logLevel` property remains available.
* **Fixed published Maven version**: the `:sdk` module publication was still declaring `0.9.0` in `build.gradle.kts` despite the `SDK_VERSION` constant being bumped. The published POM now matches the git tag and `SyncDefaults.SDK_VERSION`.

## 0.10.0

* **Sync telemetry**: new `/logs` endpoint integration for initial full sync diagnostics.
  - `historical_data_sync_start` event sent before the first payload with per-type record counts, time range, and device state.
  - `historical_data_type_sync_end` event sent per data type as each completes, with record count, duration, success status, and device state snapshot.
  - Device state includes battery level/state, thermal state, low power mode, and RAM usage.
  - Types with zero records are excluded from end events.
  - Type names in logs now match payload record types (e.g. `STEP_COUNT`, `HEART_RATE`).
* **Auto full export on first sync**: `syncNow` now automatically upgrades to full export when the initial sync hasn't been completed, matching iOS behavior.
* **Fixed OkHttp connection leaks**: response bodies are now properly closed in sync payload uploads, token refresh retries, and log requests.

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
