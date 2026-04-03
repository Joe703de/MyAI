package com.joe703.eggdetect

import android.Manifest
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joe703.eggdetect.ui.theme.EggdetectTheme
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isOpenCVInitialized = OpenCVLoader.initDebug()
        Log.d("OpenCV", "OpenCV initialized: $isOpenCVInitialized")

        setContent {
            EggdetectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    EggDetectApp()
                }
            }
        }
    }
}

@Composable
fun EggDetectApp() {
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
            CameraScreen()
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
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detectionResult by remember { mutableStateOf(DetectionResult(0, 0, 0, 0L, emptyList(), 0, 0)) }
    var previewViewSize by remember { mutableStateOf(android.util.Size(0, 0)) }

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
                    EggAnalyzer2Stage(context) { result ->
                        detectionResult = result
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
            Box(modifier = Modifier.weight(0.1f))
            StatusBar(detectionResult)

            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize().clipToBounds()
                    )

                    if (previewViewSize.width > 0 && detectionResult.imageWidth > 0) {
                        DetectionOverlayFixed(
                            objects = detectionResult.objects,
                            previewWidth = previewViewSize.width.toFloat(),
                            previewHeight = previewViewSize.height.toFloat(),
                            imageWidth = detectionResult.imageWidth.toFloat(),
                            imageHeight = detectionResult.imageHeight.toFloat()
                        )
                    }
                }
            }

            EggIndicatorRow(count = detectionResult.eggCount, modifier = Modifier.weight(0.15f))
            Box(modifier = Modifier.weight(0.05f))
        }
    }
}

@Composable
fun StatusBar(result: DetectionResult) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Links: S1 (immer grau, feste Breite ~1 Stelle breiter)
            Box(modifier = Modifier.width(90.dp)) {
                Text(
                    text = "S1: ${result.candidateCount}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Mitte: S2 (ca 4 Stellen nach links)
            Box(modifier = Modifier.width(50.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = "S2: ${result.s2Count}",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Rechts: Framerate@Auflösung (breiter wie vorher)
            Box(modifier = Modifier.width(220.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = "${result.processingTimeMs}ms@${result.imageWidth}x${result.imageHeight}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * OVERLAY - Canvas hat volle PreviewView-Größe, Koordinaten werden skaliert
 */
@Composable
fun DetectionOverlayFixed(
    objects: List<DetectedObject>,
    previewWidth: Float,
    previewHeight: Float,
    imageWidth: Float,
    imageHeight: Float
) {
    // Nach Rotation: width/height getauscht (Landscape -> Portrait)
    val rotatedImageWidth = imageHeight
    val rotatedImageHeight = imageWidth
    
    // Berechne FILL_CENTER Transformation (wie PreviewView)
    val scaleX = previewWidth / rotatedImageWidth
    val scaleY = previewHeight / rotatedImageHeight
    val scale = max(scaleX, scaleY)
    
    // Berechne Offset für Zentrierung (wie PreviewView)
    val scaledWidth = rotatedImageWidth * scale
    val scaledHeight = rotatedImageHeight * scale
    val offsetX = (previewWidth - scaledWidth) / 2
    val offsetY = (previewHeight - scaledHeight) / 2
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Zeichne Boxen - skaliere und verschiebe wie das Kamera-Bild
        objects.forEach { obj ->
            val color = when {
                obj.isValidated -> Color.Green  // Eier
                obj.isS2Filtered -> Color.Yellow // S2 gefiltert
                else -> Color.Red
            }
            
            // 1. ROTATION (obj.x/y sind im Bild-Koordinatensystem)
            val rotatedX = obj.y.toFloat()
            val rotatedY = obj.x.toFloat()
            val rotatedW = obj.height.toFloat()
            val rotatedH = obj.width.toFloat()
            
            // 2. X-ACHSE SPIEGELN
            val mirroredX = rotatedImageWidth - rotatedX - rotatedW
            
            // 3. SKALIERUNG und VERSCHIEBUNG (wie PreviewView)
            val finalX = (mirroredX * scale) + offsetX
            val finalY = (rotatedY * scale) + offsetY
            val finalW = rotatedW * scale
            val finalH = rotatedH * scale
            
            // Zeichnen
            drawRect(
                color = color,
                topLeft = Offset(finalX, finalY),
                size = Size(finalW, finalH),
                style = Stroke(width = 3f)
            )
        }
    }
}

@Composable
fun EggIndicatorRow(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            repeat(6) { index ->
                val isActive = index < count
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isActive) Color.Yellow else Color.DarkGray,
                            shape = CircleShape
                        )
                        .then(
                            if (isActive) Modifier.border(2.dp, Color.Yellow.copy(alpha = 0.5f), CircleShape)
                            else Modifier
                        )
                )
            }
        }
    }
}