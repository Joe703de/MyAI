package com.joe703.eggdetect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.joe703.eggdetect.ui.theme.EggdetectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            
            // Text oben rechts (außerhalb des Kamera-Rahmens)
            Box(
                modifier = Modifier.fillMaxWidth(0.9f),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = "Kamera läuft",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            
            // Kamera-Preview Bereich: 70% Höhe, mit weißem Rahmen
            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color.White),
                contentAlignment = Alignment.Center
            ) {
                // Kamera-Container
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Echte Kamera - mit Portrait-Konfiguration
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            // FILL_CENTER: Füllt den Rahmen vollständig
                            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            
                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    
                                    // Portrait-Auflösung konfigurieren
                                    val resolutionSelector = ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                android.util.Size(1080, 1920),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()
                                    
                                    val preview = CameraPreview.Builder()
                                        .setResolutionSelector(resolutionSelector)
                                        .setTargetRotation(Surface.ROTATION_0)  // Portrait
                                        .build()
                                        .also { previewInstance ->
                                            previewInstance.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                    
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            // Eier-Bereich: 15%
            Box(
                modifier = Modifier
                    .weight(0.15f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    // 6 gelbe Eier
                    repeat(6) { index ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.Yellow, CircleShape)
                        )
                    }
                }
            }
            
            // Abstand unten: 5%
            Box(modifier = Modifier.weight(0.05f))
        }
    }
}

// VEREINFACHTE PREVIEW - nur UI, keine Kamera
@androidx.compose.ui.tooling.preview.Preview(device = "id:pixel_5", showBackground = true, showSystemUi = true)
@Composable
fun CameraScreenPreview() {
    EggdetectTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
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
                    
                    // Text oben rechts
                    Box(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Text(
                            text = "Kamera läuft (Preview)",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    // Kamera-Bereich: 70% Höhe
                    Box(
                        modifier = Modifier
                            .weight(0.7f)
                            .fillMaxWidth(0.9f)
                            .border(1.dp, Color.White)
                            .background(Color.Blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Kamera-Preview",
                            color = Color.White
                        )
                    }
                    
                    // Eier-Bereich: 15%
                    Box(
                        modifier = Modifier
                            .weight(0.15f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            repeat(6) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color.Yellow, CircleShape)
                                )
                            }
                        }
                    }
                    
                    // Abstand unten: 5%
                    Box(modifier = Modifier.weight(0.05f))
                }
            }
        }
    }
}
