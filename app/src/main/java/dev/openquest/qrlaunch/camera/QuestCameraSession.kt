package com.zephyr.qr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.zephyr.qr.R
import com.zephyr.qr.logging.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QuestCameraSession(
    context: Context,
    private val textureView: TextureView,
    private val onQrDetected: (String) -> Unit,
    private val onStatusChanged: (Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val decoder = QrCodeDecoder()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scanInProgress = AtomicBoolean(false)
    private val frameInFlight = AtomicBoolean(false)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSize: Size? = null

    fun resumeScanning() {
        AppLogger.debug("Scanner resumed")
        scanInProgress.set(false)
    }

    fun hasPassthroughCamera(): Boolean = findPassthroughConfig() != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!textureView.isAvailable || captureSession != null || cameraDevice != null) {
            AppLogger.debug("Ignoring scanner start because a session is already active or preview is unavailable")
            return
        }

        val config = findPassthroughConfig()
        if (config == null) {
            AppLogger.warn("No passthrough Camera2 configuration found")
            dispatchStatus(R.string.status_unsupported_device)
            return
        }

        startBackgroundThread()
        activeSize = chooseSize(config.outputSizes)
        val size = activeSize ?: run {
            AppLogger.error("Unable to select a passthrough camera output size")
            dispatchStatus(R.string.status_camera_unavailable)
            return
        }
        AppLogger.info("Opening passthrough camera ${config.cameraId} at ${size.width}x${size.height}")

        val surfaceTexture = textureView.surfaceTexture ?: run {
            AppLogger.error("Preview surface texture was unavailable when starting the camera")
            dispatchStatus(R.string.status_camera_unavailable)
            return
        }

        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        applyPreviewTransform(size)

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!frameInFlight.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                cameraExecutor.execute {
                    try {
                        image.use { latest ->
                            val result = decoder.decode(latest)
                            if (result != null && scanInProgress.compareAndSet(false, true)) {
                                AppLogger.info("QR decode succeeded")
                                textureView.post {
                                    onQrDetected(result.text)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        AppLogger.error("QR decode pipeline failed", t)
                    } finally {
                        frameInFlight.set(false)
                    }
                }
            }, backgroundHandler)
        }

        cameraManager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                AppLogger.info("Passthrough camera opened")
                cameraDevice = camera
                createSession(camera, surfaceTexture)
            }

            override fun onDisconnected(camera: CameraDevice) {
                AppLogger.warn("Passthrough camera disconnected")
                camera.close()
                stop()
                dispatchStatus(R.string.status_camera_unavailable)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                AppLogger.error("Passthrough camera error code=$error")
                camera.close()
                stop()
                dispatchStatus(R.string.status_camera_unavailable)
            }
        }, backgroundHandler)
    }

    fun stop() {
        AppLogger.debug("Stopping scanner session")
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        scanInProgress.set(false)
        frameInFlight.set(false)
        stopBackgroundThread()
    }

    private fun createSession(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        val previewSurface = Surface(surfaceTexture)
        val imageSurface = imageReader?.surface ?: return

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    OutputConfiguration(previewSurface),
                    OutputConfiguration(imageSurface)
                ),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        AppLogger.info("Camera capture session configured")
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(imageSurface)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        session.setRepeatingRequest(request, null, backgroundHandler)
                        dispatchStatus(R.string.status_scanning)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        AppLogger.error("Camera capture session configuration failed")
                        stop()
                        dispatchStatus(R.string.status_camera_unavailable)
                    }
                }
            )
        )
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            return
        }
        AppLogger.debug("Starting camera background thread")
        backgroundThread = HandlerThread("quest-camera").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    private fun stopBackgroundThread() {
        AppLogger.debug("Stopping camera background thread")
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun chooseSize(sizes: Array<Size>): Size? {
        return sizes
            .sortedByDescending { it.width * it.height }
            .firstOrNull { it.width <= 1280 && it.height <= 1280 }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    private fun applyPreviewTransform(bufferSize: Size) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) {
            return
        }

        val scale = maxOf(
            viewWidth / bufferSize.width.toFloat(),
            viewHeight / bufferSize.height.toFloat()
        )
        val scaledWidth = bufferSize.width * scale
        val scaledHeight = bufferSize.height * scale
        val dx = (viewWidth - scaledWidth) / 2f
        val dy = (viewHeight - scaledHeight) / 2f

        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        textureView.setTransform(matrix)
    }

    private fun findPassthroughConfig(): CameraConfig? {
        val sourceKey = CameraCharacteristics.Key<Int>(META_CAMERA_SOURCE, Int::class.javaObjectType)
        val positionKey = CameraCharacteristics.Key<Int>(META_CAMERA_POSITION, Int::class.javaObjectType)

        return cameraManager.cameraIdList
            .mapNotNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (characteristics.get(sourceKey) != PASSTHROUGH_SOURCE) {
                    return@mapNotNull null
                }

                val outputSizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?: return@mapNotNull null

                CameraConfig(
                    cameraId = cameraId,
                    position = characteristics.get(positionKey),
                    outputSizes = outputSizes
                )
            }
            .sortedWith(compareBy<CameraConfig> { it.position ?: Int.MAX_VALUE }.thenBy { it.cameraId })
            .firstOrNull()
    }

    private fun dispatchStatus(messageId: Int) {
        textureView.post {
            onStatusChanged(messageId)
        }
    }

    private data class CameraConfig(
        val cameraId: String,
        val position: Int?,
        val outputSizes: Array<Size>
    )

    private companion object {
        const val META_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source"
        const val META_CAMERA_POSITION = "com.meta.extra_metadata.position"
        const val PASSTHROUGH_SOURCE = 0
    }
}
