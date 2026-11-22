package com.samsung.android.sdk.health.data.data

import com.samsung.android.sdk.health.data.stub
import java.time.Instant
import java.time.ZoneOffset

interface DataPoint

class HealthDataPoint private constructor() : DataPoint {
    val uid: String? get() = stub()
    val dataSource: DataSource? get() = stub()
    val startTime: Instant get() = stub()
    val endTime: Instant? get() = stub()
    val zoneOffset: ZoneOffset? get() = stub()
}

class DataSource private constructor() {
    val appId: String? get() = stub()
    val deviceId: String? get() = stub()
}

enum class ChangeType { UPSERT, DELETE }

class Change<T : DataPoint> private constructor() {
    val changeType: ChangeType get() = stub()
    val upsertDataPoint: T? get() = stub()
}

class AggregatedData<T> private constructor()

class Field<T> private constructor()
