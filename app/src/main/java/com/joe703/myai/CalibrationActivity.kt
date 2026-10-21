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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
                        confThresholdProvider = { app.confidenceThreshold }
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

            // Auto, das das Fenster am knappsten verfehlt: am nächsten an 1.0, aber außerhalb 1.1..1.3
            val borderCandidate = objects
                .filter { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    ratio > 0f && (ratio < 1.1f || ratio > 1.3f)
                }
                .minByOrNull { obj ->
                    val ratio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                    kotlin.math.abs(ratio - 1f)
                }

            Row(
                modifier = Modifier.fillMaxWidth(0.9f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                CalibrationStatusBar(
                    result = detectionResult,
                    targetClassIndex = app.targetClassIndex,
                    modifier = Modifier.weight(1f)
                )

                borderCandidate?.let { obj ->
                    val imageWidth = detectionResult?.imageWidth ?: 0
                    val imageHeight = detectionResult?.imageHeight ?: 0
                    val rawBitmap = detectionResult?.rawBitmap
                    val left = obj.x.coerceIn(0, imageWidth)
                    val top = obj.y.coerceIn(0, imageHeight)
                    val right = (obj.x + obj.width).coerceIn(0, imageWidth)
                    val bottom = (obj.y + obj.height).coerceIn(0, imageHeight)
                    val cropped = if (right > left && bottom > top && rawBitmap != null) {
                        Bitmap.createBitmap(rawBitmap, left, top, right - left, bottom - top)
                    } else null

                    cropped?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Knappstes Auto",
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .height(40.dp)
                                .width(60.dp)
                                .border(1.dp, Color.Yellow)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(0.9f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (objects.isEmpty()) {
                    Text(
                        text = "Keine Objekte erkannt",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                    )
                } else {
                    objects.forEachIndexed { index, obj ->
                        val aspectRatio = if (obj.height > 0) obj.width.toFloat() / obj.height.toFloat() else 0f
                        val outOfRange = aspectRatio < 1.1f || aspectRatio > 1.3f
                        Text(
                            text = "${index + 1}: ${obj.width}×${obj.height}px, SV ${"%.2f".format(aspectRatio)}:1",
                            color = if (outOfRange) Color.Red else Color.Cyan,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Durchschnittliches Seitenverhältnis unten links, oberhalb der Navigationsleiste
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(top = 4.dp, bottom = 8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
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
