package com.joe703.myai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joe703.myai.ui.theme.MyAITheme
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    MyAIApp()
                }
            }
        }
    }
}

@Composable
fun MyAIApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
    }

    val currentContext = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            MainCameraScreen()
        } else {
            Text(
                text = "Kamera-Berechtigung wird benötigt",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            LaunchedEffect(currentContext) {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

@Composable
fun MainCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as MyAIApplication

    var detectionResult by remember {
        mutableStateOf(
            DetectionResult(
                eggCount = 0,
                candidateCount = 0,
                s2Count = 0,
                processingTimeMs = 0L,
                objects = emptyList(),
                imageWidth = 0,
                imageHeight = 0,
                rawBitmap = createTestBitmap()
            )
        )
    }
    var previewViewSize by remember { mutableStateOf(android.util.Size(0, 0)) }
    var frameCount by remember { mutableStateOf(0) }

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
                        app.setCurrentDetectionResult(result)
                        val activity = context as? ComponentActivity
                        activity?.runOnUiThread {
                            detectionResult = result
                            frameCount++
                        } ?: run {
                            detectionResult = result
                            frameCount++
                        }
                    }
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status-Zeile oben
            MainStatusBar(
                result = detectionResult,
                targetClassIndex = app.targetClassIndex,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(horizontal = 3.dp, vertical = 4.dp)
            )

            // Kamera-Preview nimmt den verfügbaren Platz nach oben
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                val zoomedBitmap = detectionResult.zoomedObject?.bitmap

                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                        previewView.post {
                            previewViewSize = android.util.Size(previewView.width, previewView.height)
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = CameraPreview.Builder()
                                    .build()
                                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalyzer
                                )
                            } catch (e: Exception) {
                                Log.e("MyAI", "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds()
                )

                if (previewViewSize.width > 0 && detectionResult.imageWidth > 0) {
                    DetectionOverlay(
                        objects = detectionResult.objects,
                        previewWidth = previewViewSize.width.toFloat(),
                        previewHeight = previewViewSize.height.toFloat(),
                        imageWidth = detectionResult.imageWidth.toFloat(),
                        imageHeight = detectionResult.imageHeight.toFloat()
                    )
                }

                // Badge über jedem erkannten gelben Auto anzeigen
                if (previewViewSize.width > 0 && detectionResult.imageWidth > 0) {
                    ObjectBadges(
                        objects = detectionResult.objects,
                        previewWidth = previewViewSize.width.toFloat(),
                        previewHeight = previewViewSize.height.toFloat(),
                        imageWidth = detectionResult.imageWidth.toFloat(),
                        imageHeight = detectionResult.imageHeight.toFloat()
                    )
                }
            }

            // Unten Platz für zukünftige Elemente
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 8.dp)
            ) {
                // Debug-Button unten rechts
                Button(
                    onClick = {
                        val intent = Intent(context, DebugActivity::class.java)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.DarkGray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text("Debug")
                }

                // Kalibrierung-Button links daneben
                Button(
                    onClick = {
                        val intent = Intent(context, CalibrationActivity::class.java)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.DarkGray,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 80.dp)
                ) {
                    Text("Kalibrierung")
                }
            }
        }
    }
}

@Composable
fun ObjectBadges(
    objects: List<DetectedObject>,
    previewWidth: Float,
    previewHeight: Float,
    imageWidth: Float,
    imageHeight: Float
) {
    val scaleX = previewWidth / imageWidth
    val scaleY = previewHeight / imageHeight
    val scale = max(scaleX, scaleY)

    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale
    val offsetX = (previewWidth - scaledWidth) / 2
    val offsetY = (previewHeight - scaledHeight) / 2

    Box(modifier = Modifier.fillMaxSize()) {
        objects.forEach { obj ->
            if (obj.colorName != "Gelb") return@forEach

            val finalX = (obj.x.toFloat() * scale) + offsetX
            val finalY = (obj.y.toFloat() * scale) + offsetY

            Box(
                modifier = Modifier
                    .offset(
                        x = finalX.dp,
                        y = (finalY - 40f).coerceAtLeast(0f).dp
                    )
                    .background(
                        color = Color(0xFF1A1A1A).copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Gelbes Auto erkannt",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun MainStatusBar(
    result: DetectionResult,
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
                text = "$className: ${result.eggCount}",
                color = Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        Box(modifier = Modifier.width(220.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "${result.processingTimeMs}ms@${result.imageWidth}x${result.imageHeight}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}
