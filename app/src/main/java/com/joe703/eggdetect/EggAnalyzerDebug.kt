package com.joe703.eggdetect

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * DEBUG-Version: Zeigt mittlere Farbe des Bildes an
 */
class EggAnalyzerDebug(context: Context, private val onResult: (DebugResult) -> Unit) : ImageAnalysis.Analyzer {

    private var lastProcessTime = 0L
    private val processIntervalMs = 500

    data class DebugResult(
        val avgB: Double,
        val avgG: Double,
        val avgR: Double,
        val avgH: Double,
        val avgS: Double,
        val avgV: Double,
        val processingTimeMs: Long
    )

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastProcessTime < processIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val startTime = System.currentTimeMillis()

        try {
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            uBuffer.get(nv21, ySize, uSize)
            vBuffer.get(nv21, ySize + uSize, vSize)

            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            // NUR RGB (gedreht!)
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)

            // RGB Durchschnitt
            val meanRgb = Core.mean(rgbMat)
            val avgR = meanRgb.`val`[0]
            val avgG = meanRgb.`val`[1]
            val avgB = meanRgb.`val`[2]

            // HSV aus RGB
            val hsvRgb = Mat()
            Imgproc.cvtColor(rgbMat, hsvRgb, Imgproc.COLOR_RGB2HSV)
            val meanHsv = Core.mean(hsvRgb)

            Log.d("ColorDebug", "RGB: R=$avgR, G=$avgG, B=$avgB")
            Log.d("ColorDebug", "HSV from RGB: H=${meanHsv.`val`[0]}, S=${meanHsv.`val`[1]}, V=${meanHsv.`val`[2]}")

            // Wir verwenden jetzt RGB!
            onResult(DebugResult(
                avgB = avgB,
                avgG = avgG,
                avgR = avgR,
                avgH = meanHsv.`val`[0],
                avgS = meanHsv.`val`[1],
                avgV = meanHsv.`val`[2],
                processingTimeMs = System.currentTimeMillis() - startTime
            ))

            yuvMat.release()
            rgbMat.release()
            hsvRgb.release()

        } catch (e: Exception) {
            Log.e("ColorDebug", "Error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}