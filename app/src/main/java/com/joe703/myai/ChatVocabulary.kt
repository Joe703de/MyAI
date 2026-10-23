package com.joe703.myai

import java.util.Locale

/** Exact word matching with explicit German inflections, not fuzzy substring matching. */
internal object ChatVocabulary {
    private val classes = mapOf(
        2 to listOf("auto", "autos", "wagen", "fahrzeug", "fahrzeuge", "fahrzeugen", "pkw", "pkws", "personenkraftwagen"),
        5 to listOf("bus", "busse", "bussen", "busses", "omnibus", "omnibusse", "omnibussen"),
        7 to listOf("lkw", "lkws", "lastwagen", "lastkraftwagen", "sattelzug", "sattelzuege", "sattelzuegen", "sattelschlepper", "sattelschleppern"),
        9 to listOf("ampel", "ampeln", "verkehrsampel", "verkehrsampeln"),
        11 to listOf("stoppschild", "stoppschilder", "stoppschildern", "stoppschildes", "stoppschilds",
            "stopschild", "stopschilder", "stopschildern", "stopschildes", "stopschilds", "stop", "stopp")
    ).flatMap { (id, words) -> words.map { it to id } }.toMap()

    fun tokens(question: String): MutableList<String> {
        val normalized = question.lowercase(Locale.ROOT)
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("(?<=\\d)m\\b"), " ")
            // Quotes, Unicode dashes and punctuation all act as word boundaries.
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\bstopp?\\s+(schildern|schilder|schildes|schilds|schild)\\b")) {
                "stop${it.groupValues[1]}"
            }
            .replace(Regex("\\bverkehrs\\s+(ampeln|ampel)\\b"), "verkehrs$1")
            .replace(Regex("\\blast\\s+(kraft\\s+)?wagen\\b"), "lastwagen")
            .replace(Regex("\\bpersonen\\s+kraft\\s+wagen\\b"), "personenkraftwagen")
            .replace(Regex("\\bsattel\\s+(zuegen|zuege|zug|schleppern|schlepper)\\b"), "sattel$1")
        return normalized.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
    }

    fun classId(tokens: List<String>): Int? = tokens.firstNotNullOfOrNull { classes[it] }
}
