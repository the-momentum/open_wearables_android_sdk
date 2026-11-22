package com.samsung.android.sdk.health.data

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.data.AggregatedData
import com.samsung.android.sdk.health.data.data.Change
import com.samsung.android.sdk.health.data.data.DataPoint
import com.samsung.android.sdk.health.data.device.Device
import com.samsung.android.sdk.health.data.device.DeviceType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.ChangedDataRequest
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import com.samsung.android.sdk.health.data.response.DataResponse

internal fun stub(): Nothing =
    throw UnsupportedOperationException("Samsung Health Data SDK compile-only stub")

object HealthDataService {
    @JvmStatic
    fun getStore(context: Context): HealthDataStore = stub()
}

interface HealthDataStore {
    suspend fun getGrantedPermissions(permissions: Set<Permission>): Set<Permission>
    suspend fun requestPermissions(permissions: Set<Permission>, activity: Activity): Set<Permission>
    suspend fun <T : DataPoint> readData(request: ReadDataRequest<T>): DataResponse<T>
    suspend fun <T> aggregateData(request: AggregateRequest<T>): DataResponse<AggregatedData<T>>
    suspend fun <T : DataPoint> readChanges(request: ChangedDataRequest<T>): DataResponse<Change<T>>
    fun getDeviceManager(): DeviceManager
}

interface DeviceManager {
    suspend fun getDevices(deviceType: DeviceType): List<Device>
}
