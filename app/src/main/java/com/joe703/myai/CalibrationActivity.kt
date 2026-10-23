package com.joe703.myai

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.joe703.myai.ui.theme.MyAITheme
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private data class FrozenCalibrationItem(
    val label: String,
    val obj: DetectedObject,
    val thumbnail: Bitmap?
)

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(executor: java.util.concurrent.Executor): T =
    suspendCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, executor)
    }

class CalibrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    CalibrationScreen()
                }
            }
        }
    }
}

@Composable
fun CalibrationScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as MyAIApplication

    var detectionResult by remember { mutableStateOf(app.currentDetectionResult) }

    DisposableEffect(app) {
        val listener = {
            detectionResult = app.currentDetectionResult
        }
        app.addListener(listener)
        onDispose {
            app.removeListener(listener)
        }
    }

    // Eigenständiger Kamera-Analyzer, damit Calibration unabhängig von MainActivity läuft
    val imageAnalyzer = remember(context) {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
            .also {
                it.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    YoloObjectAnalyzer(
                        context,
                        targetClassProvider = { app.activeDetectionClasses },
                        confThresholdProvider = { app.confidenceThreshold },
                        enableOcr = false
                    ) { result ->
                        app.updateCurrentDetectionResult(result)
                    }
                )
            }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await(ContextCompat.getMainExecutor(context))
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("MyAI", "Calibration camera binding failed", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Leerer Abstand oben wie in DebugActivity
            Box(modifier = Modifier.weight(0.1f))

            // Meta-Daten für alle erkannten Objekte der aktuellen Kalibrierklasse
            val currentClass = app.calibrationTargetClassIndex
            val allObjects: List<DetectedObject> = detectionResult?.objects ?: emptyList()
            val classFilteredObjects = remember(allObjects, currentClass) {
                allObjects.filter { obj -> obj.classId == currentClass }
            }
            val averageAspectRatio = if (classFilteredObjects.isNotEmpty()) {
                classFilteredObjects.map { it.width.toFloat() / it.height.toFloat() }.average().toFloat()
            } else {
                0f
            }
            val imageWidth = detectionResult?.imageWidth ?: 0
            val imageHeight = detectionResult?.imageHeight ?: 0
            val rawBitmap = detectionResult?.rawBitmap

            var inCalibrationMode by remember { mutableStateOf(false) }

            // Lokale Slider-Werte, werden erst beim Verlassen gespeichert
            var pendingMinDistanceM by remember { mutableFloatStateOf(app.calibrationMinDistanceM) }
            var pendingMaxDistanceM by remember { mutableFloatStateOf(app.calibrationMaxDistanceM) }
            var pendingRatioMin by remember { mutableFloatStateOf(app.calibrationRatioMin) }
            var pendingRatioMax by remember { mutableFloatStateOf(app.calibrationRatioMax) }
            var ratioMinText by remember { mutableStateOf("%.2f".format(pendingRatioMin)) }
            var ratioMaxText by remember { mutableStateOf("%.2f".format(pendingRatioMax)) }

            // Eingefrorene Kalibrierungsobjekte mit Bitmap-Kopien.
            // Bleiben erhalten, solange der Kalibrierungsmodus aktiv ist.
            var frozenItems by remember { mutableStateOf<List<FrozenCalibrationItem>>(emptyList()) }

            // Bei Klassenwechsel die gespeicherten Werte der neuen Klasse laden und eingefrorene Objekte zurücksetzen
            LaunchedEffect(app.calibrationTargetClassIndex, inCalibrationMode) {
                pendingMinDistanceM = app.calibrationMinDistanceM
                pendingMaxDistanceM = app.calibrationMaxDistanceM
                pendingRatioMin = app.calibrationRatioMin
                pendingRatioMax = app.calibrationRatioMax
                ratioMinText = "%.2f".format(pendingRatioMin)
                ratioMaxText = "%.2f".format(pendingRatioMax)
                if (inCalibrationMode) {
                    frozenItems = emptyList()
                }
            }

            // Gültige Objekte innerhalb des klassenspezifischen Seitenverhältnis-Fensters
            val ratioMin = pendingRatioMin
            val ratioMax = pendingRatioMax
            val validObjects = remember(classFilteredObjects, ratioMin, ratioMax) {
                classFilteredObjects.filter { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    ratio in ratioMin..ratioMax
                }
            }
            val farthestObject = remember(validObjects) {
                validObjects.minByOrNull { it.width }
            }
            val nearestObject = remember(validObjects) {
                validObjects.maxByOrNull { it.width }
            }

            // 5 Objekte der aktuellen Klasse, die das Fenster am knappsten verfehlen
            val missCandidates = classFilteredObjects
                .filter { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    ratio > 0f && (ratio < ratioMin || ratio > ratioMax)
                }
                .sortedBy { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    kotlin.math.min(
                        kotlin.math.abs(ratio - ratioMin),
                        kotlin.math.abs(ratio - ratioMax)
                    )
                }
                .take(5)

            // Beim Einschalten einfrieren (inkl. Bitmap-Kopie).
            // Beim Ausschalten die aktuellen eingefrorenen Objekte + Slider-Werte speichern.
            LaunchedEffect(inCalibrationMode) {
                if (inCalibrationMode) {
                    val nearest = nearestObject
                    val farthest = farthestObject
                    if (nearest != null && farthest != null) {
                        val nearestThumb = cropBitmap(rawBitmap, imageWidth, imageHeight, nearest)?.copy(Bitmap.Config.ARGB_8888, false)
                        val farthestThumb = cropBitmap(rawBitmap, imageWidth, imageHeight, farthest)?.copy(Bitmap.Config.ARGB_8888, false)
                        frozenItems = listOfNotNull(
                            farthestThumb?.let { FrozenCalibrationItem("Max", farthest, it) }
                                ?: FrozenCalibrationItem("Max", farthest, null),
                            nearestThumb?.let { FrozenCalibrationItem("Min", nearest, it) }
                                ?: FrozenCalibrationItem("Min", nearest, null)
                        ).distinctBy { it.obj }

                        // Slider initial über die GESPEICHERTEN Kalibrierwerte vorbelegen.
                        val minD = app.estimateDistanceFromSavedPoint(
                            app.calibrationMinWidthPx,
                            app.calibrationMinDistanceM,
                            nearest.width
                        ).coerceIn(10f, 300f).takeIf { it >= 0f } ?: 10f
                        val maxD = app.estimateDistanceFromSavedPoint(
                            app.calibrationMaxWidthPx,
                            app.calibrationMaxDistanceM,
                            farthest.width
                        ).coerceIn(10f, 300f).takeIf { it >= 0f } ?: 300f

                        // Sicherstellen, dass Max > Min bleibt
                        pendingMinDistanceM = minD
                        pendingMaxDistanceM = if (maxD > minD) maxD else minD + 1f
                        if (pendingMaxDistanceM > 300f) {
                            pendingMaxDistanceM = 300f
                            pendingMinDistanceM = 299f
                        }
                    }
                } else {
                    // Kalibrierung beendet -> aktuelle eingefrorene Objekte + Slider-Werte speichern
                    val currentMin = frozenItems.find { it.label == "Min" }?.obj
                    val currentMax = frozenItems.find { it.label == "Max" }?.obj
                    if (currentMin != null && currentMax != null) {
                        app.updateCalibrationObjects(
                            minWidth = currentMin.width,
                            minHeight = currentMin.height,
                            maxWidth = currentMax.width,
                            maxHeight = currentMax.height,
                            classIndex = app.targetClassIndex
                        )
                    }
                    // Texteingaben validieren und übernehmen
                    val parsedMin = ratioMinText.replace(",", ".").toFloatOrNull()?.coerceIn(0.1f, 10f) ?: pendingRatioMin
                    val parsedMax = ratioMaxText.replace(",", ".").toFloatOrNull()?.coerceIn(0.1f, 10f) ?: pendingRatioMax
                    pendingRatioMin = kotlin.math.min(parsedMin, parsedMax - 0.01f).coerceAtLeast(0.1f)
                    pendingRatioMax = kotlin.math.max(parsedMax, pendingRatioMin + 0.01f).coerceAtMost(10f)
                    ratioMinText = "%.2f".format(pendingRatioMin)
                    ratioMaxText = "%.2f".format(pendingRatioMax)

                    app.updateCalibrationDistances(minM = pendingMinDistanceM, maxM = pendingMaxDistanceM, classIndex = app.targetClassIndex)
                    app.updateCalibrationRatios(pendingRatioMin, pendingRatioMax, app.targetClassIndex)
                    app.saveCalibration()
                    frozenItems = emptyList()
                }
            }

            // Obere Status-Zeile
            CalibrationStatusBar(
                result = detectionResult,
                targetClassIndex = app.calibrationTargetClassIndex,
                targetClassSet = app.targetClassSet,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(bottom = 4.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(0.9f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (inCalibrationMode) {
                    // Kalibrierungsansicht bleibt auch bei "Keine Objekte erkannt" sichtbar
                    if (frozenItems.isEmpty()) {
                        Text(
                            text = "Keine Objekte im Bereich 1,1..1,35",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                        )
                    } else {
                        val currentMinWidth = frozenItems.find { it.label == "Min" }?.obj?.width ?: 0
                        val currentMaxWidth = frozenItems.find { it.label == "Max" }?.obj?.width ?: 0
                        frozenItems.forEach { item ->
                            when (item.label) {
                                "Max" -> CalibrationItemCard(
                                    label = item.label,
                                    obj = item.obj,
                                    thumbnail = item.thumbnail,
                                    app = app,
                                    initialDistanceM = pendingMaxDistanceM,
                                    currentMinWidth = currentMinWidth,
                                    currentMaxWidth = currentMaxWidth,
                                    otherProvider = { pendingMinDistanceM },
                                    onDistanceChange = { pendingMaxDistanceM = it }
                                )
                                else -> CalibrationItemCard(
                                    label = item.label,
                                    obj = item.obj,
                                    thumbnail = item.thumbnail,
                                    app = app,
                                    initialDistanceM = pendingMinDistanceM,
                                    currentMinWidth = currentMinWidth,
                                    currentMaxWidth = currentMaxWidth,
                                    otherProvider = { pendingMaxDistanceM },
                                    onDistanceChange = { pendingMinDistanceM = it }
                                )
                            }
                        }
                    }

                    // SV-Grenzen-Eingabefelder immer im Kalibrierungsmodus, auch ohne erkannte Objekte
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "SV-Grenzen",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = ratioMinText,
                                onValueChange = { ratioMinText = it },
                                label = { Text("Min", color = Color.White, style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                modifier = Modifier.width(100.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = ratioMaxText,
                                onValueChange = { ratioMaxText = it },
                                label = { Text("Max", color = Color.White, style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                modifier = Modifier.width(100.dp),
                                singleLine = true
                            )
                        }
                    }

                    // Debug-Daten unter den SV-Grenzen anzeigen, auch bei genau einem Objekt
                    Spacer(modifier = Modifier.height(8.dp))
                    val displayObjects = if (frozenItems.isNotEmpty()) frozenItems.map { it.obj } else emptyList()
                    if (displayObjects.isEmpty()) {
                        Text(
                            text = "Keine eingefrorenen Objekte",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                        )
                    } else {
                        displayObjects.forEachIndexed { index, obj ->
                            val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                            val outOfRange = aspectRatio < ratioMin || aspectRatio > ratioMax
                            val thumbnail = cropBitmap(rawBitmap, imageWidth, imageHeight, obj)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${index + 1}: ${obj.width}×${obj.height}px, SV ${"%.2f".format(aspectRatio)}:1",
                                    color = if (outOfRange) Color.Red else Color.Cyan,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                                    modifier = Modifier.weight(1f)
                                )
                                thumbnail?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Knappes Objekt",
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .height(56.dp)
                                            .width(84.dp)
                                            .border(1.dp, Color.Yellow)
                                    )
                                }
                            }
                        }
                    }
                }

                // Debug-Anzeige der aktuell erkannten Objekte nur wenn Kalibrierung AUS ist
                if (!inCalibrationMode) {
                    if (classFilteredObjects.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Keine Objekte der Klasse erkannt",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        classFilteredObjects.forEachIndexed { index, obj ->
                            val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                            val outOfRange = aspectRatio < ratioMin || aspectRatio > ratioMax
                            val thumbnail = missCandidates.firstOrNull { it === obj }?.let { candidate ->
                                cropBitmap(rawBitmap, imageWidth, imageHeight, candidate)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${index + 1}: ${obj.width}×${obj.height}px, SV ${"%.2f".format(aspectRatio)}:1",
                                    color = if (outOfRange) Color.Red else Color.Cyan,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                                    modifier = Modifier.weight(1f)
                                )
                                thumbnail?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Knappes Objekt",
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .height(56.dp)
                                            .width(84.dp)
                                            .border(1.dp, Color.Yellow)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Fußzeile mit Durchschnitt und Kalibrierung-Schalter
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (averageAspectRatio > 0f) {
                            "Ø SV: ${"%.2f".format(averageAspectRatio)}:1"
                        } else {
                            "Ø SV: -"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.7f))
                    )
                    Text(
                        text = "SV<${"%.2f".format(pendingRatioMin)}|>${"%.2f".format(pendingRatioMax)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.7f))
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { inCalibrationMode = !inCalibrationMode }
                    ) {
                        Text(
                            text = "Kalibrierung",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = inCalibrationMode,
                            onCheckedChange = { inCalibrationMode = it }
                        )
                    }
                }
            }

            // Klassenauswahl: nur im Set-Modus Dropdown anzeigen
            if (app.targetClassIndex == app.SET_MODE_CLASS_INDEX) {
                SimpleClassDropdown(
                    selectedIndex = app.calibrationTargetClassIndex,
                    onSelectionChanged = { app.updateCalibrationTargetClass(it) },
                    allowedIndices = app.targetClassSet,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun CalibrationStatusBar(
    result: DetectionResult?,
    targetClassIndex: Int,
    targetClassSet: List<Int>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.width(180.dp), contentAlignment = Alignment.CenterStart) {
            val className = CocoClassNames.getOrElse(targetClassIndex) { "Objekt" }
            Text(
                text = "Set: ${targetClassSet.joinToString(", ")}\n$className: ${result?.eggCount ?: 0}",
                color = Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        Box(modifier = Modifier.width(220.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "${result?.processingTimeMs ?: 0}ms@${result?.imageWidth ?: 0}×${result?.imageHeight ?: 0}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

private fun cropBitmap(rawBitmap: Bitmap?, imageWidth: Int, imageHeight: Int, obj: DetectedObject): Bitmap? {
    if (rawBitmap == null) return null
    val left = obj.x.coerceIn(0, imageWidth)
    val top = obj.y.coerceIn(0, imageHeight)
    val right = (obj.x + obj.width).coerceIn(0, imageWidth)
    val bottom = (obj.y + obj.height).coerceIn(0, imageHeight)
    return if (right > left && bottom > top) {
        Bitmap.createBitmap(rawBitmap, left, top, right - left, bottom - top)
    } else null
}

@Composable
private fun CalibrationItemCard(
    label: String,
    obj: DetectedObject,
    thumbnail: Bitmap?,
    app: MyAIApplication,
    initialDistanceM: Float,
    currentMinWidth: Int,
    currentMaxWidth: Int,
    otherProvider: () -> Float,
    onDistanceChange: (Float) -> Unit
) {
    val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f

    var distanceM by remember(label, initialDistanceM) {
        mutableFloatStateOf(initialDistanceM)
    }

    // Debug-Infos nur unter dem Min-Slider anzeigen
    val showDebug = label == "Min"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label: ${obj.width}×${obj.height}px, SV ${"%.2f".format(aspectRatio)}:1",
                color = Color.Green,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                modifier = Modifier.weight(1f)
            )
            thumbnail?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$label Objekt",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .height(56.dp)
                        .width(84.dp)
                        .border(1.dp, Color.Green)
                )
            }
        }

        Text(
            text = "Entfernung",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${distanceM.toInt()}m",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(64.dp)
            )
            Slider(
                value = distanceM,
                onValueChange = { newValue ->
                    val clamped = newValue.coerceIn(10f, 300f)
                    val other = otherProvider()
                    distanceM = if (label == "Min") {
                        clamped.coerceAtMost(other - 1f)
                    } else {
                        clamped.coerceAtLeast(other + 1f)
                    }
                    onDistanceChange(distanceM)
                },
                valueRange = 10f..300f,
                modifier = Modifier.weight(1f)
            )
        }

        if (showDebug) {
            val savedMinW = app.calibrationMinWidthPx
            val savedMinD = app.calibrationMinDistanceM
            val savedMaxW = app.calibrationMaxWidthPx
            val savedMaxD = app.calibrationMaxDistanceM
            val calcMinD = if (savedMinW > 0 && currentMinWidth > 0) savedMinD * savedMinW / currentMinWidth else -1f
            val calcMaxD = if (savedMaxW > 0 && currentMaxWidth > 0) savedMaxD * savedMaxW / currentMaxWidth else -1f
            val willSaveMaxD = otherProvider()

            Column(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    text = "gespeichert Min: ${savedMinW}px @ ${savedMinD.toInt()}m",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "gespeichert Max: ${savedMaxW}px @ ${savedMaxD.toInt()}m",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "aktuell Min: ${currentMinWidth}px @ ${calcMinD.toInt()}m",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "aktuell Max: ${currentMaxWidth}px @ ${calcMaxD.toInt()}m",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "beim Beenden speichern:",
                    color = Color.Cyan,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Min: ${currentMinWidth}px @ ${distanceM.toInt()}m",
                    color = Color.Cyan,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Max: ${currentMaxWidth}px @ ${willSaveMaxD.toInt()}m",
                    color = Color.Cyan,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleClassDropdown(
    selectedIndex: Int,
    onSelectionChanged: (Int) -> Unit,
    allowedIndices: List<Int>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = CocoClassNames.getOrElse(selectedIndex) { "Unbekannt" }
    val allowedNames = allowedIndices.mapNotNull { idx -> CocoClassNames.getOrNull(idx)?.let { idx to it } }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        TextField(
            value = "COCO #$selectedIndex: $selectedName",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allowedNames.forEach { (index, name) ->
                DropdownMenuItem(
                    text = { Text("$index: $name") },
                    onClick = {
                        onSelectionChanged(index)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
