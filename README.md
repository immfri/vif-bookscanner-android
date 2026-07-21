# VIF BookScanner (Android)

Android app for a physical dual-camera book scanner (2x USB UVC webcam, e.g. ArduCAM IMX298 AF) — goal: best possible, OCR-ready captures of book pages.

Copyright (c) 2026 Immanuel Friedrichsen — see [NOTICE](NOTICE) for the full copyright/license attribution.

This project is a **fork of [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera)** (Copyright (c) 2014-2017 saki t_saki@serenegiant.com). The original UVC driver served as a dated but solid base (last upstream activity around 2020) and was modernized and extended with a full app layer for this project.

License: **Apache License, Version 2.0** (see [LICENSE](LICENSE)) — for the entire repository, both original and additions.

---

## Project structure

| Module | Content |
|---|---|
| `bookscanner-app` | The actual Android app (Kotlin/Jetpack Compose): state machine, camera calibration, settings UI, file naming |
| `libuvccamera` | UVC driver (native C++, JNI) — modernized (CMake instead of ndk-build, AndroidX, several Android 12/13/14 compatibility fixes) |
| `usbCameraCommon` | Handler layer between app and driver (`UVCCameraHandler`, UVC control APIs) |
| `usbCameraTest0`–`usbCameraTest8` | Original sample projects from the upstream repo (reference only, not part of the actual app) |

## Core features (bookscanner-app)

- Dual-camera connection (L/R) via a USB hub, dynamic resolution selection from the camera's actually supported sizes (no hardcoded camera model)
- Preview (low resolution) / capture (full sensor resolution) with mandatory mode switch — UVC webcams don't support parallel streams, only one switchable stream
- Camera calibration: auto mode (let auto-focus/auto-white-balance settle, then lock) and manual mode (sliders for all UVC parameters, live preview)
- Focus sweep calibration: systematic scan across the focus range, sharpness lookup curve (Laplacian variance) instead of relying purely on the camera's built-in auto logic
- Per-capture sharpness verification (instead of a fixed recalibration interval): focus is re-asserted before every shot + sharpness compared against the reference value
- File naming: `{ProjectName}_S{Page:04d}_{L|R}.jpg`, timestamp in EXIF

Full concept/development history (internal only, not part of this repo): `04-projects/vif-bookscanner/android/plan-2026-07-21-android-portierung.md` in the private KI-OS vault.

## Build

Standard Android Gradle build:

```
./gradlew.bat :bookscanner-app:assembleDebug
```

Requires Android SDK + NDK (see `local.properties`).

---

## Origin: UVCCamera (original upstream)

The original `library and sample to access to UVC web camera on non-rooted Android device` code by saki4510t forms the technical basis of the `libuvccamera`/`usbCameraCommon` modules as well as the `usbCameraTestN` sample projects. Original changelog: [github.com/saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera).

Key modernizations compared to the original (details in [NOTICE](NOTICE) and this repo's git history):
- JCenter (shut down 2021/22) → mavenCentral/google
- Android Gradle Plugin 3.1.4 → 8.5.2, Gradle Wrapper 4.4 → 8.7
- `ndk-build`/`Android.mk` → CMake
- AndroidX migration
- Several Android 12/13/14 runtime compatibility fixes (PendingIntent flags, `RECEIVER_EXPORTED`, USB permission timing)
- Real implementation of `resize()` (was a permanent `UnsupportedOperationException` stub in the original)
