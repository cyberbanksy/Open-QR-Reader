package com.orgista.openqr.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.orgista.openqr.logging.AppLogger
import java.net.URI

object BrowserLauncher {
    private val allowedSchemes = setOf("http", "https")

    fun sanitize(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (scheme !in allowedSchemes || host.isEmpty() || uri.userInfo != null) {
            return null
        }

        return uri.normalize().toASCIIString()
    }

    fun launchExternal(activity: Activity, rawValue: String): LaunchResult {
        val sanitized = sanitize(rawValue) ?: return LaunchResult.InvalidUrl
        return launchExternalUri(activity, Uri.parse(sanitized))
    }

    fun launchExternalUri(activity: Activity, uri: Uri): LaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)

        if (intent.resolveActivity(activity.packageManager) == null) {
            AppLogger.warn("No browser activity resolved for supported web URL")
            return LaunchResult.NoBrowser
        }

        AppLogger.info("Launching external browser for supported web URL")
        activity.startActivity(intent)
        return LaunchResult.Launched(uri)
    }
}

sealed interface LaunchResult {
    data class Launched(val uri: Uri) : LaunchResult
    data object InvalidUrl : LaunchResult
    data object NoBrowser : LaunchResult
}
