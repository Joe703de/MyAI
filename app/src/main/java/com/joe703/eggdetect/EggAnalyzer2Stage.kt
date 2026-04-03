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

data class DetectedObject(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isValidated: Boolean,
    val isS2Filtered: Boolean = false  // true = durch S2-Filter gelaufen (gelb)
)

data class DetectionResult(
    val eggCount: Int,
    val candidateCount: Int,     // S1 count
    val s2Count: Int,            // NEW: S2 count (yellow filter stage)
    val processingTimeMs: Long,
    val objects: List<DetectedObject> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

class EggAnalyzer2Stage(context: Context, private val onResult: (DetectionResult) -> Unit) : ImageAnalysis.Analyzer {

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

            // Verwende RGB (was auf diesem Gerät funktioniert)
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)

            // Schritt 1: Kandidaten finden (S1)
            val s1Candidates = findYellowCandidates(rgbMat, wideRange = true)
            Log.d("EggAnalyzer", "S1: Found ${s1Candidates.size} candidates")

            // Schritt 2: S2 Filter (verschärft wenn S1 > 25)
            val s2Candidates = if (s1Candidates.size > 25) {
                findYellowCandidates(rgbMat, wideRange = false) // Verschärfter Filter
            } else {
                s1Candidates // Sonst gleich wie S1
            }
            Log.d("EggAnalyzer", "S2: ${s2Candidates.size} candidates")

            // Schritt 3: Eier = alle S2-Kandidaten (automatisch validiert)
            val validatedObjects = mutableListOf<DetectedObject>()
            var eggCount = 0

            for (candidate in s2Candidates) {
                val centerX = candidate.x + candidate.width / 2
                val centerY = candidate.y + candidate.height / 2

                var tooClose = false
                for (obj in validatedObjects) {
                    val dx = (centerX - (obj.x + obj.width/2)).toDouble()
                    val dy = (centerY - (obj.y + obj.height/2)).toDouble()
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist < 80) {
                        tooClose = true
                        break
                    }
                }
                if (tooClose) continue

                // Kein ORB-Feature-Matching mehr - alle S2-Objekte sind Eier
                validatedObjects.add(DetectedObject(
                    x = candidate.x,
                    y = candidate.y,
                    width = candidate.width,
                    height = candidate.height,
                    isValidated = true, // Automatisch als Ei validiert
                    isS2Filtered = true
                ))

                eggCount++

                if (eggCount >= 6) break
            }

            val processingTime = System.currentTimeMillis() - startTime

            onResult(DetectionResult(
                eggCount = eggCount,
                candidateCount = s1Candidates.size,
                s2Count = s2Candidates.size,
                processingTimeMs = processingTime,
                objects = validatedObjects,
                imageWidth = imageProxy.width,
                imageHeight = imageProxy.height
            ))

            yuvMat.release()
            rgbMat.release()

        } catch (e: Exception) {
            Log.e("EggAnalyzer", "Error: ${e.message}", e)
            onResult(DetectionResult(
                eggCount = 0,
                candidateCount = 0,
                s2Count = 0,
                processingTimeMs = System.currentTimeMillis() - startTime,
                objects = emptyList(),
                imageWidth = imageProxy?.width ?: 0,
                imageHeight = imageProxy?.height ?: 0
            ))
        } finally {
            imageProxy.close()
        }
    }

    private fun findYellowCandidates(mat: Mat, wideRange: Boolean = true): List<EggCandidate> {
        val candidates = mutableListOf<EggCandidate>()

        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

        // ANGEPASST FÜR DIESSES GERÄT:
        // Das "Gelb" auf diesem Gerät hat H=90-100° (Cyan/Grün)
        val (lowerYellow, upperYellow) = if (wideRange) {
            // S1: Weiter Bereich
            Pair(Scalar(80.0, 100.0, 80.0), Scalar(110.0, 255.0, 200.0))
        } else {
            // S2: Enger Bereich (verschärfter Filter)
            Pair(Scalar(80.0, 150.0, 120.0), Scalar(110.0, 255.0, 210.0))
        }

        val mask = Mat()
        Core.inRange(hsv, lowerYellow, upperYellow, mask)

        val kernel = Mat.ones(5, 5, CvType.CV_8U)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        Log.d("EggAnalyzer", "Found ${contours.size} contours")

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area < 100) continue

            val rect = Imgproc.boundingRect(cnt)
            val aspect = rect.width.toFloat() / rect.height.toFloat()

            if (aspect < 0.3 || aspect > 3.0) continue

            val padding = 10
            val x1 = (rect.x - padding).coerceAtLeast(0)
            val y1 = (rect.y - padding).coerceAtLeast(0)
            val x2 = (rect.x + rect.width + padding).coerceAtMost(mat.cols())
            val y2 = (rect.y + rect.height + padding).coerceAtMost(mat.rows())

            if (x2 <= x1 || y2 <= y1) continue

            val roi = Mat(mat, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
            candidates.add(EggCandidate(x1, y1, x2 - x1, y2 - y1, roi))

            Log.d("EggAnalyzer", "Candidate: area=$area, size=${rect.width}x${rect.height}")
        }

        hsv.release()
        mask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return candidates
    }

    data class EggCandidate(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val roi: Mat
    )
}