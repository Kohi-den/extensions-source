package eu.kanade.tachiyomi.animeextension.en.hstream

import android.util.Log

object HstreamLogger {
    private const val TAG = "HSTREAM"

    fun debug(context: String, message: String) {
        Log.d(TAG, "$context: $message")
    }

    fun info(context: String, message: String) {
        Log.i(TAG, "$context: $message")
    }

    fun warn(context: String, message: String) {
        Log.w(TAG, "$context: $message")
    }

    fun error(context: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, "$context: $message", throwable)
        } else {
            Log.e(TAG, "$context: $message")
        }
    }
}
