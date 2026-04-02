package com.joe703.eggdetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Zweistufige Eiererkennung:
 * 1. Schritt: Farbfilterung (HSV) -> Kandidaten finden
 * 2. Schritt: ORB Feature-Matching -> Validierung
 */
class EggAnalyzer2Stage(context: Context, private val onEggsDetected: (Int) -> Unit) : ImageAnalysis.Analyzer {
    
    private val referenceFeatures = mutableListOf<ReferenceFeature>()
    private var lastProcessTime = 0L
    private val processIntervalMs = 300
    
    // ORB für Feature-Matching
    private val orb = ORB.create(500)
    private val bfMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
    
    data class ReferenceFeature(
        val name: String,
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat
    )
    
    init {
        loadReferenceFeatures(context)
    }
    
    /**
     * Lädt Referenzbilder und extrahiert ORB-Features
     */
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
                        
                        // In Graustufen für ORB
                        val grayMat = Mat()
                        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
                        
                        val keypoints = MatOfKeyPoint()
                        val descriptors = Mat()
                        orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)
                        
                        if (!descriptors.empty()) {
                            referenceFeatures.add(ReferenceFeature(filename, keypoints, descriptors))
                        }
                        
                        mat.release()
                        grayMat.release()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
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
        
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            // YUV zu RGB
            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, bytes)
            
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)
            
            // Schritt 1: Kandidaten finden
            val candidates = findYellowCandidates(rgbMat)
            
            // Schritt 2: Validierung mit Feature-Matching
            val eggCount = validateCandidates(rgbMat, candidates)
            
            onEggsDetected(eggCount)
            
            yuvMat.release()
            rgbMat.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * Schritt 1: Gelbe Kandidaten finden (HSV)
     */
    private fun findYellowCandidates(mat: Mat): List<EggCandidate> {
        val candidates = mutableListOf<EggCandidate>()
        
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)
        
        // Gelb-Orange Farbbereich
        val lower1 = Scalar(15.0, 60.0, 80.0)
        val upper1 = Scalar(35.0, 255.0, 255.0)
        
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
        
        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area < 800 || area > 25000) continue
            
            val rect = Imgproc.boundingRect(cnt)
            val aspect = rect.width.toFloat() / rect.height.toFloat()
            
            if (aspect < 0.6 || aspect > 1.8) continue
            
            // ROI mit Padding
            val padding = 20
            val x1 = (rect.x - padding).coerceAtLeast(0)
            val y1 = (rect.y - padding).coerceAtLeast(0)
            val x2 = (rect.x + rect.width + padding).coerceAtMost(mat.cols())
            val y2 = (rect.y + rect.height + padding).coerceAtMost(mat.rows())
            
            val roi = Mat(mat, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
            
            candidates.add(EggCandidate(x1, y1, roi))
        }
        
        hsv.release()
        mask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }
        
        return candidates
    }
    
    /**
     * Schritt 2: Kandidaten mit ORB validieren
     */
    private fun validateCandidates(mat: Mat, candidates: List<EggCandidate>): Int {
        if (referenceFeatures.isEmpty()) return 0
        
        var eggCount = 0
        val usedCenters = mutableListOf<Pair<Int, Int>>()
        
        for (candidate in candidates) {
            // Mittelpunkt berechnen
            val centerX = candidate.x + candidate.roi.cols() / 2
            val centerY = candidate.y + candidate.roi.rows() / 2
            
            // Prüfe Abstand zu bereits akzeptierten Eiern
            var tooClose = false
            for ((ux, uy) in usedCenters) {
                val dist = kotlin.math.sqrt(
                    ((centerX - ux) * (centerX - ux) + (centerY - uy) * (centerY - uy)).toDouble()
                )
                if (dist < 100) {
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
    
    /**
     * Matcht ROI gegen Referenzbilder mit ORB
     */
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
        
        return bestMatches >= 3
    }
    
    data class EggCandidate(
        val x: Int,
        val y: Int,
        val roi: Mat
    )
}