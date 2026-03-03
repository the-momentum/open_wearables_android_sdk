# Open Wearables Android SDK

Native Android SDK for reading and syncing health data from **Samsung Health** and **Health Connect** to your backend.

> **Part of [Open Wearables](https://github.com/the-momentum/open-wearables)** - a self-hosted platform to unify wearable health data through one AI-ready API.

## Features

- **Dual Provider Support** - Samsung Health and Health Connect through a unified API
- **Background Sync** - WorkManager-based periodic sync with foreground service support
- **Resumable Sync** - Interrupted syncs resume from where they left off
- **Token Auth + Auto-Refresh** - Access/refresh tokens with automatic 401 retry
- **API Key Auth** - Simpler alternative for server-to-server setups
- **Incremental Updates** - Anchored queries ensure only new data is synced
- **Encrypted Storage** - Credentials stored in Android EncryptedSharedPreferences
- **Unified Payload** - All provider data normalized to a single format

## Supported Data Types

| Category | Types |
|----------|-------|
| **Activity** | steps, distanceWalkingRunning, distanceCycling, flightsClimbed |
| **Energy** | activeEnergy, basalEnergy |
| **Heart** | heartRate, restingHeartRate, heartRateVariabilitySDNN, vo2Max, oxygenSaturation |
| **Respiratory** | respiratoryRate |
| **Body** | bodyMass, height, bodyFatPercentage, leanBodyMass, bodyTemperature |
| **Blood** | bloodGlucose, bloodPressure (systolic + diastolic) |
| **Nutrition** | water |
| **Sleep** | sleep (with stages) |
| **Workouts** | workout (with segments, laps, route, samples) |

---

## Requirements

- **minSdk**: 29 (Android 10)
- **compileSdk**: 36
- **Kotlin**: 2.1.0
- **Java**: 17

### For Samsung Health

- Samsung device
- Samsung Health app v6.30.2+
- Samsung Health Data SDK is bundled in the repository (no manual download needed)

### For Health Connect

- Health Connect app installed (built-in on Android 14+)
- Activity extending `ComponentActivity` (needed for permission dialog launcher)

---

## Installation

### Step 1: Clone and publish to Maven Local

```bash
git clone https://github.com/the-momentum/open_wearables_android_sdk.git
cd open_wearables_android_sdk
./gradlew :sdk:publishReleasePublicationToMavenLocal
```

This publishes `com.openwearables.health:sdk:0.1.0` and the Samsung Health SDK to your local Maven repository (`~/.m2/repository/`).

### Step 2: Add dependency

In your app's `build.gradle.kts`:

```kotlin
repositories {
    google()
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.openwearables.health:sdk:0.1.0")
}
```

> **Future**: Once published to Maven Central, you won't need `mavenLocal()` - just the dependency line.

### Step 3: Configure AndroidManifest.xml

The SDK's manifest is automatically merged into your app via manifest merger. It declares all required permissions (Health Connect, foreground service, internet, etc.) and the WorkManager foreground service.

You need to add Health Connect permission rationale activities to your app's manifest:

```xml
<application>
    <!-- Your activities... -->

    <!-- Health Connect: permissions rationale (Android 14+) -->
    <activity-alias
        android:name="ViewPermissionUsageActivity"
        android:exported="true"
        android:targetActivity=".MainActivity"
        android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
        <intent-filter>
            <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
            <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
        </intent-filter>
    </activity-alias>

    <!-- Health Connect: permissions rationale (Android 12-13) -->
    <activity-alias
        android:name="ShowPermissionRationaleActivity"
        android:exported="true"
        android:targetActivity=".MainActivity">
        <intent-filter>
            <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
        </intent-filter>
    </activity-alias>
</application>
```

### Samsung Health

The Samsung Health Data SDK `.aar` is bundled in the repository at `sdk/libs/maven/` - no manual download needed.

When you run `publishReleasePublicationToMavenLocal`, the Samsung SDK is also installed to `~/.m2/repository/` so consuming apps can resolve it.

For testing on Samsung devices, enable Developer Mode in Samsung Health:
Settings > About Samsung Health > tap version 10 times > Developer options > enable Developer mode.

For production, you need to register as a Samsung Health partner at the [Samsung Developer Portal](https://developer.samsung.com/health/android/overview.html).

---

## Usage

### Initialize

```kotlin
// In Application.onCreate() or Activity.onCreate()
val sdk = OpenWearablesHealthSDK.initialize(applicationContext)
```

The SDK is a singleton - call `initialize()` once, then use `getInstance()` anywhere:

```kotlin
val sdk = OpenWearablesHealthSDK.getInstance()
```

### Configure

```kotlin
sdk.configure(
    host = "https://api.example.com",
    customSyncUrl = null  // optional: override the sync endpoint
)
```

### Set Activity (required for permission dialogs)

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    OpenWearablesHealthSDK.getInstance().setActivity(this)
}

override fun onDestroy() {
    super.onDestroy()
    OpenWearablesHealthSDK.getInstance().setActivity(null)
}
```

### Sign In

```kotlin
// With tokens (supports auto-refresh on 401)
sdk.signIn(
    userId = "user-123",
    accessToken = "Bearer eyJ...",
    refreshToken = "refresh-token",
    apiKey = null
)

// Or with API key (simpler, no auto-refresh)
sdk.signIn(
    userId = "user-123",
    accessToken = null,
    refreshToken = null,
    apiKey = "your-api-key"
)
```

### Select Provider

```kotlin
val providers = sdk.getAvailableProviders()
// [{"id": "samsung", "displayName": "Samsung Health", "isAvailable": true},
//  {"id": "google", "displayName": "Health Connect", "isAvailable": true}]

sdk.setProvider("samsung")  // or "google"
```

### Request Permissions

```kotlin
val authorized = sdk.requestAuthorization(
    listOf("steps", "heartRate", "sleep", "workout", "oxygenSaturation")
)
```

### Start Background Sync

```kotlin
sdk.startBackgroundSync()
```

This performs an initial sync immediately, then schedules periodic syncs every 5 minutes via WorkManager.

### Lifecycle (important for foreground/background behavior)

```kotlin
// In your Activity or use ProcessLifecycleOwner
sdk.onForeground()   // resumes interrupted syncs
sdk.onBackground()   // schedules expedited background sync
```

### Listen to Logs and Auth Errors

```kotlin
sdk.logListener = { message ->
    Log.d("MyApp", "[SDK] $message")
}

sdk.authErrorListener = { statusCode, message ->
    Log.e("MyApp", "Auth error $statusCode: $message")
    // Navigate to login screen, etc.
}
```

### Sync Control

```kotlin
// Trigger sync immediately
sdk.syncNow()

// Check sync status
val status = sdk.getSyncStatus()
// {"hasResumableSession": true, "sentCount": 1500, "completedTypes": 3, ...}

// Resume interrupted sync
sdk.resumeSync()

// Reset anchors (forces full re-export)
sdk.resetAnchors()

// Stop background sync
sdk.stopBackgroundSync()
```

### Sign Out

```kotlin
sdk.signOut()
```

### Cleanup

```kotlin
// When your app is shutting down
sdk.destroy()
```

---

## Sync Architecture

```
┌─────────────────────────────────────────────────┐
│ OpenWearablesHealthSDK (facade / singleton)      │
│  ├── configure, signIn, signOut                  │
│  ├── requestAuthorization                        │
│  ├── startBackgroundSync / stopBackgroundSync     │
│  └── provider management                         │
├─────────────────────────────────────────────────┤
│ HealthDataProvider (interface)                    │
│  ├── SamsungHealthManager (Samsung Health SDK)    │
│  └── HealthConnectManager (Health Connect API)   │
├─────────────────────────────────────────────────┤
│ SyncManager                                      │
│  ├── Reads data via HealthDataProvider            │
│  ├── Chunks & uploads as Unified Health Payload   │
│  ├── Manages anchors for incremental sync         │
│  ├── Token refresh on 401                         │
│  └── Resumable sync state (file-based)            │
├─────────────────────────────────────────────────┤
│ HealthSyncWorker (WorkManager CoroutineWorker)   │
│  └── Runs sync in background as foreground svc   │
├─────────────────────────────────────────────────┤
│ SecureStorage (EncryptedSharedPreferences)        │
│  └── Credentials, config, anchors                │
└─────────────────────────────────────────────────┘
```

### Sync Endpoint

Data is POSTed to `{host}/api/v1/sdk/users/{userId}/sync` (or `customSyncUrl` if configured) as JSON:

```json
{
  "provider": "samsung",
  "sdkVersion": "0.1.0",
  "syncTimestamp": "2025-01-15T10:30:00Z",
  "data": {
    "records": [
      {
        "id": "abc-123",
        "type": "HEART_RATE",
        "startDate": "2025-01-15T10:00:00Z",
        "endDate": "2025-01-15T10:00:00Z",
        "value": 72.0,
        "unit": "bpm",
        "source": {
          "appId": "com.samsung.health",
          "deviceManufacturer": "Samsung",
          "deviceModel": "Galaxy Watch 6",
          "deviceType": "watch"
        }
      }
    ],
    "workouts": [],
    "sleep": []
  }
}
```

---

## Building from Source

```bash
# Assemble release AAR
./gradlew :sdk:assembleRelease

# Publish to Maven Local
./gradlew :sdk:publishReleasePublicationToMavenLocal

# Run tests
./gradlew :sdk:test
```

The AAR output is at `sdk/build/outputs/aar/sdk-release.aar`.

---

## License

MIT License
