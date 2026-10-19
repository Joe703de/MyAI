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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joe703.eggdetect.ui.theme.EggdetectTheme
import java.util.concurrent.Executors

import kotlin.math.max


val CocoClassNames = listOf(
    "Person",
    "Fahrrad",
    "Auto",
    "Motorrad",
    "Flugzeug",
    "Bus",
    "Zug",
    "LKW",
    "Boot",
    "Ampel",
    "Feuerhydrant",
    "Stoppschild",
    "Parkuhr",
    "Bank",
    "Vogel",
    "Katze",
    "Hund",
    "Pferd",
    "Schaf",
    "Kuh",
    "Elefant",
    "Bär",
    "Zebra",
    "Giraffe",
    "Rucksack",
    "Regenschirm",
    "Handtasche",
    "Krawatte",
    "Koffer",
    "Frisbee",
    "Ski",
    "Snowboard",
    "Sportball",
    "Drachen",
    "Baseballschläger",
    "Baseballhandschuh",
    "Skateboard",
    "Surfbrett",
    "Tennisball",
    "Flasche",
    "Weinglas",
    "Tasse",
    "Gabel",
    "Messer",
    "Löffel",
    "Schüssel",
    "Banane",
    "Apfel",
    "Sandwich",
    "Orange",
    "Brokkoli",
    "Karotte",
    "Hot Dog",
    "Pizza",
    "Donut",
    "Kuchen",
    "Stuhl",
    "Sofa",
    "Topfpflanze",
    "Bett",
    "Esstisch",
    "Toilette",
    "Fernseher",
    "Laptop",
    "Maus",
    "Fernbedienung",
    "Tastatur",
    "Handy",
    "Mikrowelle",
    "Backofen",
    "Toaster",
    "Spülbecken",
    "Kühlschrank",
    "Buch",
    "Uhr",
    "Vase",
    "Schere",
    "Teddybär",
    "Haartrockner",
    "Zahnbürste"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

fun createTestBitmap(): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.BLUE)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.YELLOW
        textSize = 32f
    }
    canvas.drawText("TEST", 20f, 170f, paint)
    return bitmap
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
    var targetClassIndex by remember { mutableStateOf(2) } // COCO-Klasse 2 = Auto
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
                    YoloEggAnalyzer(context, targetClassProvider = { targetClassIndex }) { result ->
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
            Box(modifier = Modifier.weight(0.1f))
            StatusBar(detectionResult, previewViewSize.width.toFloat(), previewViewSize.height.toFloat())

            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
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
                                Log.e("EggDetect", "Camera binding failed", e)
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
            }

            EggIndicatorRow(
                objects = detectionResult.objects,
                modifier = Modifier.weight(0.15f)
            )

            ClassDropdown(
                selectedIndex = targetClassIndex,
                onSelectionChanged = { targetClassIndex = it },
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 4.dp)
            )

            Box(modifier = Modifier.weight(0.05f))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassDropdown(
    selectedIndex: Int,
    onSelectionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = CocoClassNames.getOrElse(selectedIndex) { "Unbekannt" }

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
            CocoClassNames.forEachIndexed { index, name ->
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
@Composable
fun StatusBar(result: DetectionResult, previewWidth: Float, previewHeight: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(horizontal = 3.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = "Eier: ${result.eggCount}",
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
            Text(
                text = "Target: ${"%.3f".format(result.topCarScore)} Global: ${"%.3f".format(result.maxGlobalScore)} class=${result.maxGlobalClassId}",
                color = Color.Cyan,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text(
                text = "prep=${result.timingMs.preprocessMs} inf=${result.timingMs.inferenceMs} post=${result.timingMs.postprocessMs} [${result.timingMs.delegateName}]",
                color = Color.Cyan,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text(
                text = "post: read=${result.timingMs.postReadMs} nms=${result.timingMs.postNmsMs} color=${result.timingMs.postColorMs}",
                color = Color.Cyan,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun DetectionOverlay(
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

    Canvas(modifier = Modifier.fillMaxSize()) {
        objects.forEach { obj ->
            val color = Color(obj.colorR, obj.colorG, obj.colorB)

            val finalX = (obj.x.toFloat() * scale) + offsetX
            val finalY = (obj.y.toFloat() * scale) + offsetY
            val finalW = obj.width.toFloat() * scale
            val finalH = obj.height.toFloat() * scale

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
fun EggIndicatorRow(objects: List<DetectedObject>, modifier: Modifier = Modifier) {
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
                val isActive = index < objects.size
                val eggColor = if (isActive) {
                    Color(objects[index].colorR, objects[index].colorG, objects[index].colorB)
                } else {
                    Color.DarkGray
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = eggColor,
                            shape = CircleShape
                        )
                        .then(
                            if (isActive) Modifier.border(2.dp, eggColor.copy(alpha = 0.5f), CircleShape)
                            else Modifier
                        )
                )
            }
        }
    }
}
