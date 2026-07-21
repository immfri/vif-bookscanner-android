package de.vif.bookscanner.state

/**
 * Die sechs Zustaende der Buchscanner-State-Machine.
 *
 * Quelle: vif-bookscanner-camera-system.md / vif-bookscanner-gui-layer.md (RPi-Vorlaeufer,
 * Vault-Pfad 04-projects/vif-bookscanner/) sowie plan-2026-07-21-android-portierung.md.
 */
enum class ScannerState {
    /** Kalibrierung der beiden Kameras (Ausrichtung/Belichtung) vor dem eigentlichen Scan. */
    CALIBRATION,

    /** Kamera-Parameter sind fixiert (gesperrt), Buch kann eingelegt werden. */
    LOCK,

    /** Live-Vorschau in 320x240 MJPEG, wartet auf Capture-Trigger. */
    PREVIEW,

    /** Kurzer Mode-Switch: volle Sensor-Aufloesung, genau ein Frame wird aufgenommen. */
    CAPTURE,

    /** Nutzer prueft die zuletzt aufgenommene Seite, bevor es zur naechsten Seite geht. */
    RECHECK,

    /** Einstellungs-UI (Kamera-Auswahl, Split-Layout mit Live-Feed). */
    SETUP
}
