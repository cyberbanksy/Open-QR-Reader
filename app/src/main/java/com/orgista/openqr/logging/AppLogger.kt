package com.orgista.openqr.logging

import android.util.Log
import com.orgista.openqr.BuildConfig

object AppLogger {
    private const val TAG = "OpenQR"

    fun debug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    fun info(message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message)
        }
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
