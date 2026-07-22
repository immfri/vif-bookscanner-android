# VIF BookScanner (Android)

Android app for a physical dual-camera book scanner (2x USB UVC webcam, e.g. ArduCAM IMX298 AF) — goal: best possible, OCR-ready captures of book pages.

## Why this exists

Large-scale book digitization today is largely dominated by a handful of big players (Google Books and similar), often at inconsistent quality. This project's goal is to give antiquarian bookshops, libraries, archives, and any institution without a big budget a way to build a **high-quality, low-cost book scanner** themselves — the software here is one half of that (the hardware rig is a separate, planned companion project). It is meant to stay free for everyone, forever — see the licensing section below.

Copyright (c) 2026 Immanuel Friedrichsen — see [NOTICE](NOTICE) for the full copyright/license attribution.

This project is a **fork of [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera)** (Copyright (c) 2014-2017 saki t_saki@serenegiant.com). The original UVC driver served as a dated but solid base (last upstream activity around 2020) and was modernized and extended with a full app layer for this project.

## License (mixed — two licenses, deliberately)

- **`bookscanner-app`** (the actual app — everything built from scratch for this project): **[PolyForm Noncommercial License 1.0.0](bookscanner-app/LICENSE)**. Free and open source for anyone, but **no commercial use** — nobody gets to build a paid product on top of this. This is the deliberate choice reflecting the project's mission (see above).
- **`libuvccamera`, `usbCameraCommon`, `usbCameraTest0`–`usbCameraTest8`** (inherited from upstream): **[Apache License, Version 2.0](LICENSE)** — unchanged from the original, and legally cannot be restricted further (the original author already granted everyone, including commercial users, an irrevocable Apache 2.0 license to this code).

See [NOTICE](NOTICE) for the full explanation of why this split exists and is legally necessary.

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
- Optional separate capture-only "look" profile (brightness/contrast/sharpness/gain/gamma/saturation/hue/white balance/zoom) distinct from the live-feed profile — off by default (capture uses the live profile unchanged); focus itself is never split, since it's resolution-independent
- File naming: `{ProjectName}_S{Page:04d}_{L|R}.jpg`, timestamp in EXIF

Full concept/development history (internal only, not part of this repo): `04-projects/vif-bookscanner/android/plan-2026-07-21-android-portierung.md` in the private KI-OS vault.

## How the app works

**Hardware/driver layer.** `USBMonitor` (from `libuvccamera`) detects the two attached UVC webcams and requests USB permission for each. `UvcCameraBridge` (`bookscanner-app/hardware/`) owns one `UVCCameraHandler` per physical slot (LEFT/RIGHT), assigned as devices connect. Each handler wraps one `UVCCamera` instance bound to a Compose `AndroidView` (`UvcPreview`) that supplies the rendering `Surface`.

**Preview vs. capture.** UVC webcams on USB2 can't stream two resolutions at once, so the app always runs at low-res preview (dynamically chosen close to 320x240 from the camera's real supported-size list) and switches to the camera's maximum reported resolution only for the instant of capture, then switches back. The switch is a full `close()`+`open()` cycle on the same retained `UsbControlBlock` (`reopenAtSize()`), not the driver's in-place `resize()` (see NOTICE for why). Both cameras can run this at full resolution *simultaneously* — verified live, parallel dual capture takes ~3.3–4.0s total for both pages.

**Calibration.** Auto mode lets the camera's own auto-focus/auto-white-balance settle, reads the resulting values, then locks them (no drift between shots) — followed by a focus sweep (systematic scan across the whole focus range) that measures sharpness (Laplacian variance) at each step and keeps the true measured peak as the final focus value, more precise than trusting the auto-focus end value alone. Two reference sharpness values are stored (one at full capture resolution, one at preview resolution) since preview resolution alone doesn't resolve fine text detail reliably.

**Per-capture verification.** Instead of a fixed "recalibrate every N shots" interval (no reliable number exists in the literature for this), every single capture: (1) re-sends the locked focus value to the driver (UVC webcams can silently reset focus on USB reconnect/standby — a plain re-apply, not a new auto-focus run), (2) measures the resulting image's sharpness and compares it against the calibration reference within a configurable tolerance band, optionally auto-correcting the focus value (via the stored sweep curve, not blind guessing) for the *next* shot if it drifted outside tolerance.

**State flow (Compose UI):** `MainActivity` → `ScannerViewModel` (state machine: CALIBRATION → LOCK → PREVIEW → CAPTURE → RECHECK → SETUP, see `state/ScannerState.kt`) → `CalibrationScreen` (per-camera calibration + manual controls) → `SettingsScreen` (project/page naming, rotation, L/R swap, sharpness tolerance) → main capture screen (`BookscannerApp.kt`, split live preview + Capture button). A start-gate (`DeviceGateScreen`) blocks the whole app below 2 attached USB cameras.

**Storage.** Captured JPEGs are written directly (no re-encode) to app-private storage as `{ProjectName}_S{Page:04d}_{L|R}.jpg` via `ScanStorageRepository`, with the capture timestamp written into EXIF (not the filename) so filenames stay simply sortable.

**Known limitation — no true per-camera-identity settings.** Settings are persisted per physical *slot* (LEFT/RIGHT), not per camera device identity (vendor/product ID + serial), even though the UVC layer could in principle support that. Verified live: both ArduCAM IMX298 units report the **identical** USB serial number (`UC724`) — there is no reliable way to tell the two physical cameras apart by device identity on this hardware, so slot-based persistence is the deliberate, correct choice here. A future webcam brand with distinct serials per unit would allow real per-device persistence, but that is not implemented.

## Build

Standard Android Gradle build:

```
./gradlew.bat :bookscanner-app:assembleDebug
```

Requires Android SDK + NDK (see `local.properties`).

---

## Origin: UVCCamera (original upstream)

The original `library and sample to access to UVC web camera on non-rooted Android device` code by saki4510t forms the technical basis of the `libuvccamera`/`usbCameraCommon` modules as well as the `usbCameraTestN` sample projects. Original changelog: [github.com/saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera).

Key modernizations compared to the original — **full list with exact rationale in [NOTICE](NOTICE)** (legally required changelog under Apache 2.0 §4(d)), short version here:
- JCenter (shut down 2021/22) → mavenCentral/google
- Android Gradle Plugin 3.1.4 → 8.5.2, Gradle Wrapper 4.4 → 8.7
- `ndk-build`/`Android.mk` → CMake
- AndroidX migration
- Real implementation of `resize()` (was a permanent `UnsupportedOperationException` stub in the original) — later superseded at the app layer by full close+reopen cycles, see NOTICE
- A native data race (`CallbackPipeline::do_capture()`) and an unconditional JNI `DetachCurrentThread()` bug, both causing native crashes under repeated reopen cycles
- Several Android 12/13/14 runtime compatibility fixes (`PendingIntent` flags, `RECEIVER_EXPORTED`, USB permission timing)
- Two rounds of USB permission request-queue fixes for simultaneous dual-camera cold start (sequential in-flight queue, then a dedup fix against the vendored library re-firing `onAttach()` for already-handled devices)
- An orphaned-preview-stream fix (`onSurfaceDestroy()` now actually stops the deselected camera's preview instead of leaving it running against a destroyed `Surface` indefinitely)
