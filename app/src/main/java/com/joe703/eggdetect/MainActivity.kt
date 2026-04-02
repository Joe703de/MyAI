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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Display anlassen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // OpenCV initialisieren
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
                color = Color.Cyan,
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

    // Erkennungsergebnis
    var detectionResult by remember { mutableStateOf(DetectionResult(0, 0, 0L, emptyList(), 0, 0)) }

    // PreviewView Größe
    var previewViewSize by remember { mutableStateOf(android.util.Size(0, 0)) }

    // ImageAnalyzer
    val imageAnalyzer = remember(context) {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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

            // Kamera-Preview mit Rahmen
            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Kamera
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

                    // Overlay mit ROTATION
                    if (previewViewSize.width > 0 && detectionResult.imageWidth > 0) {
                        RotatedDetectionOverlay(
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
            Text(
                text = "${result.processingTimeMs}ms",
                color = Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            val step1Color = if (result.candidateCount > 0) Color.Green else Color.Gray
            Text(
                text = "S1: ${result.candidateCount}",
                color = step1Color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Text(
                text = "${result.imageWidth.toInt()}x${result.imageHeight.toInt()}",
                color = Color.Cyan,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * OVERLAY MIT 90° ROTATION
 * Kamera liefert Hochformat, Preview zeigt Querformat
 */
@Composable
fun RotatedDetectionOverlay(
    objects: List<DetectedObject>,
    previewWidth: Float,
    previewHeight: Float,
    imageWidth: Float,
    imageHeight: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Bild ist 90° gedreht: Tausche width/height
        val rotatedImageWidth = imageHeight   // z.B. 1920
        val rotatedImageHeight = imageWidth  // z.B. 1080

        // Skalierung
        val scaleX = previewWidth / rotatedImageWidth
        val scaleY = previewHeight / rotatedImageHeight
        val scale = kotlin.math.max(scaleX, scaleY)

        // Sichtbarer Bereich
        val visibleWidth = previewWidth / scale
        val visibleHeight = previewHeight / scale
        val offsetX = (rotatedImageWidth - visibleWidth) / 2
        val offsetY = (rotatedImageHeight - visibleHeight) / 2

        objects.forEach { obj ->
            val color = if (obj.isValidated) Color.Green else Color.Red

            // KOORDINATEN KORREKT ROTIEREN mit Spiegelung
            // Portrait (x,y) -> Landscape (y, width-x) für korrekte Ausrichtung
            val rotatedX = obj.y
            val rotatedY = imageWidth - obj.x - obj.width  // Spiegelung korrigiert
            val rotatedW = obj.height
            val rotatedH = obj.width

            val centerX = rotatedX + rotatedW / 2
            val centerY = rotatedY + rotatedH / 2

            // Nur anzeigen wenn im sichtbaren Bereich
            if (centerX >= offsetX && centerX <= offsetX + visibleWidth &&
                centerY >= offsetY && centerY <= offsetY + visibleHeight
            ) {
                val x = (rotatedX - offsetX) * scale
                val y = (rotatedY - offsetY) * scale
                val w = rotatedW * scale
                val h = rotatedH * scale

                drawRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    style = Stroke(width = 3f)
                )
            }
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