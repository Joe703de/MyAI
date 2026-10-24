# Chat – Regelbasiertes Sprachmodell

Die `ChatActivity` beantwortet Fragen zum aktuell von der Kamera erfassten Bild.
Der Chat betreibt eine eigene, an seinen Activity-Lebenszyklus gebundene Kameraanalyse.
Oben rechts schwebt eine kleine Live-Vorschau des zuletzt analysierten Frames über dem Chat.
Sie behält ihre Position und Größe auch bei geöffneter Bildschirmtastatur. Nur der
Chatbereich samt Eingabe passt sich an den verfügbaren Platz über der Tastatur an.
Beim Absenden (Senden-Button oder Tastatur) wartet die Anfrage auf einen neu analysierten
Frame. Das alte Bild aus der Hauptansicht wird nicht verwendet. Währenddessen ist
weiteres Senden gesperrt. Ohne neues Kamerabild gibt es nach spätestens acht Sekunden
einen Hinweis statt einer Antwort auf ein veraltetes Bild.
Beim Verlassen des Chats pausiert die Kamera; nach der Rückkehr startet sie erneut.
Wenn die Anfrage passende Objekte findet, öffnet sich `ChatResultActivity` mit einem
Zoom-Übergang aus der kleinen Vorschau (abhängig von Androids Animationseinstellungen).
Antwort, Ergebnisbild und alle Overlay-Koordinaten stammen aus demselben Frame und
werden gemeinsam eingefroren. Die Ergebnisansicht bleibt absichtlich ein Standbild.
Darin werden passende Objekte mit einem **grünen** Rahmen hervorgehoben;
alle anderen erkannten Objekte werden mit einem **gelben** Rahmen angezeigt.

## Gespeicherte Ergebnisse

Die Antwort erscheint sofort nach der Kameraanalyse im Chat. Das passende Bild wird
anschließend verlustfrei als PNG im Hintergrund gespeichert. Währenddessen ist sein
„Bild“-Button deaktiviert; weitere Fragen sind bereits möglich. Die Ergebnisansicht
öffnet sich ausschließlich durch Antippen von „Bild“, nicht automatisch.
Bei einem Speicherfehler bleibt die Antwort erhalten und erhält einen Fehlerhinweis.


Unter dem Ergebnisbild stehen die ersten drei sichtbaren Zeilen der zugehörigen
Chatantwort. Längere Antworten werden mit „…“ gekürzt; im Chat bleibt der volle Text.

Jede Antwort mit Ergebnisbild erhält einen kleinen **Bild**-Button. Er öffnet das
ursprüngliche Bild mit den damaligen Markierungen und der dazugehörigen Antwort,
auch wenn inzwischen weitere Fragen gestellt wurden. Bilder, Overlay-Koordinaten
werden für die laufende App-Sitzung lokal im privaten App-Speicher abgelegt;
der Chatverlauf bleibt im Arbeitsspeicher. Bilder werden erst beim Öffnen geladen,
nicht alle gleichzeitig. Beim normalen Beenden der App werden Verlauf und Bilder
entfernt. Nach einem erzwungenen Prozessabbruch ohne Android-Abschluss-Callback werden
übrig gebliebene Bilddateien beim nächsten Start gelöscht, nie wiederhergestellt.
Der Wechsel zwischen Chat, Ergebnisbild und Hauptansicht sowie ein vorübergehender
Wechsel in den Hintergrund löschen den Chat nicht. Ein neuer App-Task startet leer.
Nutzer-Nachrichten verwenden dieselbe Theme-Farbe wie der Senden-Button, mit der
dazugehörigen kontrastreichen Textfarbe.

**Chat löschen** entfernt nach Bestätigung den Verlauf und alle zugehörigen Bilder.
Dabei werden auch die gemerkten Farb-/Klassenfilter zurückgesetzt. Nicht bestätigtes
Löschen verändert nichts. Ältere Antworten, die vor dieser Änderung entstanden sind,
haben kein nachträglich rekonstruierbares Ergebnisbild.

## Wie das Regelmodell arbeitet

1. **Text normalisieren** – Umlaute werden aufgelöst, Satzzeichen und das direkt auf eine Zahl folgende „m“ für Meter entfernt.
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
| Auto, Autos, Wagen, Fahrzeug, Fahrzeuge, Fahrzeugen, PKW, PKWs, Personenkraftwagen | Auto (2) |
| Bus, Busse, Bussen, Busses, Omnibus, Omnibusse, Omnibussen | Bus (5) |
| LKW, LKWs, Lastwagen, Lastkraftwagen, Sattelzug, Sattelzüge, Sattelzügen, Sattelschlepper, Sattelschleppern | LKW (7) |
| Ampel, Ampeln, Verkehrsampel, Verkehrsampeln | Ampel (9) |
| Stoppschild, Stoppschilder, Stoppschildern, Stopschild, Stopschilder, Stopschildern, Stop, Stopp | Stoppschild (11) |

