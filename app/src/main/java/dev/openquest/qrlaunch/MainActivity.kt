package com.zephyr.qr

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.zephyr.qr.browser.BrowserLauncher
import com.zephyr.qr.browser.LaunchResult
import com.zephyr.qr.camera.QuestCameraSession
import com.zephyr.qr.logging.AppLogger

class MainActivity : AppCompatActivity() {
    private lateinit var stateIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var cameraPreview: TextureView
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
    private var pendingBrowserHandoff: Runnable? = null
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
            startScanner()
        } else {
            renderPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenQR)
        super.onCreate(savedInstanceState)
        AppLogger.info("MainActivity created")
        setContentView(R.layout.activity_main)

        stateIcon = findViewById(R.id.state_icon)
        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        cameraPreview = findViewById(R.id.camera_preview)
        scannerEdgeGlow = findViewById(R.id.scanner_edge_glow)
        pulseRing = findViewById(R.id.pulse_ring)
        bracketsView = findViewById(R.id.brackets_view)
        sweepView = findViewById(R.id.sweep_view)
        centerDot = findViewById(R.id.center_dot)
        cameraPreview.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))

        cameraSession = QuestCameraSession(
            context = this,
            textureView = cameraPreview,
            onQrDetected = ::handleQrCode,
            onStatusChanged = { messageId -> handleCameraStatus(messageId) }
        )

        actionButton.setOnClickListener {
            if (hasAllPermissions()) {
                startScanner()
            } else if (permanentDenial) {
                openAppSettings()
            } else {
                requestPermissions()
            }
        }

        cameraPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (shouldStartWhenReady && hasAllPermissions()) {
                    startScanner()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                cameraSession.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }

        if (!cameraSession.hasPassthroughCamera()) {
            AppLogger.warn("Passthrough camera not available on this device")
            renderState(
                statusRes = R.string.status_unsupported_device,
                buttonTextRes = R.string.action_unavailable,
                iconRes = R.drawable.ic_state_warning,
                buttonEnabled = false
            )
            return
        }

        renderPermissionState()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.debug("MainActivity resumed")
        if (!browserHandoffComplete && hasAllPermissions()) {
            shouldStartWhenReady = true
            startScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.debug("MainActivity paused")
        clearPendingBrowserHandoff()
        stopVisualAnimations()
        cameraSession.stop()
    }

    private fun renderPermissionState() {
        if (hasAllPermissions()) {
            startScanner()
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

    private fun startScanner() {
        clearPendingBrowserHandoff()
        if (!cameraPreview.isAvailable || !hasAllPermissions()) {
            AppLogger.debug(
                "Scanner start deferred: previewAvailable=${cameraPreview.isAvailable}, allPermissions=${hasAllPermissions()}"
            )
            shouldStartWhenReady = true
            return
        }

        AppLogger.info("Starting scanner session")
        shouldStartWhenReady = false
        renderScanningState()
        cameraSession.resumeScanning()
        cameraSession.start()
    }

    private fun renderScanningState() {
        stopVisualAnimations()
        statusText.visibility = View.GONE
        stateIcon.visibility = View.GONE
        actionButton.visibility = View.VISIBLE
        actionButton.isEnabled = false
        actionButton.setText(R.string.action_scanning)
        pulseRing.visibility = View.VISIBLE
        bracketsView.visibility = View.VISIBLE
        sweepView.visibility = View.VISIBLE
        centerDot.visibility = View.VISIBLE
        startScanAnimation()
    }

    private fun handleQrCode(rawValue: String) {
        AppLogger.info("QR candidate detected")
        val sanitized = BrowserLauncher.sanitize(rawValue)
        if (sanitized == null) {
            AppLogger.warn("Rejected QR content because it is not a supported web URL")
            cameraSession.stop()
            renderState(
                statusRes = R.string.status_invalid_code,
                buttonTextRes = R.string.action_rescan,
                iconRes = R.drawable.ic_state_warning
            )
            mainHandler.postDelayed({ startScanner() }, INVALID_CODE_RETRY_DELAY_MS)
            return
        }

        cameraSession.stop()
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
                    renderState(
                        statusRes = R.string.status_invalid_code,
                        buttonTextRes = R.string.action_rescan,
                        iconRes = R.drawable.ic_state_warning
                    )
                    mainHandler.postDelayed({ startScanner() }, INVALID_CODE_RETRY_DELAY_MS)
                }

                LaunchResult.NoBrowser -> {
                    AppLogger.error("External browser handoff failed because no browser resolved")
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

    private companion object {
        const val BROWSER_HANDOFF_DELAY_MS = 550L
        const val FINISH_AFTER_HANDOFF_DELAY_MS = 250L
        const val INVALID_CODE_RETRY_DELAY_MS = 1100L
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            "horizonos.permission.HEADSET_CAMERA"
        )
    }
}
