package de.vif.bookscanner.state

/** Welche der beiden UVC-Kameras aktuell im Settings-Screen ausgewaehlt/angezeigt wird. */
enum class CameraSelection {
    LEFT,
    RIGHT;

    fun other(): CameraSelection = if (this == LEFT) RIGHT else LEFT
}

/** Bild-Rotation der Buchvorlage — Vorgabe: Rotate-180-Grad-Toggle im Overlay. */
enum class PageOrientation {
    NORMAL,
    ROTATED_180;

    fun toggled(): PageOrientation = if (this == NORMAL) ROTATED_180 else NORMAL
}
