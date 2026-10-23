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

    /** Aktives Set von COCO-Klassen, die gleichzeitig erkannt werden sollen. */
    val targetClassSet = listOf(2, 9, 11)

    /** Aktuell für die Kalibrierung ausgewählte Klasse (muss im Set liegen). */
    var calibrationTargetClassIndex by mutableIntStateOf(2)
        private set

    /** Legacy: aktuell ausgewählte COCO-Klasse (entspricht der Kalibrierklasse im Set). */
    val targetClassIndex: Int
        get() = calibrationTargetClassIndex

    /** aktueller Confidence-Threshold, thread-safe lesbar */
    var confidenceThreshold by mutableFloatStateOf(0.3f)
        private set

    /** zuletzt empfangenes DetectionResult aus MainActivity / DebugActivity */
    var currentDetectionResult: DetectionResult? by mutableStateOf(null)
        private set

    /** Kalibrierung: separate Werte für jede COCO-Klasse (80 Klassen). */
    data class Calibration(
        val minWidthPx: Int = 0,
        val minHeightPx: Int = 0,
        val maxWidthPx: Int = 0,
        val maxHeightPx: Int = 0,
        val minDistanceM: Float = 10f,
        val maxDistanceM: Float = 300f,
        val ratioMin: Float = 1.1f,
        val ratioMax: Float = 1.35f
    )

    private val calibrationByClass = Array(80) { mutableStateOf(Calibration()) }

    /** Aktuelle Kalibrierung für die aktuell ausgewählte Kalibrierklasse. */
    val currentCalibration: Calibration
        get() = calibrationByClass[calibrationTargetClassIndex.coerceIn(0, 79)].value

    /** Kalibrierung: eingefrorene Breiten/Höhen des kleinsten/größten gültigen Objekts */
    var calibrationMinWidthPx: Int
        get() = currentCalibration.minWidthPx
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(minWidthPx = value)) }
    var calibrationMinHeightPx: Int
        get() = currentCalibration.minHeightPx
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(minHeightPx = value)) }
    var calibrationMaxWidthPx: Int
        get() = currentCalibration.maxWidthPx
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(maxWidthPx = value)) }
    var calibrationMaxHeightPx: Int
        get() = currentCalibration.maxHeightPx
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(maxHeightPx = value)) }

    /** Kalibrierung: Seitenverhältnis-Grenzen für die aktuelle Klasse. */
    var calibrationRatioMin: Float
        get() = currentCalibration.ratioMin
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(ratioMin = value)) }
    var calibrationRatioMax: Float
        get() = currentCalibration.ratioMax
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(ratioMax = value)) }

    /** Kalibrierung: eingestellte Entfernungen in Metern */
    var calibrationMinDistanceM: Float
        get() = currentCalibration.minDistanceM
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(minDistanceM = value)) }
    var calibrationMaxDistanceM: Float
        get() = currentCalibration.maxDistanceM
        private set(value) { updateCalibrationForClass(calibrationTargetClassIndex, currentCalibration.copy(maxDistanceM = value)) }

    private val listeners = mutableListOf<() -> Unit>()

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCalibration()
    }

    fun updateTargetClass(index: Int) {
        calibrationTargetClassIndex = targetClassSet.firstOrNull { it == index } ?: targetClassSet.firstOrNull() ?: 2
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

    private fun updateCalibrationForClass(classIndex: Int, calibration: Calibration) {
        calibrationByClass[classIndex.coerceIn(0, 79)].value = calibration
    }

    fun updateCalibrationObjects(
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        classIndex: Int = calibrationTargetClassIndex
    ) {
        val current = calibrationByClass[classIndex.coerceIn(0, 79)].value
        updateCalibrationForClass(classIndex, current.copy(
            minWidthPx = minWidth,
            minHeightPx = minHeight,
            maxWidthPx = maxWidth,
            maxHeightPx = maxHeight
        ))
        notifyListeners()
    }

    fun updateCalibrationDistances(minM: Float, maxM: Float, classIndex: Int = calibrationTargetClassIndex) {
        val current = calibrationByClass[classIndex.coerceIn(0, 79)].value
        updateCalibrationForClass(classIndex, current.copy(minDistanceM = minM, maxDistanceM = maxM))
        notifyListeners()
    }

    fun updateCalibrationRatios(ratioMin: Float, ratioMax: Float, classIndex: Int = calibrationTargetClassIndex) {
        val current = calibrationByClass[classIndex.coerceIn(0, 79)].value
        updateCalibrationForClass(classIndex, current.copy(ratioMin = ratioMin, ratioMax = ratioMax))
        notifyListeners()
    }

    fun clearCalibrationObjects(classIndex: Int = calibrationTargetClassIndex) {
        val current = calibrationByClass[classIndex.coerceIn(0, 79)].value
        updateCalibrationForClass(classIndex, current.copy(
            minWidthPx = 0,
            minHeightPx = 0,
            maxWidthPx = 0,
            maxHeightPx = 0
        ))
        notifyListeners()
    }

    /** Speichert die Kalibrierwerte persistent. Wird beim sauberen Beenden der Kalibrierung aufgerufen. */
    fun saveCalibration() {
        val editor = prefs.edit()
        calibrationByClass.forEachIndexed { index, state ->
            val cal = state.value
            editor.putInt(KEY_MIN_WIDTH_PX.format(index), cal.minWidthPx)
            editor.putInt(KEY_MIN_HEIGHT_PX.format(index), cal.minHeightPx)
            editor.putInt(KEY_MAX_WIDTH_PX.format(index), cal.maxWidthPx)
            editor.putInt(KEY_MAX_HEIGHT_PX.format(index), cal.maxHeightPx)
            editor.putFloat(KEY_MIN_DISTANCE_M.format(index), cal.minDistanceM)
            editor.putFloat(KEY_MAX_DISTANCE_M.format(index), cal.maxDistanceM)
            editor.putFloat(KEY_MIN_RATIO.format(index), cal.ratioMin)
            editor.putFloat(KEY_MAX_RATIO.format(index), cal.ratioMax)
        }
        editor.apply()
    }

    /** Lädt die Kalibrierwerte aus SharedPreferences. */
    private fun loadCalibration() {
        calibrationByClass.forEachIndexed { index, state ->
            val minW = prefs.getInt(KEY_MIN_WIDTH_PX.format(index), 0)
            val minH = prefs.getInt(KEY_MIN_HEIGHT_PX.format(index), 0)
            val maxW = prefs.getInt(KEY_MAX_WIDTH_PX.format(index), 0)
            val maxH = prefs.getInt(KEY_MAX_HEIGHT_PX.format(index), 0)
            val minD = prefs.getFloat(KEY_MIN_DISTANCE_M.format(index), 10f)
            val maxD = prefs.getFloat(KEY_MAX_DISTANCE_M.format(index), 300f)
            val ratioMin = prefs.getFloat(KEY_MIN_RATIO.format(index), 1.1f)
            val ratioMax = prefs.getFloat(KEY_MAX_RATIO.format(index), 1.35f)
            state.value = Calibration(minW, minH, maxW, maxH, minD, maxD, ratioMin, ratioMax)
        }
    }

    /**
     * Schätzt die Entfernung eines Objekts über Dreisatz anhand seiner Pixelbreite.
     * Kalibrierung: Min = nah (große Breite), Max = weit (kleine Breite).
     * Gibt -1f zurück, wenn nicht genug Kalibrierdaten vorhanden sind.
     */
    fun estimateDistance(widthPx: Int, classIndex: Int = calibrationTargetClassIndex): Float {
        val cal = calibrationByClass[classIndex.coerceIn(0, 79)].value
        val minW = cal.minWidthPx
        val maxW = cal.maxWidthPx
        val minD = cal.minDistanceM
        val maxD = cal.maxDistanceM
        if (minW <= 0 || maxW <= 0 || minW == maxW) return -1f
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

    /**
     * Berechnet die Breite in Pixeln für eine Zielentfernung über den gespeicherten
     * Kalibrierpunkten. Umkehrfunktion zu [estimateDistanceFromSavedPoint].
     */
    fun estimateWidthForDistance(savedWidthPx: Int, savedDistanceM: Float, targetDistanceM: Float): Int {
        if (savedWidthPx <= 0 || savedDistanceM <= 0f || targetDistanceM <= 0f) return -1
        return (savedWidthPx.toFloat() * savedDistanceM / targetDistanceM).toInt()
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
        private const val KEY_MIN_WIDTH_PX = "calibration_min_width_px_%d"
        private const val KEY_MIN_HEIGHT_PX = "calibration_min_height_px_%d"
        private const val KEY_MAX_WIDTH_PX = "calibration_max_width_px_%d"
        private const val KEY_MAX_HEIGHT_PX = "calibration_max_height_px_%d"
        private const val KEY_MIN_DISTANCE_M = "calibration_min_distance_m_%d"
        private const val KEY_MAX_DISTANCE_M = "calibration_max_distance_m_%d"
        private const val KEY_MIN_RATIO = "calibration_min_ratio_%d"
        private const val KEY_MAX_RATIO = "calibration_max_ratio_%d"
    }
}
