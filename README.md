# Egg Detect

Android-App zur Eierkennung mit OpenCV und CameraX.

## Bekannte Probleme

### ⚠️ RGB/BGR Farbkonvertierung
**Wichtig:** Das Gerät liefert Bilder im RGB-Format, aber die HSV-Konvertierung erwartet BGR. Dies führt zu abweichenden Hue-Werten.

**Lösung:** Die HSV-Werte in der JSON-Konfiguration wurden entsprechend angepasst:
- Gelb wird auf dem Gerät als Cyan/Grün (H=80-110) erkannt, nicht als Standard-Gelb (H=20-35)
- S1/S2 HSV-Ranges in `egg_colors.json` angepasst an das konkrete Gerät

### ⚠️ 16 KB Page Size Warnung
Die App zeigt beim Build eine Warnung bezüglich 16 KB Page Sizes:
```
APK app-debug.apk is not compatible with 16 KB devices
```

**Grund:** OpenCV 4.5.3 (quickbirdstudios) wurde vor der Einführung von 16 KB Page Sizes erstellt.

**Deadline:** Ab 1. November 2025 müssen alle neuen Apps und Updates im Google Play Store 16 KB unterstützen.

**Lösung erforderlich:** OpenCV auf eine neuere Version aktualisieren oder alternativen Wrapper finden, der 16 KB kompatibel ist.

## Technischer Stack
- Kotlin + Jetpack Compose
- CameraX 1.4.0-alpha05
- OpenCV 4.5.3 (quickbirdstudios)
- MinSDK: 24, TargetSDK: 36

## Branch: opencv
Aktuelle Entwicklung zur Integration von OpenCV Bildverarbeitung.
