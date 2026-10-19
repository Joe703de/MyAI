package com.joe703.eggdetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO-basierter Eier-Erkennungs-Analyzer.
 *
 * Erwartet unter assets/yolo_eggs.tflite ein YOLO-Modell.
 * Solange kein echtes Modell vorhanden ist, liefert der Analyzer keine Detections.
 *
 * Unterstützt gängige YOLO-TFLite-Output-Formate:
 *   - [1, N, 5 + C]  (x, y, w, h, conf, class-scores...)
 *   - [1, 5 + C, N]  (transponiert)
 *
 * Die Farbklassifikation erfolgt nach dem YOLO-Crop per einfacher Mittelwert-Analyse,
 * ohne OpenCV.
 */
class YoloEggAnalyzer(
    context: Context,
    private val onResult: (DetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val modelInputSize = 640
    private val confThreshold = 0.5f
    private val nmsThreshold = 0.45f
    private val maxDetections = 12

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var outputBoxesDim: Int = -1
    private var outputValuesDim: Int = -1
    private var transposedOutput: Boolean = false

    private val colorConfigs: List<EggColor> = loadColorConfig(context)

    init {
        try {
            val model = loadModel(context)
            val options = Interpreter.Options().apply {
                numThreads = 4
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            }
            interpreter = Interpreter(model, options)
            inspectOutputShape()
            Log.d("YoloEggAnalyzer", "YOLO interpreter initialized")
        } catch (e: Exception) {
            Log.e("YoloEggAnalyzer", "Failed to load model: ${e.message}", e)
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd("yolo_eggs.tflite").use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val channel = inputStream.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    private fun inspectOutputShape() {
        val shape = interpreter?.getOutputTensor(0)?.shape() ?: return
        if (shape.size != 3) {
            Log.w("YoloEggAnalyzer", "Unexpected output shape: ${shape.contentToString()}")
            return
        }
        // Entscheide, ob die Boxen in Dim 1 oder Dim 2 liegen
        val dim1 = shape[1]
        val dim2 = shape[2]
        transposedOutput = when {
            dim1 >= dim2 -> {
                outputBoxesDim = dim1
                outputValuesDim = dim2
                false
            }
            else -> {
                outputBoxesDim = dim2
                outputValuesDim = dim1
                true
            }
        }
        Log.d("YoloEggAnalyzer", "Output shape ${shape.contentToString()}, boxes=$outputBoxesDim, values=$outputValuesDim, transposed=$transposedOutput")
    }

    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        try {
            val bitmap = imageProxy.toBitmapSafe()
                ?: createFallbackBitmap(imageProxy.width, imageProxy.height)
            if (bitmap == null) {
                Log.w("YoloEggAnalyzer", "imageProxy.toBitmapSafe() returned null")
                onEmptyResult(startTime, imageProxy.width, imageProxy.height, null)
                return
            }

            val detections = interpreter?.let { detect(it, bitmap, imageProxy.width, imageProxy.height) }
                ?: (emptyList<DetectedObject>() to bitmap)

            Log.d("YoloEggAnalyzer", "Frame OK: raw=${bitmap.width}x${bitmap.height}, scaled=${detections.second.width}x${detections.second.height}, detections=${detections.first.size}")

            onResult(
                DetectionResult(
                    eggCount = detections.first.size,
                    candidateCount = 0,
                    s2Count = 0,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    objects = detections.first,
                    imageWidth = imageProxy.width,
                    imageHeight = imageProxy.height,
                    processedBitmap = detections.second,
                    rawBitmap = bitmap
                )
            )
        } catch (e: Exception) {
            Log.e("YoloEggAnalyzer", "Error during analysis: ${e.message}", e)
            onEmptyResult(startTime, imageProxy.width, imageProxy.height, createFallbackBitmap(imageProxy.width, imageProxy.height))
        } finally {
            imageProxy.close()
        }
    }

    private fun onEmptyResult(startTime: Long, width: Int, height: Int, bitmap: Bitmap?) {
        Log.w("YoloEggAnalyzer", "Empty result: ${width}x${height}, bitmap=${bitmap?.let { "${it.width}x${it.height}" } ?: "null"}")
        onResult(
            DetectionResult(
                eggCount = 0,
                candidateCount = 0,
                s2Count = 0,
                processingTimeMs = System.currentTimeMillis() - startTime,
                objects = emptyList(),
                imageWidth = width,
                imageHeight = height,
                processedBitmap = bitmap,
                rawBitmap = bitmap
            )
        )
    }

    private fun detect(
        interpreter: Interpreter,
        bitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int
    ): Pair<List<DetectedObject>, Bitmap> {
        val scaled = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * modelInputSize * modelInputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())

        for (y in 0 until modelInputSize) {
            for (x in 0 until modelInputSize) {
                val pixel = scaled.getPixel(x, y)
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((pixel and 0xFF) / 255f)
            }
        }

        // Eingabe: [1, 640, 640, 3]
        val inputArray = arrayOf(inputBuffer)

        // Ausgabe: Shape dynamisch aus Tensor-Metadaten
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputSize = outputShape.fold(1) { acc, value -> acc * value }
        val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
            .order(ByteOrder.nativeOrder())
        val outputArray = arrayOf(outputBuffer)

        interpreter.runForMultipleInputsOutputs(inputArray, mapOf(0 to outputArray))

        outputBuffer.rewind()
        val valuesPerBox = outputValuesDim
        if (valuesPerBox <= 0) return emptyList<DetectedObject>() to scaled

        // YOLOv8: 84 Werte = 4 Box-Koordinaten + 80 Klassen-Scores (keine objectness)
        // YOLOv5/v7: 85 Werte = 4 Box-Koordinaten + objectness + 80 Klassen-Scores
        val hasObjectness = valuesPerBox == 85
        val classCount = if (hasObjectness) valuesPerBox - 5 else valuesPerBox - 4

        Log.d("YoloEggAnalyzer", "Processing output: boxes=$outputBoxesDim, valuesPerBox=$valuesPerBox, classCount=$classCount, hasObjectness=$hasObjectness, transposed=$transposedOutput")

        val candidates = mutableListOf<RawDetection>()
        var maxSeenScore = 0f
        var maxSeenBox = -1
        for (i in 0 until outputBoxesDim) {
            val cx = readOutputValue(outputBuffer, i, 0)
            val cy = readOutputValue(outputBuffer, i, 1)
            val w = readOutputValue(outputBuffer, i, 2)
            val h = readOutputValue(outputBuffer, i, 3)

            val classScores = FloatArray(classCount) { j ->
                val valueOffset = if (hasObjectness) 5 else 4
                readOutputValue(outputBuffer, i, valueOffset + j)
            }
            val classId = classScores.indices.maxByOrNull { classScores[it] } ?: 0
            val classScore = classScores.getOrElse(classId) { 0f }

            if (classScore > maxSeenScore) {
                maxSeenScore = classScore
                maxSeenBox = i
            }

            // YOLOv8: confidence ist direkt der maximale Klassen-Score
            val finalScore = if (hasObjectness) {
                val objectness = readOutputValue(outputBuffer, i, 4)
                objectness * classScore
            } else {
                classScore
            }

            if (finalScore < confThreshold) continue

            if (candidates.size < 5) {
                Log.d("YoloEggAnalyzer", "Detection candidate box=$i class=$classId score=$finalScore box=[$cx, $cy, $w, $h]")
            }

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f

            candidates.add(
                RawDetection(
                    x1 = x1.coerceIn(0f, modelInputSize.toFloat()),
                    y1 = y1.coerceIn(0f, modelInputSize.toFloat()),
                    x2 = x2.coerceIn(0f, modelInputSize.toFloat()),
                    y2 = y2.coerceIn(0f, modelInputSize.toFloat()),
                    score = finalScore,
                    classId = classId
                )
            )
        }

        // NMS
        val nmsBoxes = nms(candidates)

        Log.d("YoloEggAnalyzer", "NMS kept ${nmsBoxes.size} detections from ${candidates.size} candidates")
        Log.d("YoloEggAnalyzer", "Max seen score was $maxSeenScore at box $maxSeenBox")

        // Skalierung auf Originalgröße
        val scaleX = originalWidth.toFloat() / modelInputSize
        val scaleY = originalHeight.toFloat() / modelInputSize

        return nmsBoxes.mapNotNull<RawDetection, DetectedObject> { raw ->
            val x = (raw.x1 * scaleX).toInt()
            val y = (raw.y1 * scaleY).toInt()
            val width = ((raw.x2 - raw.x1) * scaleX).toInt()
            val height = ((raw.y2 - raw.y1) * scaleY).toInt()

            if (width <= 0 || height <= 0) return@mapNotNull null

            val color = classifyColor(bitmap, x, y, width, height)

            DetectedObject(
                x = x,
                y = y,
                width = width,
                height = height,
                isValidated = true,
                colorR = color.r,
                colorG = color.g,
                colorB = color.b
            )
        }.take(maxDetections) to scaled
    }

    private fun readOutputValue(buffer: ByteBuffer, boxIndex: Int, valueIndex: Int): Float {
        val index = if (transposedOutput) {
            valueIndex * outputBoxesDim + boxIndex
        } else {
            boxIndex * outputValuesDim + valueIndex
        }
        return buffer.getFloat(index * 4)
    }

    private fun nms(boxes: List<RawDetection>): List<RawDetection> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }
        val kept = mutableListOf<RawDetection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i], sorted[j]) > nmsThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: RawDetection, b: RawDetection): Float {
        val x1 = max(a.x1, b.x1)
        val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2)
        val y2 = min(a.y2, b.y2)
        val interArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }

    private fun classifyColor(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): RGB {
        val clampedX = x.coerceIn(0, bitmap.width - 1)
        val clampedY = y.coerceIn(0, bitmap.height - 1)
        val clampedWidth = min(width, bitmap.width - clampedX)
        val clampedHeight = min(height, bitmap.height - clampedY)

        if (clampedWidth <= 0 || clampedHeight <= 0) {
            return RGB(255, 255, 0)
        }

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        val stepX = max(1, clampedWidth / 10)
        val stepY = max(1, clampedHeight / 10)

        for (py in clampedY until clampedY + clampedHeight step stepY) {
            for (px in clampedX until clampedX + clampedWidth step stepX) {
                val pixel = bitmap.getPixel(px, py)
                rSum += (pixel shr 16) and 0xFF
                gSum += (pixel shr 8) and 0xFF
                bSum += pixel and 0xFF
                count++
            }
        }

        if (count == 0) return RGB(255, 255, 0)

        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()

        return colorConfigs.minByOrNull<EggColor, Double> { config ->
            colorDistance(config.rgb, avgR, avgG, avgB)
        }?.rgb ?: RGB(255, 255, 0)
    }

    private fun colorDistance(rgb: RGB, r: Int, g: Int, b: Int): Double {
        val dr = rgb.r - r
        val dg = rgb.g - g
        val db = rgb.b - b
        return (dr * dr + dg * dg + db * db).toDouble()
    }

    private fun loadColorConfig(context: Context): List<EggColor> {
        return try {
            val json = context.assets.open("egg_colors.json").bufferedReader().use { it.readText() }
            Json.decodeFromString<EggColorsConfig>(json).colors
        } catch (e: Exception) {
            Log.e("YoloEggAnalyzer", "Failed to load color config: ${e.message}")
            listOf(
                EggColor("Gelb", RGB(255, 255, 0)),
                EggColor("Rot", RGB(255, 0, 0))
            )
        }
    }

    private data class RawDetection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int
    )

    private fun createFallbackBitmap(width: Int, height: Int): Bitmap {
        val w = max(width, 640)
        val h = max(height, 640)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        val paint = Paint().apply {
            color = Color.YELLOW
            textSize = 48f
        }
        canvas.drawText("FALLBACK", 20f, h / 2f, paint)
        Log.w("YoloEggAnalyzer", "Created fallback bitmap: ${w}x${h}")
        return bitmap
    }

    private fun ImageProxy.toBitmapSafe(): Bitmap? {
        Log.d("YoloEggAnalyzer", "toBitmapSafe: format=${format}, width=${width}, height=${height}, planes=${planes.size}")
        for ((i, plane) in planes.withIndex()) {
            Log.d("YoloEggAnalyzer", "Plane $i: bufferSize=${plane.buffer.remaining()}, rowStride=${plane.rowStride}, pixelStride=${plane.pixelStride}")
        }
        return toBitmapRgba() ?: toBitmapYuv()
    }

    private fun ImageProxy.toBitmapRgba(): Bitmap? {
        return try {
            if (planes.size != 1) {
                Log.d("YoloEggAnalyzer", "RGBA path skipped: ${planes.size} planes")
                return null
            }
            val buffer = planes[0].buffer
            val expected = width * height * 4
            if (buffer.remaining() < expected) {
                Log.w("YoloEggAnalyzer", "RGBA buffer too small: ${buffer.remaining()} < $expected")
                return null
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            Log.d("YoloEggAnalyzer", "RGBA toBitmap success: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e("YoloEggAnalyzer", "toBitmapRgba failed: ${e.message}", e)
            null
        }
    }

    private fun ImageProxy.toBitmapYuv(): Bitmap? {
        return try {
            if (planes.size < 3) {
                Log.w("YoloEggAnalyzer", "YUV path skipped: only ${planes.size} planes")
                return null
            }
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val ySize = width * height
            val uvSize = width * height / 2
            val nv21 = ByteArray(ySize + uvSize)

            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(col * yPixelStride)
                }
            }

            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            val uvHeight = height / 2
            val uvWidth = width / 2
            var nvPos = ySize
            for (row in 0 until uvHeight) {
                val uRowPos = row * uvRowStride
                val vRowPos = row * vPlane.rowStride
                uPlane.buffer.position(uRowPos)
                vPlane.buffer.position(vRowPos)
                for (col in 0 until uvWidth) {
                    val offset = col * uvPixelStride
                    nv21[nvPos++] = vPlane.buffer.get(offset)
                    nv21[nvPos++] = uPlane.buffer.get(offset)
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val output = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, output)
            val jpegBytes = output.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            Log.d("YoloEggAnalyzer", "YUV toBitmap success: ${bitmap?.width}x${bitmap?.height}")
            bitmap
        } catch (e: Exception) {
            Log.e("YoloEggAnalyzer", "toBitmapYuv failed: ${e.message}", e)
            null
        }
    }
}
