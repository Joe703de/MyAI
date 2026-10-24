# Car Detect

Android-App zur Echtzeit-Erkennung von Autos mit YOLO und TensorFlow Lite.

> **Lizenzen:** Für die verwendeten Komponenten (YOLO, ML Kit, COCO, TensorFlow Lite etc.) siehe [`LICENCE.md`](LICENCE.md).

> **Hinweis:** Das Projekt wurde von der ursprünglichen OpenCV-basierten Eiererkennung auf ein YOLO-Modell umgestellt. Aktuell wird die COCO-Klasse **2 = Auto** erkannt.

## Funktionen

- **YOLO-Objekterkennung** per TensorFlow Lite (`app/src/main/assets/yolo_objects.tflite`)
- **Echtzeit-Kamera-Analyse** mit CameraX und Jetpack Compose-UI
- **Farbklassifikation** der erkannten Autos per HSV-Pixelabstimmung, ohne OpenCV
- **GPU-Beschleunigung** über den TensorFlow Lite GPU-Delegate
- **Debug-Overlay** mit Bounding-Boxes, Preview-/Bildgröße und Top-Scores
- **Umschaltbarer Eingabemodus:** RGB 0-255 ↔ BGR 0-255

## Erkannte Farben

Für jede erkannte Auto-Box wird die dominante Farbe ermittelt. Unterstützt werden:

- Rot
- Gelb
- Grün
- Blau
- Weiß
- Schwarz
- Grau (unsichere/unbunte Bereiche)

Die Farben werden über den mittleren Bildbereich der Box per HSV-Voting klassifiziert.
Im Chat versteht das Regelmodell zusätzliche Farbbegriffe und deren Flektionsformen
(z. B. „rotes“, „rote“, „blauer“). Eine vollständige Liste der Synonyme steht in [`Chat.md`](Chat.md).

## Chat

Die App enthält eine experimentelle `ChatActivity` mit einem regelbasierten Chatbot.
Eine kleine Live-Kameravorschau steht oben rechts. Jede Anfrage wird mit einem neuen
Frame verarbeitet; passende Treffer öffnen per Zoom die eingefrorene Ergebnisansicht
mit exakt zu diesem Frame gehörenden Overlays. Ergebnisbilder zeigen bis zu drei
Antwortzeilen und sind über „Bild“ an der jeweiligen Chatantwort erneut abrufbar.
Verlauf und Bilder bleiben lokal gespeichert, bis „Chat löschen“ bestätigt wird.
Details zu den unterstützten Schlüsselwörtern, Nachfragen und Farbsynonymen findest du in [`Chat.md`](Chat.md).

## Technischer Stack

- Kotlin + Jetpack Compose
- CameraX 1.4.0-alpha05
- TensorFlow Lite 2.17.0 + GPU Delegate
- Kotlinx Serialization für JSON
- MinSDK: 24, TargetSDK: 36

## Modell

Das YOLO-Modell liegt unter:

```
app/src/main/assets/yolo_objects.tflite
```

### Herkunft

Das Modell wurde am **10.10.2026** aus dem offiziellen Ultralytics **YOLOv8n** COCO-Modell exportiert.

