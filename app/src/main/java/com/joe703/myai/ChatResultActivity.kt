package com.joe703.myai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joe703.myai.ui.theme.MyAITheme

class ChatResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ChatResultScreen(onFinish = { finish() })
                }
            }
        }
    }
}

@Composable
fun ChatResultScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MyAIApplication
    val result = app.chatResultSnapshot
    val matchedObjects = app.chatMatchedObjects

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (result != null && result.rawBitmap != null) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()
                    val imageWidth = result.imageWidth.toFloat()
                    val imageHeight = result.imageHeight.toFloat()

                    Image(
                        bitmap = result.rawBitmap.asImageBitmap(),
                        contentDescription = "Ergebnisbild",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (containerWidth > 0f && containerHeight > 0f && imageWidth > 0f && imageHeight > 0f) {
                        val scaleX = containerWidth / imageWidth
                        val scaleY = containerHeight / imageHeight
                        val scale = minOf(scaleX, scaleY)
                        val offsetX = (containerWidth - imageWidth * scale) / 2f
                        val offsetY = (containerHeight - imageHeight * scale) / 2f

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                        ) {
                            val matchedSet = matchedObjects.toSet()
                            val otherObjects = result.objects.filter { it !in matchedSet }

                            otherObjects.forEach { obj ->
                                val left = obj.x * scale + offsetX
                                val top = obj.y * scale + offsetY
                                val right = (obj.x + obj.width) * scale + offsetX
                                val bottom = (obj.y + obj.height) * scale + offsetY

                                drawRect(
                                    color = Color.Yellow,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top),
                                    style = Stroke(width = 3f)
                                )
                            }

                            matchedObjects.forEach { obj ->
                                val left = obj.x * scale + offsetX
                                val top = obj.y * scale + offsetY
                                val right = (obj.x + obj.width) * scale + offsetX
                                val bottom = (obj.y + obj.height) * scale + offsetY

                                drawRect(
                                    color = Color.Green,
                                    topLeft = Offset(left, top),
                                    size = Size(right - left, bottom - top),
                                    style = Stroke(width = 4f)
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Kein Bild verfügbar.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Button(
                onClick = onFinish,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    contentColor = Color.White
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("OK")
            }
        }
    }
}
