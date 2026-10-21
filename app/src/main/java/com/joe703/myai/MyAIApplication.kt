package com.joe703.myai

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Application-Objekt, das Zustand zwischen MainActivity und DebugActivity teilt.
 * Weil jede Activity ihren eigenen Camera-Analyzer besitzt, müssen
 * Einstellungen (Confidence, Zielklasse) zentral außerhalb der Composable-State
 * leben, damit sie beide Activities gleichzeitig sehen.
 */
class MyAIApplication : Application() {

    /** aktuell ausgewählte COCO-Klasse (z. B. 2 = Auto) */
    var targetClassIndex by mutableIntStateOf(2)
        private set

    /** aktueller Confidence-Threshold, thread-safe lesbar */
    var confidenceThreshold by mutableFloatStateOf(0.3f)
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun setTargetClass(index: Int) {
        targetClassIndex = index
        notifyListeners()
    }

    fun setConfidenceThreshold(value: Float) {
        confidenceThreshold = value
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
