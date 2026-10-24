package com.joe703.myai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** App-private files, not cache: results live until the chat is explicitly deleted. */
class ChatHistoryStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "chat_history"))

    init { directory.mkdirs() }
    private val history = File(directory, "messages.json")

    @Synchronized
    fun loadMessages(): List<ChatMessage>? {
        if (!history.exists()) return null
        return try {
            val array = JSONArray(String(AtomicFile(history).readFully(), Charsets.UTF_8))
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                ChatMessage(item.getString("sender"), item.getString("text"),
                    item.optString("imageId").takeIf { it.isNotEmpty() })
            }
        } catch (e: Exception) {
            Log.e("ChatHistory", "Could not load chat history", e)
            null
        }
    }

    @Synchronized
    fun saveMessages(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(JSONObject().put("sender", message.sender).put("text", message.text)
                .put("imageId", message.imageId ?: ""))
        }
        writeJson(history, array.toString())
    }

    @Synchronized
    fun saveScene(scene: ChatScene): String {
        val bitmap = scene.frame.rawBitmap ?: throw IOException("Kein Ergebnisbild vorhanden")
        val id = UUID.randomUUID().toString()
        val image = File(directory, "$id.png")
        val metadata = File(directory, "$id.json")
        try {
            image.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Bild konnte nicht gespeichert werden")
            }
            val objects = JSONArray()
            scene.frame.objects.forEach { obj ->
                objects.put(JSONObject().put("x", obj.x).put("y", obj.y)
                    .put("width", obj.width).put("height", obj.height)
                    .put("classId", obj.classId).put("colorName", obj.colorName)
                    .put("matched", obj in scene.matches))
            }
            writeJson(metadata, JSONObject().put("answer", scene.answer)
                .put("imageWidth", scene.frame.imageWidth).put("imageHeight", scene.frame.imageHeight)
                .put("objects", objects).toString())
            return id
        } catch (e: Exception) {
            image.delete()
            AtomicFile(metadata).delete()
            throw e
        }
    }

    @Synchronized
    fun loadScene(id: String): ChatScene? {
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        return try {
            val data = JSONObject(String(AtomicFile(File(directory, "$id.json")).readFully(), Charsets.UTF_8))
            val bitmap = BitmapFactory.decodeFile(File(directory, "$id.png").path) ?: return null
            val array = data.getJSONArray("objects")
            val objects = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                DetectedObject(x = item.getInt("x"), y = item.getInt("y"),
                    width = item.getInt("width"), height = item.getInt("height"),
                    classId = item.getInt("classId"), colorName = item.getString("colorName"))
            }
            val frame = DetectionResult(eggCount = objects.size, candidateCount = 0, s2Count = 0,
                processingTimeMs = 0, objects = objects, imageWidth = data.getInt("imageWidth"),
                imageHeight = data.getInt("imageHeight"), rawBitmap = bitmap)
            ChatScene(frame, objects.filterIndexed { index, _ -> array.getJSONObject(index).getBoolean("matched") },
                data.getString("answer"))
        } catch (e: Exception) {
            Log.e("ChatHistory", "Could not load result", e)
            null
        }
    }

    @Synchronized
    fun clear() {
        // First remove the visible history atomically, then its image attachments.
        saveMessages(emptyList())
        directory.listFiles()?.filter { it.name != history.name }?.forEach {
            if (!it.delete()) throw IOException("Chat-Bild konnte nicht gelöscht werden")
        }
    }

    private fun writeJson(file: File, json: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
