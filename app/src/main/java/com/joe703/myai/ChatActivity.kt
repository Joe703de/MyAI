package com.joe703.myai

import android.content.Intent
import android.app.ActivityOptions
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.Executors
import android.os.Bundle
import android.view.Surface as AndroidSurface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import java.io.IOException

data class ChatMessage(val sender: String, val text: String, val imageId: String? = null)

data class ChatAnswer(val text: String, val matchedObjects: List<DetectedObject>)

/** A single immutable publication keeps image, boxes and selected matches together. */
data class ChatScene(val frame: DetectionResult, val matches: List<DetectedObject>, val answer: String) {
    init { require(matches.all { it in frame.objects }) }
}

class ChatActivity : ComponentActivity() {
    // Only this Activity's analyzer may supply chat frames; never reuse MainActivity's cache.
    var liveResult: DetectionResult? by mutableStateOf(null)
        private set
    var cameraError: String? by mutableStateOf(null)
        private set
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: YoloObjectAnalyzer? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var frameStartedNanos = 0L
    private var generation = 0
    private var pendingFrame: Pair<Long, CompletableDeferred<DetectionResult>>? = null

    suspend fun captureFrame(): DetectionResult? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        val request = CompletableDeferred<DetectionResult>()
        pendingFrame = SystemClock.elapsedRealtimeNanos() to request
        return try {
            withTimeoutOrNull(8.seconds) { request.await() }
        } finally {
            if (pendingFrame?.second === request) pendingFrame = null
        }
    }

    override fun onResume() {
        super.onResume()
        liveResult = null
        cameraError = null
        val session = ++generation
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (session != generation || isFinishing) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val app = application as MyAIApplication
                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(when (window.decorView.display?.rotation) {
                        AndroidSurface.ROTATION_90 -> AndroidSurface.ROTATION_90
                        AndroidSurface.ROTATION_180 -> AndroidSurface.ROTATION_180
                        AndroidSurface.ROTATION_270 -> AndroidSurface.ROTATION_270
                        else -> AndroidSurface.ROTATION_0
                    })
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                    .build()
                analysis = useCase
                useCase.setAnalyzer(analysisExecutor) { proxy ->
                    frameStartedNanos = SystemClock.elapsedRealtimeNanos()
                    // Initialize and invoke the GPU delegate on the same executor thread.
                    val detector = analyzer ?: YoloObjectAnalyzer(
                        applicationContext,
                        targetClassProvider = { app.activeDetectionClasses },
                        confThresholdProvider = { app.confidenceThreshold }
                    ) { result ->
                        val started = frameStartedNanos
                        runOnUiThread {
                            if (session == generation) {
                                liveResult = result
                                pendingFrame?.let { (requested, deferred) ->
                                    if (started >= requested && result.rawBitmap != null) deferred.complete(result)
                                }
                            }
                        }
                    }.also { analyzer = it }
                    detector.analyze(proxy)
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            } catch (e: Exception) {
                cameraError = "Kamera nicht verfügbar: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPause() {
        ++generation
        pendingFrame?.second?.cancel()
        pendingFrame = null
        liveResult = null
        analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }
        analysis = null
        // Queued behind the last inference, so native resources are not closed mid-frame.
        analysisExecutor.execute { analyzer?.close(); analyzer = null }
        super.onPause()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

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

    val activity = context as ChatActivity
    val detectionResult = activity.liveResult
    var sending by remember { mutableStateOf(false) }
    var previewX by remember { mutableIntStateOf(0) }
    var previewY by remember { mutableIntStateOf(0) }
    var previewWidth by remember { mutableIntStateOf(1) }
    var previewHeight by remember { mutableIntStateOf(1) }
    var frozenPreview by remember { mutableStateOf<DetectionResult?>(null) }
    val messages = remember { app.chatMessages }
    var inputText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var storageError by remember { mutableStateOf<String?>(null) }

    suspend fun appendMessage(message: ChatMessage) {
        messages.add(message)
        val saved = messages.toList()
        withContext(Dispatchers.IO) { app.chatHistoryStore.saveMessages(saved) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Chat löschen?") },
        text = { Text("Alle Nachrichten und gespeicherten Ergebnisbilder werden gelöscht.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                sending = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.chatHistoryStore.clear() }
                        messages.clear()
                        app.chatRequestedClassId = null
                        app.chatRequestedColor = null
                    } catch (e: IOException) {
                        storageError = "Der Chat konnte nicht vollständig gelöscht werden. Bitte erneut versuchen."
                    } finally { sending = false }
                }
            }) { Text("Löschen") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
    )
    storageError?.let { error ->
        AlertDialog(onDismissRequest = { storageError = null },
            title = { Text("Speichern") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { storageError = null }) { Text("OK") } })
    }

    fun sendMessage() {
        if (sending || inputText.isBlank()) return
        val question = inputText
        inputText = ""
        sending = true
        frozenPreview = null
        keyboardController?.hide()
        scope.launch {
            try {
                appendMessage(ChatMessage("Du", question))
                val snapshot = activity.captureFrame()
                if (snapshot == null) {
                    appendMessage(ChatMessage("Assistant", activity.cameraError
                        ?: "Kein aktuelles Kamerabild verfügbar. Bitte erneut versuchen."))
                } else {
                    val (answer, matchedObjects) = interpretQuestion(question, snapshot, app)
                    val imageId = if (matchedObjects.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            app.chatHistoryStore.saveScene(ChatScene(snapshot, matchedObjects.toList(), answer))
                        }
                    } else null
                    appendMessage(ChatMessage("Assistant", answer, imageId))
                    if (imageId != null) {
                        frozenPreview = snapshot
                        // Let Compose display the exact snapshot before the scale-up transition.
                        androidx.compose.runtime.withFrameNanos { }
                        androidx.compose.runtime.withFrameNanos { }
                        val options = ActivityOptions.makeScaleUpAnimation(
                            activity.window.decorView, previewX, previewY, previewWidth, previewHeight)
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            context.startActivity(Intent(context, ChatResultActivity::class.java)
                                .putExtra(ChatResultActivity.EXTRA_IMAGE_ID, imageId), options.toBundle())
                        }
                    }
                }
                listState.animateScrollToItem(messages.size - 1)
            } catch (e: IOException) {
                storageError = "Die Antwort oder das Bild konnte nicht dauerhaft gespeichert werden. Bitte Speicherplatz prüfen und erneut versuchen."
            } finally {
                sending = false
                frozenPreview = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            TextButton(onClick = { confirmDelete = true }, enabled = !sending) {
                Text("Chat löschen")
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg, imageEnabled = !sending)
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
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    singleLine = true
                )
                Button(onClick = { sendMessage() }, enabled = !sending) {
                    Text("Senden")
                }
            }
        }
        // Separate overlay: IME insets affect only the chat/input column, never this anchor.
        Row(Modifier.align(Alignment.TopEnd).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                val preview = frozenPreview ?: detectionResult
                val bitmap = preview?.rawBitmap
                // Use the rotated frame's ratio, just like the result image; no cropping.
                val previewAspectRatio = bitmap?.let { it.width.toFloat() / it.height }
                    ?: (3f / 4f)
                Box(Modifier.width(84.dp).aspectRatio(previewAspectRatio).background(Color.Black)
                    .onGloballyPositioned {
                        previewX = it.positionInWindow().x.toInt()
                        previewY = it.positionInWindow().y.toInt()
                        previewWidth = it.size.width
                        previewHeight = it.size.height
                    }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), "Aktuelles Kamerabild",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("Kamera startet …", color = Color.White, fontSize = 11.sp)
                }
                Text(activity.cameraError ?: if (sending) "Bild wird ausgewertet …" else "Live-Kamera",
                    color = Color.LightGray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, imageEnabled: Boolean) {
    val isUser = msg.sender == "Du"
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
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
            msg.imageId?.let { imageId ->
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                Button(
                    enabled = imageEnabled,
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pressed) Color(0xFF0D47A1) else Color(0xFF1976D2),
                        contentColor = Color.White
                    ),
                    onClick = {
                        context.startActivity(Intent(context, ChatResultActivity::class.java)
                            .putExtra(ChatResultActivity.EXTRA_IMAGE_ID, imageId))
                    }
                ) { Text("Bild") }
            }
        }
    }
}

