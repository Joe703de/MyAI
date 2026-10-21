# Lizenzen und Nutzungsbedingungen

Dieses Dokument führt die externen Softwarekomponenten, Modelle und Datensätze auf, die in der **MyAI** Android-App verwendet werden, zusammen mit ihren Quellen und Lizenzbedingungen.

> Für die App-Codebasis selbst (die von uns geschriebenen Kotlin-Dateien, Gradle-Builds, Compose-UI usw.) gilt keine separate Open-Source-Lizenz, sofern nicht anders angegeben. Der Quellcode wird in diesem Repository veröffentlicht, aber er darf nur unter Beachtung der nachfolgenden Lizenzbedingungen der eingebundenen Komponenten weiterverwendet werden.

---

## 1. YOLO-Objekterkennung

### 1.1 Ultralytics YOLOv8

- **Komponente:** YOLO-Architektur, Trainingsframework, Export-Tools
- **Quelle:** https://github.com/ultralytics/ultralytics
- **Lizenz:** **AGPL-3.0** (GNU Affero General Public License v3.0)
- **Nutzung in MyAI:**
  - Das YOLOv8n-Modell wurde mit Ultralytics-Tools aus den COCO-vortrainierten Gewichten exportiert.
  - Die App verwendet die exportierte TensorFlow Lite-Datei (`yolo_objects.tflite` bzw. spätere Varianten), nicht den Ultralytics-Code selbst.
- **Wichtig:** Wer die Ultralytics-Tools selbst nutzt, trainiert oder das Modell auf einem Server verarbeitet, muss die AGPL-3.0 beachten. Für eine rein lokale On-Device-Nutzung der TFLite-Datei in einer Android-App ist dies in der Regel unkritisch, aber prüfe bei kommerzieller Verwendung mit einem Anwalt.

### 1.2 Verwendetes YOLO-Modell

- **Datei:** `app/src/main/assets/yolo_objects.tflite`
- **Basismodell:** Ultralytics YOLOv8n, vortrainiert auf dem **COCO-Datenset**
- **Exportiert am:** 10.10.2026
- **Erkannte Klassen:** 80 COCO-Klassen (die App filtert aktuell auf Klasse 2 = Auto)
- **Lizenz des Modells:** Fällt unter die Ultralytics-AGPL-3.0-Lizenz für die trainierten Gewichte.

---

## 2. Google ML Kit Text Recognition (OCR)

- **Komponente:** On-Device Text Recognition
- **Quelle:** https://developers.google.com/ml-kit/vision/text-recognition
- **Dependency:** `com.google.android.gms:play-services-mlkit-text-recognition:19.0.0`
- **Paket/Namespace:** `com.google.mlkit.vision.text`
- **Lizenz / Nutzungsbedingungen:**
  - **Kostenlos** zur Nutzung.
  - Unterliegt den **Google APIs Terms of Service** und den **ML Kit Nutzungsbedingungen**.
  - Die Texterkennung läuft **lokal auf dem Gerät**. Es werden keine Kamerabilder oder erkannten Texte an Google-Server übertragen.
  - Für den Play Store-Release sollte die Datenschutzerklärung darauf hinweisen, dass Google ML Kit verwendet wird, auch wenn keine Daten übermittelt werden.
- **Weitere Sprachmodelle (nicht aktiviert):**
  - `com.google.mlkit:text-recognition-chinese`
  - `com.google.mlkit:text-recognition-devanagari`
  - `com.google.mlkit:text-recognition-japanese`
  - `com.google.mlkit:text-recognition-korean`
  - Diese unterliegen denselben ML Kit-Bedingungen.

---

## 3. COCO-Datenset

- **Name:** Common Objects in Context (COCO)
- **Quelle:** https://cocodataset.org
- **Lizenz:**
  - Die Bilder stammen aus verschiedenen Quellen; jeder Bildersteller kann eigene Lizenzen haben.
  - Die COCO-Annotationen und das Datenset-Format stehen unter einer eigenen Lizenz (siehe https://cocodataset.org/#termsofuse).
  - Für die Nutzung der vortrainierten YOLOv8n-COCO-Gewichte über Ultralytics gilt die AGPL-3.0 von Ultralytics.
- **Nutzung in MyAI:**
  - Das Modell wurde auf COCO vortrainiert und erkennt daher u. a. Autos, Personen, Fahrräder, Schilder uvm.
  - Die App filtert die 80 COCO-Klassen auf die gewünschte Klasse herunter.

---

## 4. Weitere eingebundene Bibliotheken

### 4.1 TensorFlow Lite

- **Quelle:** https://github.com/tensorflow/tensorflow
- **Lizenz:** Apache-2.0
- **Dependencies:**
  - `org.tensorflow:tensorflow-lite`
  - `org.tensorflow:tensorflow-lite-gpu`
  - `org.tensorflow:tensorflow-lite-gpu-api`

### 4.2 CameraX

- **Quelle:** https://developer.android.com/jetpack/androidx/releases/camera
- **Lizenz:** Apache-2.0
- **Version:** 1.4.0-alpha05

### 4.3 Jetpack Compose

- **Quelle:** https://developer.android.com/jetpack/compose
- **Lizenz:** Apache-2.0

### 4.4 Kotlinx Serialization

- **Quelle:** https://github.com/Kotlin/kotlinx.serialization
- **Lizenz:** Apache-2.0
- **Version:** 1.6.3

### 4.5 Accompanist Permissions

- **Quelle:** https://github.com/google/accompanist
- **Lizenz:** Apache-2.0
- **Version:** 0.35.1-alpha

---

## 5. App-Icon und Medien

- **App-Icon-Quellbild:** `media/myai_icon_source.jpg`
- **Herkunft:** Eigenes Bild / vom Nutzer bereitgestelltes Bild.
- **Nutzung:** Nur für dieses Projekt bestimmt.

---

## 6. Hinweise zur kommerziellen Nutzung

- MyAI nutzt ein auf COCO vortrainiertes YOLOv8n-Modell, das über Ultralytics exportiert wurde. Für kommerzielle Zwecke sollte die **AGPL-3.0** von Ultralytics geprüft werden.
- Google ML Kit ist kostenlos, unterliegt aber den Google-Nutzungsbedingungen.
- Alle anderen genannten Bibliotheken stehen unter der Apache-2.0-Lizenz.

---

## 7. Änderungen

Diese Datei sollte bei jedem neuen eingebundenen Modell oder jeder neuen Lizenz-relevanten Dependency aktualisiert werden.
