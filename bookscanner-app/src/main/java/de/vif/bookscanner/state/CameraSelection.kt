package de.vif.bookscanner.state

/** Welche der beiden UVC-Kameras aktuell im Settings-Screen ausgewaehlt/angezeigt wird. */
enum class CameraSelection {
    LEFT,
    RIGHT;

    fun other(): CameraSelection = if (this == LEFT) RIGHT else LEFT
}

// REFACTORING 2026-07-21: PageOrientation (globaler Rotate-180-Toggle) entfernt — ersetzt
// durch die per-Kamera-Rotation (UvcControlPrefs.getRotation180/setRotation180, Live-View
// via View.rotation + Datei via verlustfreiem EXIF-Orientation-Flag).
