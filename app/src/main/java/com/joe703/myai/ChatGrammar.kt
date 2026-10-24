package com.joe703.myai

/** German display grammar, independent of Android and detection/filtering logic. */
internal object ChatGrammar {
    private data class Noun(val gender: String, val plural: String, val accusative: String)
    private val nouns = mapOf(
        "Fahrrad" to Noun("n", "Fahrräder", "Fahrrad"),
        "Auto" to Noun("n", "Autos", "Auto"),
        "Motorrad" to Noun("n", "Motorräder", "Motorrad"),
        "Flugzeug" to Noun("n", "Flugzeuge", "Flugzeug"),
        "Boot" to Noun("n", "Boote", "Boot"),
        "Stoppschild" to Noun("n", "Stoppschilder", "Stoppschild"),
        "Pferd" to Noun("n", "Pferde", "Pferd"),
        "Schaf" to Noun("n", "Schafe", "Schaf"),
        "Zebra" to Noun("n", "Zebras", "Zebra"),
        "Snowboard" to Noun("n", "Snowboards", "Snowboard"),
        "Skateboard" to Noun("n", "Skateboards", "Skateboard"),
        "Surfbrett" to Noun("n", "Surfbretter", "Surfbrett"),
        "Weinglas" to Noun("n", "Weingläser", "Weinglas"),
        "Messer" to Noun("n", "Messer", "Messer"),
        "Sandwich" to Noun("n", "Sandwiches", "Sandwich"),
        "Sofa" to Noun("n", "Sofas", "Sofa"),
        "Bett" to Noun("n", "Betten", "Bett"),
        "Handy" to Noun("n", "Handys", "Handy"),
        "Spülbecken" to Noun("n", "Spülbecken", "Spülbecken"),
        "Buch" to Noun("n", "Bücher", "Buch"),
        "Objekt" to Noun("n", "Objekte", "Objekt"),
        "Person" to Noun("f", "Personen", "Person"),
        "Ampel" to Noun("f", "Ampeln", "Ampel"),
        "Parkuhr" to Noun("f", "Parkuhren", "Parkuhr"),
        "Bank" to Noun("f", "Bänke", "Bank"),
        "Katze" to Noun("f", "Katzen", "Katze"),
        "Kuh" to Noun("f", "Kühe", "Kuh"),
        "Giraffe" to Noun("f", "Giraffen", "Giraffe"),
        "Handtasche" to Noun("f", "Handtaschen", "Handtasche"),
        "Krawatte" to Noun("f", "Krawatten", "Krawatte"),
        "Frisbee" to Noun("f", "Frisbees", "Frisbee"),
        "Flasche" to Noun("f", "Flaschen", "Flasche"),
        "Tasse" to Noun("f", "Tassen", "Tasse"),
        "Gabel" to Noun("f", "Gabeln", "Gabel"),
        "Schüssel" to Noun("f", "Schüsseln", "Schüssel"),
        "Banane" to Noun("f", "Bananen", "Banane"),
        "Orange" to Noun("f", "Orangen", "Orange"),
        "Karotte" to Noun("f", "Karotten", "Karotte"),
        "Pizza" to Noun("f", "Pizzen", "Pizza"),
        "Topfpflanze" to Noun("f", "Topfpflanzen", "Topfpflanze"),
        "Toilette" to Noun("f", "Toiletten", "Toilette"),
        "Maus" to Noun("f", "Mäuse", "Maus"),
        "Fernbedienung" to Noun("f", "Fernbedienungen", "Fernbedienung"),
        "Tastatur" to Noun("f", "Tastaturen", "Tastatur"),
        "Mikrowelle" to Noun("f", "Mikrowellen", "Mikrowelle"),
        "Uhr" to Noun("f", "Uhren", "Uhr"),
        "Vase" to Noun("f", "Vasen", "Vase"),
        "Schere" to Noun("f", "Scheren", "Schere"),
        "Zahnbürste" to Noun("f", "Zahnbürsten", "Zahnbürste"),
        "Bus" to Noun("m", "Busse", "Bus"),
        "Zug" to Noun("m", "Züge", "Zug"),
        "LKW" to Noun("m", "LKWs", "LKW"),
        "Feuerhydrant" to Noun("m", "Feuerhydranten", "Feuerhydranten"),
        "Vogel" to Noun("m", "Vögel", "Vogel"),
        "Hund" to Noun("m", "Hunde", "Hund"),
        "Elefant" to Noun("m", "Elefanten", "Elefanten"),
        "Bär" to Noun("m", "Bären", "Bären"),
        "Rucksack" to Noun("m", "Rucksäcke", "Rucksack"),
        "Regenschirm" to Noun("m", "Regenschirme", "Regenschirm"),
        "Koffer" to Noun("m", "Koffer", "Koffer"),
        "Ski" to Noun("m", "Skier", "Ski"),
        "Sportball" to Noun("m", "Sportbälle", "Sportball"),
        "Drachen" to Noun("m", "Drachen", "Drachen"),
        "Baseballschläger" to Noun("m", "Baseballschläger", "Baseballschläger"),
        "Baseballhandschuh" to Noun("m", "Baseballhandschuhe", "Baseballhandschuh"),
        "Tennisball" to Noun("m", "Tennisbälle", "Tennisball"),
        "Löffel" to Noun("m", "Löffel", "Löffel"),
        "Apfel" to Noun("m", "Äpfel", "Apfel"),
        "Brokkoli" to Noun("m", "Brokkoli", "Brokkoli"),
        "Hot Dog" to Noun("m", "Hot Dogs", "Hot Dog"),
        "Donut" to Noun("m", "Donuts", "Donut"),
        "Kuchen" to Noun("m", "Kuchen", "Kuchen"),
        "Stuhl" to Noun("m", "Stühle", "Stuhl"),
        "Esstisch" to Noun("m", "Esstische", "Esstisch"),
        "Fernseher" to Noun("m", "Fernseher", "Fernseher"),
        "Laptop" to Noun("m", "Laptops", "Laptop"),
        "Backofen" to Noun("m", "Backöfen", "Backofen"),
        "Toaster" to Noun("m", "Toaster", "Toaster"),
        "Kühlschrank" to Noun("m", "Kühlschränke", "Kühlschrank"),
        "Teddybär" to Noun("m", "Teddybären", "Teddybären"),
        "Haartrockner" to Noun("m", "Haartrockner", "Haartrockner")
    )

