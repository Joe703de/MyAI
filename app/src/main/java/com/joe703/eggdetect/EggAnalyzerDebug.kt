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

            // Teste BEIDE Konvertierungen
            val bgrMat = Mat()
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21, 3)
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)

            // BGR Durchschnitt
            val meanBgr = Core.mean(bgrMat)
            val avgB_bgr = meanBgr.`val`[0]
            val avgG_bgr = meanBgr.`val`[1]
            val avgR_bgr = meanBgr.`val`[2]

            // RGB Durchschnitt
            val meanRgb = Core.mean(rgbMat)
            val avgR_rgb = meanRgb.`val`[0]
            val avgG_rgb = meanRgb.`val`[1]
            val avgB_rgb = meanRgb.`val`[2]

            // HSV aus BGR
            val hsvBgr = Mat()
            Imgproc.cvtColor(bgrMat, hsvBgr, Imgproc.COLOR_BGR2HSV)
            val meanHsvBgr = Core.mean(hsvBgr)

            // HSV aus RGB
            val hsvRgb = Mat()
            Imgproc.cvtColor(rgbMat, hsvRgb, Imgproc.COLOR_RGB2HSV)
            val meanHsvRgb = Core.mean(hsvRgb)

            Log.d("ColorDebug", "BGR format: B=$avgB_bgr, G=$avgG_bgr, R=$avgR_bgr")
            Log.d("ColorDebug", "RGB format: R=$avgR_rgb, G=$avgG_rgb, B=$avgB_rgb")
            Log.d("ColorDebug", "HSV from BGR: H=${meanHsvBgr.`val`[0]}, S=${meanHsvBgr.`val`[1]}, V=${meanHsvBgr.`val`[2]}")
            Log.d("ColorDebug", "HSV from RGB: H=${meanHsvRgb.`val`[0]}, S=${meanHsvRgb.`val`[1]}, V=${meanHsvRgb.`val`[2]}")

            // Wir verwenden BGR als Standard
            onResult(DebugResult(
                avgB = avgB_bgr,
                avgG = avgG_bgr,
                avgR = avgR_bgr,
                avgH = meanHsvBgr.`val`[0],
                avgS = meanHsvBgr.`val`[1],
                avgV = meanHsvBgr.`val`[2],
                processingTimeMs = System.currentTimeMillis() - startTime
            ))

            yuvMat.release()
            bgrMat.release()
            rgbMat.release()
            hsvBgr.release()
            hsvRgb.release()

        } catch (e: Exception) {
            Log.e("ColorDebug", "Error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
}