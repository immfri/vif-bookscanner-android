package de.vif.bookscanner.hardware

import android.app.Activity
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandler
import com.serenegiant.widget.CameraViewInterface
import de.vif.bookscanner.state.CameraSelection
import java.io.File

/**
 * Kapselt die komplette libuvccamera-Anbindung fuer die zwei physischen UVC-Kameras
 * (links/rechts). Angelehnt an das Code-Muster von usbCameraTest7/MainActivity.java
 * (USBMonitor + UVCCameraHandler pro Kamera-Slot), auf den Zwei-Zustaende-Bedarf
 * (Preview 320x240 MJPEG / Capture volle Aufloesung mit Pflicht-Mode-Switch) reduziert.
 *
 * ERGAENZUNG (Kalibrier-Architektur, 2026-07-21): Automodus (Auto-Fokus/Auto-WB fahren lassen,
 * dann auslesen+fixieren via [calibrateAuto]) + manueller Modus ([applyManualControls]) fuer den
 * kompletten UVC-Parametersatz ([UvcControlSet]), persistiert pro Kamera-Slot ([UvcControlPrefs]).
 * Rekalibrierung erfolgt NICHT nach fester Aufnahmezahl (Recherche fand dafuer keinen belastbaren
 * Wert), sondern ereignisgesteuert: nach jeder Aufnahme wird die tatsaechliche Schaerfe
 * gemessen ([SharpnessAnalyzer]) und gegen den bei der Kalibrierung gespeicherten Referenzwert
 * verglichen ([verifyFocusAfterCapture]).
 *
 * UNGETESTET: Diese Klasse wurde ohne angeschlossene Kamera geschrieben. Sie kompiliert
 * gegen die echte libuvccamera-API, aber das tatsaechliche Geraete-Verhalten (Attach-
 * Reihenfolge, Mode-Switch-Timing, Auto-Kalibrier-Einschwingzeit) ist mit echter Hardware
 * zu verifizieren.
 */
