package com.joe703.eggdetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Scalar
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.IOException

/**
 * Ergebnis der Eiererkennung
 */
data class DetectionResult(
    val eggCount: Int,
    val candidateCount: Int,
    val processingTimeMs: Long
)

class EggAnalyzer2Stage(context: Context, private val onResult: (DetectionResult) -> Unit) : ImageAnalysis.Analyzer {

    private val referenceFeatures = mutableListOf<ReferenceFeature>()
    private var lastProcessTime = 0L
    private val processIntervalMs = 300

    private val orb = ORB.create(500)
    private val bfMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    data class ReferenceFeature(
        val name: String,
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat
    )

    init {
        loadReferenceFeatures(context)
        Log.d("EggAnalyzer", "Loaded ${referenceFeatures.size} reference features")
    }

    private fun loadReferenceFeatures(context: Context) {
        val assetManager = context.assets
        val referenceFiles = listOf("ei-gelb_1.jpg", "ei-gelb_2.jpg", "ei-gelb_3.jpg", "ei-gelb_4.jpg")

        referenceFiles.forEach { filename ->
            try {
                assetManager.open("reference/$filename").use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)

                        val grayMat = Mat()
                        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

                        val keypoints = MatOfKeyPoint()
                        val descriptors = Mat()
                        orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                        if (!descriptors.empty()) {
                            referenceFeatures.add(ReferenceFeature(filename, keypoints, descriptors))
                            Log.d("EggAnalyzer", "Loaded reference: $filename")
                        }

                        mat.release()
                        grayMat.release()
                    }
                }
            } catch (e: IOException) {
                Log.e("EggAnalyzer", "Failed to load $filename: ${e.message}")
            }
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastProcessTime < processIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val startTime = System.currentTimeMillis()

        try {
            // Korrekte YUV Konvertierung für alle 3 Planes
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // NV21 Format: Y Plane + interleaved UV Planes
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            uBuffer.get(nv21, ySize, uSize)
            vBuffer.get(nv21, ySize + uSize, vSize)

            // YUV zu RGB
            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)

            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)

            Log.d("EggAnalyzer", "Processing image: ${rgbMat.cols()}x${rgbMat.rows()}")

            // Schritt 1: Kandidaten finden
            val candidates = findYellowCandidates(rgbMat)
            Log.d("EggAnalyzer", "Step 1: Found ${candidates.size} candidates")

            // Schritt 2: Validierung
            val eggCount = validateCandidates(rgbMat, candidates)
            Log.d("EggAnalyzer", "Step 2: Validated $eggCount eggs")

            val processingTime = System.currentTimeMillis() - startTime
            Log.d("EggAnalyzer", "Total time: ${processingTime}ms")

            onResult(DetectionResult(
                eggCount = eggCount,
                candidateCount = candidates.size,
                processingTimeMs = processingTime
            ))

            yuvMat.release()
            rgbMat.release()

        } catch (e: Exception) {
            Log.e("EggAnalyzer", "Error in analyze: ${e.message}", e)
            val processingTime = System.currentTimeMillis() - startTime
            onResult(DetectionResult(0, 0, processingTime))
        } finally {
            imageProxy.close()
        }
    }

    private fun findYellowCandidates(mat: Mat): List<EggCandidate> {
        val candidates = mutableListOf<EggCandidate>()

        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

        // Erweiterter Gelb-Orange Bereich
        val lower1 = Scalar(10.0, 50.0, 80.0)
        val upper1 = Scalar(40.0, 255.0, 255.0)

        val mask = Mat()
        Core.inRange(hsv, lower1, upper1, mask)

        // Rauschen entfernen
        val kernel = Mat.ones(7, 7, CvType.CV_8U)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        // Konturen finden
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        Log.d("EggAnalyzer", "Found ${contours.size} contours")

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area < 500) continue // Noch kleinere Objekte erlauben

            val rect = Imgproc.boundingRect(cnt)
            val aspect = rect.width.toFloat() / rect.height.toFloat()

            if (aspect < 0.4 || aspect > 2.5) continue // Mehr Toleranz

            // ROI mit Padding
            val padding = 20
            val x1 = (rect.x - padding).coerceAtLeast(0)
            val y1 = (rect.y - padding).coerceAtLeast(0)
            val x2 = (rect.x + rect.width + padding).coerceAtMost(mat.cols())
            val y2 = (rect.y + rect.height + padding).coerceAtMost(mat.rows())

            if (x2 <= x1 || y2 <= y1) continue

            val roi = Mat(mat, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
            candidates.add(EggCandidate(x1, y1, roi, area.toInt()))

            Log.d("EggAnalyzer", "Candidate: area=$area, aspect=$aspect")
        }

        hsv.release()
        mask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return candidates
    }

    private fun validateCandidates(mat: Mat, candidates: List<EggCandidate>): Int {
        if (referenceFeatures.isEmpty()) {
            Log.w("EggAnalyzer", "No reference features loaded!")
            return 0
        }

        var eggCount = 0
        val usedCenters = mutableListOf<Pair<Int, Int>>()

        for (candidate in candidates) {
            val centerX = candidate.x + candidate.roi.cols() / 2
            val centerY = candidate.y + candidate.roi.rows() / 2

            // Prüfe Abstand
            var tooClose = false
            for ((ux, uy) in usedCenters) {
                val dx = (centerX - ux).toDouble()
                val dy = (centerY - uy).toDouble()
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist < 80) {
                    tooClose = true
                    break
                }
            }
            if (tooClose) continue

            // ORB Feature-Matching
            val isEgg = matchFeatures(candidate.roi)

            if (isEgg) {
                eggCount++
                usedCenters.add(Pair(centerX, centerY))
            }

            if (eggCount >= 6) break
        }

        return eggCount
    }

    private fun matchFeatures(roi: Mat): Boolean {
        val grayRoi = Mat()
        Imgproc.cvtColor(roi, grayRoi, Imgproc.COLOR_RGB2GRAY)

        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(grayRoi, Mat(), keypoints, descriptors)

        if (descriptors.empty()) {
            grayRoi.release()
            return false
        }

        var bestMatches = 0

        for (ref in referenceFeatures) {
            try {
                val matches = MatOfDMatch()
                bfMatcher.match(descriptors, ref.descriptors, matches)

                val matchList = matches.toList()
                val goodMatches = matchList.count { it.distance < 50 }

                if (goodMatches > bestMatches) {
                    bestMatches = goodMatches
                }

                matches.release()
            } catch (e: Exception) {
                continue
            }
        }

        grayRoi.release()
        keypoints.release()
        descriptors.release()

        Log.d("EggAnalyzer", "Best matches: $bestMatches")
        return bestMatches >= 3
    }

    data class EggCandidate(
        val x: Int,
        val y: Int,
        val roi: Mat,
        val area: Int
    )
}