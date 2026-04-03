package com.joe703.eggdetect

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
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
    private val colorConfigs: List<EggColor> = loadConfig(context)
    private var currentColorIndex: Int = 0

    init {
        Log.d("EggAnalyzer", "Loaded ${colorConfigs.size} color configs")
    }

    private fun loadConfig(context: Context): List<EggColor> {
        return try {
            val json = context.assets.open("egg_colors.json").bufferedReader().use { it.readText() }
            val config = Json.decodeFromString<EggColorsConfig>(json)
            config.colors
        } catch (e: Exception) {
            Log.e("EggAnalyzer", "Failed to load config: ${e.message}")
            // Fallback-Werte
            listOf(
                EggColor("Gelb", RGB(255, 255, 0),
                    HSVConfig(
                        HSVRange(80, 110, 100, 255, 80, 200),
                        HSVRange(80, 110, 150, 255, 120, 210)
                    ), 25, 0.3, ShapeMatching(0.4, 2.0, 0.2, 500)),
                EggColor("Rot", RGB(255, 0, 0),
                    HSVConfig(
                        HSVRange(140, 180, 100, 255, 80, 200),
                        HSVRange(150, 170, 150, 255, 120, 210)
                    ), 25, 0.3, ShapeMatching(0.4, 2.0, 0.2, 500))
            )
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

            // Prüfe beide Farben nacheinander
            val allValidatedObjects = mutableListOf<DetectedObject>()
            var totalEggCount = 0
            
            // Sammle Ergebnisse für alle Farben
            var lastS1Count = 0
            var lastS2Count = 0
            
            for ((index, colorConfig) in colorConfigs.withIndex()) {
                // Setze aktuellen Index für findYellowCandidates
                currentColorIndex = index
                
                // Schritt 1: Kandidaten finden (S1)
                val s1Candidates = findYellowCandidates(rgbMat, wideRange = true)
                Log.d("EggAnalyzer", "${colorConfig.name} S1: ${s1Candidates.size} candidates")

                // Schritt 2: S2 Filter
                val s2Candidates = if (s1Candidates.size > colorConfig.s2Threshold) {
                    findYellowCandidates(rgbMat, wideRange = false)
                } else {
                    s1Candidates
                }
                Log.d("EggAnalyzer", "${colorConfig.name} S2: ${s2Candidates.size} candidates")

                // Speichere letzte Werte für Status (letzte Farbe = Rot)
                if (index == colorConfigs.size - 1) {
                    lastS1Count = s1Candidates.size
                    lastS2Count = s2Candidates.size
                }

                // Schritt 3: Eier erkennen
                for (candidate in s2Candidates) {
                    val centerX = candidate.x + candidate.width / 2
                    val centerY = candidate.y + candidate.height / 2

                    var tooClose = false
                    for (obj in allValidatedObjects) {
                        val dx = (centerX - (obj.x + obj.width/2)).toDouble()
                        val dy = (centerY - (obj.y + obj.height/2)).toDouble()
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist < 80) {
                            tooClose = true
                            break
                        }
                    }
                    if (tooClose) continue

                    val isEgg = detectEggShape(candidate.roi, colorConfig)

                    // Füge ALLE S2-Kandidaten hinzu (für Overlay)
                    allValidatedObjects.add(DetectedObject(
                        x = candidate.x,
                        y = candidate.y,
                        width = candidate.width,
                        height = candidate.height,
                        isValidated = isEgg,
                        isS2Filtered = true
                    ))
                    
                    if (isEgg) {
                        totalEggCount++
                    }

                    if (totalEggCount >= 12) break // Max 6 pro Farbe
                }
                
                if (totalEggCount >= 12) break
            }

            val processingTime = System.currentTimeMillis() - startTime

            onResult(DetectionResult(
                eggCount = totalEggCount,
                candidateCount = lastS1Count,
                s2Count = lastS2Count,
                processingTimeMs = processingTime,
                objects = allValidatedObjects,
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

        // Werte aus aktueller JSON-Konfiguration
        val colorConfig = colorConfigs[currentColorIndex]
        val hsvRange = if (wideRange) colorConfig.hsv.s1 else colorConfig.hsv.s2
        
        // Prüfe auf Wrap-around (hMin > hMax)
        val masks = mutableListOf<Mat>()
        
        if (hsvRange.hMin > hsvRange.hMax) {
            // Wrap-around: zwei Bereiche (hMin-255 und 0-hMax)
            val lower1 = Scalar(hsvRange.hMin.toDouble(), hsvRange.sMin.toDouble(), hsvRange.vMin.toDouble())
            val upper1 = Scalar(255.0, hsvRange.sMax.toDouble(), hsvRange.vMax.toDouble())
            val lower2 = Scalar(0.0, hsvRange.sMin.toDouble(), hsvRange.vMin.toDouble())
            val upper2 = Scalar(hsvRange.hMax.toDouble(), hsvRange.sMax.toDouble(), hsvRange.vMax.toDouble())
            
            val mask1 = Mat()
            val mask2 = Mat()
            Core.inRange(hsv, lower1, upper1, mask1)
            Core.inRange(hsv, lower2, upper2, mask2)
            
            // Kombiniere beide Masken
            val combinedMask = Mat()
            Core.bitwise_or(mask1, mask2, combinedMask)
            masks.add(combinedMask)
            mask1.release()
            mask2.release()
        } else {
            // Normaler Bereich
            val lowerYellow = Scalar(hsvRange.hMin.toDouble(), hsvRange.sMin.toDouble(), hsvRange.vMin.toDouble())
            val upperYellow = Scalar(hsvRange.hMax.toDouble(), hsvRange.sMax.toDouble(), hsvRange.vMax.toDouble())
            val mask = Mat()
            Core.inRange(hsv, lowerYellow, upperYellow, mask)
            masks.add(mask)
        }

        // Verwende die (kombinierte) Maske
        val mask = masks.first()

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

    private fun detectEggShape(roi: Mat, colorConfig: EggColor): Boolean {
        // ROI vergrößern (aus Config für große Eier)
        val extendX = (roi.cols() * colorConfig.roiExpansion).toInt()
        val extendY = (roi.rows() * colorConfig.roiExpansion).toInt()
        
        // Erstelle erweiterte ROI
        val extendedRoi = Mat()
        Core.copyMakeBorder(roi, extendedRoi, extendY, extendY, extendX, extendX, Core.BORDER_REPLICATE)
        
        // Konvertiere zu Grayscale
        val gray = Mat()
        Imgproc.cvtColor(extendedRoi, gray, Imgproc.COLOR_RGB2GRAY)

        // Weichzeichnen
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)

        // Adaptive Threshold für bessere Konturen
        val thresh = Mat()
        Imgproc.adaptiveThreshold(blurred, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0)

        // Finde Konturen
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            extendedRoi.release()
            gray.release()
            blurred.release()
            thresh.release()
            hierarchy.release()
            return false
        }

        // Größte Kontur finden
        var largestContour: org.opencv.core.MatOfPoint? = null
        var largestArea = 0.0
        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area > largestArea) {
                largestArea = area
                largestContour = cnt
            }
        }

        if (largestContour == null || largestArea < colorConfig.shapeMatching.minArea) {
            extendedRoi.release()
            gray.release()
            blurred.release()
            thresh.release()
            hierarchy.release()
            contours.forEach { it.release() }
            return false
        }

        // Prüfe Ovalidität
        val rect = Imgproc.boundingRect(largestContour)
        val aspectRatio = rect.width.toFloat() / rect.height.toFloat()

        // Kreisförmigkeit
        val perimeter = Imgproc.arcLength(org.opencv.core.MatOfPoint2f(*largestContour.toArray()), true)
        val circularity = if (perimeter > 0) 4 * Math.PI * largestArea / (perimeter * perimeter) else 0.0

        // Kriterien aus Config
        val isEgg = aspectRatio >= colorConfig.shapeMatching.aspectRatioMin &&
                    aspectRatio <= colorConfig.shapeMatching.aspectRatioMax &&
                    circularity > colorConfig.shapeMatching.circularityMin

        Log.d("EggAnalyzer", "Egg check: aspect=$aspectRatio, circularity=$circularity, area=$largestArea, isEgg=$isEgg")

        extendedRoi.release()
        gray.release()
        blurred.release()
        thresh.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return isEgg
    }

    data class EggCandidate(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val roi: Mat
    )
}