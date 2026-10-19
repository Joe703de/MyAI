package com.joe703.eggdetect

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
    val topCarScore: Float = 0f
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
    val colorB: Int = 0
)
