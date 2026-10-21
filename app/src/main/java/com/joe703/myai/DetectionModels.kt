package com.joe703.myai

import android.graphics.Bitmap

/**
 * Ergebnis einer Objekterkennungs-Analyse.
 */
data class DetectionResult(
    val eggCount: Int,
    val candidateCount: Int,
    val s2Count: Int,
    val processingTimeMs: Long,
    val objects: List<DetectedObject>,
    val imageWidth: Int,
    val imageHeight: Int,
    val processedBitmap: Bitmap? = null,
    val rawBitmap: Bitmap? = null,
    val maxScore: Float = 0f,
    val topClassId: Int = -1,
    val inputMode: Int = 0,
    val topCarScore: Float = 0f,
    val maxGlobalScore: Float = 0f,
    val maxGlobalClassId: Int = -1,
    val timingMs: TimingBreakdown = TimingBreakdown(),
    val zoomedObject: ZoomedObject? = null,
    val recognizedText: String = ""
)

/**
 * Ein erkanntes Objekt, das groß angezeigt werden soll.
 */
data class ZoomedObject(
    val bitmap: android.graphics.Bitmap,
    val colorName: String,
    val score: Float
)

/**
 * Detailliertes Timing für die Analysepipeline.
 */
data class TimingBreakdown(
    val preprocessMs: Long = 0,
    val inferenceMs: Long = 0,
    val postprocessMs: Long = 0,
    val postReadMs: Long = 0,
    val postNmsMs: Long = 0,
    val postColorMs: Long = 0,
    val totalMs: Long = 0,
    val delegateName: String = "unknown"
)

/**
 * Ein erkanntes Objekt im Bild.
 */
data class DetectedObject(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isValidated: Boolean = true,
    val colorR: Int = 255,
    val colorG: Int = 255,
    val colorB: Int = 0,
    val colorName: String = ""
)