private fun interpretQuestion(question: String, result: DetectionResult?, app: MyAIApplication): ChatAnswer {
    if (result == null) return ChatAnswer("Ich sehe gerade kein Bild.", emptyList())
    val objects = result.objects
    if (objects.isEmpty()) return ChatAnswer("Aktuell sind keine Objekte erkannt.", emptyList())

    val q = ChatVocabulary.tokens(question)

    // Zwei-Wort-Phrasen zusammenfassen, damit "ganz links" und "ganz rechts" erkannt werden
    var i = 0
    while (i < q.size - 1) {
        val pair = "${q[i]} ${q[i + 1]}"
        if (pair in listOf("ganz links", "ganz rechts")) {
            q[i] = pair.replace(" ", "")
            q.removeAt(i + 1)
        } else {
            i++
        }
    }

    val targetDistance = q.firstNotNullOfOrNull { it.toIntOrNull() }

    val colorMap = mapOf(
        "rot" to "Rot", "rote" to "Rot", "roter" to "Rot", "rotes" to "Rot",
        "blau" to "Blau", "blaue" to "Blau", "blauer" to "Blau", "blaues" to "Blau",
        "gruen" to "Gruen", "gruene" to "Gruen", "gruener" to "Gruen", "gruenes" to "Gruen",
        "gelb" to "Gelb", "gelbe" to "Gelb", "gelber" to "Gelb", "gelbes" to "Gelb",
        "schwarz" to "Schwarz", "schwarze" to "Schwarz", "schwarzer" to "Schwarz", "schwarzes" to "Schwarz",
        "weiss" to "Weiss", "weisse" to "Weiss", "weisser" to "Weiss", "weisses" to "Weiss",
        "grau" to "Grau", "graue" to "Grau", "grauer" to "Grau", "graues" to "Grau",
        "silber" to "Silber", "silberne" to "Silber", "silberner" to "Silber", "silbernes" to "Silber",
        "orange" to "Orange", "orangene" to "Orange", "orangener" to "Orange", "oranges" to "Orange",
        "braun" to "Braun", "braune" to "Braun", "brauner" to "Braun", "braunes" to "Braun",
        "lila" to "Lila", "violett" to "Lila", "violette" to "Lila", "violetter" to "Lila", "violettes" to "Lila",
        "pink" to "Pink", "rosa" to "Pink", "rosane" to "Pink", "rosaner" to "Pink", "rosanes" to "Pink",
        "beige" to "Beige", "beiger" to "Beige", "beiges" to "Beige",
        "gold" to "Gold", "goldene" to "Gold", "goldener" to "Gold", "goldenes" to "Gold",
        "tuerkis" to "Tuerkis", "tuerkise" to "Tuerkis", "tuerkiser" to "Tuerkis", "tuerkises" to "Tuerkis",
        "weinrot" to "Rot", "weinrote" to "Rot", "bordeaux" to "Rot",
        "dunkelblau" to "Blau", "hellblau" to "Blau",
        "dunkelgruen" to "Gruen", "hellgruen" to "Gruen",
        "dunkelrot" to "Rot", "hellrot" to "Rot",
        "dunkelgrau" to "Grau", "hellgrau" to "Grau"
    )
    var requestedColor = colorMap.entries.firstOrNull { q.contains(it.key) }?.value

    var requestedClassId = ChatVocabulary.classId(q)

    val isOverview = q.contains("was") && q.contains("siehst") || q.contains("alles")
    val isFollowUp = !isOverview && q.any { it in listOf(
        "liste", "links", "rechts", "linke", "linkes", "rechte", "rechtes", "ganzlinks", "ganzrechts",
        "mitte", "mittlere", "nah", "nahes", "nahe", "naechste", "naechsten", "fern", "weit", "weitweg") }
    if (isFollowUp) {
        requestedClassId = requestedClassId ?: app.chatRequestedClassId
        requestedColor = requestedColor ?: app.chatRequestedColor
    }
    app.chatRequestedClassId = requestedClassId
    app.chatRequestedColor = requestedColor
    val currentCandidates = objects.filter { obj ->
        (requestedClassId == null || obj.classId == requestedClassId) &&
            (requestedColor == null || obj.colorName.equals(requestedColor, ignoreCase = true))
    }
    if (q.contains("liste")) return ChatAnswer(describeAllObjects(currentCandidates, app), currentCandidates)

    if (q.any { it in listOf("was", "siehst", "gibt", "alles", "objekte", "liste") } && requestedClassId == null && requestedColor == null) {
        return ChatAnswer(describeAllObjects(objects, app), objects)
    }

    val askingWhere = q.any { it in listOf("wo", "position", "seite", "links", "rechts") }
    val askingDistance = q.any { it in listOf("weit", "entfernung", "entfernt", "distanz") }

    if (q.any { it in listOf("naechste", "naechsten", "nächste", "nächsten", "n") } ||
        q.any { it in listOf("linke", "linkes", "links", "ganzlinks") } ||
        q.any { it in listOf("rechte", "rechtes", "rechts", "ganzrechts") } ||
        q.any { it in listOf("mitte", "mittlere") } ||
        q.any { it in listOf("nah", "nahes", "nahe") } ||
        q.any { it in listOf("fern", "weit", "weites", "weiter", "weitweg", "weit weg") }
    ) {
        if (currentCandidates.isNotEmpty()) {
            val filtered = when {
                q.any { it in listOf("naechste", "naechsten", "nächste", "nächsten", "n") } ->
                    currentCandidates.sortedBy { app.estimateDistance(it.width, it.classId) }.take(1)
                q.any { it in listOf("ganzlinks") } ->
                    listOf(currentCandidates.minByOrNull { it.x + it.width / 2 }!!)
                q.any { it in listOf("ganzrechts") } ->
                    listOf(currentCandidates.maxByOrNull { it.x + it.width / 2 }!!)
                q.any { it in listOf("linke", "linkes", "links") } ->
                    currentCandidates.filter { it.x + it.width / 2 < result.imageWidth / 3 }
                q.any { it in listOf("rechte", "rechtes", "rechts") } ->
                    currentCandidates.filter { it.x + it.width / 2 > 2 * result.imageWidth / 3 }
                q.any { it in listOf("mitte", "mittlere") } ->
                    currentCandidates.filter { (it.x + it.width / 2) in (result.imageWidth / 3)..(2 * result.imageWidth / 3) }
                q.any { it in listOf("nah", "nahes", "nahe") } ->
                    currentCandidates.sortedBy { app.estimateDistance(it.width, it.classId) }.take(1)
                q.any { it in listOf("fern", "weit", "weites", "weiter", "weitweg") } ->
                    currentCandidates.sortedByDescending { app.estimateDistance(it.width, it.classId) }.take(1)
                else -> currentCandidates
            }
            if (filtered.isEmpty()) return ChatAnswer("Dort sehe ich aktuell kein passendes Objekt.", emptyList())
            return if (askingWhere) {
                ChatAnswer(describePositions(filtered, result, app), filtered)
            } else if (askingDistance) {
                ChatAnswer(describeDistances(filtered, app), filtered)
            } else {
                ChatAnswer(describeObjects(filtered, app), filtered)
            }
        }
    }

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

    if (currentCandidates.isEmpty()) {
        val className = requestedClassId?.let { CocoClassNames.getOrElse(it) { "Objekt" } }
        val colorPart = requestedColor?.let { " in $it" } ?: ""
        return ChatAnswer("Ich sehe gerade kein${if (requestedClassId == 11) "" else "e"} ${className ?: "Objekt"}${colorPart}.", emptyList())
    }

    if (currentCandidates.size > 3) {
        val className = requestedClassId?.let { CocoClassNames.getOrElse(it) { "Objekte" } } ?: "Objekte"
        val colorPart = requestedColor?.let { " $it" } ?: ""
        val first = currentCandidates.first()
        val est = app.estimateDistance(first.width, first.classId)
        val distHint = if (est >= 0f) " Das nächste ist ${est.toInt()}m entfernt." else ""
        return ChatAnswer(
            "Ich sehe ${currentCandidates.size} ${className.lowercase()}${colorPart}.${distHint}\n\nSoll ich Dir die komplette Liste zeigen oder nur ein bestimmtes Auto?",
            emptyList()
        )
    }

    return when {
        askingWhere -> ChatAnswer(describePositions(currentCandidates, result, app), currentCandidates)
        askingDistance -> ChatAnswer(describeDistances(currentCandidates, app), currentCandidates)
        else -> ChatAnswer(describeObjects(currentCandidates, app), currentCandidates)
    }
}