class UvcCameraBridge(
    private val activity: Activity,
    // DEBUG-TEST 2026-07-21: 320x240 stand in der Spec (aus der RPi/V4L2-Doku uebernommen),
    // aber die echte ArduCAM meldet laut UVC-Descriptor NUR 640x480 als kleinste unterstuetzte
    // Groesse (MJPEG: 4656x3496...640x480, Uncompressed: 800x600/640x480) — 320x240 existiert
    // in keiner der beiden Formatlisten. Testweise auf 640x480 gesetzt, um zu pruefen ob das
    // der Grund fuer das leere Vorschaubild ist. TODO: sobald bestaetigt, gehoert das in die
    // generische UVC-Settings-Architektur (dynamische Groessenwahl aus getSupportedSize()).
    private val previewWidth: Int = 640,
    private val previewHeight: Int = 480,
    private val listener: Listener
) {

    interface Listener {
        /** Wird aufgerufen sobald eine Kamera einem Slot (L/R) zugeordnet und geoeffnet wurde. */
        fun onCameraOpened(camera: CameraSelection)

        /** Wird aufgerufen wenn eine Kamera getrennt/geschlossen wurde. */
        fun onCameraClosed(camera: CameraSelection)

        fun onError(camera: CameraSelection, error: Exception)

        /** Optionaler Hook: Schaerfe nach einer Aufnahme lag ausserhalb des Toleranzbands
         * gegen den Kalibrier-Referenzwert (siehe [verifyFocusAfterCapture]). Default no-op,
         * damit bestehende Listener-Implementierungen nicht brechen. */
        fun onSharpnessOutOfTolerance(camera: CameraSelection, measured: Double, reference: Double) {}
    }

    companion object {
        private const val TAG = "UvcCameraBridge"

        /** Volle Sensor-Aufloesung fuer den Capture-Modus (USB2-Bandbreite erzwingt Mode-Switch). */
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720

        private const val CAPTURE_SETTLE_DELAY_MS = 400L

        /** Wartezeit bis der Auto-Fokus/Auto-WB-Regelkreis eingeschwungen ist, bevor der
         * erreichte Wert ausgelesen und Auto abgeschaltet (fixiert) wird. Ungetestet mit
         * echter Kamera, konservativ gewaehlt. */
        private const val AUTO_CALIBRATION_SETTLE_DELAY_MS = 1200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val controlPrefs = UvcControlPrefs(activity.applicationContext)

    private var usbMonitor: USBMonitor? = null

    private var handlerL: UVCCameraHandler? = null
    private var handlerR: UVCCameraHandler? = null

    private var viewL: CameraViewInterface? = null
    private var viewR: CameraViewInterface? = null

    private var pendingSurfaceL: Surface? = null
    private var pendingSurfaceR: Surface? = null

    // Falls onConnect() feuert BEVOR die Compose-UvcPreview (und damit bindCameraView) fuer
    // diesen Slot existiert (z.B. waehrend die App noch auf CALIBRATION/LOCK steht): das
    // UsbControlBlock zwischenspeichern und beim spaeteren bindCameraView() nachholen, statt
    // die Verbindung stillschweigend zu verlieren.
    private var pendingCtrlBlockL: UsbControlBlock? = null
    private var pendingCtrlBlockR: UsbControlBlock? = null

    /** Muss vor [register] aufgerufen werden, sobald die Compose-AndroidView bereit ist. */
    fun bindCameraView(camera: CameraSelection, view: CameraViewInterface) {
        when (camera) {
            CameraSelection.LEFT -> viewL = view
            CameraSelection.RIGHT -> viewR = view
        }
        ensureHandler(camera)
        openPendingConnectionIfAny(camera)
    }

    private fun ensureHandler(camera: CameraSelection) {
        val view = when (camera) {
            CameraSelection.LEFT -> viewL
            CameraSelection.RIGHT -> viewR
        } ?: return

        val existing = handlerFor(camera)
        if (existing != null) return

        val bandwidthFactor = 0.5f // zwei Kameras teilen sich die USB2-Bandbreite (wie usbCameraTest7).
        val newHandler = UVCCameraHandler.createHandler(
            activity, view, previewWidth, previewHeight, bandwidthFactor
        )
        when (camera) {
            CameraSelection.LEFT -> handlerL = newHandler
            CameraSelection.RIGHT -> handlerR = newHandler
        }

        // WICHTIG: view.surfaceTexture ist direkt nach View-Erzeugung meist noch null (die
        // TextureView-Surface wird erst asynchron nach dem Attach/Layout verfuegbar). Ohne
        // diesen Callback bricht startPreview() beim ersten Versuch (Kamera-open-Zeitpunkt)
        // still ab und wird nie erneut aufgerufen -> Preview bleibt dauerhaft ein grauer Block.
        view.setCallback(object : CameraViewInterface.Callback {
            override fun onSurfaceCreated(v: CameraViewInterface, surface: Surface) {
                Log.d(TAG, "onSurfaceCreated fuer $camera — starte Preview falls Kamera offen")
                when (camera) {
                    CameraSelection.LEFT -> pendingSurfaceL = surface
                    CameraSelection.RIGHT -> pendingSurfaceR = surface
                }
                if (handlerFor(camera)?.isOpened == true) {
                    startPreview(camera)
                }
            }

            override fun onSurfaceChanged(v: CameraViewInterface, surface: Surface, width: Int, height: Int) {}

            override fun onSurfaceDestroy(v: CameraViewInterface, surface: Surface) {
                Log.d(TAG, "onSurfaceDestroy fuer $camera")
            }
        })
    }

    private fun openPendingConnectionIfAny(camera: CameraSelection) {
        val ctrlBlock = when (camera) {
            CameraSelection.LEFT -> pendingCtrlBlockL
            CameraSelection.RIGHT -> pendingCtrlBlockR
        } ?: return
        val handler = handlerFor(camera) ?: return
        if (handler.isOpened) return

        Log.d(TAG, "openPendingConnectionIfAny: hole nachgeholte Verbindung fuer $camera nach")
        when (camera) {
            CameraSelection.LEFT -> pendingCtrlBlockL = null
            CameraSelection.RIGHT -> pendingCtrlBlockR = null
        }
        try {
            handler.open(ctrlBlock)
            mainHandler.post {
                startPreview(camera)
                applyManualControls(camera, controlPrefs.load(camera))
                listener.onCameraOpened(camera)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openPendingConnectionIfAny: Handler.open() fuer $camera fehlgeschlagen", e)
            listener.onError(camera, e)
        }
    }

    private fun handlerFor(camera: CameraSelection): UVCCameraHandler? = when (camera) {
        CameraSelection.LEFT -> handlerL
        CameraSelection.RIGHT -> handlerR
    }

    /** Registriert den USBMonitor. In Activity.onStart aufrufen. */
    fun register() {
        if (usbMonitor == null) {
            usbMonitor = USBMonitor(activity, deviceConnectListener)
        }
        usbMonitor?.register()
    }

    /** Deregistriert den USBMonitor + schliesst beide Handler. In Activity.onStop aufrufen. */
    fun unregister() {
        handlerL?.close()
        handlerR?.close()
        usbMonitor?.unregister()
    }

    /** Gibt alle nativen Ressourcen frei. In Activity.onDestroy aufrufen. */
    fun release() {
        handlerL = null
        handlerR = null
        usbMonitor?.destroy()
        usbMonitor = null
    }

    fun isOpened(camera: CameraSelection): Boolean = handlerFor(camera)?.isOpened == true

    /** Startet die Live-Vorschau fuer den angegebenen Kamera-Slot. */
    fun startPreview(camera: CameraSelection) {
        val handler = handlerFor(camera) ?: return
        val view = when (camera) {
            CameraSelection.LEFT -> viewL
            CameraSelection.RIGHT -> viewR
        } ?: return
        // view.getSurface() ist die vom CameraViewInterface.Callback (onSurfaceCreated)
        // bereitgestellte Surface — robuster als getSurfaceTexture(), das direkt nach
        // View-Erzeugung meist noch null ist (siehe ensureHandler-Kommentar).
        val surface = if (view.hasSurface()) view.surface else null
        if (surface == null) {
            Log.d(TAG, "startPreview($camera): Surface noch nicht bereit, warte auf onSurfaceCreated")
            return
        }
        when (camera) {
            CameraSelection.LEFT -> pendingSurfaceL = surface
            CameraSelection.RIGHT -> pendingSurfaceR = surface
        }
        handler.startPreview(surface)
    }

    fun stopPreview(camera: CameraSelection) {
        handlerFor(camera)?.stopPreview()
    }

    /**
     * Pflicht-Mode-Switch + Capture: schaltet kurz auf volle Sensor-Aufloesung, nimmt genau
     * einen Frame binaer auf (via UVCCameraHandler#captureStill, kein manuelles Decode/Encode
     * im App-Code) und schaltet danach zurueck auf die Vorschau-Aufloesung.
     *
     * USB2-Bandbreite erlaubt keinen simultanen Preview+Capture in voller Aufloesung, deshalb
     * der explizite resize()-Umweg (siehe UVCCameraHandler#resize -> AbstractUVCCameraHandler).
     *
     * Nach dem Capture wird die Schaerfe der geschriebenen Datei gegen den Kalibrier-
     * Referenzwert geprueft (siehe [verifyFocusAfterCapture]) — kein festes N-Aufnahmen-
     * Intervall (siehe Klassenkommentar), sondern echte Pro-Aufnahme-Verifikation.
     *
     * @param targetFile Zieldatei (Dateiname bereits via BookscanFileNamer gebaut).
     * @param onDone Callback (Main-Thread) nach Abschluss des Mode-Switch-Zyklus.
     */
    fun captureFullResolutionThenReturnToPreview(
        camera: CameraSelection,
        targetFile: File,
        onDone: () -> Unit
    ) {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) {
            listener.onError(camera, IllegalStateException("Kamera $camera nicht geoeffnet"))
            onDone()
            return
        }

        try {
            handler.resize(CAPTURE_WIDTH, CAPTURE_HEIGHT)
        } catch (e: Exception) {
            Log.w(TAG, "resize auf Capture-Aufloesung fehlgeschlagen, capture mit aktueller Aufloesung", e)
        }

        // Kurze Verzoegerung damit der native Mode-Switch (neues UVC-Streaming-Setup) greift,
        // bevor der Still-Capture ausgeloest wird. Zeitwert ungetestet, mit echter Kamera pruefen.
        mainHandler.postDelayed({
            try {
                handler.captureStill(targetFile.absolutePath)
            } catch (e: Exception) {
                listener.onError(camera, e)
            }

            mainHandler.postDelayed({
                try {
                    handler.resize(previewWidth, previewHeight)
                } catch (e: Exception) {
                    Log.w(TAG, "resize zurueck auf Preview-Aufloesung fehlgeschlagen", e)
                }
                applyManualControls(camera, controlPrefs.load(camera))
                verifyFocusAfterCapture(camera, targetFile)
                onDone()
            }, CAPTURE_SETTLE_DELAY_MS)
        }, CAPTURE_SETTLE_DELAY_MS)
    }

    /**
     * Automodus-Kalibrierung: schaltet Auto-Fokus + Auto-Weissabgleich EIN, wartet auf
     * Einschwingen, liest dann die tatsaechlich erreichten Werte aus und schaltet Auto
     * wieder AB — der ausgelesene Wert wird explizit erneut gesetzt (fixiert/eingefroren).
     * Kein Nachlaufen zwischen einzelnen Aufnahmen (Vorgabe: maximale OCR-Qualitaet, ein
     * "wandernder" Fokus wuerde das sabotieren).
     *
     * Nimmt danach zusaetzlich einen Referenz-Still auf (in den App-Cache, wird wieder
     * geloescht) und misst dessen Schaerfe ([SharpnessAnalyzer]) als Referenzwert fuer die
     * Pro-Aufnahme-Verifikation ([verifyFocusAfterCapture]). Ergebnis wird persistiert.
     */
    fun calibrateAuto(camera: CameraSelection, onResult: (UvcControlSet) -> Unit) {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) {
            listener.onError(camera, IllegalStateException("Kamera $camera nicht geoeffnet, keine Auto-Kalibrierung moeglich"))
            return
        }
        try {
            handler.setAutoFocus(true)
            handler.setAutoWhiteBalance(true)
        } catch (e: Exception) {
            Log.w(TAG, "calibrateAuto($camera): Auto-Modi einschalten fehlgeschlagen", e)
        }

        mainHandler.postDelayed({
            val fixed = try {
                val fixedFocus = handler.getValue(UVCCamera.CTRL_FOCUS_ABS)
                val fixedWb = handler.getValue(UVCCamera.PU_WB_TEMP)
                handler.setAutoFocus(false)
                handler.setAutoWhiteBalance(false)
                // Ausgelesenen Wert explizit erneut setzen (fixieren) — nicht nur Auto
                // abschalten, da der Regelkreis sonst beim letzten internen Sollwert bleiben
                // koennte statt beim zuletzt ausgelesenen (Sicherheitsnetz, siehe Arducam-Fund
                // zu Fokus-Reset bei Reconnect: expliziter Re-Apply ist der robuste Weg).
                handler.setValue(UVCCamera.CTRL_FOCUS_ABS, fixedFocus)
                handler.setValue(UVCCamera.PU_WB_TEMP, fixedWb)
                getCurrentControls(camera).copy(
                    focus = fixedFocus,
                    focusAuto = false,
                    whiteBalance = fixedWb,
                    whiteBalanceAuto = false
                )
            } catch (e: Exception) {
                listener.onError(camera, e)
                null
            } ?: return@postDelayed

            // Referenz-Still fuer die Schaerfe-Baseline aufnehmen (App-Cache, temporaer).
            val refFile = File(activity.cacheDir, "calibration_ref_${camera}_${System.currentTimeMillis()}.jpg")
            try {
                handler.captureStill(refFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "calibrateAuto($camera): Referenz-Still fehlgeschlagen, referenceSharpness bleibt 0.0", e)
            }
            mainHandler.postDelayed({
                val referenceSharpness = measureSharpness(refFile) ?: 0.0
                refFile.delete()
                val result = fixed.copy(referenceSharpness = referenceSharpness)
                controlPrefs.save(camera, result)
                onResult(result)
            }, CAPTURE_SETTLE_DELAY_MS)
        }, AUTO_CALIBRATION_SETTLE_DELAY_MS)
    }

    /** Schreibt einen kompletten manuellen Parametersatz auf den Treiber (kein Persist hier —
     * Aufrufer entscheidet, ob/wann gespeichert wird, siehe [saveControlSet]). */
    fun applyManualControls(camera: CameraSelection, controls: UvcControlSet) {
        val handler = handlerFor(camera) ?: return
        if (!handler.isOpened) return
        try {
            handler.setAutoFocus(controls.focusAuto)
            if (!controls.focusAuto) handler.setValue(UVCCamera.CTRL_FOCUS_ABS, controls.focus)
            handler.setAutoWhiteBalance(controls.whiteBalanceAuto)
            if (!controls.whiteBalanceAuto) handler.setValue(UVCCamera.PU_WB_TEMP, controls.whiteBalance)
            handler.setValue(UVCCamera.PU_BRIGHTNESS, controls.brightness)
            handler.setValue(UVCCamera.PU_CONTRAST, controls.contrast)
            handler.setValue(UVCCamera.PU_SHARPNESS, controls.sharpness)
            handler.setValue(UVCCamera.PU_GAIN, controls.gain)
            handler.setValue(UVCCamera.PU_GAMMA, controls.gamma)
            handler.setValue(UVCCamera.PU_SATURATION, controls.saturation)
            handler.setValue(UVCCamera.PU_HUE, controls.hue)
            handler.setValue(UVCCamera.CTRL_ZOOM_ABS, controls.zoom)
        } catch (e: Exception) {
            Log.w(TAG, "applyManualControls($camera) teilweise fehlgeschlagen", e)
        }
    }

    /** Liest den aktuell am Treiber stehenden Parametersatz aus (fuer Slider-Initialwerte/UI). */
    fun getCurrentControls(camera: CameraSelection): UvcControlSet {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) return controlPrefs.load(camera)
        return try {
            controlPrefs.load(camera).copy(
                focus = handler.getValue(UVCCamera.CTRL_FOCUS_ABS),
                focusAuto = handler.getAutoFocus(),
                brightness = handler.getValue(UVCCamera.PU_BRIGHTNESS),
                contrast = handler.getValue(UVCCamera.PU_CONTRAST),
                sharpness = handler.getValue(UVCCamera.PU_SHARPNESS),
                gain = handler.getValue(UVCCamera.PU_GAIN),
                gamma = handler.getValue(UVCCamera.PU_GAMMA),
                saturation = handler.getValue(UVCCamera.PU_SATURATION),
                hue = handler.getValue(UVCCamera.PU_HUE),
                whiteBalance = handler.getValue(UVCCamera.PU_WB_TEMP),
                whiteBalanceAuto = handler.getAutoWhiteBalance(),
                zoom = handler.getValue(UVCCamera.CTRL_ZOOM_ABS)
            )
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentControls($camera) fehlgeschlagen, gespeicherter Stand zurueckgegeben", e)
            controlPrefs.load(camera)
        }
    }

    /** Persistiert [controls] fuer den Slot [camera] (slot-basiert, siehe [UvcControlPrefs]). */
    fun saveControlSet(camera: CameraSelection, controls: UvcControlSet) {
        controlPrefs.save(camera, controls)
    }

    /** Geladener/gespeicherter Parametersatz fuer [camera] (Defaults falls noch nie
     * kalibriert/gespeichert wurde). */
    fun loadControlSet(camera: CameraSelection): UvcControlSet = controlPrefs.load(camera)

    /**
     * Pro-Aufnahme-Schaerfe-Check (ersetzt festes N-Aufnahmen-Rekalibrierungs-Intervall, siehe
     * Klassenkommentar): misst die Laplace-Varianz von [capturedFile] und vergleicht sie gegen
     * den bei [calibrateAuto] gespeicherten Referenzwert. Bei Unterschreitung des Toleranzbands
     * (siehe [UvcControlPrefs.getSharpnessToleranceBandPercent]) wird optional automatisch der
     * fixierte Fokuswert erneut geschrieben (Auto-Retry, siehe [UvcControlPrefs.getAutoRetryEnabled])
     * und in jedem Fall [Listener.onSharpnessOutOfTolerance] ausgeloest.
     */
    private fun verifyFocusAfterCapture(camera: CameraSelection, capturedFile: File) {
        val controls = controlPrefs.load(camera)
        if (controls.referenceSharpness <= 0.0) return // noch nie kalibriert, nichts zu vergleichen

        val measured = measureSharpness(capturedFile) ?: return
        val toleranceBand = controlPrefs.getSharpnessToleranceBandPercent()
        if (SharpnessAnalyzer.isWithinTolerance(measured, controls.referenceSharpness, toleranceBand)) {
            return
        }

        Log.w(TAG, "verifyFocusAfterCapture($camera): Schaerfe $measured ausserhalb Toleranz (Referenz ${controls.referenceSharpness}, Band $toleranceBand%)")
        if (controlPrefs.getAutoRetryEnabled() && !controls.focusAuto) {
            // Fokus einfach erneut fixiert schreiben (Arducam-Fund: Fokuswert kann bei
            // Erschuetterung/Reconnect verrutschen, expliziter Re-Apply ist der robuste Weg).
            val handler = handlerFor(camera)
            try {
                handler?.setValue(UVCCamera.CTRL_FOCUS_ABS, controls.focus)
            } catch (e: Exception) {
                Log.w(TAG, "verifyFocusAfterCapture($camera): Auto-Retry-Re-Apply fehlgeschlagen", e)
            }
        }
        mainHandler.post { listener.onSharpnessOutOfTolerance(camera, measured, controls.referenceSharpness) }
    }

    private fun measureSharpness(file: File): Double? {
        if (!file.exists()) return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            SharpnessAnalyzer.laplacianVariance(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun getSharpnessToleranceBandPercent(): Int = controlPrefs.getSharpnessToleranceBandPercent()

    fun setSharpnessToleranceBandPercent(value: Int) = controlPrefs.setSharpnessToleranceBandPercent(value)

    fun getAutoRetryEnabled(): Boolean = controlPrefs.getAutoRetryEnabled()

    fun setAutoRetryEnabled(value: Boolean) = controlPrefs.setAutoRetryEnabled(value)

    private val deviceConnectListener = object : OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.v(TAG, "onAttach: $device")
            // Nur fuer noch freie Kamera-Slots (L/R) um Permission fragen — sonst wuerde bei
            // jedem beliebigen dritten USB-Geraet unnoetig ein Dialog aufpoppen.
            val freeSlotAvailable = handlerL?.isOpened != true || handlerR?.isOpened != true
            if (freeSlotAvailable) {
                usbMonitor?.requestPermission(device)
            }
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
            Log.d(TAG, "onConnect: $device createNew=$createNew")
            // Ein Slot gilt als "belegt", sobald er entweder schon offen ist ODER schon einen
            // nachzuholenden ControlBlock wartend haelt (verhindert, dass zwei Geraete beide
            // auf LEFT gemapped werden, wenn onConnect fuer beide feuert bevor irgendeine
            // UvcPreview-View existiert).
            val leftTaken = handlerL?.isOpened == true || pendingCtrlBlockL != null
            val rightTaken = handlerR?.isOpened == true || pendingCtrlBlockR != null
            val slot = when {
                !leftTaken -> CameraSelection.LEFT
                !rightTaken -> CameraSelection.RIGHT
                else -> null
            }
            if (slot == null) {
                Log.d(TAG, "onConnect: kein freier Slot mehr, Geraet wird ignoriert")
                return
            }
            Log.d(TAG, "onConnect: Slot $slot zugewiesen, oeffne Handler")

            ensureHandler(slot)
            val handler = handlerFor(slot)
            if (handler == null) {
                // UvcPreview (bindCameraView) fuer diesen Slot existiert noch nicht (z.B. App
                // steht noch auf CALIBRATION/LOCK) — ControlBlock zwischenspeichern, wird in
                // bindCameraView()/openPendingConnectionIfAny() nachgeholt statt verworfen.
                Log.d(TAG, "onConnect: kein Handler fuer Slot $slot, ControlBlock wird zwischengespeichert")
                when (slot) {
                    CameraSelection.LEFT -> pendingCtrlBlockL = ctrlBlock
                    CameraSelection.RIGHT -> pendingCtrlBlockR = ctrlBlock
                }
                return
            }
            try {
                handler.open(ctrlBlock)
                Log.d(TAG, "onConnect: Handler.open() fuer $slot aufgerufen")
            } catch (e: Exception) {
                Log.e(TAG, "onConnect: Handler.open() fuer $slot fehlgeschlagen", e)
                listener.onError(slot, e)
                return
            }

            mainHandler.post {
                startPreview(slot)
                // Gespeicherte Einstellungen dieses Slots (falls schon einmal kalibriert/
                // gespeichert) sofort erneut auf den Treiber schreiben — siehe Arducam-
                // Recherche (Fokuswert geht bei USB-Reconnect verloren, muss aktiv neu
                // gesendet werden).
                applyManualControls(slot, controlPrefs.load(slot))
                listener.onCameraOpened(slot)
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
            val slot = when {
                handlerL?.isEqual(device) == true -> CameraSelection.LEFT
                handlerR?.isEqual(device) == true -> CameraSelection.RIGHT
                else -> null
            } ?: return

            handlerFor(slot)?.close()
            mainHandler.post { listener.onCameraClosed(slot) }
        }

        override fun onDettach(device: UsbDevice) {
            Log.v(TAG, "onDettach: $device")
        }

        override fun onCancel(device: UsbDevice) {
            Log.v(TAG, "onCancel: $device")
        }
    }
}
