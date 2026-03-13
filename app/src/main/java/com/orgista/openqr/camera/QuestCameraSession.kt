package com.orgista.openqr.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
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
import android.os.SystemClock
import android.util.Size
import com.orgista.openqr.R
import com.orgista.openqr.logging.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QuestCameraSession(
    context: Context,
    private val onQrDetected: (String) -> Unit,
    private val onStatusChanged: (Int) -> Unit,
    private val onPreviewFrame: (Bitmap) -> Unit
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val decoder = QrCodeDecoder()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val decodeEnabled = AtomicBoolean(false)
    private val scanInProgress = AtomicBoolean(false)
    private val frameInFlight = AtomicBoolean(false)
    private val previewFrameInFlight = AtomicBoolean(false)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var lastPreviewFrameAtMs: Long = 0L

    fun resumeScanning() {
        AppLogger.debug("Scanner resumed")
        decodeEnabled.set(true)
        scanInProgress.set(false)
    }

    fun pauseScanning() {
        AppLogger.debug("Scanner paused")
        decodeEnabled.set(false)
        scanInProgress.set(false)
    }

    fun hasPassthroughCamera(): Boolean = findPassthroughConfig() != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (captureSession != null || cameraDevice != null) {
            AppLogger.debug("Ignoring scanner start because a session is already active")
            return
        }

        val config = findPassthroughConfig()
        if (config == null) {
            AppLogger.warn("No passthrough Camera2 configuration found")
            dispatchStatus(R.string.status_unsupported_device)
            return
        }

        startBackgroundThread()
        val analysisSize = chooseAnalysisSize(config.analysisOutputSizes) ?: run {
            AppLogger.error("Unable to select a passthrough camera output size")
            dispatchStatus(R.string.status_camera_unavailable)
            return
        }
        AppLogger.info(
            "Opening passthrough camera ${config.cameraId} analysis=${analysisSize.width}x${analysisSize.height}"
        )

        imageReader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!frameInFlight.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                cameraExecutor.execute {
                    try {
                        image.use { latest ->
                            maybeDispatchPreviewFrame(latest)
                            if (!decodeEnabled.get()) {
                                return@use
                            }
                            val result = decoder.decode(latest)
                            if (result != null && scanInProgress.compareAndSet(false, true)) {
                                AppLogger.info("QR decode succeeded")
                                onQrDetected(result.text)
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
                createSession(camera)
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
        decodeEnabled.set(false)
        scanInProgress.set(false)
        frameInFlight.set(false)
        previewFrameInFlight.set(false)
        lastPreviewFrameAtMs = 0L
        stopBackgroundThread()
    }

    private fun createSession(camera: CameraDevice) {
        val imageSurface = imageReader?.surface ?: return

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    OutputConfiguration(imageSurface)
                ),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        AppLogger.info("Camera capture session configured")
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
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

    private fun chooseAnalysisSize(sizes: Array<Size>): Size? {
        return sizes
            .sortedByDescending { it.width * it.height }
            .firstOrNull { it.width == 1280 && it.height == 1280 }
            ?: sizes.firstOrNull { it.width <= 1280 && it.height <= 1280 }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    private fun maybeDispatchPreviewFrame(image: android.media.Image) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewFrameAtMs < PREVIEW_FRAME_INTERVAL_MS) {
            return
        }
        if (!previewFrameInFlight.compareAndSet(false, true)) {
            return
        }

        if (image.planes.size < 3) {
            previewFrameInFlight.set(false)
            return
        }
        val previewBitmap = image.toPreviewBitmap()
        lastPreviewFrameAtMs = now
        onPreviewFrame(previewBitmap)
        previewFrameInFlight.set(false)
    }

    private fun android.media.Image.toPreviewBitmap(): Bitmap {
        val sample = PREVIEW_SAMPLE_STEP
        val width = width
        val height = height
        val outWidth = width / sample
        val outHeight = height / sample
        val pixels = IntArray(outWidth * outHeight)
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yRowData = ByteArray(yPlane.rowStride)
        val uvRowDataU = ByteArray(uPlane.rowStride)
        val uvRowDataV = ByteArray(vPlane.rowStride)
        var index = 0

        for (row in 0 until outHeight) {
            val sourceRow = row * sample
            val uvSourceRow = sourceRow / 2
            yBuffer.position(sourceRow * yPlane.rowStride)
            yBuffer.get(yRowData, 0, yPlane.rowStride)
            uBuffer.position(uvSourceRow * uPlane.rowStride)
            uBuffer.get(uvRowDataU, 0, uPlane.rowStride)
            vBuffer.position(uvSourceRow * vPlane.rowStride)
            vBuffer.get(uvRowDataV, 0, vPlane.rowStride)

            for (col in 0 until outWidth) {
                val sourceCol = col * sample
                val uvCol = sourceCol / 2
                val y = yRowData[sourceCol * yPlane.pixelStride].toInt() and 0xFF
                val u = (uvRowDataU[uvCol * uPlane.pixelStride].toInt() and 0xFF) - 128
                val v = (uvRowDataV[uvCol * vPlane.pixelStride].toInt() and 0xFF) - 128

                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                pixels[index++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
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

                val scalerMap = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@mapNotNull null

                val analysisOutputSizes = scalerMap.getOutputSizes(ImageFormat.YUV_420_888)
                    ?: return@mapNotNull null

                CameraConfig(
                    cameraId = cameraId,
                    position = characteristics.get(positionKey),
                    analysisOutputSizes = analysisOutputSizes
                )
            }
            .sortedWith(compareBy<CameraConfig> { it.position ?: Int.MAX_VALUE }.thenBy { it.cameraId })
            .firstOrNull()
    }

    private fun dispatchStatus(messageId: Int) {
        onStatusChanged(messageId)
    }

    private data class CameraConfig(
        val cameraId: String,
        val position: Int?,
        val analysisOutputSizes: Array<Size>
    )

    private companion object {
        const val META_CAMERA_SOURCE = "com.meta.extra_metadata.camera_source"
        const val META_CAMERA_POSITION = "com.meta.extra_metadata.position"
        const val PASSTHROUGH_SOURCE = 0
        const val PREVIEW_FRAME_INTERVAL_MS = 120L
        const val PREVIEW_SAMPLE_STEP = 4
    }
}
