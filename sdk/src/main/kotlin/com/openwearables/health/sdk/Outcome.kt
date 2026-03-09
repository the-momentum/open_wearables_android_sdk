package com.openwearables.health.sdk

/**
 * A generic result wrapper that forces callers to handle both success and error cases.
 * Replaces silent exception swallowing throughout the SDK.
 */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Error(val exception: Throwable, val message: String? = null) : Outcome<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Error -> null
    }

    fun exceptionOrNull(): Throwable? = when (this) {
        is Success -> null
        is Error -> exception
    }

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (Throwable, String?) -> R
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Error -> onError(exception, message)
    }
}