private fun describeObjects(objects: List<DetectedObject>, app: MyAIApplication): String {
    return if (objects.size == 1) {
        val obj = objects.first()
        val est = app.estimateDistance(obj.width, obj.classId)
        val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
        val distPart = if (est >= 0f) " bei ungefähr ${est.toInt()}m" else ""
        "Ich sehe ein ${colorName(obj.colorName)} $className$distPart."
    } else {
        "Ich sehe ${objects.size} Objekte:\n" + objects.joinToString("\n") { obj ->
            val est = app.estimateDistance(obj.width, obj.classId)
            val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
            val distPart = if (est >= 0f) " ~${est.toInt()}m" else ""
            "• ${colorName(obj.colorName)} $className$distPart"
        }
    }
}

private fun describePositions(objects: List<DetectedObject>, result: DetectionResult, app: MyAIApplication): String {
    return if (objects.size == 1) {
        val obj = objects.first()
        val xPos = when {
            obj.x + obj.width / 2 < result.imageWidth / 3 -> "links"
            obj.x + obj.width / 2 > 2 * result.imageWidth / 3 -> "rechts"
            else -> "in der Mitte"
        }
        val distM = app.estimateDistance(obj.width, obj.classId)
        val yPos = when {
            distM >= 0f -> if (distM < 60f) "nah (${distM.toInt()}m)" else "weit weg (${distM.toInt()}m)"
            obj.y + obj.height / 2 > 2 * result.imageHeight / 3 -> "nah"
            obj.y + obj.height / 2 < result.imageHeight / 3 -> "weit weg"
            else -> "mittlere Entfernung"
        }
        val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
        "Das ${colorName(obj.colorName)} $className ist $xPos und $yPos."
    } else {
        "Ich sehe ${objects.size} Objekte:\n" + objects.joinToString("\n") { obj ->
            val xPos = when {
                obj.x + obj.width / 2 < result.imageWidth / 3 -> "links"
                obj.x + obj.width / 2 > 2 * result.imageWidth / 3 -> "rechts"
                else -> "Mitte"
            }
            val distM = app.estimateDistance(obj.width, obj.classId)
            val yPos = when {
                distM >= 0f -> if (distM < 60f) "nah (${distM.toInt()}m)" else "weit weg (${distM.toInt()}m)"
                obj.y + obj.height / 2 > 2 * result.imageHeight / 3 -> "nah"
                obj.y + obj.height / 2 < result.imageHeight / 3 -> "weit weg"
                else -> "mittlere Entfernung"
            }
            val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
            "• ${colorName(obj.colorName)} $className $xPos, $yPos"
        }
    }
}

