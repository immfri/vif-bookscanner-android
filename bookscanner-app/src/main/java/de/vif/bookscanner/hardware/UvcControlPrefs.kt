package de.vif.bookscanner.hardware

import android.content.Context
import de.vif.bookscanner.state.CameraSelection

/**
 * Persistiert [UvcControlSet] je Kamera-Slot (L/R) sowie die Einstellungen fuer den
 * Pro-Aufnahme-Schaerfe-Check. Einfaches SharedPreferences-Muster (kein JSON, kein
 * DI-Framework) — robust genug fuer diesen Umfang (Vorgabe: kein Overengineering).
 *
 * Schluessel bewusst am Slot (CameraSelection) statt an der physischen USB-Geraete-Identitaet
 * festgemacht — deckungsgleich mit [UvcCameraBridge], die Kameras ebenfalls slot-basiert (L/R)
 * verwaltet, nicht geraete-individuell.
 *
 * KORREKTUR 2026-07-21 (User-Praezisierung): kein festes "alle N Aufnahmen neu kalibrieren"
 * mehr (urspruengliche Anforderung), stattdessen Pro-Capture-Schaerfe-Verifikation gegen einen
 * bei der Kalibrierung gespeicherten Referenzwert ([UvcControlSet.referenceSharpness]) mit
 * Toleranzband + optionalem Auto-Retry.
 */
class UvcControlPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(camera: CameraSelection): UvcControlSet =
        UvcControlSet.fromPrefsString(prefs.getString(key(camera), null)) ?: UvcControlSet()

    fun save(camera: CameraSelection, controls: UvcControlSet) {
        prefs.edit().putString(key(camera), controls.toPrefsString()).apply()
    }

    private fun key(camera: CameraSelection) = "controls_$camera"

    /** Toleranzband in Prozent fuer den Pro-Aufnahme-Schaerfe-Vergleich gegen den
     * Referenzwert aus der Kalibrierung (z.B. 30 = Schaerfe darf bis zu 30% unter die
     * Referenz fallen, bevor gewarnt/retried wird). */
    fun getSharpnessToleranceBandPercent(): Int = prefs.getInt(KEY_TOLERANCE_BAND, DEFAULT_TOLERANCE_BAND)

    fun setSharpnessToleranceBandPercent(value: Int) {
        prefs.edit().putInt(KEY_TOLERANCE_BAND, value).apply()
    }

    /** Ob bei zu geringer Schaerfe automatisch ein Retry (erneuter Fokus-Write + erneute
     * Messung) versucht wird, bevor tatsaechlich gewarnt/blockiert wird. */
    fun getAutoRetryEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_RETRY, true)

    fun setAutoRetryEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RETRY, value).apply()
    }

    /** 180-Grad-Rotation je Kamera (User-Vorgabe 2026-07-21): die physischen Kameras sind
     * nicht per ID unterscheidbar und koennen kopfueber montiert sein — Rotation wird pro
     * Slot persistiert, auf die Live-Vorschau (View.rotation) UND als verlustfreies
     * EXIF-Orientation-Flag auf die gespeicherten JPEGs angewandt. */
    fun getRotation180(camera: CameraSelection): Boolean =
        prefs.getBoolean("rotation180_$camera", false)

    fun setRotation180(camera: CameraSelection, value: Boolean) {
        prefs.edit().putBoolean("rotation180_$camera", value).apply()
    }

    companion object {
        private const val PREFS_NAME = "uvc_control_prefs"
        private const val KEY_TOLERANCE_BAND = "sharpness_tolerance_band_percent"
        private const val KEY_AUTO_RETRY = "sharpness_auto_retry_enabled"
        private const val DEFAULT_TOLERANCE_BAND = 30
    }
}