    internal fun supports(name: String): Boolean = name in nouns
    private fun noun(name: String) = nouns[name] ?: nouns.getValue("Objekt")
    private fun singular(name: String) = if (supports(name)) name else "Objekt"

    private fun adjective(color: String?, ending: String): String {
        val stem = when (color?.trim()?.lowercase()) {
            null, "", "unbekannt", "unknown" -> return ""
            "rot", "rote", "roter", "rotes" -> "rot"
            "blau", "blaue", "blauer", "blaues" -> "blau"
            "gruen", "grün", "gruene", "grüne", "gruener", "grüner", "gruenes", "grünes" -> "grün"
            "gelb" -> "gelb"
            "schwarz" -> "schwarz"
            "weiss", "weiß", "weisse", "weiße", "weisser", "weißer", "weisses", "weißes" -> "weiß"
            "grau" -> "grau"
            "silber" -> "silbern"
            "gold" -> "golden"
            "orange" -> "orang"
            "beige" -> "beig"
            "tuerkis", "türkis" -> "türkis"
            "braun" -> "braun"
            "violett" -> "violett"
            "pink" -> "pink"
            "lila" -> return "lila"
            "rosa" -> return "rosa"
            else -> return "" // An unknown color label is not a German adjective.
        }
        return stem + ending
    }

    private fun phrase(article: String, color: String?, ending: String, name: String): String =
        listOf(article, adjective(color, ending), name).filter { it.isNotBlank() }.joinToString(" ")

    /** One detection per bullet, regardless of the total number of detections. */
    fun listItem(color: String?, name: String): String {
        val ending = when (noun(name).gender) { "m" -> "er"; "f" -> "e"; else -> "es" }
        return phrase("", color, ending, singular(name))
    }

    /** Accusative after "Ich sehe" and "für", including masculine weak nouns. */
    fun seenObject(color: String?, name: String, negative: Boolean = false): String {
        val form = noun(name)
        val article = when (form.gender) { "m" -> "einen"; "f" -> "eine"; else -> "ein" }
        val ending = when (form.gender) { "m" -> "en"; "f" -> "e"; else -> "es" }
        return phrase(if (negative) "k$article" else article, color, ending, form.accusative)
    }

    /** Nominative subject at the beginning of a sentence. */
    fun subject(color: String?, name: String): String {
        val article = when (noun(name).gender) { "m" -> "Der"; "f" -> "Die"; else -> "Das" }
        return phrase(article, color, "e", singular(name))
    }

    fun counted(count: Int, color: String?, name: String): String =
        if (count == 1) seenObject(color, name)
        else "$count " + phrase("", color, "e", noun(name).plural)
}
