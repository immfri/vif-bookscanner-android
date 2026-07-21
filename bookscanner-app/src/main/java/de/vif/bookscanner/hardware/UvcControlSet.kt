package de.vif.bookscanner.hardware

/**
 * Kompletter UVC-Parametersatz einer Kamera. Alle numerischen Werte sind auf den von
 * [com.serenegiant.usb.UVCCamera] selbst normalisierten Bereich 0..100 bezogen
 * (setBrightness()/setFocus()/... rechnen intern bereits auf den echten Min..Max-
 * Hardwarebereich um, siehe UVCCamera.java Zeilen ~472-900) — die App muss also keine
 * eigene Min/Max/Def-Ermittlung pro Kamera vornehmen.
 *
 * [referenceSharpness] ist die bei der Kalibrierung in VOLLER Capture-Aufloesung gemessene
 * Laplace-Varianz ([SharpnessAnalyzer.laplacianVariance]) am Peak des Fokus-Sweeps
 * ([UvcCameraBridge.calibrateAuto]) — Referenzwert fuer die Post-Capture-Sicherheitspruefung
 * ([UvcCameraBridge] verifyFocusAfterCapture). 0.0 = noch nie kalibriert, dann wird der Check
 * uebersprungen.
 *
 * [referenceSharpnessPreview] ist der EIGENE, zweite Referenzwert aus der Preview-Aufloesung
 * (kein Modus-Wechsel), genutzt fuer die schnelle Vor-Aufnahme-Pruefung. Preview- und
 * Capture-Aufloesung liefern unterschiedliche absolute Schaerfewerte (andere Pixelzahl) —
 * deshalb zwei getrennte Referenzen statt einer gemeinsamen (Regel 24: eindeutige Variablen).
 *
 * [focusSweepCurve] ist die bei der Kalibrierung aufgenommene Fokus->Schaerfe-Lookup-Kurve
 * (Fokuswert 0..100 -> gemessene Laplace-Varianz in voller Aufloesung), Peak = optimaler
 * Fokuswert. Wird fuer die lokale Korrektursuche bei Toleranzband-Unterschreitung genutzt
 * (siehe [UvcCameraBridge] correctFocusUsingCurve) statt vollem Re-Sweep oder blindem Retry.
 */
data class UvcControlSet(
    val focus: Int = 50,
    val focusAuto: Boolean = true,
    val brightness: Int = 50,
    val contrast: Int = 50,
    val sharpness: Int = 50,
    val gain: Int = 50,
    val gamma: Int = 50,
    val saturation: Int = 50,
    val hue: Int = 50,
    val whiteBalance: Int = 50,
    val whiteBalanceAuto: Boolean = true,
    val zoom: Int = 0,
    val referenceSharpness: Double = 0.0,
    val referenceSharpnessPreview: Double = 0.0,
    val focusSweepCurve: List<Pair<Int, Double>> = emptyList()
) {
    /** Einfache, robuste Key=Value-Serialisierung fuer SharedPreferences (kein JSON-Overhead noetig).
     * [focusSweepCurve] wird als eigenes Feld "focusN:schaerfeN" komma-getrennt anghaengt. */
    fun toPrefsString(): String = listOf(
        "focus=$focus",
        "focusAuto=$focusAuto",
        "brightness=$brightness",
        "contrast=$contrast",
        "sharpness=$sharpness",
        "gain=$gain",
        "gamma=$gamma",
        "saturation=$saturation",
        "hue=$hue",
        "whiteBalance=$whiteBalance",
        "whiteBalanceAuto=$whiteBalanceAuto",
        "zoom=$zoom",
        "referenceSharpness=$referenceSharpness",
        "referenceSharpnessPreview=$referenceSharpnessPreview",
        "focusSweepCurve=${focusSweepCurve.joinToString(",") { "${it.first}:${it.second}" }}"
    ).joinToString(";")

    companion object {
        fun fromPrefsString(raw: String?): UvcControlSet? {
            if (raw.isNullOrBlank()) return null
            val map = raw.split(";").mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val defaults = UvcControlSet()
            return try {
                UvcControlSet(
                    focus = map["focus"]?.toInt() ?: defaults.focus,
                    focusAuto = map["focusAuto"]?.toBoolean() ?: defaults.focusAuto,
                    brightness = map["brightness"]?.toInt() ?: defaults.brightness,
                    contrast = map["contrast"]?.toInt() ?: defaults.contrast,
                    sharpness = map["sharpness"]?.toInt() ?: defaults.sharpness,
                    gain = map["gain"]?.toInt() ?: defaults.gain,
                    gamma = map["gamma"]?.toInt() ?: defaults.gamma,
                    saturation = map["saturation"]?.toInt() ?: defaults.saturation,
                    hue = map["hue"]?.toInt() ?: defaults.hue,
                    whiteBalance = map["whiteBalance"]?.toInt() ?: defaults.whiteBalance,
                    whiteBalanceAuto = map["whiteBalanceAuto"]?.toBoolean() ?: defaults.whiteBalanceAuto,
                    zoom = map["zoom"]?.toInt() ?: defaults.zoom,
                    referenceSharpness = map["referenceSharpness"]?.toDouble() ?: defaults.referenceSharpness,
                    referenceSharpnessPreview = map["referenceSharpnessPreview"]?.toDouble() ?: defaults.referenceSharpnessPreview,
                    focusSweepCurve = map["focusSweepCurve"]?.let { raw2 ->
                        if (raw2.isBlank()) emptyList()
                        else raw2.split(",").mapNotNull { pair ->
                            val kv = pair.split(":", limit = 2)
                            if (kv.size == 2) kv[0].toIntOrNull()?.let { f -> kv[1].toDoubleOrNull()?.let { s -> f to s } } else null
                        }
                    } ?: defaults.focusSweepCurve
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
