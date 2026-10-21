package com.joe703.myai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicLong

/**
 * Leichtgewichtiger Analyzer nur für OCR.
 * Verarbeitet maximal ein Frame pro Sekunde, um Ressourcen zu schonen.
 */
class OcrOnlyAnalyzer(
    private val context: Context,
    private val onResult: (List<String>) -> Unit
) : ImageAnalysis.Analyzer {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val lastProcessTime = AtomicLong(0L)

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime.get() < 1000L) {
            imageProxy.close()
            return
        }
        lastProcessTime.set(now)

        try {
            val rawBitmap = imageProxy.toBitmap()
            if (rawBitmap == null) {
                imageProxy.close()
                return
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = if (rotationDegrees != 0) rotateBitmap(rawBitmap, rotationDegrees) else rawBitmap

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val lines = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line -> line.text }
                    }.filter { it.isNotBlank() }
                    onResult(lines)
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    Log.e("OcrOnlyAnalyzer", "OCR failed: ${e.message}", e)
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e("OcrOnlyAnalyzer", "Error during OCR analysis: ${e.message}", e)
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
