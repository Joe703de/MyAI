package com.joe703.myai

import android.app.Application
import android.content.Context
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
    var calibrationMaxDistanceM: Float by mutableFloatStateOf(300f)
        private set

    private val listeners = mutableListOf<() -> Unit>()

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCalibration()
    }

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
        // Entfernungen bleiben erhalten, falls vom letzten Mal geladen
        notifyListeners()
    }

    /** Speichert die Kalibrierwerte persistent. Wird beim sauberen Beenden der Kalibrierung aufgerufen. */
    fun saveCalibration() {
        prefs.edit().apply {
            putInt(KEY_MIN_WIDTH_PX, calibrationMinWidthPx)
            putInt(KEY_MIN_HEIGHT_PX, calibrationMinHeightPx)
            putInt(KEY_MAX_WIDTH_PX, calibrationMaxWidthPx)
            putInt(KEY_MAX_HEIGHT_PX, calibrationMaxHeightPx)
            putFloat(KEY_MIN_DISTANCE_M, calibrationMinDistanceM)
            putFloat(KEY_MAX_DISTANCE_M, calibrationMaxDistanceM)
            apply()
        }
    }

    /** Lädt die Kalibrierwerte aus SharedPreferences. */
    private fun loadCalibration() {
        calibrationMinWidthPx = prefs.getInt(KEY_MIN_WIDTH_PX, 0)
        calibrationMinHeightPx = prefs.getInt(KEY_MIN_HEIGHT_PX, 0)
        calibrationMaxWidthPx = prefs.getInt(KEY_MAX_WIDTH_PX, 0)
        calibrationMaxHeightPx = prefs.getInt(KEY_MAX_HEIGHT_PX, 0)
        calibrationMinDistanceM = prefs.getFloat(KEY_MIN_DISTANCE_M, 10f)
        calibrationMaxDistanceM = prefs.getFloat(KEY_MAX_DISTANCE_M, 300f)
    }

    /**
     * Schätzt die Entfernung eines Objekts über Dreisatz anhand seiner Pixelbreite.
     * Kalibrierung: Min = nah (große Breite), Max = weit (kleine Breite).
     * Gibt -1f zurück, wenn nicht genug Kalibrierdaten vorhanden sind.
     */
    fun estimateDistance(widthPx: Int): Float {
        val minW = calibrationMinWidthPx
        val maxW = calibrationMaxWidthPx
        val minD = calibrationMinDistanceM
        val maxD = calibrationMaxDistanceM
        if (minW <= 0 || maxW <= 0 || minW == maxW) return -1f
        // Große Breite -> nah (minD), kleine Breite -> weit (maxD)
        return minD + (maxD - minD) * (minW - widthPx).toFloat() / (minW - maxW).toFloat()
    }

    /**
     * Berechnet die Entfernung für ein aktuelles Objekt anhand eines einzelnen
     * gespeicherten Kalibrierpunkts über Dreisatz (Breite ist umgekehrt proportional zur Entfernung).
     */
    fun estimateDistanceFromSavedPoint(savedWidthPx: Int, savedDistanceM: Float, currentWidthPx: Int): Float {
        if (savedWidthPx <= 0 || savedDistanceM <= 0f || currentWidthPx <= 0) return -1f
        return savedDistanceM * savedWidthPx.toFloat() / currentWidthPx.toFloat()
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

    companion object {
        private const val PREFS_NAME = "myai_calibration"
        private const val KEY_MIN_WIDTH_PX = "calibration_min_width_px"
        private const val KEY_MIN_HEIGHT_PX = "calibration_min_height_px"
        private const val KEY_MAX_WIDTH_PX = "calibration_max_width_px"
        private const val KEY_MAX_HEIGHT_PX = "calibration_max_height_px"
        private const val KEY_MIN_DISTANCE_M = "calibration_min_distance_m"
        private const val KEY_MAX_DISTANCE_M = "calibration_max_distance_m"
    }
}
