package dev.openquest.qrlaunch.camera

import android.media.Image
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.EnumMap

class QrCodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                put(DecodeHintType.TRY_HARDER, true)
            }
        )
    }

    fun decode(image: Image): Result? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) {
            return null
        }

        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val data = plane.toLumaByteArray(width, height)
        val source = RotatableLuminanceSource(data, width, height)

        return try {
            decodeSource(source) ?: decodeSource(source.rotateCounterClockwise())
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun Image.Plane.toLumaByteArray(width: Int, height: Int): ByteArray {
        val rowStride = rowStride
        val pixelStride = pixelStride
        val buffer = buffer.duplicate()
        val out = ByteArray(width * height)
        copyPlane(buffer, width, height, rowStride, pixelStride, out)
        return out
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        out: ByteArray
    ) {
        val rowData = ByteArray(rowStride)
        var outputOffset = 0
        for (row in 0 until height) {
            val rowStart = row * rowStride
            buffer.position(rowStart)
            if (pixelStride == 1) {
                buffer.get(out, outputOffset, width)
                outputOffset += width
            } else {
                buffer.get(rowData, 0, rowStride)
                for (col in 0 until width) {
                    out[outputOffset++] = rowData[col * pixelStride]
                }
            }
        }
    }

    private fun decodeSource(source: com.google.zxing.LuminanceSource): Result? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decodeWithState(bitmap)
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }
}

private class RotatableLuminanceSource(
    private val luminanceData: ByteArray,
    private val dataWidth: Int,
    private val dataHeight: Int
) : com.google.zxing.LuminanceSource(dataWidth, dataHeight) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val start = y * width
        val target = row?.takeIf { it.size >= width } ?: ByteArray(width)
        System.arraycopy(luminanceData, start, target, 0, width)
        return target
    }

    override fun getMatrix(): ByteArray = luminanceData

    override fun isCropSupported(): Boolean = false

    override fun isRotateSupported(): Boolean = true

    override fun rotateCounterClockwise(): com.google.zxing.LuminanceSource {
        val rotated = ByteArray(luminanceData.size)
        var offset = 0
        for (x in 0 until dataWidth) {
            for (y in dataHeight - 1 downTo 0) {
                rotated[offset++] = luminanceData[y * dataWidth + x]
            }
        }
        return RotatableLuminanceSource(rotated, dataHeight, dataWidth)
    }
}
