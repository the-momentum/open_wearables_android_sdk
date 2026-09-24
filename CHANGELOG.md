# Changelog

## 0.13.0

* **Optional mTLS client certificates** (#34, #16): the shared OkHttp client can present a client cert from the Android KeyChain (`pickClientCertificate` / `clearClientCertificate`) or a bundled `.p12` in assets. No cert configured = plain TLS, so existing backends keep working. The HTTP client reloads at runtime when the alias changes.
* **`redeemInvitationCode(host, code)`**: POST `{host}/api/v1/invitation-code/redeem` through the same OkHttp client, so redeem also sends the client cert when mTLS is configured.
* **Upload metrics**: streamed sync payloads now count the actual bytes sent instead of the response `Content-Length`.
* **Repo cleanup**: stop tracking accidentally committed `sdk/build/` artifacts (already in `.gitignore`).
* **Reject implausible timestamps** (#35, #28, #25): Samsung/Health Connect records with negative or far-future timestamps (observed: Fit3 heart-rate samples dated 2033–2105) are dropped at the read funnel before they can reach payloads, cursors, or persisted sync anchors. Default future-skew tolerance is 5 minutes.
* **Samsung watch device type** (#29): when Samsung reports a wearable as `MOBILE`, infer `watch` from the device name (`Galaxy Watch7`) or the Watch7+ model prefix (`SM-L*`). The group used in `getDevices()` is preferred over `Device.getGroup()` so a watch listed under both MOBILE and WATCH stays `watch`.
* **Health Connect change tokens** (#19): incremental HC sync uses `getChangesToken` / `getChanges` (write order). Backfilled records with an old `startTime` are no longer skipped. After a successful full-export type the token is minted at "now"; mint failure leaves the type incomplete so the next run retries instead of silently dropping the change stream. A missing or expired token does a one-time 30-day catch-up (HC token lifetime) then resumes change tracking. Deletions are counted for paging, not uploaded.
* **`signIn()` resets anchors**: every sign-in clears anchors and change tokens and the next sync is a full export. Skipping the reset for the same user is deferred until sync can follow a sync status from the backend.
* **`signOut()` reports disconnect** (#24): `DELETE {host}/api/v1/users/{userId}/connections/{samsung|google}` is sent before credentials are cleared. Best effort with a 10s timeout; network / 401 / timeout still sign out locally. Nothing is sent when there is no session.
* **Background / foreground sync** (#20): `startBackgroundSync` now kicks the first sync immediately instead of waiting for WorkManager. Periodic work uses `KEEP` so app restarts do not reset the 15-minute timer (OxygenOS/OnePlus). Workers retry when the device is locked (Keystore), connect the provider, fall back to the stored host, use 100-record background chunks, and re-enqueue expedited work if the run did not finish. Process lifecycle is observed inside the SDK so `onForeground` resumes an incomplete export without the host calling it. `stopBackgroundSync` also cancels the expedited one-shot.
* **Health Connect sync no longer pauses on unsupported types**: types with no Health Connect record (for example `walkingSpeed`) were treated as a failed change-token mint and stopped the whole export after the first uploaded round. Those types are dropped from the tracked set, and a type with no change stream is marked complete so the rest of the export continues.
* **Health Connect request quota**: the sync-start log no longer pages through the whole store to count records. That scan exhausted the Health Connect quota before any payload was uploaded. Start-log counts are already-sent totals; a fresh session reports 0. Workout session side-queries are capped at 24 per page, skip a metric after one missing-permission error, and pause for 15 minutes after a rate limit, so the rest of the sync can still read and upload.
* **Provider survives sign-out**: `signOut()` cleared the saved health provider along with the session. The next `configure()` then auto-selected Samsung Health whenever that app was installed, even after the user had chosen Health Connect. The provider preference is kept across sign-out.
* **Health Connect history read**: full export stopped about 30 days back (steps in one run: 2289 records, oldest around mid-August) because the SDK never requested `READ_HEALTH_DATA_HISTORY`. Without that permission Health Connect only returns the recent window, and the short last page was treated as the end of the store. The permission is now requested with the other Health Connect reads. Granting it for the first time clears anchors so the next sync re-exports the older records; a later incremental sync would not, because the finished export already minted change tokens at "now".
* **Samsung workout activity type** (#27): Samsung exercise codes (`11007` = cycling) are sent as the same names Health Connect already uses (`CYCLING`, `RUNNING`, …). A raw code was stored as `other`.
* **Samsung body composition numbers** (#26): weight, height, BMI, and fat fields are read as any number. Samsung sometimes returns `Integer` instead of `Float`; the old `Float` cast threw and the whole record was dropped.
* **Samsung Health changes**: incremental Samsung sync uses `readChanges`, filtered by when the record was written. A sample inserted later with an older start time is no longer skipped. Steps and active energy have no change stream and stay on a timestamp cursor. Blood pressure and body-composition ids that share one Samsung record are read once.
* **Switching provider clears anchors**: choosing the other health store (Samsung Health or Health Connect) drops the previous cursors and starts a full export. A running sync cannot write those cursors back after the switch. Setting the same provider again does not reset.
* **Faster Health Connect full export**: alias type ids that share one record (`distanceWalkingRunning` / `distanceCycling`, the three blood-pressure ids, speed / power / hydration) were each read and uploaded separately. A round now reads up to four Health Connect types at once, reads the next page while the current one uploads, and writes records straight to the JSON body instead of copying them into a Map tree first. The page size stays 2000.
* **Faster full export while the app is open**: a WorkManager run always used the 100-record background page, split across every incomplete type (often 3–6 records per Health Connect call). Quota is charged per call, so a few thousand records took a long chain of tiny rounds, then a rate-limit was treated as “all data sent”, the change-token mint failed, and the worker restarted immediately and hit the limit again. An open app now uses the 2000-record page. A rate-limited read leaves the cursor unchanged and the next run waits 2 minutes.

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
