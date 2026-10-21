package com.joe703.myai

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Application-Objekt, das Zustand zwischen MainActivity, DebugActivity und CalibrationActivity teilt.
 * Weil jede Activity ihren eigenen Camera-Analyzer besitzt, müssen
 * Einstellungen (Confidence, Zielklasse) und aktuelle Erkennungsdaten zentral außerhalb der Composable-State
 * leben, damit sie alle Activities gleichzeitig sehen.
 */
class MyAIApplication : Application() {

    /** aktuell ausgewählte COCO-Klasse (z. B. 2 = Auto) */
    var targetClassIndex by mutableIntStateOf(2)
        private set

    /** aktueller Confidence-Threshold, thread-safe lesbar */
    var confidenceThreshold by mutableFloatStateOf(0.3f)
        private set

    /** zuletzt empfangenes DetectionResult aus MainActivity / DebugActivity */
    var currentDetectionResult: DetectionResult? by mutableStateOf(null)
        private set

    /** Kalibrierung: eingefrorene Breiten/Höhen des kleinsten/größten gültigen Objekts */
    var calibrationMinWidthPx: Int by mutableIntStateOf(0)
        private set
    var calibrationMinHeightPx: Int by mutableIntStateOf(0)
        private set
    var calibrationMaxWidthPx: Int by mutableIntStateOf(0)
        private set
    var calibrationMaxHeightPx: Int by mutableIntStateOf(0)
        private set

    /** Kalibrierung: eingestellte Entfernungen in Metern */
    var calibrationMinDistanceM: Float by mutableFloatStateOf(10f)
        private set
    var calibrationMaxDistanceM: Float by mutableFloatStateOf(200f)
        private set

    private val listeners = mutableListOf<>() -> Unit>()

    fun updateTargetClass(index: Int) {
        targetClassIndex = index
        notifyListeners()
    }

    fun updateConfidenceThreshold(value: Float) {
        confidenceThreshold = value
        notifyListeners()
    }

    fun updateCurrentDetectionResult(result: DetectionResult) {
        currentDetectionResult = result
        notifyListeners()
    }

    fun updateCalibrationObjects(
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ) {
        calibrationMinWidthPx = minWidth
        calibrationMinHeightPx = minHeight
        calibrationMaxWidthPx = maxWidth
        calibrationMaxHeightPx = maxHeight
        notifyListeners()
    }

    fun updateCalibrationDistances(minM: Float, maxM: Float) {
        calibrationMinDistanceM = minM
        calibrationMaxDistanceM = maxM
        notifyListeners()
    }

    fun clearCalibrationObjects() {
        calibrationMinWidthPx = 0
        calibrationMinHeightPx = 0
        calibrationMaxWidthPx = 0
        calibrationMaxHeightPx = 0
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
