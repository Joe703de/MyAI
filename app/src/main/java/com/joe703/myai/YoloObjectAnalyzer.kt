package com.joe703.myai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO-basierter Eier-Erkennungs-Analyzer.
 *
 * Erwartet unter assets/yolo_objects.tflite ein YOLO-Modell.
 * Solange kein echtes Modell vorhanden ist, liefert der Analyzer keine Detections.
 *
 * Unterstützt gängige YOLO-TFLite-Output-Formate:
 *   - [1, N, 5 + C]  (x, y, w, h, conf, class-scores...)
 *   - [1, 5 + C, N]  (transponiert)
 *
 * Die Farbklassifikation erfolgt im erkannten Bereich per HSV-Pixelabstimmung,
 * ohne OpenCV.
 */
class YoloObjectAnalyzer(
    context: Context,
    private val targetClassProvider: () -> Int,
    private val confThresholdProvider: () -> Float,
    private val onResult: (DetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val modelInputSize = 640
    private val nmsThreshold = 0.45f
    private val maxDetections = 25
    private val inputMode = 2 // fest RGB 0-255

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var outputBoxesDim: Int = -1
    private var outputValuesDim: Int = -1
    private var transposedOutput: Boolean = false
    private var lastZoomedObject: ZoomedObject? = null
    private var yellowMissFrames: Int = 0
    private val yellowMissThreshold: Int = 1
    private var lastRecognizedText: String = ""
    private var lastRecognizedTextLines: List<String> = emptyList()

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
            inspectModel(interpreter)
            inspectOutputShape()
            Log.i("YoloObjectAnalyzer", "YOLO interpreter initialized")
        } catch (e: Exception) {
            Log.i("YoloObjectAnalyzer", "Failed to load model: ${e.message}", e)
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd("yolo_objects.tflite").use { fileDescriptor ->
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

    private fun inspectModel(interpreter: Interpreter?) {
        if (interpreter == null) {
            Log.i("YoloObjectAnalyzer", "inspectModel called with null interpreter")
            return
        }
        try {
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            Log.d(
                "YoloObjectAnalyzer",
                "Model input: shape=${inputTensor.shape().contentToString()}, dtype=${inputTensor.dataType()}"
            )
            Log.d(
                "YoloObjectAnalyzer",
                "Model output: shape=${outputTensor.shape().contentToString()}, dtype=${outputTensor.dataType()}"
            )
        } catch (e: Exception) {
            Log.i("YoloObjectAnalyzer", "inspectModel failed: ${e.message}", e)
        }
    }

    private fun inspectOutputShape() {
        val shape = interpreter?.getOutputTensor(0)?.shape() ?: return
        if (shape.size != 3) {
            Log.i("YoloObjectAnalyzer", "Unexpected output shape: ${shape.contentToString()}")
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
        Log.i("YoloObjectAnalyzer", "Output shape ${shape.contentToString()}, boxes=$outputBoxesDim, values=$outputValuesDim, transposed=$transposedOutput")
    }

    override fun analyze(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        try {
            val rawBitmap = imageProxy.toBitmap()
            if (rawBitmap == null) {
                Log.i("YoloObjectAnalyzer", "imageProxy.toBitmap() returned null")
                onEmptyResult(startTime, imageProxy.width, imageProxy.height, createFallbackBitmap(imageProxy.width, imageProxy.height))
                imageProxy.close()
                return
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = if (rotationDegrees != 0) rotateBitmap(rawBitmap, rotationDegrees) else rawBitmap

            // OCR runs asynchronously on the same bitmap and closes the ImageProxy when done.
            // Do not call imageProxy.close() elsewhere in this path.
            runOcr(imageProxy, bitmap)

            val preprocessTime = System.currentTimeMillis() - startTime
            val detections = interpreter?.let { detect(it, bitmap, bitmap.width, bitmap.height, targetClassProvider(), preprocessTime, confThresholdProvider()) }
                ?: DetectedFrame(
                    emptyList(),
                    scaleToModelInput(bitmap),
                    0f,
                    -1,
                    0f,
                    0f,
                    -1,
                    TimingBreakdown(preprocessMs = preprocessTime, totalMs = System.currentTimeMillis() - startTime, delegateName = "no-model")
                )

            Log.i(
                "YoloObjectAnalyzer",
                "Frame OK: class=${targetClassProvider()} raw=${rawBitmap.width}x${rawBitmap.height}, rotated=${bitmap.width}x${bitmap.height}, " +
                "scaled=${detections.scaled.width}x${detections.scaled.height}, detections=${detections.objects.size}, " +
                "maxScore=${detections.maxScore}, topClass=${detections.topClassId}, topCarScore=${detections.topCarScore} " +
                "timing=${detections.timing}"
            )

            onResult(
                DetectionResult(
                    eggCount = detections.objects.size,
                    candidateCount = 0,
                    s2Count = 0,
                    processingTimeMs = detections.timing.totalMs,
                    objects = detections.objects,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    processedBitmap = detections.scaled,
                    rawBitmap = bitmap,
                    maxScore = detections.maxScore,
                    topClassId = detections.topClassId,
                    inputMode = inputMode,
                    topCarScore = detections.topCarScore,
                    maxGlobalScore = detections.maxGlobalScore,
                    maxGlobalClassId = detections.maxGlobalClassId,
                    timingMs = detections.timing,
                    zoomedObject = detections.zoomedObject,
                    recognizedText = lastRecognizedText,
                    recognizedTextLines = lastRecognizedTextLines
                )
            )
        } catch (e: Exception) {
            Log.i("YoloObjectAnalyzer", "Error during analysis: ${e.message}", e)
            onEmptyResult(startTime, imageProxy.width, imageProxy.height, createFallbackBitmap(imageProxy.width, imageProxy.height))
            imageProxy.close()
        }
    }

    private fun onEmptyResult(startTime: Long, width: Int, height: Int, bitmap: Bitmap?) {
        Log.i("YoloObjectAnalyzer", "Empty result: ${width}x${height}, bitmap=${bitmap?.let { "${it.width}x${it.height}" } ?: "null"}")
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
                rawBitmap = bitmap,
                timingMs = TimingBreakdown(
                    preprocessMs = System.currentTimeMillis() - startTime,
                    totalMs = System.currentTimeMillis() - startTime,
                    delegateName = "error"
                )
            )
        )
    }

    private data class DetectedFrame(
        val objects: List<DetectedObject>,
        val scaled: Bitmap,
        val maxScore: Float,
        val topClassId: Int,
        val topCarScore: Float,
        val maxGlobalScore: Float,
        val maxGlobalClassId: Int,
        val timing: TimingBreakdown,
        val zoomedObject: ZoomedObject? = null
    )

    private fun detect(
        interpreter: Interpreter,
        bitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        targetClassId: Int,
        preprocessMs: Long,
        confThreshold: Float
    ): DetectedFrame {
        val scaled = scaleToModelInput(bitmap)
        val inputBuffer = bitmapToFloatBuffer(scaled, normalize255 = true, bgr = false)

        Log.i("YoloObjectAnalyzer", "Running inference for targetClass=$targetClassId")

        val inferenceStart = System.currentTimeMillis()

        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputSize = outputShape.fold(1) { acc, value -> acc * value }
        val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
            .order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)

        val inferenceMs = System.currentTimeMillis() - inferenceStart
        outputBuffer.rewind()

        val postprocessStart = System.currentTimeMillis()
        val outputReadStart = System.currentTimeMillis()

        // Kompletten Output in ein FloatArray kopieren, dann schnell indexieren
        val outputArray = FloatArray(outputSize)
        outputBuffer.asFloatBuffer().get(outputArray)
        val valuesPerBox = outputValuesDim

        if (valuesPerBox <= 0) return DetectedFrame(
            emptyList(), scaled, 0f, -1, 0f, 0f, -1,
            TimingBreakdown(preprocessMs = preprocessMs, inferenceMs = inferenceMs, postprocessMs = 0L, totalMs = preprocessMs + inferenceMs, delegateName = if (gpuDelegate != null) "GPU" else "CPU")
        )

        val hasObjectness = valuesPerBox == 85
        val classCount = if (hasObjectness) valuesPerBox - 5 else valuesPerBox - 4

        Log.i("YoloObjectAnalyzer", "Processing output: boxes=$outputBoxesDim, valuesPerBox=$valuesPerBox, classCount=$classCount, hasObjectness=$hasObjectness, transposed=$transposedOutput")

        val candidates = mutableListOf<RawDetection>()
        var maxSeenScore = 0f
        var maxSeenBox = -1
        var topClassId = -1
        var topConfidence = 0f
        var topCarScore = 0f
        var candidateCount = 0
        var maxGlobalScore = 0f
        var maxGlobalClassId = -1
        val classScores = FloatArray(classCount)

        for (i in 0 until outputBoxesDim) {
            val cx = readOutputValue(outputArray, i, 0)
            val cy = readOutputValue(outputArray, i, 1)
            val w = readOutputValue(outputArray, i, 2)
            val h = readOutputValue(outputArray, i, 3)

            // Bei YOLOv8-Format (85 Werte) erst objectness prüfen.
            // Nur bei relevantem objectness die Klassen-Scores auslesen.
            val objectness = if (hasObjectness) readOutputValue(outputArray, i, 4) else 1f
            if (hasObjectness && objectness < confThreshold * 0.5f) {
                continue
            }

            val valueOffset = if (hasObjectness) 5 else 4
            for (j in classScores.indices) {
                classScores[j] = readOutputValue(outputArray, i, valueOffset + j)
            }
            val classId = classScores.indices.maxByOrNull { classScores[it] } ?: 0
            val classScore = classScores.getOrElse(classId) { 0f }
            val targetScore = classScores.getOrElse(targetClassId) { 0f }

            // Globale Top-Klasse unabhängig vom Ziel-Filter merken
            if (classScore > maxGlobalScore) {
                maxGlobalScore = classScore
                maxGlobalClassId = classId
            }

            if (classScore > maxSeenScore) {
                maxSeenScore = classScore
                maxSeenBox = i
                topClassId = classId
                topConfidence = objectness
            }

            // Besten Target-Score für Debug-Anzeige merken
            if (targetScore > topCarScore) {
                topCarScore = targetScore
            }

            // Box verwenden, wenn der Target-Score über dem Threshold liegt
            if (targetScore < confThreshold) continue

            candidateCount++

            if (candidateCount <= 5) {
                Log.i("YoloObjectAnalyzer", "Detection candidate box=$i class=$classId targetScore=$targetScore box=[$cx, $cy, $w, $h]")
            }

            val finalScore = targetScore

            val x1 = (cx - w / 2f) * modelInputSize
            val y1 = (cy - h / 2f) * modelInputSize
            val x2 = (cx + w / 2f) * modelInputSize
            val y2 = (cy + h / 2f) * modelInputSize

            candidates.add(
                RawDetection(
                    x1 = x1.coerceIn(0f, modelInputSize.toFloat()),
                    y1 = y1.coerceIn(0f, modelInputSize.toFloat()),
                    x2 = x2.coerceIn(0f, modelInputSize.toFloat()),
                    y2 = y2.coerceIn(0f, modelInputSize.toFloat()),
                    score = finalScore,
                    classId = targetClassId
                )
            )
        }

        val postReadMs = System.currentTimeMillis() - outputReadStart

        Log.i("YoloObjectAnalyzer", "Top score: class=$topClassId confidence=$topConfidence maxClassScore=$maxSeenScore topTargetScore=$topCarScore candidateCount=$candidateCount at box=$maxSeenBox")

        val nmsStart = System.currentTimeMillis()
        val nmsBoxes = nms(candidates)
        val postNmsMs = System.currentTimeMillis() - nmsStart

        Log.i("YoloObjectAnalyzer", "NMS kept ${nmsBoxes.size} detections from ${candidates.size} candidates")
        val colorStart = System.currentTimeMillis()
        var zoomedObject: ZoomedObject? = null
        nmsBoxes.forEachIndexed { index, raw ->
            Log.i("YoloObjectAnalyzer", "NMS result $index: score=${raw.score} box=[${raw.x1}, ${raw.y1}, ${raw.x2}, ${raw.y2}]")
        }
        Log.i("YoloObjectAnalyzer", "Max seen score was $maxSeenScore at box $maxSeenBox")

        val postprocessMs = System.currentTimeMillis() - postprocessStart

        // Center-Crop und Modellskalierung ins vollständige Bitmap zurückrechnen.
        // Dieselben ganzzahligen Ausschnitt-Offsets wie in cropToCenterSquare verwenden.
        val cropSize = min(originalWidth, originalHeight)
        val cropX = (originalWidth - cropSize) / 2
        val cropY = (originalHeight - cropSize) / 2
        val scale = cropSize.toFloat() / modelInputSize

        val objects = nmsBoxes.mapNotNull<RawDetection, DetectedObject> { raw ->
                val x = (raw.x1 * scale + cropX).toInt()
                val y = (raw.y1 * scale + cropY).toInt()
                val width = ((raw.x2 - raw.x1) * scale).toInt()
                val height = ((raw.y2 - raw.y1) * scale).toInt()

                if (width <= 0 || height <= 0) return@mapNotNull null

                val color = classifyColor(bitmap, x, y, width, height)
                val colorName = colorConfigs.firstOrNull { it.rgb.r == color.r && it.rgb.g == color.g && it.rgb.b == color.b }?.name ?: ""

                if (zoomedObject == null && colorName == "Gelb") {
                    zoomedObject = createZoomedObject(bitmap, x, y, width, height, colorName, raw.score)
                }

                DetectedObject(
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    isValidated = true,
                    colorR = color.r,
                    colorG = color.g,
                    colorB = color.b,
                    colorName = colorName
                )
            }.take(maxDetections)
        val postColorMs = System.currentTimeMillis() - colorStart

        // Entprellen: Gelbes Auto bleibt erhalten, bis 2 Frames hintereinander keins erkannt wird.
        val finalZoomedObject = when {
            zoomedObject != null -> {
                yellowMissFrames = 0
                lastZoomedObject = zoomedObject
                zoomedObject
            }
            yellowMissFrames < yellowMissThreshold -> {
                yellowMissFrames++
                lastZoomedObject
            }
            else -> {
                lastZoomedObject = null
                null
            }
        }

        return DetectedFrame(
            objects = objects,
            scaled = scaled,
            maxScore = maxSeenScore,
            topClassId = topClassId,
            topCarScore = topCarScore,
            maxGlobalScore = maxGlobalScore,
            maxGlobalClassId = maxGlobalClassId,
            timing = TimingBreakdown(
                preprocessMs = preprocessMs,
                inferenceMs = inferenceMs,
                postprocessMs = postprocessMs,
                postReadMs = postReadMs,
                postNmsMs = postNmsMs,
                postColorMs = postColorMs,
                totalMs = preprocessMs + inferenceMs + postprocessMs,
                delegateName = if (gpuDelegate != null) "GPU" else "CPU"
            ),
            zoomedObject = finalZoomedObject
        )
    }

    private fun readOutputValue(array: FloatArray, boxIndex: Int, valueIndex: Int): Float {
        val index = if (transposedOutput) {
            valueIndex * outputBoxesDim + boxIndex
        } else {
            boxIndex * outputValuesDim + valueIndex
        }
        return array[index]
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


    private fun createZoomedObject(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int, colorName: String, score: Float): ZoomedObject? {
        val left = x.coerceIn(0, bitmap.width)
        val top = y.coerceIn(0, bitmap.height)
        val right = (x + width).coerceIn(0, bitmap.width)
        val bottom = (y + height).coerceIn(0, bitmap.height)
        if (right <= left || bottom <= top) return null
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val bordered = drawBorder(cropped, 0xFFFFFF00.toInt(), borderWidth = 4)
        return ZoomedObject(bordered, colorName, score)
    }

    private fun drawBorder(bitmap: Bitmap, color: Int, borderWidth: Int): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            this.color = color
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = borderWidth.toFloat()
            isAntiAlias = false
        }
        canvas.drawRect(
            borderWidth / 2f,
            borderWidth / 2f,
            result.width - borderWidth / 2f,
            result.height - borderWidth / 2f,
            paint
        )
        return result
    }

    private fun classifyColor(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): RGB {
        val unknown = RGB(128, 128, 128)
        val left = x.coerceIn(0, bitmap.width)
        val top = y.coerceIn(0, bitmap.height)
        val right = (x + width).coerceIn(0, bitmap.width)
        val bottom = (y + height).coerceIn(0, bitmap.height)
        if (right <= left || bottom <= top) return unknown

        // Rand etwas auslassen: Dort liegt besonders häufig Hintergrund.
        val insetX = (right - left) / 10
        val insetY = (bottom - top) / 10
        val sampleLeft = left + insetX
        val sampleTop = top + insetY
        val sampleRight = right - insetX
        val sampleBottom = bottom - insetY
        if (sampleRight <= sampleLeft || sampleBottom <= sampleTop) return unknown

        val stepX = max(1, (sampleRight - sampleLeft) / 24)
        val stepY = max(1, (sampleBottom - sampleTop) / 24)
        val names = listOf("Rot", "Gelb", "Grün", "Blau")
        val votes = IntArray(names.size)
        val hsv = FloatArray(3)
        var sampled = 0
        var chromatic = 0

        // Komplette Pixelzeile in einem Rutsch auslesen, dann abtasten.
        val rowPixels = IntArray(sampleRight - sampleLeft)

        for (py in sampleTop until sampleBottom step stepY) {
            bitmap.getPixels(rowPixels, 0, rowPixels.size, sampleLeft, py, rowPixels.size, 1)
            var pxIndex = 0
            while (pxIndex < rowPixels.size) {
                sampled++
                Color.colorToHSV(rowPixels[pxIndex], hsv)
                // Keine Abstimmung durch Grau, Weiß oder sehr dunkle Schatten.
                if (hsv[1] < 0.30f || hsv[2] < 0.20f) {
                    pxIndex += stepX
                    continue
                }
                chromatic++
                val hue = hsv[0]
                val index = when {
                    hue < 25f || hue >= 335f -> 0
                    hue >= 35f && hue < 75f -> 1
                    hue >= 75f && hue < 170f -> 2
                    hue >= 190f && hue < 265f -> 3
                    else -> -1
                }
                if (index >= 0) votes[index]++
                pxIndex += stepX
            }
        }

        val winner = votes.indices.maxByOrNull { votes[it] } ?: return unknown
        if (sampled == 0 || votes[winner] < 3 ||
            votes[winner] < sampled * 0.10f || votes[winner] <= chromatic * 0.60f
        ) return unknown

        return colorConfigs.firstOrNull { it.name == names[winner] }?.rgb ?: unknown
    }

    private fun loadColorConfig(context: Context): List<EggColor> {
        return try {
            val json = context.assets.open("egg_colors.json").bufferedReader().use { it.readText() }
            Json.decodeFromString<EggColorsConfig>(json).colors
        } catch (e: Exception) {
            Log.i("YoloObjectAnalyzer", "Failed to load color config: ${e.message}")
            listOf(
                EggColor("Gelb", RGB(255, 255, 0)),
                EggColor("Rot", RGB(255, 0, 0)),
                EggColor("Grün", RGB(0, 255, 0)),
                EggColor("Blau", RGB(0, 0, 255))
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

    private fun bitmapToFloatBuffer(bitmap: Bitmap, normalize255: Boolean, bgr: Boolean): ByteBuffer {
        return bitmapToFloatBufferNCHW(bitmap, normalize255, bgr)
    }

    private fun bitmapToFloatBufferNCHW(bitmap: Bitmap, normalize255: Boolean, bgr: Boolean): ByteBuffer {
        val size = modelInputSize * modelInputSize * 3 * 4
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        val r = FloatArray(modelInputSize * modelInputSize)
        val g = FloatArray(modelInputSize * modelInputSize)
        val b = FloatArray(modelInputSize * modelInputSize)
        for (y in 0 until modelInputSize) {
            for (x in 0 until modelInputSize) {
                val pixel = bitmap.getPixel(x, y)
                val rf = ((pixel shr 16) and 0xFF).toFloat()
                val gf = ((pixel shr 8) and 0xFF).toFloat()
                val bf = (pixel and 0xFF).toFloat()
                val scale = if (normalize255) 255f else 1f
                val idx = y * modelInputSize + x
                if (bgr) {
                    r[idx] = bf / scale
                    g[idx] = gf / scale
                    b[idx] = rf / scale
                } else {
                    r[idx] = rf / scale
                    g[idx] = gf / scale
                    b[idx] = bf / scale
                }
            }
        }
        r.forEach { buffer.putFloat(it) }
        g.forEach { buffer.putFloat(it) }
        b.forEach { buffer.putFloat(it) }
        buffer.rewind()
        return buffer
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropToCenterSquare(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, x, y, size, size)
    }

    private fun scaleToModelInput(bitmap: Bitmap): Bitmap {
        val square = cropToCenterSquare(bitmap)
        return Bitmap.createScaledBitmap(square, modelInputSize, modelInputSize, true)
    }

    private fun runOcr(imageProxy: ImageProxy, bitmap: Bitmap) {
        // Use the already-rotated bitmap. Do not touch imageProxy.image after analyze() returns.
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks.flatMap { block ->
                    block.lines.map { line -> line.text }
                }.filter { it.isNotBlank() }
                val text = lines.joinToString("\n")
                if (text.isNotBlank()) {
                    lastRecognizedText = text
                    lastRecognizedTextLines = lines
                    Log.i("YoloObjectAnalyzer", "OCR lines: $lines")
                }
                imageProxy.close()
            }
            .addOnFailureListener { e ->
                Log.e("YoloObjectAnalyzer", "OCR failed: ${e.message}", e)
                imageProxy.close()
            }
    }

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
        Log.i("YoloObjectAnalyzer", "Created fallback bitmap: ${w}x${h}")
        return bitmap
    }

    private fun ImageProxy.toBitmapSafe(): Bitmap? {
        return try {
            val bitmap = this.toBitmap()
            Log.i("YoloObjectAnalyzer", "CameraX toBitmap success: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: NoSuchMethodError) {
            Log.i("YoloObjectAnalyzer", "CameraX toBitmap not available in this version", e)
            null
        } catch (e: Exception) {
            Log.i("YoloObjectAnalyzer", "CameraX toBitmap failed: ${e.message}", e)
            null
        }
    }
}