- **Basismodell:** Die trainierten Gewichte kommen von Ultralytics und wurden auf dem **COCO-Datenset** (Common Objects in Context) vortrainiert. Das Modell erkennt 80 COCO-Klassen; die App filtert auf die ausgewählte Klasse.
- **Eigenes Modell:** Für eine spezialisierte Erkennung (z. B. nur Autos, nur gelbe Autos oder Eier) benötigst du eigene Trainingsdaten. Diese müssen gesammelt und annotiert werden, zum Beispiel mit:
  - [Roboflow](https://roboflow.com) (online, annotieren + exportieren)
  - [LabelImg](https://github.com/tzutalin/labelImg) (lokal)
  - [CVAT](https://www.cvat.ai) (online/lokal)

### Eigenes Modell trainieren (Fine-Tuning)

Du kannst das vortrainierte YOLOv8n COCO-Modell mit eigenen Daten weitertrainieren, z. B. um Eier zu erkennen. Das nennt man **Transfer Learning** – das Modell behält die allgemeinen Bildmerkmale aus COCO und lernt dazu, deine neuen Objekte zu erkennen.

**Voraussetzungen:**
- Eigene Bilder des Objekts (z. B. Eier in verschiedenen Positionen, Lichtverhältnissen und Hintergründen)
- Annotationen im YOLO-Format (`*.txt` pro Bild mit relativen Bounding-Box-Koordinaten)
- Eine `data.yaml`, die Pfade zu Trainings-/Validierungsbildern und Klassennamen enthält

**Empfohlene Anzahl Bilder:**
- Minimum: ~50–100 Bilder pro Klasse
- Besser: mehrere Hundert für robuste Ergebnisse

**Tools zum Annotieren:**
- [Roboflow](https://roboflow.com) – online, einfach zu teilen, exportiert direkt ins YOLO-Format
- [LabelImg](https://github.com/tzutalin/labelImg) – lokales Tool, klassisch
- [CVAT](https://www.cvat.ai) – online oder selbst gehostet

**Beispiel: Training und Export nach TFLite:**

```bash
pip install torch ultralytics

# data.yaml muss die Klassen und Pfade definieren
yolo detect train model=yolov8n.pt data=pfad/zu/data.yaml epochs=50 imgsz=640

# Bestes Modell exportieren
yolo export model=runs/detect/train/weights/best.pt format=tflite
```

Die exportierte `best.tflite` kann dann in `app/src/main/assets/yolo_objects.tflite` umbenannt und in die App eingebunden werden.

Falls du das Modell neu erzeugen möchtest:

```bash
pip install torch ultralytics
# Optional, falls Ultralytics danach fragt:
pip install litert-torch ai-edge-litert

yolo export model=yolov8n.pt format=tflite
```

Die exportierte Datei `yolov8n.tflite` wird anschließend in `app/src/main/assets/yolo_objects.tflite` umbenannt.

> Das Modell wird im YOLOv8-Format mit 640×640 Pixel Eingabe erwartet. Die App unterstützt sowohl das Standardformat `[1, N, 5 + C]` als auch das transponierte Format `[1, 5 + C, N]`.

## Farbkonfiguration

Die anzeigbaren Farben sind in `app/src/main/assets/egg_colors.json` definiert:

```json
{
  "colors": [
    { "name": "Gelb", "rgb": { "r": 255, "g": 255, "b": 0 } },
    { "name": "Rot",  "rgb": { "r": 255, "g": 0,   "b": 0 } },
    { "name": "Grün", "rgb": { "r": 0,   "g": 255, "b": 0 } },
    { "name": "Blau", "rgb": { "r": 0,   "g": 0,   "b": 255 } }
  ]
}
```

## UI

- Live-Kameravorschau
- Status-Bar mit:
  - Anzahl erkannter Autos (noch als `Eier` in der UI beschriftet)
  - Verarbeitungszeit und Auflösung
  - Preview-/Bildgröße
  - TargetScore und GlobalScore
- Farbige Indikator-Kreise für bis zu 6 erkannte Objekte
- `Input`-Button zum Umschalten zwischen RGB/BGR

## Bekannte Limitierungen

- Die UI-Beschriftungen (`Eier`, `EggIndicator`) stammen noch aus der Vorgängerversion und sind nicht mehr semantisch korrekt.
- Das Modell `yolo_objects.tflite` ist ein YOLOv8n COCO-Modell, das neben Autos auch andere Klassen erkennen könnte – gefiltert wird aktuell auf Klasse 2 (Auto).
- Der Debug-Overlay zeigt ein festes Magenta-Rechteck in der Bildmitte zur Kalibrierung.

## Branches

- `master` – aktueller YOLO/Cars-Stand
- `next` – Entwicklungsbranch für kommende Änderungen
- `yolo` – Historischer Feature-Branch für die YOLO-Umstellung
- `opencv` – Archivierter OpenCV/Eier-Stand
