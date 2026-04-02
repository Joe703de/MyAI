package com.joe703.eggdetect

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Analysiert Kamera-Frames und erkennt gelbe Eier
 */
class EggAnalyzer(private val onEggsDetected: (Int) -> Unit) : ImageAnalysis.Analyzer {
    
    private var lastProcessTime = 0L
    private val processIntervalMs = 300 // Analysiere alle 300ms
    
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
            
            // YUV zu RGB konvertieren
            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, bytes)
            
            val rgbMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)
            
            // Eier zählen
            val eggCount = countEggs(rgbMat)
            
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
     * Zählt gelbe Eier im Bild
     */
    private fun countEggs(mat: Mat): Int {
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)
        
        // Gelb-Orange Farbbereich für Eier
        val lowerYellow = Scalar(15.0, 60.0, 80.0)
        val upperYellow = Scalar(35.0, 255.0, 255.0)
        
        val mask = Mat()
        Core.inRange(hsv, lowerYellow, upperYellow, mask)
        
        // Rauschen entfernen
        val kernel = Mat.ones(5, 5, CvType.CV_8U)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        
        // Konturen finden
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        // Eier filtern
        var eggCount = 0
        val usedAreas = mutableListOf<android.graphics.Rect>()
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            
            // Minimale Fläche für Eier
            if (area < 500) continue
            
            val rect = Imgproc.boundingRect(contour)
            val aspect = rect.width.toFloat() / rect.height.toFloat()
            
            // Seitenverhältnis prüfen (Eier sind oval)
            if (aspect < 0.5 || aspect > 2.0) continue
            
            // Überlappung mit bereits gefundenen Eiern prüfen
            var tooClose = false
            for (used in usedAreas) {
                val centerDist = sqrt(
                    ((rect.x + rect.width/2) - (used.centerX())).toDouble().pow(2) +
                    ((rect.y + rect.height/2) - (used.centerY())).toDouble().pow(2)
                )
                if (centerDist < 80) {
                    tooClose = true
                    break
                }
            }
            
            if (!tooClose) {
                eggCount++
                usedAreas.add(android.graphics.Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height))
            }
            
            if (eggCount >= 6) break // Max 6 Eier
        }
        
        // Cleanup
        hsv.release()
        mask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }
        
        return eggCount.coerceIn(0, 6)
    }
}

// Hilfsfunktion für Rect
private fun android.graphics.Rect.centerX() = (left + right) / 2
private fun android.graphics.Rect.centerY() = (top + bottom) / 2
private fun Double.pow(exp: Int) = kotlin.math.pow(this, exp.toDouble())
