package com.openqr.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.openqr.app.browser.BrowserLauncher
import com.openqr.app.browser.LaunchResult
import com.openqr.app.camera.QuestCameraSession
import com.openqr.app.logging.AppLogger

class MainActivity : AppCompatActivity() {
    private lateinit var stateIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var cameraModeButton: ImageButton
    private lateinit var cameraPreview: ImageView
    private lateinit var previewScrim: View
    private lateinit var scannerEdgeGlow: View
    private lateinit var pulseRing: View
    private lateinit var bracketsView: ImageView
    private lateinit var sweepView: ImageView
    private lateinit var centerDot: View
    private lateinit var cameraSession: QuestCameraSession

    private val mainHandler = Handler(Looper.getMainLooper())
    private var shouldStartWhenReady = false
    private var permanentDenial = false
    private var browserHandoffComplete = false
    private var scanRequested = false
    private var cameraModeEnabled = false
    private var pendingBrowserHandoff: Runnable? = null
    private var pendingScanTimeout: Runnable? = null
    private var pulseAnimator: AnimatorSet? = null
    private var bracketsAnimator: ObjectAnimator? = null
    private var sweepAnimator: ObjectAnimator? = null
    private var edgeGlowAnimator: ObjectAnimator? = null
    private var stateIconAnimator: AnimatorSet? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        AppLogger.info("Permission result received: allGranted=${hasAllPermissions()}")
        permanentDenial = REQUIRED_PERMISSIONS.any { permission ->
            !shouldShowRequestPermissionRationale(permission) && !hasPermission(permission)
        }
        if (hasAllPermissions()) {
            permanentDenial = false
            renderReadyState()
        } else {
            renderPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenQR)
        super.onCreate(savedInstanceState)
        AppLogger.info("MainActivity created")
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.activity_main)

        stateIcon = findViewById(R.id.state_icon)
        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        cameraModeButton = findViewById(R.id.camera_mode_button)
        cameraPreview = findViewById(R.id.camera_preview)
        previewScrim = findViewById(R.id.preview_scrim)
        scannerEdgeGlow = findViewById(R.id.scanner_edge_glow)
        pulseRing = findViewById(R.id.pulse_ring)
        bracketsView = findViewById(R.id.brackets_view)
        sweepView = findViewById(R.id.sweep_view)
        centerDot = findViewById(R.id.center_dot)
        applyPreviewMode()

        cameraSession = QuestCameraSession(
            context = this,
            onQrDetected = { value -> runOnUiThread { handleQrCode(value) } },
            onStatusChanged = { messageId -> runOnUiThread { handleCameraStatus(messageId) } },
            onPreviewFrame = ::renderPreviewFrame
        )

        actionButton.setOnClickListener {
            if (hasAllPermissions()) {
                scanRequested = true
                startScanner()
            } else if (permanentDenial) {
                openAppSettings()
            } else {
                requestPermissions()
            }
        }
        cameraModeButton.setOnClickListener {
            if (hasAllPermissions()) {
                cameraModeEnabled = !cameraModeEnabled
                if (!cameraModeEnabled) {
                    clearPreviewFrame()
                }
                applyPreviewMode()
                if (cameraModeEnabled) {
                    scanRequested = true
                    startScanner()
                } else if (!scanRequested) {
                    renderReadyState()
                }
            } else if (permanentDenial) {
                openAppSettings()
            } else {
                requestPermissions()
            }
        }

        renderPermissionState()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.debug("MainActivity resumed")
        if (!browserHandoffComplete && scanRequested && hasAllPermissions()) {
            shouldStartWhenReady = true
            startScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.debug("MainActivity paused")
        clearPendingBrowserHandoff()
        clearPendingScanTimeout()
        stopVisualAnimations()
        cameraSession.stop()
        clearPreviewFrame()
        scanRequested = false
    }

    override fun onStop() {
        super.onStop()
        AppLogger.debug("MainActivity stopped")
        clearPendingBrowserHandoff()
        clearPendingScanTimeout()
        stopVisualAnimations()
        if (::cameraSession.isInitialized) {
            cameraSession.stop()
        }
        clearPreviewFrame()
        scanRequested = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::cameraSession.isInitialized) {
            return
        }

        if (!hasFocus) {
            AppLogger.debug("Window focus lost; stopping scanner early")
            clearPendingBrowserHandoff()
            clearPendingScanTimeout()
            stopVisualAnimations()
            cameraSession.stop()
            clearPreviewFrame()
            scanRequested = false
            return
        }

        if (!browserHandoffComplete && scanRequested && hasAllPermissions()) {
            AppLogger.debug("Window focus regained; resuming scanner")
            shouldStartWhenReady = true
            startScanner()
        }
    }

    private fun renderPermissionState() {
        if (hasAllPermissions()) {
            renderReadyState()
            return
        }

        renderState(
            statusRes = if (permanentDenial) R.string.status_permission_denied else R.string.status_permission_needed,
            buttonTextRes = if (permanentDenial) R.string.action_open_settings else R.string.action_grant_access,
            iconRes = R.drawable.ic_state_lock
        )
    }

    private fun requestPermissions() {
        AppLogger.info("Requesting camera permissions")
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun renderReadyState() {
        renderState(
            statusRes = null,
            buttonTextRes = R.string.action_start_scan,
            iconRes = null
        )
    }

    private fun startScanner() {
        clearPendingBrowserHandoff()
        clearPendingScanTimeout()
        if (!cameraSession.hasPassthroughCamera()) {
            AppLogger.warn("Passthrough camera not available on this device")
            renderState(
                statusRes = R.string.status_unsupported_device,
                buttonTextRes = R.string.action_unavailable,
                iconRes = R.drawable.ic_state_warning,
                buttonEnabled = false
            )
            scanRequested = false
            return
        }
        if (!hasAllPermissions()) {
            AppLogger.debug(
                "Scanner start deferred: allPermissions=${hasAllPermissions()}"
            )
            shouldStartWhenReady = true
            return
        }

        AppLogger.info("Starting scanner session")
        shouldStartWhenReady = false
        renderScanningState()
        cameraSession.resumeScanning()
        cameraSession.start()
        pendingScanTimeout = Runnable {
            AppLogger.info("Scan session timed out; releasing camera")
            cameraSession.stop()
            clearPreviewFrame()
            scanRequested = false
            renderReadyState()
        }
        mainHandler.postDelayed(pendingScanTimeout!!, SCAN_SESSION_TIMEOUT_MS)
    }

    private fun renderScanningState() {
        stopVisualAnimations()
        statusText.visibility = View.GONE
        stateIcon.visibility = View.GONE
        actionButton.visibility = View.VISIBLE
        actionButton.isEnabled = false
        actionButton.setText(R.string.action_scanning)
        cameraModeButton.visibility = View.VISIBLE
        cameraModeButton.isEnabled = false
        cameraModeButton.isSelected = cameraModeEnabled
        pulseRing.visibility = View.VISIBLE
        bracketsView.visibility = View.VISIBLE
        sweepView.visibility = View.VISIBLE
        centerDot.visibility = View.VISIBLE
        applyPreviewMode()
        startScanAnimation()
    }

    private fun handleQrCode(rawValue: String) {
        AppLogger.info("QR candidate detected")
        val sanitized = BrowserLauncher.sanitize(rawValue)
        if (sanitized == null) {
            AppLogger.warn("Rejected QR content because it is not a supported web URL")
            clearPendingScanTimeout()
            cameraSession.stop()
            scanRequested = false
            renderState(
                statusRes = R.string.status_invalid_code,
                buttonTextRes = R.string.action_rescan,
                iconRes = R.drawable.ic_state_warning
            )
            clearPreviewFrame()
            return
        }

        clearPendingScanTimeout()
        cameraSession.stop()
        scanRequested = false
        renderState(
            statusRes = null,
            buttonTextRes = R.string.action_opening,
            iconRes = R.drawable.ic_state_check,
            buttonEnabled = false
        )

        val launchUri = Uri.parse(sanitized)
        pendingBrowserHandoff = Runnable {
            when (val result = BrowserLauncher.launchExternalUri(this, launchUri)) {
                is LaunchResult.Launched -> {
                    AppLogger.info("External browser handoff succeeded")
                    browserHandoffComplete = true
                    renderState(
                        statusRes = null,
                        buttonTextRes = R.string.action_opening,
                        iconRes = R.drawable.ic_state_check,
                        buttonEnabled = false
                    )
                    mainHandler.postDelayed({ finish() }, FINISH_AFTER_HANDOFF_DELAY_MS)
                }

                LaunchResult.InvalidUrl -> {
                    AppLogger.warn("Supported URL became invalid before browser handoff")
                    scanRequested = false
                    clearPreviewFrame()
                    renderState(
                        statusRes = R.string.status_invalid_code,
                        buttonTextRes = R.string.action_rescan,
                        iconRes = R.drawable.ic_state_warning
                    )
                }

                LaunchResult.NoBrowser -> {
                    AppLogger.error("External browser handoff failed because no browser resolved")
                    scanRequested = false
                    clearPreviewFrame()
                    renderState(
                        statusRes = R.string.status_browser_missing,
                        buttonTextRes = R.string.action_rescan,
                        iconRes = R.drawable.ic_state_warning
                    )
                }
            }
            pendingBrowserHandoff = null
        }
        mainHandler.postDelayed(pendingBrowserHandoff!!, BROWSER_HANDOFF_DELAY_MS)
    }

    private fun openAppSettings() {
        AppLogger.info("Opening app settings for manual permission grant")
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun handleCameraStatus(messageId: Int) {
        when (messageId) {
            R.string.status_camera_unavailable -> renderState(
                statusRes = R.string.status_camera_unavailable,
                buttonTextRes = R.string.action_rescan,
                iconRes = R.drawable.ic_state_warning
            )

            R.string.status_unsupported_device -> renderState(
                statusRes = R.string.status_unsupported_device,
                buttonTextRes = R.string.action_unavailable,
                iconRes = R.drawable.ic_state_warning,
                buttonEnabled = false
            )
        }
    }

    private fun renderState(
        statusRes: Int?,
        buttonTextRes: Int,
        iconRes: Int?,
        buttonEnabled: Boolean = true
    ) {
        stopVisualAnimations()

        pulseRing.visibility = View.GONE
        bracketsView.visibility = View.GONE
        sweepView.visibility = View.GONE
        centerDot.visibility = View.GONE

        if (statusRes == null) {
            statusText.visibility = View.GONE
        } else {
            statusText.visibility = View.VISIBLE
            statusText.setText(statusRes)
        }

        if (iconRes == null) {
            stateIcon.visibility = View.GONE
        } else {
            stateIcon.visibility = View.VISIBLE
            stateIcon.setImageResource(iconRes)
            startIconPulse()
        }

        actionButton.visibility = View.VISIBLE
        actionButton.isEnabled = buttonEnabled
        actionButton.setText(buttonTextRes)
        cameraModeButton.visibility = View.VISIBLE
        cameraModeButton.isEnabled = true
        cameraModeButton.isSelected = cameraModeEnabled
        applyPreviewMode()
    }

    private fun applyPreviewMode() {
        val previewAlpha = if (cameraModeEnabled) 0.98f else 0f
        val scrimAlpha = if (cameraModeEnabled) 0.10f else 0f
        val glowAlpha = if (cameraModeEnabled) 0.48f else 0.78f

        cameraPreview.setRenderEffect(
            if (cameraModeEnabled) {
                RenderEffect.createBlurEffect(1.5f, 1.5f, Shader.TileMode.CLAMP)
            } else {
                null
            }
        )
        cameraPreview.alpha = previewAlpha
        previewScrim.alpha = scrimAlpha
        scannerEdgeGlow.alpha = glowAlpha
        if (::cameraModeButton.isInitialized) {
            cameraModeButton.isSelected = cameraModeEnabled
        }
    }

    private fun renderPreviewFrame(bitmap: Bitmap) {
        cameraPreview.post {
            cameraPreview.setImageBitmap(bitmap)
        }
    }

    private fun clearPreviewFrame() {
        cameraPreview.setImageDrawable(null)
    }

    private fun startScanAnimation() {
        pulseRing.scaleX = 1f
        pulseRing.scaleY = 1f
        pulseRing.alpha = 0.3f
        bracketsView.rotation = 0f
        sweepView.rotation = 0f

        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pulseRing, View.SCALE_X, 1f, 1.35f, 1f).apply {
                    duration = 3000L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = FastOutSlowInInterpolator()
                },
                ObjectAnimator.ofFloat(pulseRing, View.SCALE_Y, 1f, 1.35f, 1f).apply {
                    duration = 3000L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = FastOutSlowInInterpolator()
                },
                ObjectAnimator.ofFloat(pulseRing, View.ALPHA, 0.32f, 0.1f, 0.32f).apply {
                    duration = 3000L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    interpolator = FastOutSlowInInterpolator()
                }
            )
            start()
        }

        bracketsAnimator = ObjectAnimator.ofFloat(bracketsView, View.ROTATION, 0f, 360f).apply {
            duration = 10000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }

        sweepAnimator = ObjectAnimator.ofFloat(sweepView, View.ROTATION, 0f, 360f).apply {
            duration = 4000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }

        edgeGlowAnimator = ObjectAnimator.ofFloat(scannerEdgeGlow, View.ALPHA, 0.72f, 1f, 0.72f).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }

    private fun startIconPulse() {
        stateIcon.scaleX = 0.92f
        stateIcon.scaleY = 0.92f
        stateIcon.alpha = 0.85f
        val scaleX = ObjectAnimator.ofFloat(stateIcon, View.SCALE_X, 0.92f, 1f)
        val scaleY = ObjectAnimator.ofFloat(stateIcon, View.SCALE_Y, 0.92f, 1f)
        val alpha = ObjectAnimator.ofFloat(stateIcon, View.ALPHA, 0.85f, 1f)
        stateIconAnimator = AnimatorSet().apply {
            duration = 240L
            interpolator = FastOutSlowInInterpolator()
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun stopVisualAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bracketsAnimator?.cancel()
        bracketsAnimator = null
        sweepAnimator?.cancel()
        sweepAnimator = null
        edgeGlowAnimator?.cancel()
        edgeGlowAnimator = null
        stateIconAnimator?.cancel()
        stateIconAnimator = null
        scannerEdgeGlow.alpha = 0.82f
    }

    private fun clearPendingBrowserHandoff() {
        pendingBrowserHandoff?.let(mainHandler::removeCallbacks)
        pendingBrowserHandoff = null
    }

    private fun clearPendingScanTimeout() {
        pendingScanTimeout?.let(mainHandler::removeCallbacks)
        pendingScanTimeout = null
    }

    private companion object {
        const val BROWSER_HANDOFF_DELAY_MS = 550L
        const val FINISH_AFTER_HANDOFF_DELAY_MS = 250L
        const val SCAN_SESSION_TIMEOUT_MS = 3500L
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            "horizonos.permission.HEADSET_CAMERA"
        )
    }
}
