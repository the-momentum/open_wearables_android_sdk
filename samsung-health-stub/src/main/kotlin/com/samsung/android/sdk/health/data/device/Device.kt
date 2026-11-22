package com.samsung.android.sdk.health.data.device

import com.samsung.android.sdk.health.data.stub

interface DeviceType

enum class DeviceGroup : DeviceType { OTHER, MOBILE, WATCH, RING, BAND, ACCESSORY }

class Device private constructor() {
    val id: String? get() = stub()
    val manufacturer: String? get() = stub()
    val model: String? get() = stub()
    val name: String? get() = stub()
    val deviceType: DeviceType get() = stub()
}
