# VIF BookScanner (Android)

Android-App für einen physischen Dual-Kamera-Buchscanner (2× USB-UVC-Webcam, z. B. ArduCAM IMX298 AF) — Ziel: bestmögliche, OCR-taugliche Aufnahmen von Buchseiten.

Copyright (c) 2026 Immanuel Friedrichsen — siehe [NOTICE](NOTICE) für die vollständige Copyright-/Lizenz-Zuordnung.

Dieses Projekt ist ein **Fork von [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera)** (Copyright (c) 2014-2017 saki t_saki@serenegiant.com). Der ursprüngliche UVC-Treiber diente als veraltete, aber solide Basis (letzte Aktivität ca. 2020) und wurde für dieses Projekt modernisiert und um eine vollständige App-Schicht ergänzt.

Lizenz: **Apache License, Version 2.0** (siehe [LICENSE](LICENSE)) — für das gesamte Repository, Original wie Ergänzungen.

---

## Projektstruktur

| Modul | Inhalt |
|---|---|
| `bookscanner-app` | Die eigentliche Android-App (Kotlin/Jetpack Compose): State Machine, Kamera-Kalibrierung, Settings-UI, Dateibenennung |
| `libuvccamera` | UVC-Treiber (natives C++, JNI) — modernisiert (CMake statt ndk-build, AndroidX, mehrere Android-12/13/14-Kompatibilitätsfixes) |
| `usbCameraCommon` | Handler-Schicht zwischen App und Treiber (`UVCCameraHandler`, UVC-Control-APIs) |
| `usbCameraTest0`–`usbCameraTest8` | Original-Beispielprojekte aus dem Upstream-Repo (Referenz, nicht Teil der eigentlichen App) |

## Kernfunktionen (bookscanner-app)

- Dual-Kamera-Anbindung (L/R) über einen USB-Hub, dynamische Auflösungswahl aus den tatsächlich unterstützten Kamera-Größen (kein fest hartcodiertes Kameramodell)
- Preview (niedrige Auflösung) / Capture (volle Sensor-Auflösung) mit Pflicht-Mode-Switch — UVC-Webcams unterstützen keinen parallelen Stream, nur einen wechselbaren
- Kamera-Kalibrierung: Automodus (Auto-Fokus/Auto-Weißabgleich einschwingen lassen, dann fixieren) und manueller Modus (Slider für alle UVC-Parameter, Live-Vorschau)
- Fokus-Sweep-Kalibrierung: systematischer Durchlauf des Fokusbereichs, Schärfe-Lookup-Kurve (Laplace-Varianz) statt reinem Vertrauen auf die kamerainterne Auto-Logik
- Pro-Aufnahme-Schärfeprüfung (statt festem Rekalibrierungs-Intervall): Fokus wird vor jeder Aufnahme erneut gesetzt + Schärfe gegen Referenzwert verglichen
- Dateibenennung: `{Projektname}_S{Seite:04d}_{L|R}.jpg`, Zeitstempel in EXIF

Vollständiges Konzept/Entwicklungsverlauf (nur intern, nicht Teil dieses Repos): `04-projects/vif-bookscanner/android/plan-2026-07-21-android-portierung.md` im privaten KI-OS-Vault.

## Build

Standard-Android-Gradle-Build:

```
./gradlew.bat :bookscanner-app:assembleDebug
```

Benötigt Android SDK + NDK (siehe `local.properties`).

---

## Herkunft: UVCCamera (Original-Upstream)

Der ursprüngliche `library and sample to access to UVC web camera on non-rooted Android device`-Code von saki4510t bildet die technische Basis der `libuvccamera`/`usbCameraCommon`-Module sowie der `usbCameraTestN`-Beispielprojekte. Änderungshistorie des Originals: [github.com/saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera).

Wesentliche Modernisierungen gegenüber dem Original (Details siehe [NOTICE](NOTICE) und Git-Historie dieses Repos):
- JCenter (2021/22 abgeschaltet) → mavenCentral/google
- Android Gradle Plugin 3.1.4 → 8.5.2, Gradle Wrapper 4.4 → 8.7
- `ndk-build`/`Android.mk` → CMake
- AndroidX-Migration
- Mehrere Android-12/13/14-Laufzeit-Kompatibilitätsfixes (PendingIntent-Flags, `RECEIVER_EXPORTED`, USB-Permission-Timing)
- Echte Implementierung von `resize()` (war im Original ein permanenter `UnsupportedOperationException`-Stub)
