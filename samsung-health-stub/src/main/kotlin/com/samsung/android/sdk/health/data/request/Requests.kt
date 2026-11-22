package com.samsung.android.sdk.health.data.request

import com.samsung.android.sdk.health.data.data.DataPoint
import com.samsung.android.sdk.health.data.stub
import java.time.Instant
import java.time.LocalDateTime

abstract class DataType {
    abstract val name: String

    interface ChangeReadable<T : DataPoint> {
        val changedDataRequestBuilder: ChangedDataRequest.BasicBuilder<T>
    }

    class StepsType private constructor() : DataType() { override val name: String get() = stub() }
    class HeartRateType private constructor() : DataType() { override val name: String get() = stub() }
    class SleepType private constructor() : DataType() { override val name: String get() = stub() }
    class ExerciseType private constructor() : DataType() { override val name: String get() = stub() }
    class BloodOxygenType private constructor() : DataType() { override val name: String get() = stub() }
    class BloodGlucoseType private constructor() : DataType() { override val name: String get() = stub() }
    class BloodPressureType private constructor() : DataType() { override val name: String get() = stub() }
    class FloorsClimbedType private constructor() : DataType() { override val name: String get() = stub() }
    class BodyTemperatureType private constructor() : DataType() { override val name: String get() = stub() }
    class BodyCompositionType private constructor() : DataType() { override val name: String get() = stub() }
    class ActivitySummaryType private constructor() : DataType() { override val name: String get() = stub() }
    class WaterIntakeType private constructor() : DataType() { override val name: String get() = stub() }
}

interface DataTypes {
    companion object {
        @JvmField val SLEEP: DataType.SleepType = stub()
        @JvmField val STEPS: DataType.StepsType = stub()
        @JvmField val HEART_RATE: DataType.HeartRateType = stub()
        @JvmField val EXERCISE: DataType.ExerciseType = stub()
        @JvmField val BLOOD_OXYGEN: DataType.BloodOxygenType = stub()
        @JvmField val ACTIVITY_SUMMARY: DataType.ActivitySummaryType = stub()
        @JvmField val FLOORS_CLIMBED: DataType.FloorsClimbedType = stub()
        @JvmField val BLOOD_GLUCOSE: DataType.BloodGlucoseType = stub()
        @JvmField val BLOOD_PRESSURE: DataType.BloodPressureType = stub()
        @JvmField val BODY_COMPOSITION: DataType.BodyCompositionType = stub()
        @JvmField val WATER_INTAKE: DataType.WaterIntakeType = stub()
        @JvmField val BODY_TEMPERATURE: DataType.BodyTemperatureType = stub()
    }
}

enum class Ordering { ASC, DESC }

class InstantTimeFilter private constructor() {
    companion object {
        @JvmStatic
        fun of(startTime: Instant?, endTime: Instant?): InstantTimeFilter = stub()
    }
}

class LocalTimeFilter private constructor() {
    companion object {
        @JvmStatic
        fun since(startTime: LocalDateTime): LocalTimeFilter = stub()

        @JvmStatic
        fun of(startTime: LocalDateTime?, endTime: LocalDateTime?): LocalTimeFilter = stub()
    }
}

class ReadDataRequest<T : DataPoint> private constructor()

class AggregateRequest<T> private constructor()

class ChangedDataRequest<T : DataPoint> private constructor() {
    class BasicBuilder<T : DataPoint> private constructor() {
        fun setPageSize(pageSize: Int): BasicBuilder<T> = stub()
        fun setPageToken(pageToken: String): BasicBuilder<T> = stub()
        fun setChangeTimeFilter(changeTimeFilter: InstantTimeFilter): BasicBuilder<T> = stub()
        fun build(): ChangedDataRequest<T> = stub()
    }
}
