package com.joe703.eggdetect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joe703.eggdetect.ui.theme.EggdetectTheme
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    // Erkennungsergebnis
    var detectionResult by remember { mutableStateOf(DetectionResult(0, 0, 0L, emptyList())) }

    // ImageAnalyzer erstellen (zweistufige Erkennung)
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
            // Abstand von oben: 10%
            Box(modifier = Modifier.weight(0.1f))

            // Info-Texte oben
            StatusBar(detectionResult)

            // Kamera-Preview Bereich: 70% Höhe, mit weißem Rahmen
            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                // Kamera-Container mit Overlay
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Echte Kamera
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()

                                    val preview = CameraPreview.Builder()
                                        .build()
                                        .also { previewInstance ->
                                            previewInstance.setSurfaceProvider(previewView.surfaceProvider)
                                        }

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
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    )

                    // Overlay: Erkannte Objekte markieren
                    // WICHTIG: Annahme - Kamera liefert 1920x1080 (Landscape)
                    // Wir müssen die Koordinaten an das angezeigte Bild anpassen
                    DetectionOverlay(
                        objects = detectionResult.objects,
                        cameraWidth = detectionResult.imageWidth.toFloat().coerceAtLeast(1f),
                        cameraHeight = detectionResult.imageHeight.toFloat().coerceAtLeast(1f)
                    )
                }
            }

            // Eier-Bereich: 15% - dynamische Anzeige
            EggIndicatorRow(
                count = detectionResult.eggCount,
                modifier = Modifier.weight(0.15f)
            )

            // Abstand unten: 5%
            Box(modifier = Modifier.weight(0.05f))
        }
    }
}

/**
 * Status-Anzeige oben
 */
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
            // Links: Zeit
            Text(
                text = "${result.processingTimeMs}ms",
                color = Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // Mitte: Schritt 1 Status
            val step1Color = if (result.candidateCount > 0) Color.Green else Color.Gray
            Text(
                text = "S1: ${result.candidateCount}",
                color = step1Color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // Rechts: Eier
            Text(
                text = "Eier: ${result.eggCount}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Zeigt die erkannten Eier als Reihe von Kreisen
 */
@Composable
fun EggIndicatorRow(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            // 6 Eier - leuchten auf je nach Anzahl
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
                            if (isActive) {
                                Modifier.border(2.dp, Color.Yellow.copy(alpha = 0.5f), CircleShape)
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

/**
 * Zeichnet Rechtecke um erkannte Objekte
 * Grün = validiert (Ei), Rot = nur Kandidat
 * Koordinaten werden vom Kamera-Format auf den Bildschirm skaliert
 */
@Composable
fun DetectionOverlay(
    objects: List<DetectedObject>,
    cameraWidth: Float = 1920f,
    cameraHeight: Float = 1080f
) {
    val density = LocalDensity.current
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Box-Größe ermitteln (die tatsächliche Anzeigegröße)
        val boxWidth = size.width
        val boxHeight = size.height
        
        // Skalierungsfaktoren berechnen
        // FILL_CENTER: Bild füllt Box, wird aber beschnitten
        // Wir skalieren auf die kleinere Dimension
        val scaleX = boxWidth / cameraWidth
        val scaleY = boxHeight / cameraHeight
        val scale = kotlin.math.max(scaleX, scaleY)  // FILL_CENTER verwendet max
        
        // Berechne Verschiebung durch Beschneidung
        val offsetX = (boxWidth - cameraWidth * scale) / 2
        val offsetY = (boxHeight - cameraHeight * scale) / 2
        
        objects.forEach { obj ->
            val color = if (obj.isValidated) Color.Green else Color.Red
            val strokeWidth = if (obj.isValidated) 4f else 2f
            
            // Skalierte Koordinaten
            val x = obj.x * scale + offsetX
            val y = obj.y * scale + offsetY
            val w = obj.width * scale
            val h = obj.height * scale
            
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(w, h),
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

// Import für Log
private val Any.android: Any
    get() = this