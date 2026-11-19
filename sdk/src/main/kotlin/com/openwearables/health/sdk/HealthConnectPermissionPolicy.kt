package com.openwearables.health.sdk

import androidx.health.connect.client.permission.HealthPermission
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decisions behind the Health Connect permission flow, kept free of Android
 * types so they can be unit tested.
 */
internal object HealthConnectPermissionPolicy {

    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    const val HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** A permission screen that answers faster than this with no grants was never shown. */
    const val INSTANT_CANCEL_MS = 800L

    /** Android 14: Health Connect ships as a system module, not the `com.google.android.apps.healthdata` app. */
    const val HEALTH_CONNECT_MODULE_SDK = 34

    /**
     * Background and history read are optional extras. Request only the ones
     * this Health Connect version supports and the user has not granted yet.
     * Requesting an unsupported one returns instantly with no grants.
     */
    fun optionalPermissionsToRequest(
        backgroundSupported: Boolean,
        historySupported: Boolean,
        granted: Set<String>,
    ): Set<String> = buildSet {
        if (backgroundSupported) add(BACKGROUND)
        if (historySupported) add(HISTORY)
    } - granted

    fun isInstantCancel(granted: Set<String>, elapsedMs: Long): Boolean =
        granted.isEmpty() && elapsedMs < INSTANT_CANCEL_MS

    /**
     * Retry on the Health Connect app screen only when the platform dialog
     * returned instantly on Android 14+ and something can handle that screen's
     * intent. On many Android 14+ devices the Health Connect app package does
     * not exist (the system module replaced it), so launching it throws
     * `ActivityNotFoundException`.
     */
    fun shouldOpenAppScreen(instantCancel: Boolean, sdkInt: Int, appScreenResolvable: Boolean): Boolean =
        instantCancel && sdkInt >= HEALTH_CONNECT_MODULE_SDK && appScreenResolvable

    /**
     * Runs the optional background/history request. Any failure is logged and
     * reported as "nothing granted": it must never turn granted data types
     * into a failed authorization.
     */
    suspend fun requestOptional(
        missing: Set<String>,
        logger: (String) -> Unit,
        request: suspend (Set<String>) -> Set<String>,
    ): Set<String> {
        if (missing.isEmpty()) return emptySet()
        return try {
            request(missing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger(
                "Health Connect background/history permission request failed " +
                    "(${e.javaClass.simpleName}: ${e.message}). Data permissions are kept."
            )
            emptySet()
        }
    }
}