Groß-/Kleinschreibung und Anführungszeichen spielen keine Rolle. Zusammengesetzte
Begriffe wie **Stop Schilder**, **Stopp Schilder**, **Stop-Schild**, **Stopp-Schilder**,
**Verkehrs-Ampeln**, **Last-Wagen** und **Sattel-Züge** werden zusammengeführt.
Auch Unicode-Bindestriche werden unterstützt. Es werden ganze Wörter abgeglichen:
„Busch“ ist kein Bus und „Autobahn“ kein Auto.

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

- „Wo ist das blaue Auto?“
- „Wie weit ist das rote Auto?“
- „Welche Objekte sind 100m entfernt?“
- „Ich sehe ein rotes Auto“
- „Gibt es einen Bus?“
- „Das Auto ganz links“
- „Das weiteste Auto“
- „Das nächste Auto"

## Nachfragen bei mehr als drei Treffern

Wenn eine Anfrage mehr als drei passende Objekte findet, antwortet der Bot z. B.:

> „Ich sehe 5 autos. Das nächste ist 42m entfernt.
> Soll ich Dir die komplette Liste zeigen oder nur ein bestimmtes Auto?“

Mögliche Antworten auf diese Nachfrage:

- „komplette Liste“ – zeigt alle Treffer an
- „nur das nächste“ / „nah“ – nur das nächste Objekt
- „nur das rechte“ / „ganz rechts“ – Objekt(e) rechts bzw. das am weitesten rechts
- „nur das linke“ / „ganz links“ – Objekt(e) links bzw. das am weitesten links
- „nur das weiteste“ / „weit weg“ – das am weitesten entfernte Objekt

Folgeanfragen merken sich nur Klasse und Farbe, niemals alte Objektpositionen.
Nach „blaue Autos“ filtert „das Auto ganz rechts“ deshalb die blauen Autos des **neuen**
Frames und markiert das aktuell rechte. „Komplette Liste“ verwendet ebenfalls diese
Filter auf dem neuen Frame. Ein verschwundenes Objekt wird nicht im neuen Bild markiert.
„Was siehst du?“ beginnt wieder eine allgemeine Übersicht.

## Nah/Fern-Regel für den Fahrer

Für den Fahrer sind „oben“ und „unten“ im Bild keine sinnvollen Beschreibungen.
Daher verwendet der Chat für die Entfernungsbeschreibung folgende Regel:

1. **Primär: Kalibrierung** – Wenn für die erkannte Klasse Kalibrierdaten vorhanden sind,
   wird `estimateDistance(...)` verwendet.
   - unter 60 m → „nah“
   - ab 60 m → „weit weg“
2. **Fallback: Bildposition** – Wenn keine Kalibrierdaten vorhanden sind:
   - Objekt unten im Bild → „nah“
   - Objekt oben im Bild → „weit weg“
   - mittig im Bild → „mittlere Entfernung“

Beispiel: „Das rote Auto ist links und nah."

## Geräteprüfung

1. Chat öffnen, Kamera schwenken: Die Vorschau muss ohne erneutes Öffnen folgen.
2. Eine Frage senden und danach die Kamera bewegen: Die Ergebnisansicht behält den
   beantworteten Frame; grüne und gelbe Rahmen passen weiterhin zu diesem Bild.
3. Nach einer Nachfrage zu blauen Autos die Kamera schwenken und „ganz rechts“ senden:
   Nur ein aktuell erkanntes blaues Auto darf grün markiert werden.
4. Ergebnis schließen, erneut fragen; außerdem Hintergrund/Vordergrund und Drehung
   prüfen. Jede neue Frage braucht wieder einen neuen Frame.
5. Kamera blockieren/entziehen: Keine Antwort mit einem alten Bild; stattdessen Hinweis.

6. Zwei verschiedene Ergebnisse erzeugen und beide per „Bild“ wieder öffnen:
   Bild, Rahmen und Antwort müssen jeweils zur ausgewählten Nachricht gehören.
7. Lange Antwort auf drei sichtbare Zeilen mit „…“ prüfen. Interne Ansichtswechsel
   und kurzzeitigen Hintergrund prüfen: Verlauf bleibt erhalten. App beenden und
   neu starten: Verlauf und Bilder sind entfernt. Dasselbe nach erzwungenem Stopp prüfen.
8. „Chat löschen“ erst abbrechen, dann bestätigen: Nur beim Bestätigen wird gelöscht.

## Grammatik der Antworten

Jeder Aufzählungspunkt beschreibt genau ein erkanntes Objekt: „rotes Auto“,
„weißer LKW“, „grüne Ampel“ oder „rotes Stoppschild“. Nur Zusammenfassungen
mehrerer Objekte verwenden den Plural, etwa „3 rote Autos“. Nach „Ich sehe“
steht der Akkusativ („einen weißen LKW“); als Satzsubjekt dagegen
„Der weiße LKW“. `ChatGrammar` enthält explizite Formen für alle 80 COCO-Klassen.
