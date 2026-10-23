package com.joe703.myai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joe703.myai.ui.theme.MyAITheme
import kotlinx.coroutines.launch

data class ChatMessage(val sender: String, val text: String)

data class ChatAnswer(val text: String, val matchedObjects: List<DetectedObject>)

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ChatScreen()
                }
            }
        }
    }
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MyAIApplication
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var detectionResult by remember { mutableStateOf(app.currentDetectionResult) }
    val messages = remember { app.chatMessages }
    var inputText by remember { mutableStateOf("") }

    DisposableEffect(app) {
        val listener = { detectionResult = app.currentDetectionResult }
        app.addListener(listener)
        onDispose { app.removeListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Frage stellen...", color = Color.Gray) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            messages.add(ChatMessage("Du", inputText))
                            val (answer, matchedObjects) = interpretQuestion(inputText, detectionResult, app)
                            messages.add(ChatMessage("Assistant", answer))
                            if (matchedObjects.isNotEmpty()) {
                                app.chatResultSnapshot = detectionResult
                                val intent = Intent(context, ChatResultActivity::class.java)
                                context.startActivity(intent)
                            }
                            inputText = ""
                            keyboardController?.hide()
                            scope.launch { listState.animateScrollToItem(messages.size - 1) }
                        }
                    }),
                    singleLine = true
                )
                Button(onClick = {
                    if (inputText.isNotBlank()) {
                        messages.add(ChatMessage("Du", inputText))
                        val (answer, matchedObjects) = interpretQuestion(inputText, detectionResult, app)
                        messages.add(ChatMessage("Assistant", answer))
                        if (matchedObjects.isNotEmpty()) {
                            app.setChatResultSnapshot(detectionResult)
                            val intent = Intent(context, ChatResultActivity::class.java)
                            context.startActivity(intent)
                        }
                        inputText = ""
                        keyboardController?.hide()
                        scope.launch { listState.animateScrollToItem(messages.size - 1) }
                    }
                }) {
                    Text("Senden")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == "Du"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (isUser) Color(0xFF1E88E5) else Color(0xFF424242),
                    shape = MaterialTheme.shapes.medium
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = msg.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
            )
        }
    }
}

private fun interpretQuestion(question: String, result: DetectionResult?, app: MyAIApplication): ChatAnswer {
    if (result == null) return ChatAnswer("Ich sehe gerade kein Bild.", emptyList())
    val objects = result.objects
    if (objects.isEmpty()) return ChatAnswer("Aktuell sind keine Objekte erkannt.", emptyList())

    val q = question.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace("m", " ")
        .replace("?", " ")
        .replace(".", " ")
        .replace(",", " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (q.any { it in listOf("was", "siehst", "gibt", "alles", "objekte", "liste") }) {
        return ChatAnswer(describeAllObjects(objects, app), objects)
    }

    val targetDistance = q.mapNotNull { it.toIntOrNull() }.firstOrNull()

    val colorMap = mapOf(
        "rot" to "Rot",
        "blau" to "Blau",
        "gruen" to "Gruen",
        "gelb" to "Gelb",
        "schwarz" to "Schwarz",
        "weiss" to "Weiss",
        "grau" to "Grau",
        "silber" to "Silber",
        "orange" to "Orange"
    )
    val requestedColor = colorMap.entries.firstOrNull { q.contains(it.key) }?.value

    val classMap = mapOf(
        "auto" to 2,
        "bus" to 5,
        "lkw" to 7,
        "ampel" to 9,
        "stoppschild" to 11,
        "stopschild" to 11,
        "stop" to 11
    )
    val requestedClassId = classMap.entries.firstOrNull { q.contains(it.key) }?.value

    val askingWhere = q.any { it in listOf("wo", "position", "seite", "links", "rechts") }
    val askingDistance = q.any { it in listOf("weit", "entfernung", "entfernt", "distanz") }

    if (targetDistance != null && !askingDistance && !askingWhere && requestedClassId == null && requestedColor == null) {
        val matches = objects.mapNotNull { obj ->
            val est = app.estimateDistance(obj.width, obj.classId)
            if (est >= 0f && kotlin.math.abs(est - targetDistance) <= 25f) obj to est else null
        }
        if (matches.isEmpty()) return ChatAnswer("Kein Objekt ist ungefähr ${targetDistance}m entfernt.", emptyList())
        return ChatAnswer(matches.joinToString("\n") { (obj, est) ->
            val name = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
            "• ${colorName(obj.colorName)} $name bei ${est.toInt()}m"
        }, matches.map { it.first })
    }

    val candidates = objects.filter { obj ->
        val classOk = requestedClassId == null || obj.classId == requestedClassId
        val colorOk = requestedColor == null || obj.colorName.equals(requestedColor, ignoreCase = true)
        classOk && colorOk
    }

    if (candidates.isEmpty()) {
        val className = requestedClassId?.let { CocoClassNames.getOrElse(it) { "Objekt" } }
        val colorPart = requestedColor?.let { " in $it" } ?: ""
        return ChatAnswer("Ich sehe gerade kein${if (requestedClassId == 11) "" else "e"} ${className ?: "Objekt"}${colorPart}.", emptyList())
    }

    val obj = candidates.first()
    val est = app.estimateDistance(obj.width, obj.classId)
    val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
    val colorPart = if (obj.colorName.isNotBlank()) " ${colorName(obj.colorName)}" else ""

    return when {
        askingWhere -> {
            val xPos = when {
                obj.x + obj.width / 2 < result.imageWidth / 3 -> "links"
                obj.x + obj.width / 2 > 2 * result.imageWidth / 3 -> "rechts"
                else -> "in der Mitte"
            }
            val yPos = when {
                obj.y + obj.height / 2 < result.imageHeight / 3 -> "oben"
                obj.y + obj.height / 2 > 2 * result.imageHeight / 3 -> "unten"
                else -> "in der Mitte"
            }
            ChatAnswer("Das $colorPart$className ist $xPos $yPos.", candidates)
        }
        askingDistance -> {
            if (est >= 0f) ChatAnswer("Das $colorPart$className ist ungefähr ${est.toInt()}m entfernt.", candidates)
            else ChatAnswer("Für das $colorPart$className fehlen Kalibrierdaten, ich kann die Entfernung nicht schätzen.", candidates)
        }
        else -> {
            val distPart = if (est >= 0f) " bei ungefähr ${est.toInt()}m" else ""
            ChatAnswer("Ich sehe ein$colorPart $className$distPart.", candidates)
        }
    }
}

private fun describeAllObjects(objects: List<DetectedObject>, app: MyAIApplication): String {
    return if (objects.size == 1) {
        val obj = objects.first()
        val est = app.estimateDistance(obj.width, obj.classId)
        val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
        val distPart = if (est >= 0f) " bei ungefähr ${est.toInt()}m" else ""
        "Ich sehe ein ${colorName(obj.colorName)} $className$distPart."
    } else {
        objects.joinToString("\n") { obj ->
            val est = app.estimateDistance(obj.width, obj.classId)
            val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
            val distPart = if (est >= 0f) " ~${est.toInt()}m" else ""
            "• ${colorName(obj.colorName)} $className$distPart"
        }
    }
}

private fun colorName(name: String?): String {
    if (name.isNullOrBlank()) return ""
    return when (name.lowercase()) {
        "rot" -> "rotes"
        "blau" -> "blaues"
        "gruen" -> "grünes"
        "gelb" -> "gelbes"
        "schwarz" -> "schwarzes"
        "weiss" -> "weißes"
        "grau" -> "graues"
        "silber" -> "silbernes"
        "orange" -> "oranges"
        else -> name
    }
}
