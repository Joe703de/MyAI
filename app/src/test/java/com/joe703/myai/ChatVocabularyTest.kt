package com.joe703.myai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatVocabularyTest {
    @Test fun stopSignSpellingsStaySpecific() {
        listOf("Stoppschild", "Stoppschilder", "Stopschild", "Stopschilder",
            "Stop Schilder", "Stopp Schilder", "Stop-Schild", "Stopp-Schilder",
            "STOP–SCHILDER", "Stoppschildern", "\"Stoppschilder\"", "„Stoppschilder“")
            .forEach { word ->
                assertEquals(word, 11, ChatVocabulary.classId(ChatVocabulary.tokens("Siehst Du $word?")))
            }
    }

    @Test fun allSupportedClassesHaveSingularAndPlural() {
        mapOf(2 to listOf("Auto", "Autos", "Fahrzeugen", "PKWs"),
            5 to listOf("Bus", "Busse", "Bussen"),
            7 to listOf("LKW", "LKWs", "Last-Wagen", "Sattelzüge", "Sattel Zügen"),
            9 to listOf("Ampel", "Ampeln", "Verkehrs-Ampeln"))
            .forEach { (id, words) -> words.forEach { word ->
                assertEquals(word, id, ChatVocabulary.classId(ChatVocabulary.tokens("Siehst du $word?")))
            } }
    }

    @Test fun matchingDoesNotUseSubstrings() {
        listOf("Autobahn", "Busch", "verstopft", "Stopuhr").forEach {
            assertNull(ChatVocabulary.classId(ChatVocabulary.tokens(it)))
        }
    }

    @Test fun normalizationPreservesWordsAndDistance() {
        assertEquals(listOf("ampeln", "in", "100"), ChatVocabulary.tokens("Ampeln in 100m?"))
        assertEquals(listOf("ganz", "rechts"), ChatVocabulary.tokens("GANZ RECHTS"))
    }
}
