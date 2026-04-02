package com.joe703.eggdetect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.joe703.eggdetect.ui.theme.EggdetectTheme
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV initialisieren
        OpenCVLoader.initLocal()

        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            EggdetectTheme {
                EggDetectApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (hasCameraPermission) {
                CameraPreview()
            } else {
                Text(
                    text = "Kamera-Berechtigung wird benötigt",
                    modifier = Modifier.align(Alignment.Center)
                )
                // Request permission on launch
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }
}

@Composable
fun CameraPreview() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detectedEggs by remember { mutableStateOf(listOf<EggDetection>()) }
    var isProcessing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                Executors.newSingleThreadExecutor(),
                                EggAnalyzer { eggs ->
                                    detectedEggs = eggs
                                }
                            )
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay für erkannte Eier
        Canvas(modifier = Modifier.fillMaxSize()) {
            detectedEggs.forEach { egg ->
                // Zeichne Rechteck um das Ei
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.5f),
                    topLeft = Offset(egg.x - egg.radius, egg.y - egg.radius),
                    size = Size(egg.radius * 2f, egg.radius * 2f)
                )
                // Zeichne Kreis
                drawCircle(
                    color = Color.Red,
                    radius = egg.radius,
                    center = Offset(egg.x, egg.y),
                    style = androidx.compose.ui.graphics.drawscope.DrawStyle.Stroke,
                    strokeWidth = 4f
                )
            }
        }

        // Status-Anzeige
        Text(
            text = if (detectedEggs.isNotEmpty()) "${detectedEggs.size} Ei(er) gefunden" else "Suche nach Eiern...",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

// Datenklasse für erkannte Eier
data class EggDetection(
    val x: Float,
    val y: Float,
    val radius: Float,
    val confidence: Float
)

// Image Analyzer für Echtzeit-Erkennung
class EggAnalyzer(private val onEggsDetected: (List<EggDetection>) -> Unit) : ImageAnalysis.Analyzer {

    private var lastProcessTime = 0L
    private val processIntervalMs = 200 // Max 5 FPS für Performance

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastProcessTime < processIntervalMs) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // YUV zu RGB konvertieren (vereinfacht)
        val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
        yuvMat.put(0, 0, bytes)

        val rgbMat = Mat()
        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21, 3)

        // Hier die Eierkennung
        val eggs = detectEggs(rgbMat, imageProxy.width, imageProxy.height)

        // Skaliere Koordinaten auf Preview-Größe
        val scaleX = imageProxy.width.toFloat()
        val scaleY = imageProxy.height.toFloat()

        val scaledEggs = eggs.map { egg ->
            EggDetection(
                x = egg.x * scaleX,
                y = egg.y * scaleY,
                radius = egg.radius * sqrt(scaleX * scaleY),
                confidence = egg.confidence
            )
        }

        onEggsDetected(scaledEggs)

        imageProxy.close()
        yuvMat.release()
        rgbMat.release()
    }

    private fun detectEggs(mat: Mat, width: Int, height: Int): List<EggDetection> {
        val eggs = mutableListOf<EggDetection>()

        // HSV Konvertierung
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

        // Gelb-Orange Farbmaske
        val lower1 = Scalar(15.0, 60.0, 80.0)
        val upper1 = Scalar(35.0, 255.0, 255.0)
        val mask1 = Mat()
        Core.inRange(hsv, lower1, upper1, mask1)

        val lower2 = Scalar(10.0, 100.0, 100.0)
        val upper2 = Scalar(20.0, 255.0, 255.0)
        val mask2 = Mat()
        Core.inRange(hsv, lower2, upper2, mask2)

        val mask = Mat()
        Core.bitwise_or(mask1, mask2, mask)

        // Morphologische Operationen
        val kernel = Mat.ones(7, 7, CvType.CV_8U)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

        // Konturen finden
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)

            // Flächenfilter
            if (area < 800 || area > 25000) continue

            val rect = Imgproc.boundingRect(contour)
            val aspect = rect.width.toFloat() / rect.height.toFloat()

            // Seitenverhältnis prüfen
            if (aspect < 0.6 || aspect > 1.8) continue

            // Mittelpunkt und Radius
            val centerX = rect.x + rect.width / 2f
            val centerY = rect.y + rect.height / 2f
            val radius = sqrt(area / Math.PI).toFloat()

            // Normalisiere auf 0-1
            eggs.add(EggDetection(
                x = centerX / width,
                y = centerY / height,
                radius = radius / width,
                confidence = 0.8f
            ))
        }

        // Cleanup
        hsv.release()
        mask1.release()
        mask2.release()
        mask.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return eggs.take(2) // Max 2 Eier
    }
}
