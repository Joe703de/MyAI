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
 * TEST-Version: Einfacher Farbfilter ohne Feature-Matching
 * Zum Debuggen der Farberkennung
 */
class EggAnalyzerTest(context: Context, private val onResult: (DetectionResult) -> Unit) : ImageAnalysis.Analyzer {

    private var lastProcessTime = 0L
    private val processIntervalMs = 300

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

            // WICHTIG: Teste beide Konvertierungen
            val rgbMat = Mat()
            val bgrMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)
            Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21, 3)

            // Test 1: HSV aus RGB
            val hsvRgb = Mat()
            Imgproc.cvtColor(rgbMat, hsvRgb, Imgproc.COLOR_RGB2HSV)

            // Test 2: HSV aus BGR
            val hsvBgr = Mat()
            Imgproc.cvtColor(bgrMat, hsvBgr, Imgproc.COLOR_BGR2HSV)

            // TEST-FILTER: Nur reines Gelb
            // Hue 25-30 = Gelb, Saturation > 150, Value > 150
            val lowerTest = Scalar(25.0, 150.0, 150.0)
            val upperTest = Scalar(30.0, 255.0, 255.0)

            val maskRgb = Mat()
            val maskBgr = Mat()
            Core.inRange(hsvRgb, lowerTest, upperTest, maskRgb)
            Core.inRange(hsvBgr, lowerTest, upperTest, maskBgr)

            // Zähle weiße Pixel
            val rgbPixels = Core.countNonZero(maskRgb)
            val bgrPixels = Core.countNonZero(maskBgr)

            Log.d("EggAnalyzerTest", "RGB mask pixels: $rgbPixels, BGR mask pixels: $bgrPixels")

            // Einfache Konturen zählen
            val contours = mutableListOf<org.opencv.core.MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(maskRgb, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Erstelle einfache Objekte für Overlay
            val objects = mutableListOf<DetectedObject>()
            for (i in 0 until minOf(contours.size, 6)) {
                val rect = Imgproc.boundingRect(contours[i])
                objects.add(DetectedObject(
                    x = rect.x,
                    y = rect.y,
                    width = rect.width,
                    height = rect.height,
                    isValidated = true  // Im Test-Modus alles als validiert markieren
                ))
            }

            val processingTime = System.currentTimeMillis() - startTime

            onResult(DetectionResult(
                eggCount = contours.size,
                candidateCount = contours.size,
                processingTimeMs = processingTime,
                objects = objects,
                imageWidth = imageProxy.width,
                imageHeight = imageProxy.height
            ))

            yuvMat.release()
            rgbMat.release()
            bgrMat.release()
            hsvRgb.release()
            hsvBgr.release()
            maskRgb.release()
            maskBgr.release()
            hierarchy.release()
            contours.forEach { it.release() }

        } catch (e: Exception) {
            Log.e("EggAnalyzerTest", "Error: ${e.message}", e)
            onResult(DetectionResult(0, 0, System.currentTimeMillis() - startTime))
        } finally {
            imageProxy.close()
        }
    }
}