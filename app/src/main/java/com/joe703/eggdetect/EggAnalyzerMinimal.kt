package com.joe703.eggdetect

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * MINIMALER DEBUG - Nur YUV Rohdaten prüfen
 */
class EggAnalyzerMinimal(context: Context, private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private var lastProcessTime = 0L
    private val processIntervalMs = 500

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastProcessTime < processIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        try {
            // PRÜFE: YUV Format direkt
            val width = imageProxy.width
            val height = imageProxy.height
            val format = imageProxy.format

            Log.e("MINIMAL", "=== ANALYZER START ===")
            Log.e("MINIMAL", "Image: ${width}x${height}, Format: $format")

            // YUV Plane Info
            val planes = imageProxy.planes
            Log.e("MINIMAL", "Number of planes: ${planes.size}")

            for (i in planes.indices) {
                val plane = planes[i]
                Log.e("MINIMAL", "Plane $i: ${plane.buffer.remaining()} bytes, " +
                        "rowStride=${plane.rowStride}, pixelStride=${plane.pixelStride}")
            }

            // Erste paar Y-Werte (Helligkeit)
            val yBuffer = planes[0].buffer
            yBuffer.position(0)
            val yValues = ByteArray(minOf(10, yBuffer.remaining()))
            yBuffer.get(yValues)
            Log.e("MINIMAL", "First 10 Y values: ${yValues.joinToString()}")

            // Zurücksetzen für nächsten Zugriff
            yBuffer.position(0)

            // Konvertiere zu BGR
            val ySize = yBuffer.remaining()
            val uSize = planes[1].buffer.remaining()
            val vSize = planes[2].buffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            planes[1].buffer.get(nv21, ySize, uSize)
            planes[2].buffer.get(nv21, ySize + uSize, vSize)

            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            // BGR
            val bgrMat = Mat()
            Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21, 3)
            val meanBgr = Core.mean(bgrMat)

            // RGB (über OpenCV)
            val rgbMat = Mat()
            Imgproc.cvtColor(bgrMat, rgbMat, Imgproc.COLOR_BGR2RGB)
            val meanRgb = Core.mean(rgbMat)

            // RGB (direkt)
            val rgbDirectMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbDirectMat, Imgproc.COLOR_YUV2RGB_NV21, 3)
            val meanRgbDirect = Core.mean(rgbDirectMat)

            // HEX-Werte für erste Pixel (unverändert)
            val firstPixelBgr = bgrMat.get(0, 0)
            val hexBgr = String.format("0x%02X%02X%02X", firstPixelBgr[0].toInt(), firstPixelBgr[1].toInt(), firstPixelBgr[2].toInt())

            val firstPixelRgb = rgbMat.get(0, 0)
            val hexRgb = String.format("0x%02X%02X%02X", firstPixelRgb[0].toInt(), firstPixelRgb[1].toInt(), firstPixelRgb[2].toInt())

            val firstPixelRgbDirect = rgbDirectMat.get(0, 0)
            val hexRgbDirect = String.format("0x%02X%02X%02X", firstPixelRgbDirect[0].toInt(), firstPixelRgbDirect[1].toInt(), firstPixelRgbDirect[2].toInt())

            Log.e("MINIMAL", "BGR Mean: B=${meanBgr.`val`[0]}, G=${meanBgr.`val`[1]}, R=${meanBgr.`val`[2]}, HEX=$hexBgr")
            Log.e("MINIMAL", "RGB (via BGR) Mean: R=${meanRgb.`val`[0]}, G=${meanRgb.`val`[1]}, B=${meanRgb.`val`[2]}, HEX=$hexRgb")
            Log.e("MINIMAL", "RGB (direkt) Mean: R=${meanRgbDirect.`val`[0]}, G=${meanRgbDirect.`val`[1]}, B=${meanRgbDirect.`val`[2]}, HEX=$hexRgbDirect")

            val result = """
                Format: ${width}x${height}
                BGR: B=${meanBgr.`val`[0].toInt()} G=${meanBgr.`val`[1].toInt()} R=${meanBgr.`val`[2].toInt()} HEX=$hexBgr
                RGB(via): R=${meanRgb.`val`[0].toInt()} G=${meanRgb.`val`[1].toInt()} B=${meanRgb.`val`[2].toInt()} HEX=$hexRgb
                RGB(dir): R=${meanRgbDirect.`val`[0].toInt()} G=${meanRgbDirect.`val`[1].toInt()} B=${meanRgbDirect.`val`[2].toInt()} HEX=$hexRgbDirect
            """.trimIndent()

            onResult(result)

            yuvMat.release()
            bgrMat.release()
            rgbMat.release()
            rgbDirectMat.release()

        } catch (e: Exception) {
            Log.e("MINIMAL", "ERROR: ${e.message}", e)
            onResult("ERROR: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }
}