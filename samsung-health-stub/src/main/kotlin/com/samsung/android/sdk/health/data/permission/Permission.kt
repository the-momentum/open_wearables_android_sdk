package com.samsung.android.sdk.health.data.permission

import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.stub

enum class AccessType { READ, WRITE }

class Permission private constructor() {
    companion object {
        @JvmStatic
        fun of(dataType: DataType, accessType: AccessType): Permission = stub()
    }
}
