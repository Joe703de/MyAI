# Konzept: MyAI Erweiterungen

Dieses Dokument beschreibt geplante Erweiterungen für die MyAI-App. Der Code wird hier noch nicht geändert – es dient als Planungsgrundlage und als Gedächtnisstütze für die nächsten Schritte.

---

## 1. Entfernungsabschätzung erkannter Autos

### Ziel

Für jedes erkannte Auto soll eine Entfernungsangabe in **Metern** ermittelt werden.

### Grundidee

Die YOLO-Box des Autos nimmt umso mehr Bildpixel ein, je näher das Auto ist. Mit einer Kamerakalibrierung und einer bekannten Referenzgröße (Autobreite) kann daraus eine Entfernung berechnet werden.

### Formel

```
Entfernung [m] = (reale_Autobreite [m] × f_x [Pixel]) / Box_Breite_in_Pixel
```

`f_x` ist die effektive Brennweite in Pixel. Sie wird entweder aus Kamera-Spezifikationen berechnet oder per Referenzmessung bestimmt.

### Kalibrierung per Referenzbild

Ein Auto mit bekannter Breite wird in bekannter Entfernung abgefilmt. Daraus ergibt sich:

```
f_x = Box_Breite_in_Pixel × Entfernung [m] / reale_Autobreite [m]
```

Beispiel:
- Box-Breite = 120 Pixel
- Entfernung = 10 m
- reale Autobreite = 1,8 m

```
f_x = 120 × 10 / 1,8 = 667 Pixel
```

Danach kann für jedes erkannte Auto die Entfernung berechnet werden:

```
Entfernung = 1,8 × 667 / Box_Breite_in_Pixel
```

### UI-Kalibrierung / Slider

Im Debug- oder Einstellungsmodus soll ein Slider angeboten werden, mit dem `f_x` justiert werden kann. Alternativ wird `f_x` aus einer kleinen Kalibrierungsaufnahme ermittelt.

### Plausibilisierung über mehrere Autos

Wenn mehrere Autos im Bild erkannt werden, kann die relative Position und Größe genutzt werden:

- Autos, die tiefer im Bild stehen (näher am unteren Rand bei typischer Kamera-Montage), sollten kleinere Entfernungen haben.
- Autos, die höher im Bild stehen, sollten weiter entfernt sein.
- Ausreißer werden erkannt, wenn ein Auto im Hintergrund plötzlich eine sehr geringe Entfernung anzeigt.

Diese Konsistenzprüfung hilft, fehlerhafte Box-Schwankungen auszugleichen.

### Einschränkungen

1. **YOLO-Box ist auf 640×640 normalisiert.** Vor der Berechnung muss die Box auf die Originalbildauflösung hochgerechnet werden.
2. **Unbekannte Fahrzeugbreite:** Smart (~1,6 m), Mittelklasse (~1,8 m), SUV (~1,9–2,0 m). Ohne Klassifizierung entstehen Fehler von 10–15 %.
3. **Verdeckung:** Wenn das Auto teilweise verdeckt ist, wird die Box zu klein und die Entfernung zu groß.
4. **Perspektive:** Bei schräger Ansicht misst die Box nicht die reale Breite, sondern die projizierte Breite.
5. **Schwankungen:** YOLO-Boxen können von Frame zu Frame um wenige Pixel springen. Eine Glättung über mehrere Frames ist sinnvoll.

### Empfohlener Weg

1. Zuerst Referenzaufnahmen bei 5 m, 10 m, 15 m, 20 m machen.
2. Für jede Entfernung die Box-Breite loggen.
3. Daraus eine Lookup-Tabelle oder Regressionsgerade bilden.
4. Im Live-Betrieb diese Tabelle verwenden, anstatt der reinen Formel.

Das ist oft genauer, weil Kameraverzerrung, Objektiv und Perspektive automatisch mit eingelernt werden.

---

## 2. OCR-zuerst-Ansatz für Text im Bild

### Ziel

Anstatt erst ein Objekt zu suchen und dann darauf OCR auszuführen, soll die App zuerst alle Texte im Bild finden, sammeln und als anklickbare Overlays anzeigen. Optional kann YOLO dann die Region klassifizieren (z. B. Verkehrsschild, Nummernschild, Plakat).

### Ablauf

1. **OCR pro Frame:** ML Kit liefert Textblöcke mit jeweils:
   - erkanntem Text
   - Bounding Box
   - Unterteilung in Lines und Elements

2. **Tracking über Frames:** Textblöcke, die sich an ähnlicher Position befinden und ähnlichen Inhalt haben, bekommen eine stabile ID. Dadurch entsteht eine Liste von „aktuell sichtbaren Texten“ statt frame-weise wechselnder Ergebnisse.

3. **Anklickbare Overlays:** In der Compose-UI werden Rechtecke an den Textpositionen gezeichnet. Ein Klick zeigt den erkannten Text in einer Detailansicht oder einem Toast an.

4. **Optionale YOLO-Klassifikation:** Ein kleines YOLO-Modell klassifiziert die Region um den Text herum:
   - Verkehrsschild
   - Nummernschild
   - Plakat / Werbung
   - Hausnummer / Straßenschild
   - unbekannt

### Vorteile

- Die App ist nicht auf ein vortrainiertes Schilder-YOLO-Modell angewiesen.
- Texte sind sofort sichtbar, auch wenn die Klassifizierung noch fehlt.
- Der Nutzer kann gezielt auf Texte im Bild tippen, um mehr zu erfahren.

### Herausforderungen

- Texte flackern und springen zwischen Frames.
- Nicht jeder erkannte Text ist relevant.
- Performance: OCR + Tracking + Overlays + optionales YOLO müssen effizient sein.
- YOLO muss die Region um den Text herum klassifizieren, nicht nur die Box selbst.

---

## 3. Modell-Strategie

### Aktuell

- Ein allgemeines YOLOv8n COCO-Modell (`yolo_objects.tflite`) erkennt Autos.
- ML Kit OCR liest den gesamten Bildtext.

### Zukünftig

- Ein eigenes, kleines YOLO-Modell für deutsche Verkehrszeichen / Autobahnschilder trainieren.
- Alternativ: Ein kleines Region-Klassifikations-Modell für Textregionen (Schild / Nummernschild / Plakat).
- Oder: Zuerst OCR, dann YOLO als Klassifikator der Textregion.

Die Entscheidung hängt davon ab, welcher Ansatz in der Praxis besser funktioniert.

---

## 4. Nächste Schritte (Vorschlag)

1. **Entfernungsabschätzung für Autos** als Proof-of-Concept implementieren.
   - Debug-Overlay zeigt geschätzte Entfernung pro Auto.
   - Kalibrierung per Slider oder Referenzaufnahme.

2. **OCR-Tracking und anklickbare Overlays** testen.
   - Texte stabil über Frames halten.
   - Einfache Boxen als Compose-Overlay zeichnen.

3. **Eigenes Schilder-/Regionen-YOLO-Modell** trainieren, falls der OCR-zuerst-Ansatz nicht ausreicht.

---

*Dieses Dokument wird bei neuen Erkenntnissen aktualisiert.*
