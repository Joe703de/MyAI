package com.joe703.myai

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGrammarTest {
    @Test fun eachDetectionIsSingularEvenInAMixedList() {
        val detections = listOf("Rot" to "Auto", "Weiss" to "LKW", "Gruen" to "Ampel", "Rot" to "Stoppschild")
        assertEquals(listOf("rotes Auto", "weißer LKW", "grüne Ampel", "rotes Stoppschild"),
            detections.map { (color, name) -> ChatGrammar.listItem(color, name) })
    }

    @Test fun seenObjectsUseAccusativeNotNominative() {
        assertEquals("ein rotes Auto", ChatGrammar.seenObject("Rot", "Auto"))
        assertEquals("einen weißen LKW", ChatGrammar.seenObject("Weiss", "LKW"))
        assertEquals("einen roten Bus", ChatGrammar.seenObject("Rot", "Bus"))
        assertEquals("eine grüne Ampel", ChatGrammar.seenObject("Gruen", "Ampel"))
        assertEquals("einen braunen Bären", ChatGrammar.seenObject("Braun", "Bär"))
    }

    @Test fun onlyCountedGroupsUsePlural() {
        assertEquals("3 rote Autos", ChatGrammar.counted(3, "Rot", "Auto"))
        assertEquals("2 grüne Ampeln", ChatGrammar.counted(2, "Gruen", "Ampel"))
        assertEquals("4 weiße LKWs", ChatGrammar.counted(4, "Weiss", "LKW"))
        assertEquals("2 rote Stoppschilder", ChatGrammar.counted(2, "Rot", "Stoppschild"))
        assertEquals("2 rote Busse", ChatGrammar.counted(2, "Rot", "Bus"))
        assertEquals("ein rotes Auto", ChatGrammar.counted(1, "Rot", "Auto"))
    }

    @Test fun missingColorStillUsesTheCorrectArticle() {
        assertEquals("eine Ampel", ChatGrammar.seenObject(null, "Ampel"))
        assertEquals("einen Bus", ChatGrammar.seenObject("", "Bus"))
        assertEquals("Die Ampel", ChatGrammar.subject(null, "Ampel"))
        assertEquals("kein rotes Auto", ChatGrammar.seenObject("Rot", "Auto", negative = true))
        assertEquals("keinen weißen LKW", ChatGrammar.seenObject("Weiss", "LKW", negative = true))
        assertEquals("keine Ampel", ChatGrammar.seenObject(null, "Ampel", negative = true))
    }

    @Test fun sentenceSubjectsHaveDefiniteArticlesAndWeakEndings() {
        assertEquals("Der weiße LKW", ChatGrammar.subject("Weiss", "LKW"))
        assertEquals("Die grüne Ampel", ChatGrammar.subject("Gruen", "Ampel"))
        assertEquals("Das rote Auto", ChatGrammar.subject("Rot", "Auto"))
    }

    @Test fun specialColorStemsDoNotDuplicateVowels() {
        assertEquals("oranges Auto", ChatGrammar.listItem("Orange", "Auto"))
        assertEquals("beiges Auto", ChatGrammar.listItem("Beige", "Auto"))
        assertEquals("türkises Auto", ChatGrammar.listItem("Tuerkis", "Auto"))
        assertEquals("silbernes Auto", ChatGrammar.listItem("Silber", "Auto"))
        assertEquals("lila Auto", ChatGrammar.listItem("Lila", "Auto"))
        assertEquals("rosa Auto", ChatGrammar.listItem("Rosa", "Auto"))
    }
}
