package com.joe703.myai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatHistoryStoreTest {
    @Test fun differentResultsSurviveReloadAndAreRemovedWithHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "chat-test-${UUID.randomUUID()}")
        try {
            val store = ChatHistoryStore(directory)
            val left = DetectedObject(1, 2, 3, 4, classId = 2, colorName = "Rot")
            val right = DetectedObject(8, 2, 3, 4, classId = 11, colorName = "Rot")
            val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            val frame = DetectionResult(2, 0, 0, 0L, listOf(left, right), 16, 24, rawBitmap = bitmap)
            val firstId = store.saveScene(ChatScene(frame, listOf(left), "Erste Antwort\nZeile 2\nZeile 3\nZeile 4"))
            bitmap.eraseColor(Color.BLUE)
            val secondId = store.saveScene(ChatScene(frame, listOf(right), "Zweite Antwort"))
            val messages = listOf(ChatMessage("Assistant", "Erste Antwort", firstId),
                ChatMessage("Assistant", "Zweite Antwort", secondId))
            store.saveMessages(messages)

            // A new store instance simulates loading without any in-memory scene cache.
            val reloaded = ChatHistoryStore(directory)
            assertEquals(messages, reloaded.loadMessages())
            val first = requireNotNull(reloaded.loadScene(firstId))
            val second = requireNotNull(reloaded.loadScene(secondId))
            assertEquals(listOf(left), first.matches)
            assertEquals(listOf(right), second.matches)
            assertEquals(Color.RED, first.frame.rawBitmap!!.getPixel(0, 0))
            assertEquals(Color.BLUE, second.frame.rawBitmap!!.getPixel(0, 0))
            assertEquals(16, first.frame.imageWidth)
            assertEquals(24, first.frame.imageHeight)
            assertEquals("Erste Antwort\nZeile 2\nZeile 3\nZeile 4", first.answer)
            assertEquals("Zweite Antwort", second.answer)

            reloaded.clear()
            assertEquals(emptyList<ChatMessage>(), ChatHistoryStore(directory).loadMessages())
            assertEquals(listOf("messages.json"), directory.listFiles()!!.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }
}