private fun describeDistances(objects: List<DetectedObject>, app: MyAIApplication): String {
    return if (objects.size == 1) {
        val obj = objects.first()
        val est = app.estimateDistance(obj.width, obj.classId)
        val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
        if (est >= 0f) "Das ${colorName(obj.colorName)} $className ist ungefähr ${est.toInt()}m entfernt."
        else "Für das ${colorName(obj.colorName)} $className fehlen Kalibrierdaten, ich kann die Entfernung nicht schätzen."
    } else {
        "Ich sehe ${objects.size} Objekte:\n" + objects.joinToString("\n") { obj ->
            val est = app.estimateDistance(obj.width, obj.classId)
            val className = CocoClassNames.getOrElse(obj.classId) { "Objekt" }
            val distPart = if (est >= 0f) "${est.toInt()}m" else "unbekannt"
            "• ${colorName(obj.colorName)} $className: $distPart"
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
        "Ich sehe ${objects.size} Objekte:\n" + objects.joinToString("\n") { obj ->
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
        "gruen", "grün" -> "grünes"
        "gelb" -> "gelbes"
        "schwarz" -> "schwarzes"
        "weiss", "weiß" -> "weißes"
        "grau" -> "graues"
        "silber" -> "silbernes"
        "orange" -> "oranges"
        "braun" -> "braunes"
        "lila", "violett" -> "violettes"
        "pink", "rosa" -> "rosanes"
        "beige" -> "beiges"
        "gold" -> "goldenes"
        "tuerkis", "türkis" -> "türkises"
        else -> name
    }
}
