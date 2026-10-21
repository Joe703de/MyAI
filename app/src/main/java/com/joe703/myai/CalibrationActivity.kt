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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
                        targetClassProvider = { app.targetClassIndex },
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

            // Meta-Daten für alle erkannten Objekte
            val objects = detectionResult?.objects ?: emptyList()
            val averageAspectRatio = if (objects.isNotEmpty()) {
                objects.map { it.width.toFloat() / it.height.toFloat() }.average().toFloat()
            } else {
                0f
            }
            val imageWidth = detectionResult?.imageWidth ?: 0
            val imageHeight = detectionResult?.imageHeight ?: 0
            val rawBitmap = detectionResult?.rawBitmap

            var inCalibrationMode by remember { mutableStateOf(false) }

            // Eingefrorene Kalibrierungsobjekte mit Bitmap-Kopien.
            // Bleiben erhalten, solange der Kalibrierungsmodus aktiv ist.
            var frozenItems by remember { mutableStateOf<List<FrozenCalibrationItem>>(emptyList()) }

            // Gültige Objekte innerhalb des Seitenverhältnis-Fensters 1.1..1.35
            val validObjects = remember(objects) {
                objects.filter { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    ratio in 1.1f..1.35f
                }
            }
            val liveMin = validObjects.minByOrNull { it.width }
            val liveMax = validObjects.maxByOrNull { it.width }

            // 5 Autos, die das Fenster am knappsten verfehlen (am nächsten an 1.0, aber außerhalb 1.1..1.35)
            val missCandidates = objects
                .filter { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    ratio > 0f && (ratio < 1.1f || ratio > 1.35f)
                }
                .sortedBy { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    kotlin.math.abs(ratio - 1f)
                }
                .take(5)

            // Beim Einschalten einfrieren (inkl. Bitmap-Kopie), beim Ausschalten löschen
            LaunchedEffect(inCalibrationMode) {
                if (inCalibrationMode) {
                    val min = liveMin
                    val max = liveMax
                    if (min != null && max != null) {
                        val minThumb = cropBitmap(rawBitmap, imageWidth, imageHeight, min)?.copy(Bitmap.Config.ARGB_8888, false)
                        val maxThumb = cropBitmap(rawBitmap, imageWidth, imageHeight, max)?.copy(Bitmap.Config.ARGB_8888, false)
                        frozenItems = listOfNotNull(
                            minThumb?.let { FrozenCalibrationItem("Min", min, it) }
                                ?: FrozenCalibrationItem("Min", min, null),
                            maxThumb?.let { FrozenCalibrationItem("Max", max, it) }
                                ?: FrozenCalibrationItem("Max", max, null)
                        ).distinctBy { it.obj }

                        app.updateCalibrationObjects(
                            minWidth = min.width,
                            minHeight = min.height,
                            maxWidth = max.width,
                            maxHeight = max.height
                        )
                    }
                } else {
                    frozenItems = emptyList()
                    app.clearCalibrationObjects()
                }
            }

            // Obere Status-Zeile
            CalibrationStatusBar(
                result = detectionResult,
                targetClassIndex = app.targetClassIndex,
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
                        frozenItems.forEach { item ->
                            CalibrationItemCard(
                                label = item.label,
                                obj = item.obj,
                                thumbnail = item.thumbnail,
                                app = app
                            )
                        }
                    }
                } else if (objects.isEmpty()) {
                    Text(
                        text = "Keine Objekte erkannt",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                    )
                } else {
                    objects.forEachIndexed { index, obj ->
                        val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                        val outOfRange = aspectRatio < 1.1f || aspectRatio > 1.35f
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
                                    contentDescription = "Knappes Auto",
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

            // Fußzeile mit Durchschnitt, Schwellen und Kalibrierung-Schalter
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
                        text = "SV<1,1|>1,35",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.7f))
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clickable { inCalibrationMode = !inCalibrationMode }
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
    }
}

@Composable
fun CalibrationStatusBar(
    result: DetectionResult?,
    targetClassIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
            val className = CocoClassNames.getOrElse(targetClassIndex) { "Objekt" }
            Text(
                text = "$className: ${result?.eggCount ?: 0}",
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
    app: MyAIApplication
) {
    val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f

    // Startwerte aus Application oder Standard 10..200 m
    val initialMin = app.calibrationMinDistanceM
    val initialMax = app.calibrationMaxDistanceM
    var distanceM by remember(label, initialMin, initialMax) {
        mutableFloatStateOf(if (label == "Min") initialMin else initialMax)
    }

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
                    distanceM = newValue
                    if (label == "Min") {
                        app.updateCalibrationDistances(minM = newValue, maxM = app.calibrationMaxDistanceM)
                    } else {
                        app.updateCalibrationDistances(minM = app.calibrationMinDistanceM, maxM = newValue)
                    }
                },
                valueRange = 10f..200f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
