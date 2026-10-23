# Chat – Regelbasiertes Sprachmodell

Die `ChatActivity` beantwortet Fragen zum aktuell von der Kamera erfassten Bild.
Sie nutzt das letzte `DetectionResult` aus der laufenden Objekterkennung.
Beim Absenden einer Anfrage wird ein Snapshot des Bilds gespeichert.
Wenn die Anfrage passende Objekte findet, öffnet sich `ChatResultActivity` mit dem Bild.
Darin werden passende Objekte mit einem **grünen** Rahmen hervorgehoben;
alle anderen erkannten Objekte werden mit einem **gelben** Rahmen angezeigt.

## Wie das Regelmodell arbeitet

1. **Text normalisieren** – Umlaute werden aufgelöst, Satzzeichen und das „m“ für Meter entfernt.
2. **Intent erkennen** – Der Bot sucht nach Schlüsselwörtern für:
   - komplette Übersicht („Was siehst du?“)
   - Ort („Wo ist…?“)
   - Entfernung („Wie weit ist…?“)
   - Entfernungsfilter („100m“)
3. **Entitäten extrahieren** – Farbe und Objektklasse werden aus der Frage gelesen.
4. **Objekte filtern** – Alle erkannten Objekte werden auf Farbe und Klasse geprüft.
5. **Antwort erzeugen** –
   - Kein Treffer: „Ich sehe gerade kein rotes Auto.“
   - 1–3 Treffer: Liste mit Position/Entfernung.
   - Mehr als 3 Treffer: Zahl der Treffer plus Nachfrage.

## Erkannte Objektklassen

| Schlüsselwörter | COCO-Klasse |
|---|---|
| Auto, Autos, Wagen, Fahrzeug, Fahrzeuge, PKW | Auto (2) |
| Bus, Busse | Bus (5) |
| LKW, Lastwagen, Lastkraftwagen, Sattelzug, Sattelschlepper | LKW (7) |
| Ampel, Ampeln, Verkehrsampel | Ampel (9) |
| Stoppschild, Stopschild, Stop, Stopschilder | Stoppschild (11) |

## Erkannte Farben

Die Farben aus der Kamera-Analyse sind aktuell: **Rot, Gelb, Grün, Blau, Weiß, Schwarz, Grau**.
Der Chat versteht aber viele weitere Farbbegriffe und ordnet sie der nächsten bekannten Farbe zu.

| Begriffe | Gemappt auf |
|---|---|
| rot, rote, roter, rotes, weinrot, bordeaux, dunkelrot, hellrot | Rot |
| blau, blaue, blauer, blaues, dunkelblau, hellblau | Blau |
| grün, grüne, grüner, grünes, dunkelgrün, hellgrün | Grün |
| gelb, gelbe, gelber, gelbes | Gelb |
| schwarz, schwarze, schwarzer, schwarzes | Schwarz |
| weiß, weiße, weißer, weißes, weiss, weisse, weisser, weisses | Weiß |
| grau, graue, grauer, graues, dunkelgrau, hellgrau | Grau |
| silber, silberne, silberner, silbernes | Grau/Silber |
| orange, orangene, orangener, oranges | Orange |
| braun, braune, brauner, braunes | Braun |
| lila, violett, violette, violetter, violettes | Lila → wird als unbunt/Grau behandelt, falls nicht anders konfiguriert |
| pink, rosa, rosane, rosaner, rosanes | Rosa → wird als unbunt/Grau behandelt, falls nicht anders konfiguriert |
| beige, beiger, beiges | Beige → wird als unbunt/Grau behandigt, falls nicht anders konfiguriert |
| gold, goldene, goldener, goldenes | Gold → wird als Gelb behandelt |
| türkis, türkise, türkiser, türkises | Türkis → wird als Grün/Blau behandelt |

> Hinweis: Die tatsächlich vom Analyzer erkannten Farben hängen von `object_colors.json` ab.
> Nicht erkannte Farben werden intern auf die nächste passende bekannte Farbe abgebildet.

## Beispielanfragen

- „Was siehst du?“
- „Wo ist das blaue Auto?“
- „Wie weit ist das rote Auto?“
- „Welche Objekte sind 100m entfernt?“
- „Ich sehe ein rotes Auto“
- „Gibt es einen Bus?“

## Nachfragen bei mehr als drei Treffern

Wenn eine Anfrage mehr als drei passende Objekte findet, antwortet der Bot z. B.:

> „Ich sehe 5 autos. Das nächste ist 42m entfernt.
> Soll ich dir die komplette Liste zeigen, nur das nächste, nur das rechte oder nur die drei nächsten?“

Mögliche Antworten auf diese Nachfrage:

- „komplette Liste“ – zeigt alle Treffer an
- „nur das nächste“ – nur das nächste Objekt
- „nur das rechte“ – nur Objekte rechts
- „nur das linke“ – nur Objekte links
- „nur die drei nächsten“ – die drei nächsten Objekte (derzeit implizit über „nächste“)

Diese Folgeanfragen greifen auf die letzten Kandidaten zurück und filtern sie erneut.
