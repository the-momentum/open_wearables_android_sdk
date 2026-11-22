package com.samsung.android.sdk.health.data.response

import com.samsung.android.sdk.health.data.stub

class DataResponse<T> private constructor() {
    val pageToken: String? get() = stub()
    val dataList: List<T> get() = stub()
}
