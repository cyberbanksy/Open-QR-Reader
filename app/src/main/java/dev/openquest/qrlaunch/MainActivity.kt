package dev.openquest.qrlaunch

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.openquest.qrlaunch.browser.BrowserLauncher
import dev.openquest.qrlaunch.browser.LaunchResult
import dev.openquest.qrlaunch.camera.QuestCameraSession

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var actionButton: Button
    private lateinit var cameraPreview: TextureView
    private lateinit var cameraSession: QuestCameraSession

    private var shouldStartWhenReady = false
    private var permanentDenial = false
    private var browserHandoffComplete = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
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
        setTheme(R.style.Theme_QuestQrLaunch)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        hintText = findViewById(R.id.hint_text)
        actionButton = findViewById(R.id.action_button)
        cameraPreview = findViewById(R.id.camera_preview)

        cameraSession = QuestCameraSession(
            context = this,
            textureView = cameraPreview,
            onQrDetected = ::handleQrCode,
            onStatusChanged = { statusText.setText(it) }
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
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (shouldStartWhenReady && hasAllPermissions()) {
                    startScanner()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                cameraSession.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }

        if (!cameraSession.hasPassthroughCamera()) {
            statusText.setText(R.string.status_unsupported_device)
            hintText.setText(R.string.hint_unsupported_device)
            actionButton.isEnabled = false
            actionButton.text = getString(R.string.action_unavailable)
            return
        }

        renderPermissionState()
    }

    override fun onResume() {
        super.onResume()
        if (!browserHandoffComplete && hasAllPermissions()) {
            shouldStartWhenReady = true
            startScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraSession.stop()
    }

    private fun renderPermissionState() {
        if (hasAllPermissions()) {
            statusText.setText(R.string.status_ready)
            hintText.setText(R.string.hint_ready)
            actionButton.text = getString(R.string.action_rescan)
            actionButton.isEnabled = true
            startScanner()
            return
        }

        statusText.setText(
            if (permanentDenial) R.string.status_permission_denied else R.string.status_permission_needed
        )
        hintText.setText(
            if (permanentDenial) R.string.hint_permission_denied else R.string.hint_permission_needed
        )
        actionButton.text = getString(
            if (permanentDenial) R.string.action_open_settings else R.string.action_grant_access
        )
        actionButton.isEnabled = true
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun startScanner() {
        if (!cameraPreview.isAvailable || !hasAllPermissions()) {
            shouldStartWhenReady = true
            return
        }

        shouldStartWhenReady = false
        statusText.setText(R.string.status_scanning)
        hintText.setText(R.string.hint_scanning)
        actionButton.text = getString(R.string.action_rescan)
        cameraSession.resumeScanning()
        cameraSession.start()
    }

    private fun handleQrCode(rawValue: String) {
        when (val result = BrowserLauncher.launchExternal(this, rawValue)) {
            is LaunchResult.Launched -> {
                browserHandoffComplete = true
                statusText.setText(R.string.status_opening_browser)
                cameraSession.stop()
                finishAndRemoveTask()
            }

            LaunchResult.InvalidUrl -> {
                statusText.setText(R.string.status_invalid_code)
                hintText.setText(R.string.hint_invalid_code)
                cameraSession.resumeScanning()
            }

            LaunchResult.NoBrowser -> {
                statusText.setText(R.string.status_browser_missing)
                hintText.setText(R.string.hint_browser_missing)
                cameraSession.resumeScanning()
            }
        }
    }

    private fun openAppSettings() {
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

    private companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            "horizonos.permission.HEADSET_CAMERA"
        )
    }
}
