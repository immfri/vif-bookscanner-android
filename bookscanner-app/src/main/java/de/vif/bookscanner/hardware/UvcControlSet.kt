package de.vif.bookscanner.hardware

/**
 * Kompletter UVC-Parametersatz einer Kamera. Alle numerischen Werte sind auf den von
 * [com.serenegiant.usb.UVCCamera] selbst normalisierten Bereich 0..100 bezogen
 * (setBrightness()/setFocus()/... rechnen intern bereits auf den echten Min..Max-
 * Hardwarebereich um, siehe UVCCamera.java Zeilen ~472-900) — die App muss also keine
 * eigene Min/Max/Def-Ermittlung pro Kamera vornehmen.
 *
 * [referenceSharpness] ist die bei der Kalibrierung gemessene Laplace-Varianz
 * ([SharpnessAnalyzer.laplacianVariance]) des fixierten Fokus — Referenzwert fuer den
 * Pro-Aufnahme-Schaerfe-Check ([UvcCameraBridge.verifyFocusBeforeCapture]). 0.0 = noch nie
 * kalibriert, dann wird der Check uebersprungen.
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
    val referenceSharpness: Double = 0.0
) {
    /** Einfache, robuste Key=Value-Serialisierung fuer SharedPreferences (kein JSON-Overhead noetig). */
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
        "referenceSharpness=$referenceSharpness"
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
                    referenceSharpness = map["referenceSharpness"]?.toDouble() ?: defaults.referenceSharpness
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
