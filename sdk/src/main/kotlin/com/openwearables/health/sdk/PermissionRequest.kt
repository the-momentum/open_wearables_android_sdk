package com.openwearables.health.sdk

internal fun permissionRequestGranted(
    requested: Set<String>,
    alreadyGranted: Set<String>,
    newlyGranted: Set<String>
): Boolean = requested.all { it in alreadyGranted || it in newlyGranted }
