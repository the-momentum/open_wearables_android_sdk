package com.openwearables.health.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The Samsung Health Data SDK is compile-only. Apps that use Samsung Health add the
 * AAR they download from Samsung, so code that touches Samsung classes checks
 * [isPresent] first and apps without the AAR never load them.
 */
internal object SamsungHealthSdk {
    private const val TAG = "OpenWearablesHealthSDK"
    private const val ENTRY_CLASS = "com.samsung.android.sdk.health.data.HealthDataService"

    /** [OpenWearablesHealthSDK.getAvailableProviders] reason when the AAR is missing. */
    const val UNAVAILABLE_REASON_SDK_MISSING = "samsungSdkMissing"

    const val MISSING_MESSAGE =
        "Samsung Health Data SDK not found. To use the \"samsung\" provider, download the SDK " +
            "from Samsung and add the .aar to your app module: " +
            "https://openwearables.io/docs/sdk/android/integration"

    val isPresent: Boolean by lazy {
        try {
            Class.forName(ENTRY_CLASS, false, SamsungHealthSdk::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Call when the host asks for Samsung Health, via `setProvider("samsung")` or a
     * stored provider. Debuggable apps throw, so a missing AAR shows up during
     * development. Release builds log and report Samsung Health as unavailable.
     */
    fun checkRequested(
        isDebuggable: Boolean,
        log: (String) -> Unit,
        isPresent: Boolean = this.isPresent,
    ): Boolean {
        if (isPresent) return true
        if (isDebuggable) throw IllegalStateException(MISSING_MESSAGE)
        Log.w(TAG, MISSING_MESSAGE)
        log(MISSING_MESSAGE)
        return false
    }
}
