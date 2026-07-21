package de.vif.bookscanner.hardware

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
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
import com.serenegiant.usbcameracommon.AbstractUVCCameraHandler.CameraCallback
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
    // ERSETZT die vorherige feste 640x480-Debug-Loesung (Testwert speziell fuer die ArduCAM
    // IMX298, siehe Git-Historie): dient jetzt NUR NOCH als Konstruktor-/Fallback-Wert, bevor
    // die tatsaechlich angeschlossene Kamera bekannt ist bzw. falls die Groessenermittlung
    // fehlschlaegt (siehe [resolveCameraSizes]). Sobald eine Kamera verbunden ist, wird die
    // ECHTE Preview-Groesse dynamisch aus [UVCCamera.getSupportedSizeList] ermittelt (naechste
    // an 320x240, der urspruenglichen Spec-Vorgabe) — generische UVC-Kompatibilitaet statt
    // kamera-spezifischem Festwert.
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

        /** Start-Gate (User-Vorgabe 2026-07-21): Anzahl der angeschlossenen USB-Kameras hat
         * sich geaendert (Attach/Detach). Die App blockiert unter 2 Kameras mit einer
         * Fehlermeldung statt weiterzulaufen. Default no-op. */
        fun onAttachedCameraCountChanged(count: Int) {}
    }

    companion object {
        private const val TAG = "UvcCameraBridge"

        /** Fallback-Sensor-Aufloesung fuer den Capture-Modus (USB2-Bandbreite erzwingt Mode-
         * Switch), NUR falls die dynamische Groessenermittlung (siehe [resolveCameraSizes])
         * fehlschlaegt oder noch nicht gelaufen ist. Im Normalfall wird pro Kamera-Slot die
         * tatsaechlich groesste vom UVC-Descriptor gemeldete Groesse verwendet (maximale
         * Qualitaet, kamera-agnostisch statt hartcodiert). */
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720

        /** Zielflaeche fuer die dynamische Preview-Groessenwahl — die urspruengliche Spec
         * verlangte 320x240; existiert diese Groesse nicht in der von der Kamera gemeldeten
         * Liste, wird die flaechenmaessig naechstgelegene ECHTE unterstuetzte Groesse gewaehlt
         * (siehe [resolveCameraSizes]), nicht geraten. */
        private const val PREVIEW_TARGET_AREA = 320L * 240L

        // 2026-07-21 von 400 auf 2000 erhoeht: die Live-Messung (diagnoseModeSwitchSettleTime,
        // beide Kameras, je 10 Frames) zeigte, dass nach dem resize() auf volle Sensor-
        // Aufloesung (4656x3496 via USB2) der erste verwertbare Frame real ~1,9s braucht —
        // 400ms war ein ungetesteter Platzhalter weit unterhalb der Realitaet und Mitursache
        // der Capture-Timeouts. Spaeter ggf. weiter optimierbar, sobald der Raw-Frame-Pfad
        // (siehe AbstractUVCCameraHandler#handleCaptureStill) am Geraet vermessen ist.
        private const val CAPTURE_SETTLE_DELAY_MS = 2000L

        /** Wartezeit bis der Auto-Fokus/Auto-WB-Regelkreis eingeschwungen ist, bevor der
         * erreichte Wert ausgelesen und Auto abgeschaltet (fixiert) wird. Ungetestet mit
         * echter Kamera, konservativ gewaehlt. */
        private const val AUTO_CALIBRATION_SETTLE_DELAY_MS = 1200L

        /** Fokus-Sweep (Punkt 1, Kalibrier-Architektur 2026-07-21): Schrittweite auf der
         * 0..100-Skala (siehe [UvcControlSet]-Kommentar — [UVCCamera.setFocus]/getFocus()
         * rechnen intern bereits auf den echten Hardware-Min..Max-Bereich um). */
        private const val FOCUS_SWEEP_STEP_PERCENT = 5

        /** Einschwingzeit pro Sweep-Schritt bevor die Schaerfe gemessen wird. Ungetestet,
         * konservativ — siehe Punkt 4 (Frame-Settle-Diagnose) fuer den empirischen Weg,
         * diesen Wert spaeter zu verkuerzen. */
        private const val FOCUS_SWEEP_STEP_SETTLE_MS = 200L

        /** Suchfenster (in Fokus-Prozentpunkten) fuer die lokale Korrektur um den zuletzt
         * bekannten Peak bei Toleranzband-Unterschreitung (siehe [correctFocusUsingCurve]) —
         * kein voller Re-Sweep, nur Nachbarschaftssuche in der gespeicherten Kurve. */
        private const val FOCUS_LOCAL_SEARCH_WINDOW_PERCENT = 15

        /** Frame-Settle-Diagnose (Punkt 4): Wartezeit VOR dem allerersten Diagnose-Frame nach
         * dem resize()-Aufruf, damit der Handler den Moduswechsel ueberhaupt entgegennehmen
         * kann. Danach werden die Frames OHNE weitere Wartezeit angefordert (das ist ja gerade
         * die Messgroesse). */
        /** Minimale technische Wartezeit zwischen zwei Diagnose-Frame-Anforderungen, NUR damit
         * die native Schreib-Pipeline die Datei lesbar fertigstellt — keine Settle-Annahme
         * ueber Bildqualitaet (das ist ja genau der Messwert, den [diagnoseModeSwitchSettleTime]
         * ermitteln soll). Absichtlich sehr kurz gehalten. */
        private const val FRAME_READ_POLL_DELAY_MS = 30L

        /** Sicherheits-Obergrenze fuer [diagnoseModeSwitchSettleTime]s Datei-Ready-Polling
         * (siehe dortiger Kommentar) — verhindert endloses Warten, falls captureStill() fuer
         * einen Frame nie eine lesbare Datei liefert (z.B. Timeout im nativen Layer). */
        private const val FRAME_READ_POLL_MAX_ATTEMPTS = 60

        /** USB-Bandbreiten-Faktor je Kamera im Dual-Preview-Betrieb (zwei parallele
         * 320x240-Streams). Fuer Voll-Aufloesungs-Captures wird temporaer 1.0 angefordert
         * (siehe captureFullResolutionThenReturnToPreview, Root-Cause "too few scanlines"). */
        const val DUAL_PREVIEW_BANDWIDTH_FACTOR = 0.5f
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

    // FIX Bug 1 (Race Condition Slot-Zuweisung, 2026-07-21): handler.open() ist ASYNCHRON
    // (postet nur eine MSG_OPEN-Message an den CameraThread, siehe AbstractUVCCameraHandler#open
    // -> sendMessage). handlerX?.isOpened wird deshalb NICHT sofort nach dem Aufruf von
    // handler.open() wahr, sondern erst wenn der CameraThread handleOpen() abgearbeitet hat.
    // Wenn onConnect() fuer das zweite Geraet feuert BEVOR das, sah die alte Pruefung
    // (isOpened == true || pendingCtrlBlock != null) den Slot faelschlich noch als frei an ->
    // beide Kameras wurden LEFT zugewiesen. Diese Flags werden SYNCHRON gesetzt, in dem Moment
    // in dem onConnect() einen Slot einem Geraet zuweist (nicht erst wenn der Handler tatsaechlich
    // offen ist) und wieder freigegeben bei Fehlschlag/Disconnect.
    private var slotClaimedL: Boolean = false
    private var slotClaimedR: Boolean = false

    private fun setSlotClaimed(camera: CameraSelection, claimed: Boolean) {
        when (camera) {
            CameraSelection.LEFT -> slotClaimedL = claimed
            CameraSelection.RIGHT -> slotClaimedR = claimed
        }
    }

    // Dynamisch ermittelte Groessen (Punkt "generische UVC-Vollkompatibilitaet", 2026-07-21):
    // pro Kamera-Slot befuellt in [resolveCameraSizes], NACHDEM handler.open() erfolgreich war
    // und die echte UVC-Descriptor-Groessenliste abgefragt werden konnte. null = noch nicht
    // ermittelt bzw. Ermittlung fehlgeschlagen -> [previewSizeFor]/[captureSizeFor] fallen dann
    // auf die Konstruktor-/Companion-Fallback-Werte zurueck.
    private var resolvedPreviewSizeL: Pair<Int, Int>? = null
    private var resolvedPreviewSizeR: Pair<Int, Int>? = null
    private var resolvedCaptureSizeL: Pair<Int, Int>? = null
    private var resolvedCaptureSizeR: Pair<Int, Int>? = null

    private fun previewSizeFor(camera: CameraSelection): Pair<Int, Int> =
        (when (camera) {
            CameraSelection.LEFT -> resolvedPreviewSizeL
            CameraSelection.RIGHT -> resolvedPreviewSizeR
        }) ?: (previewWidth to previewHeight)

    private fun captureSizeFor(camera: CameraSelection): Pair<Int, Int> =
        (when (camera) {
            CameraSelection.LEFT -> resolvedCaptureSizeL
            CameraSelection.RIGHT -> resolvedCaptureSizeR
        }) ?: (CAPTURE_WIDTH to CAPTURE_HEIGHT)

    /**
     * Fragt NACH erfolgreichem `handler.open()` die vom UVC-Descriptor tatsaechlich gemeldeten
     * Groessen ab ([UVCCamera.getSupportedSizeList], ueber [UVCCameraHandler.getCamera] — siehe
     * Klassenkommentar/AbstractUVCCameraHandler-Ergaenzung) und waehlt:
     * - Preview: die Groesse mit der zur Zielflaeche [PREVIEW_TARGET_AREA] (320x240) naechst-
     *   gelegenen Flaeche (nicht geraten, echte Liste).
     * - Capture: die Groesse mit der GROESSTEN Flaeche (maximale Qualitaet laut Spec).
     *
     * Setzt die Preview-Groesse zusaetzlich sofort ueber [UVCCameraHandler.setPreviewSize] (wirkt
     * nur VOR dem ersten [startPreview] fuer diesen Slot — muss deshalb vor [startPreview]
     * aufgerufen werden, siehe dortiger Kommentar). Die Capture-Groesse wird nur gespeichert
     * ([captureSizeFor]) und von den bestehenden resize()-Aufrufen (Mode-Switch) gelesen.
     *
     * Bei leerer/fehlender Liste (z.B. Kamera meldet nichts) bleiben die Fallback-Werte
     * (Konstruktor-previewWidth/-Height bzw. [CAPTURE_WIDTH]/[CAPTURE_HEIGHT]) unveraendert
     * aktiv — klar geloggt, kein Absturz.
     */
    private fun resolveCameraSizes(camera: CameraSelection, handler: UVCCameraHandler) {
        val rawCamera = handler.camera
        val sizes = try {
            rawCamera?.supportedSizeList
        } catch (e: Exception) {
            Log.w(TAG, "resolveCameraSizes($camera): getSupportedSizeList() fehlgeschlagen, Fallback-Groessen bleiben aktiv", e)
            null
        }
        if (sizes.isNullOrEmpty()) {
            Log.w(TAG, "resolveCameraSizes($camera): Kamera meldet keine unterstuetzten Groessen, Fallback ${previewWidth}x$previewHeight (Preview) / ${CAPTURE_WIDTH}x$CAPTURE_HEIGHT (Capture) bleibt aktiv")
            return
        }

        sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - PREVIEW_TARGET_AREA) }?.let { preview ->
            when (camera) {
                CameraSelection.LEFT -> resolvedPreviewSizeL = preview.width to preview.height
                CameraSelection.RIGHT -> resolvedPreviewSizeR = preview.width to preview.height
            }
            try {
                handler.setPreviewSize(preview.width, preview.height)
            } catch (e: Exception) {
                Log.w(TAG, "resolveCameraSizes($camera): setPreviewSize(${preview.width}x${preview.height}) fehlgeschlagen", e)
            }
            Log.i(TAG, "resolveCameraSizes($camera): Preview dynamisch ermittelt: ${preview.width}x${preview.height} (Ziel nahe 320x240, ${sizes.size} echte Groessen gemeldet)")
        }

        sizes.maxByOrNull { it.width.toLong() * it.height }?.let { capture ->
            when (camera) {
                CameraSelection.LEFT -> resolvedCaptureSizeL = capture.width to capture.height
                CameraSelection.RIGHT -> resolvedCaptureSizeR = capture.width to capture.height
            }
            Log.i(TAG, "resolveCameraSizes($camera): Capture dynamisch ermittelt (max. Qualitaet): ${capture.width}x${capture.height}")
        }
    }

    // Regionsbezogene Schaerfemessung (Punkt 2, 2026-07-21): optionaler Bildausschnitt pro
    // Kamera-Slot, aus dem im Live-Feed herangezoomten/ausgewaehlten Bereich abgeleitet (siehe
    // SettingsScreen.LiveFeedPinchZoom). null = Vollbild (Default-/Rueckwaertskompatibles
    // Verhalten). WICHTIG: Koordinaten sind FRAKTIONAL (0f..1f relativ zur jeweiligen
    // Bild-Breite/Hoehe), NICHT in absoluten Pixeln — Sweep/Kalibrierung messen in voller
    // Capture-Aufloesung, die Pro-Aufnahme-Schnellpruefung in Preview-Aufloesung (Punkt 3);
    // ein fraktionaler Bereich ist in beiden Aufloesungen gueltig, ein absoluter Pixel-Wert waere
    // es nicht (siehe [cropRectFor], das den Bruchteil erst am tatsaechlichen Bitmap umrechnet).
    private val focusRegionCrop = mutableMapOf<CameraSelection, RectF?>()

    /** Setzt/loescht den (fraktionalen) Bildausschnitt fuer die regionsbezogene Schaerfemessung
     * (Fokus-Sweep + Pro-Aufnahme-Checks) dieser Kamera. Wird von der UI (Pinch-Zoom-Zustand)
     * NAEHERUNGSWEISE aus dem Zoom/Pan des Live-Feeds berechnet — siehe Kommentar in
     * SettingsScreen.kt zur dortigen Vereinfachung/Annahme. null = wieder Vollbild verwenden. */
    fun setFocusRegion(camera: CameraSelection, fractionalCrop: RectF?) {
        focusRegionCrop[camera] = fractionalCrop
    }

    fun getFocusRegion(camera: CameraSelection): RectF? = focusRegionCrop[camera]

    /** Rechnet den fraktionalen Bildausschnitt fuer [camera] auf die tatsaechlichen Pixel-
     * Dimensionen von [bitmap] um (funktioniert dadurch unabhaengig davon, ob [bitmap] in
     * Preview- oder Capture-Aufloesung vorliegt). null falls kein Ausschnitt gesetzt ist. */
    private fun cropRectFor(camera: CameraSelection, bitmap: Bitmap): Rect? {
        val f = focusRegionCrop[camera] ?: return null
        val left = (f.left * bitmap.width).toInt()
        val top = (f.top * bitmap.height).toInt()
        val right = (f.right * bitmap.width).toInt()
        val bottom = (f.bottom * bitmap.height).toInt()
        return Rect(left, top, right, bottom)
    }

    /** Aktuelle (dynamisch ermittelte, sonst Fallback-) Preview-Aufloesung von [camera] — fuer
     * die naeherungsweise Crop-Rect-Umrechnung in der UI. */
    fun getPreviewSize(camera: CameraSelection): Pair<Int, Int> = previewSizeFor(camera)

    /** Aktuelle (dynamisch ermittelte, sonst Fallback-) volle Capture-Aufloesung von [camera] —
     * fuer die naeherungsweise Crop-Rect-Umrechnung in der UI. */
    fun getCaptureSize(camera: CameraSelection): Pair<Int, Int> = captureSizeFor(camera)

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
        if (existing == null) {
            val bandwidthFactor = DUAL_PREVIEW_BANDWIDTH_FACTOR // zwei Kameras teilen sich die USB2-Bandbreite (wie usbCameraTest7).
            val newHandler = UVCCameraHandler.createHandler(
                activity, view, previewWidth, previewHeight, bandwidthFactor
            )
            when (camera) {
                CameraSelection.LEFT -> handlerL = newHandler
                CameraSelection.RIGHT -> handlerR = newHandler
            }
        }

        // WICHTIG (live gefunden, 2026-07-21): Bei Screen-Wechsel (z.B. CalibrationScreen ->
        // SettingsScreen) entsteht eine NEUE UvcPreview-TextureView fuer dieselbe Kamera, die
        // alte wird disposed. Ohne den Callback bei JEDEM bindCameraView-Aufruf neu zu
        // registrieren (nicht nur beim allerersten Handler-Create) blieb die Preview nach so
        // einem Wechsel dauerhaft grau — der Handler war bereits offen, aber niemand hoerte
        // mehr auf onSurfaceCreated der neuen View, also wurde startPreview() nie erneut mit
        // der neuen Surface aufgerufen.
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

        // Falls die View (z.B. TextureView) schon vor diesem Aufruf eine gueltige Surface
        // hatte (etwa weil sie vom Compose-Recycling wiederverwendet wurde), kommt
        // onSurfaceCreated fuer diese Surface u.U. nie erneut — direkt pruefen und ggf. sofort
        // starten, statt nur auf den Callback zu warten.
        if (view.hasSurface() && handlerFor(camera)?.isOpened == true) {
            startPreview(camera)
        }
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
        // FIX Bug 2 (siehe onConnect-Kommentar): auch hier auf den echten CameraCallback.onOpen()
        // warten statt direkt nach dem asynchronen handler.open() zu posten.
        val cameraCallback = object : CameraCallback {
            override fun onOpen() {
                mainHandler.post {
                    resolveCameraSizes(camera, handler)
                    startPreview(camera)
                    applyManualControls(camera, controlPrefs.load(camera))
                    listener.onCameraOpened(camera)
                }
                handler.removeCallback(this)
            }

            override fun onClose() {
                handler.removeCallback(this)
            }

            override fun onStartPreview() {}
            override fun onStopPreview() {}
            override fun onStartRecording() {}
            override fun onStopRecording() {}

            override fun onError(e: Exception?) {
                mainHandler.post {
                    listener.onError(camera, e ?: IllegalStateException("Unbekannter Kamerafehler ($camera)"))
                }
                handler.removeCallback(this)
            }
        }
        handler.addCallback(cameraCallback)

        try {
            handler.open(ctrlBlock)
        } catch (e: Exception) {
            Log.e(TAG, "openPendingConnectionIfAny: Handler.open() fuer $camera fehlgeschlagen", e)
            handler.removeCallback(cameraCallback)
            setSlotClaimed(camera, false)
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
        // WICHTIG (live gefunden, 2026-07-21): handler.startPreview() ist auf dem nativen
        // CameraThread hart gegen Mehrfachaufruf gesperrt (No-Op falls bereits previewing).
        // Nach einem Compose-Screen-Wechsel (neue TextureView fuer dieselbe schon offene
        // Kamera, siehe ensureHandler) blieb der Frame-Strom deshalb dauerhaft an der ALTEN,
        // laengst zerstoerten Surface haengen — sichtbar als dauerhaft grauer Preview-Kasten
        // UND Logcat-Dauerspam "BufferQueue has been abandoned". rebindSurface() stoppt die
        // laufende Preview kurz und haengt sie an die neue Surface um.
        if (handler.isPreviewing) {
            handler.rebindSurface(surface)
        } else {
            handler.startPreview(surface)
        }
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

        // Fruehwarnung/-korrektur (Punkt 3) auf dem aktuellen Preview-Frame, BEVOR der teure
        // Mode-Switch ueberhaupt beginnt — siehe [quickPreviewFocusCheck].
        quickPreviewFocusCheck(camera)

        // OPTIMIERUNG (User-Vorgabe 2026-07-21, "max 1s Warten + 1s Uebertragung"): KEINE
        // blinden Fix-Delays mehr. Die frueheren zwei CAPTURE_SETTLE_DELAY_MS-Wartezeiten
        // (zuletzt 2x 2000ms) waren Raten-Puffer aus der Zeit, als der Capture am naechsten
        // TextureView-Frame hing. Seit dem Raw-Frame-Pfad (AbstractUVCCameraHandler#
        // handleCaptureStill) sind sie architektonisch unnoetig, denn:
        // 1. resize(), captureStill() und resize() zurueck sind MESSAGES an denselben
        //    CameraThread — sie werden garantiert seriell in Reihenfolge abgearbeitet,
        //    captureStill startet also erst, wenn der Mode-Switch komplett durch ist.
        // 2. handleCaptureStill wartet EREIGNISBASIERT auf den ersten echten Frame des
        //    neuen Streams (IFrameCallback, Timeout nur als Obergrenze) — genau die Zeit,
        //    die die Hardware wirklich braucht, keine Millisekunde mehr.
        // 3. Der Abschluss wird ueber eine vierte Message (handler.post) signalisiert, die
        //    erst NACH dem Rueck-resize verarbeitet wird — onDone feuert damit exakt beim
        //    echten Abschluss statt nach geratenem Timer.
        // Verbleibende Gesamtzeit = echte Hardware-Zeit: Stream-Neustart + 1 Frame-Intervall
        // (bei 4656x3496 sensorbedingt bis ~1,9s) + Stream-Neustart. Schneller geht es nur
        // noch ueber die Kamera-Firmware selbst (hoehere Voll-Aufloesungs-Framerate).
        val (captureW, captureH) = captureSizeFor(camera)
        val (previewW, previewH) = previewSizeFor(camera)
        try {
            // Volle USB-Bandbreite (1.0) fuer den Voll-Aufloesungs-Moment (Root-Cause-Fix
            // 2026-07-21: mit dem Dual-Preview-Faktor 0.5 kamen 4656x3496-Frames nur
            // unvollstaendig an — libuvc verwarf JEDEN Frame mit "too few scanlines",
            // weshalb nie ein Capture-Frame Callback/Surface erreichte). Zurueck auf
            // Preview-Groesse wieder mit 0.5, damit beide Live-Streams parallel passen.
            // Konsequenz: Voll-Aufloesungs-Captures laufen SEQUENZIELL je Kamera
            // (siehe ScannerViewModel.start_capture).
            handler.resize(captureW, captureH, 1.0f)
            handler.captureStill(targetFile.absolutePath)
            handler.resize(previewW, previewH, DUAL_PREVIEW_BANDWIDTH_FACTOR)
        } catch (e: Exception) {
            listener.onError(camera, e)
        }
        // Vierte Message: laeuft auf dem CameraThread erst nach den drei obigen — sicherer
        // "alles fertig"-Zeitpunkt, zurueck auf den Main-Thread fuer Verify + UI-Callback.
        handler.post {
            mainHandler.post {
                applyManualControls(camera, controlPrefs.load(camera))
                applyExifMetadata(camera, targetFile)
                verifyFocusAfterCapture(camera, targetFile)
                onDone()
            }
        }
    }

    /** 180-Grad-Rotation je Kamera (User-Vorgabe): persistiert in [UvcControlPrefs],
     * angewandt auf Live-Vorschau (UI, View.rotation) + gespeicherte JPEGs (EXIF). */
    fun getRotation180(camera: CameraSelection): Boolean = controlPrefs.getRotation180(camera)

    fun setRotation180(camera: CameraSelection, value: Boolean) {
        controlPrefs.setRotation180(camera, value)
    }

    /**
     * Schreibt EXIF-Metadaten in die gespeicherte JPEG-Datei — VERLUSTFREI: die
     * komprimierten Bilddaten (Scan-Segmente) bleiben byte-identisch, es wird nur das
     * Metadaten-Segment ergaenzt/geaendert (User-Vorgabe: keine Neukompression).
     *
     * 1. Aufnahme-Zeitstempel (TAG_DATETIME_ORIGINAL, User-Vorgabe: Timestamp gehoert ins
     *    EXIF, NICHT in den Dateinamen — das rohe Sensor-MJPEG bringt selbst keines mit).
     * 2. Bei aktiver 180-Grad-Rotation zusaetzlich das Orientation-Flag ROTATE_180 (jeder
     *    EXIF-konforme Viewer/jede OCR-Pipeline zeigt das Bild damit richtig herum).
     *
     * Fehler hier duerfen den Capture nie scheitern lassen — nur Log.
     */
    private fun applyExifMetadata(camera: CameraSelection, file: File) {
        if (!file.exists() || file.length() == 0L) return
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            val timestamp = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, timestamp)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, timestamp)
            if (controlPrefs.getRotation180(camera)) {
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180.toString()
                )
            }
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "applyExifMetadata($camera): EXIF-Metadaten setzen fehlgeschlagen", e)
        }
    }

    /**
     * Diagnose-Modus (Punkt 4): schaltet auf Capture-Aufloesung und nimmt danach [frameCount]
     * Frames HINTEREINANDER auf, OHNE Wartezeit dazwischen (die feste [CAPTURE_SETTLE_DELAY_MS]
     * wird hier bewusst NICHT verwendet — genau die ist ja die zu ueberpruefende Annahme). Fuer
     * jeden Frame wird Schaerfe + Zeitstempel seit dem resize()-Aufruf geloggt
     * ("Frame N: Schaerfe=X, +Yms seit Moduswechsel"). Ziel: den echten minimalen Settle-Wert
     * empirisch ermitteln statt zu raten (siehe [CAPTURE_SETTLE_DELAY_MS]-Kommentar — 400ms ist
     * ein ungetesteter Platzhalter). Schaltet am Ende zurueck auf Preview-Aufloesung.
     *
     * Reiner Debug-/Messwerkzeug-Code — der User wertet das Logcat-Ergebnis beim naechsten
     * Live-Test aus, diese Funktion liefert selbst keine Entscheidung/Anpassung von
     * [CAPTURE_SETTLE_DELAY_MS] (das waere Raten ohne echte Geraetedaten).
     */
    fun diagnoseModeSwitchSettleTime(camera: CameraSelection, frameCount: Int = 10) {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) {
            Log.w(TAG, "diagnoseModeSwitchSettleTime($camera): Kamera nicht geoeffnet, Diagnose abgebrochen")
            return
        }
        val (captureW, captureH) = captureSizeFor(camera)
        Log.i(TAG, "diagnoseModeSwitchSettleTime($camera): starte Moduswechsel auf ${captureW}x$captureH, $frameCount Frames ohne Wartezeit")
        val switchStartMs = System.currentTimeMillis()
        try {
            handler.resize(captureW, captureH)
        } catch (e: Exception) {
            Log.w(TAG, "diagnoseModeSwitchSettleTime($camera): resize fehlgeschlagen, Diagnose abgebrochen", e)
            return
        }

        fun captureFrame(index: Int) {
            if (index >= frameCount) {
                val (previewW, previewH) = previewSizeFor(camera)
                try {
                    handler.resize(previewW, previewH)
                } catch (e: Exception) {
                    Log.w(TAG, "diagnoseModeSwitchSettleTime($camera): resize zurueck auf Preview fehlgeschlagen", e)
                }
                Log.i(TAG, "diagnoseModeSwitchSettleTime($camera): fertig, zurueck auf Preview-Aufloesung")
                return
            }
            val frameFile = File(activity.cacheDir, "settle_diag_${camera}_${index}.jpg")
            val requestSentMs = System.currentTimeMillis()
            try {
                handler.captureStill(frameFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "diagnoseModeSwitchSettleTime($camera): captureStill Frame $index fehlgeschlagen", e)
            }
            // KORREKTUR (live gefunden, 2026-07-21): ein fixes Delay vor dem Lesen war zu kurz —
            // handleCaptureStill() schreibt SYNCHRON auf dem CameraThread (bitmap.compress() bei
            // voller Sensor-Aufloesung dauert je nach Geraet deutlich laenger als angenommene
            // 30ms), der erste Live-Messlauf lieferte deshalb bei ALLEN 10 Frames "kein lesbares
            // Bild" statt echter Schaerfewerte. Fix: statt zu raten, auf tatsaechliche
            // Datei-Fertigstellung pollen (Existenz + ueber zwei Polls stabile Groesse), mit
            // Sicherheits-Timeout. Die dafuer benoetigte Zeit IST zugleich die eigentlich
            // gesuchte Messgroesse (wie lange braucht ein Frame nach dem Moduswechsel wirklich).
            fun pollUntilFileReady(lastSize: Long, pollCount: Int) {
                val currentSize = if (frameFile.exists()) frameFile.length() else -1L
                val stable = currentSize > 0 && currentSize == lastSize
                if (stable || pollCount >= FRAME_READ_POLL_MAX_ATTEMPTS) {
                    val elapsedMs = System.currentTimeMillis() - switchStartMs
                    val waitedForFileMs = System.currentTimeMillis() - requestSentMs
                    val sharpness = measureSharpness(frameFile, camera)
                    frameFile.delete()
                    if (sharpness != null) {
                        Log.i(TAG, "diagnoseModeSwitchSettleTime($camera): Frame $index: Schaerfe=$sharpness, +${elapsedMs}ms seit Moduswechsel (Datei-Schreibzeit ${waitedForFileMs}ms, $pollCount Polls)")
                    } else {
                        Log.w(TAG, "diagnoseModeSwitchSettleTime($camera): Frame $index: kein lesbares Bild, +${elapsedMs}ms seit Moduswechsel (Datei-Schreibzeit ${waitedForFileMs}ms, $pollCount Polls, stable=$stable)")
                    }
                    captureFrame(index + 1)
                } else {
                    mainHandler.postDelayed({ pollUntilFileReady(currentSize, pollCount + 1) }, FRAME_READ_POLL_DELAY_MS)
                }
            }
            mainHandler.postDelayed({ pollUntilFileReady(-1L, 0) }, FRAME_READ_POLL_DELAY_MS)
        }
        captureFrame(0)
    }

    /**
     * Automodus-Kalibrierung: schaltet Auto-Fokus + Auto-Weissabgleich EIN, wartet auf
     * Einschwingen, liest dann die tatsaechlich erreichten Werte aus und schaltet Auto
     * wieder AB. Fuehrt DANACH einen vollstaendigen Fokus-Sweep (Punkt 1) in voller
     * Capture-Aufloesung durch (Mode-Switch, siehe [captureFullResolutionThenReturnToPreview]):
     * der native Auto-Fokus-Endwert dient nur als Startpunkt, der tatsaechliche Schaerfe-Peak
     * aus der gemessenen Fokus->Schaerfe-Kurve wird als endgueltiger fixierter Fokuswert
     * uebernommen (praeziser als der reine Autofokus-Regelkreis-Endwert). Kein Nachlaufen
     * zwischen einzelnen Aufnahmen (Vorgabe: maximale OCR-Qualitaet).
     *
     * Referenz-Schaerfewerte: [UvcControlSet.referenceSharpness] aus dem Sweep-Peak (volle
     * Aufloesung), zusaetzlich EIN weiterer Preview-Aufloesungs-Still fuer
     * [UvcControlSet.referenceSharpnessPreview] (Punkt 3, zweistufige Messung — kein
     * Modus-Wechsel mehr noetig, da nach dem Sweep bereits zurueck auf Preview geschaltet ist).
     * Beide Werte + die volle Kurve werden persistiert.
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

            // Ab hier: voller Fokus-Sweep in Capture-Aufloesung (Punkt 1). Mode-Switch einmal
            // hoch, alle Sweep-Schritte darin, dann einmal zurueck (statt pro Schritt zu
            // wechseln — deutlich schneller, volle Aufloesung ist trotzdem waehrend des
            // gesamten Sweeps aktiv).
            val (captureW, captureH) = captureSizeFor(camera)
            try {
                handler.resize(captureW, captureH)
            } catch (e: Exception) {
                Log.w(TAG, "calibrateAuto($camera): resize auf Capture-Aufloesung ${captureW}x$captureH fuer Sweep fehlgeschlagen", e)
            }

            mainHandler.postDelayed({
                runFocusSweep(camera) { curve ->
                    val peak = curve.maxByOrNull { it.second }
                    val peakFocus = peak?.first ?: fixed.focus
                    val peakSharpness = peak?.second ?: 0.0
                    try {
                        handler.setValue(UVCCamera.CTRL_FOCUS_ABS, peakFocus)
                    } catch (e: Exception) {
                        Log.w(TAG, "calibrateAuto($camera): Setzen des Sweep-Peak-Fokus fehlgeschlagen", e)
                    }

                    val (previewW, previewH) = previewSizeFor(camera)
                    try {
                        handler.resize(previewW, previewH)
                    } catch (e: Exception) {
                        Log.w(TAG, "calibrateAuto($camera): resize zurueck auf Preview-Aufloesung ${previewW}x$previewH fehlgeschlagen", e)
                    }

                    // Zweiter, EIGENER Referenzwert in Preview-Aufloesung (Punkt 3) — kein
                    // weiterer Modus-Wechsel mehr noetig, wir sind bereits zurueck auf Preview.
                    mainHandler.postDelayed({
                        val previewRefFile = File(activity.cacheDir, "calibration_ref_preview_${camera}_${System.currentTimeMillis()}.jpg")
                        try {
                            handler.captureStill(previewRefFile.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "calibrateAuto($camera): Preview-Referenz-Still fehlgeschlagen, referenceSharpnessPreview bleibt 0.0", e)
                        }
                        mainHandler.postDelayed({
                            val referenceSharpnessPreview = measureSharpness(previewRefFile, camera) ?: 0.0
                            previewRefFile.delete()

                            val result = fixed.copy(
                                focus = peakFocus,
                                referenceSharpness = peakSharpness,
                                referenceSharpnessPreview = referenceSharpnessPreview,
                                focusSweepCurve = curve
                            )
                            controlPrefs.save(camera, result)
                            onResult(result)
                        }, FOCUS_SWEEP_STEP_SETTLE_MS)
                    }, CAPTURE_SETTLE_DELAY_MS)
                }
            }, CAPTURE_SETTLE_DELAY_MS)
        }, AUTO_CALIBRATION_SETTLE_DELAY_MS)
    }

    /**
     * Fuehrt den eigentlichen Fokus-Sweep durch (Punkt 1): faehrt die Fokus-Skala 0..100 in
     * Schritten von [FOCUS_SWEEP_STEP_PERCENT] ab, misst pro Schritt die Schaerfe (optional
     * nur im Bildausschnitt aus [focusRegionCrop], siehe Punkt 2 — regionsbezogene Messung)
     * und ruft [onDone] mit der kompletten (Fokuswert, Schaerfe)-Liste auf, sobald fertig.
     * MUSS aufgerufen werden waehrend die Kamera bereits in Capture-Aufloesung steht — dieser
     * Aufruf selbst macht keinen resize().
     */
    private fun runFocusSweep(camera: CameraSelection, onDone: (List<Pair<Int, Double>>) -> Unit) {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) {
            onDone(emptyList())
            return
        }
        val steps = (0..100 step FOCUS_SWEEP_STEP_PERCENT).toList()
        val curve = mutableListOf<Pair<Int, Double>>()

        fun stepAt(index: Int) {
            if (index >= steps.size) {
                onDone(curve)
                return
            }
            val focusValue = steps[index]
            try {
                handler.setValue(UVCCamera.CTRL_FOCUS_ABS, focusValue)
            } catch (e: Exception) {
                Log.w(TAG, "runFocusSweep($camera): setValue bei Schritt $focusValue fehlgeschlagen", e)
            }
            mainHandler.postDelayed({
                val stepFile = File(activity.cacheDir, "focus_sweep_${camera}_${focusValue}.jpg")
                try {
                    handler.captureStill(stepFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "runFocusSweep($camera): captureStill bei Schritt $focusValue fehlgeschlagen", e)
                }
                mainHandler.postDelayed({
                    val sharpness = measureSharpness(stepFile, camera) ?: 0.0
                    stepFile.delete()
                    curve.add(focusValue to sharpness)
                    stepAt(index + 1)
                }, FOCUS_SWEEP_STEP_SETTLE_MS)
            }, FOCUS_SWEEP_STEP_SETTLE_MS)
        }
        stepAt(0)
    }

    /**
     * Lokale Korrektursuche in der gespeicherten Fokus-Sweep-Kurve (Punkt 1, Nutzen bei der
     * Pro-Aufnahme-Verifikation): sucht statt eines vollen Re-Sweeps oder blinden Retries nur
     * in einem Fenster von +/- [FOCUS_LOCAL_SEARCH_WINDOW_PERCENT] um den aktuell fixierten
     * Fokuswert nach dem lokal besten (schaerfsten) bekannten Kurvenpunkt. Faellt auf die
     * gesamte Kurve zurueck falls das Fenster leer ist. null falls noch nie ein Sweep lief.
     */
    private fun correctFocusUsingCurve(controls: UvcControlSet): Int? =
        de.vif.bookscanner.util.FocusCurveSearch.bestFocusNear(
            controls.focusSweepCurve, controls.focus, FOCUS_LOCAL_SEARCH_WINDOW_PERCENT
        )

    /**
     * Schnelle Vor-Aufnahme-Pruefung (Punkt 3, zweistufige Messung): misst die Schaerfe des
     * AKTUELLEN Live-Preview-Frames (ueber [CameraViewInterface.captureStillImage], synchron,
     * KEIN Modus-Wechsel, KEINE Datei) und vergleicht sie gegen
     * [UvcControlSet.referenceSharpnessPreview]. Bei Unterschreitung wird — falls Auto-Retry
     * aktiv und eine Sweep-Kurve vorliegt — sofort per lokaler Kurvensuche korrigiert
     * ([correctFocusUsingCurve]), BEVOR ueberhaupt der teure Capture-Mode-Switch beginnt.
     * Reiner Optimierungs-/Fruehwarn-Schritt — die eigentliche, autoritative Pruefung bleibt
     * [verifyFocusAfterCapture] auf dem tatsaechlich gespeicherten Vollaufloesungs-Bild.
     */
    private fun quickPreviewFocusCheck(camera: CameraSelection) {
        val controls = controlPrefs.load(camera)
        if (controls.referenceSharpnessPreview <= 0.0) return // noch nie kalibriert
        val view = when (camera) {
            CameraSelection.LEFT -> viewL
            CameraSelection.RIGHT -> viewR
        } ?: return
        val bitmap = try {
            view.captureStillImage()
        } catch (e: Exception) {
            null
        } ?: return
        val measured = try {
            SharpnessAnalyzer.laplacianVariance(bitmap, cropRectFor(camera, bitmap))
        } finally {
            bitmap.recycle()
        }
        val toleranceBand = controlPrefs.getSharpnessToleranceBandPercent()
        if (SharpnessAnalyzer.isWithinTolerance(measured, controls.referenceSharpnessPreview, toleranceBand)) {
            return
        }
        Log.w(TAG, "quickPreviewFocusCheck($camera): Preview-Schaerfe $measured unter Referenz ${controls.referenceSharpnessPreview} (Band $toleranceBand%)")
        if (controlPrefs.getAutoRetryEnabled() && !controls.focusAuto) {
            val handler = handlerFor(camera)
            val corrected = correctFocusUsingCurve(controls) ?: controls.focus
            try {
                handler?.setValue(UVCCamera.CTRL_FOCUS_ABS, corrected)
            } catch (e: Exception) {
                Log.w(TAG, "quickPreviewFocusCheck($camera): Korrektur-Re-Apply fehlgeschlagen", e)
            }
        }
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

        val measured = measureSharpness(capturedFile, camera) ?: return
        val toleranceBand = controlPrefs.getSharpnessToleranceBandPercent()
        if (SharpnessAnalyzer.isWithinTolerance(measured, controls.referenceSharpness, toleranceBand)) {
            return
        }

        Log.w(TAG, "verifyFocusAfterCapture($camera): Schaerfe $measured ausserhalb Toleranz (Referenz ${controls.referenceSharpness}, Band $toleranceBand%)")
        if (controlPrefs.getAutoRetryEnabled() && !controls.focusAuto) {
            // Bevorzugt lokale Korrektursuche in der gespeicherten Sweep-Kurve (Punkt 1) —
            // sagt Richtung/Distanz der Korrektur an statt blind denselben Wert erneut zu
            // senden. Faellt auf den reinen Re-Apply zurueck, falls (noch) keine Kurve
            // vorliegt (Arducam-Fund: Fokuswert kann bei Erschuetterung/Reconnect verrutschen,
            // expliziter Re-Apply ist in jedem Fall der robuste Basis-Weg).
            val handler = handlerFor(camera)
            val corrected = correctFocusUsingCurve(controls) ?: controls.focus
            try {
                handler?.setValue(UVCCamera.CTRL_FOCUS_ABS, corrected)
            } catch (e: Exception) {
                Log.w(TAG, "verifyFocusAfterCapture($camera): Auto-Retry-Re-Apply fehlgeschlagen", e)
            }
        }
        mainHandler.post { listener.onSharpnessOutOfTolerance(camera, measured, controls.referenceSharpness) }
    }

    /** @param camera falls gesetzt, wird der fuer diese Kamera hinterlegte fraktionale
     * Bildausschnitt ([focusRegionCrop]/[cropRectFor]) auf die tatsaechliche Groesse von
     * [file] umgerechnet und angewendet — funktioniert dadurch sowohl fuer Preview- als auch
     * Capture-Aufloesungs-Dateien. null (Default) = Vollbild. */
    private fun measureSharpness(file: File, camera: CameraSelection? = null): Double? {
        if (!file.exists()) return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        } ?: return null
        val cropRect = camera?.let { cropRectFor(it, bitmap) }
        return try {
            SharpnessAnalyzer.laplacianVariance(bitmap, cropRect)
        } finally {
            bitmap.recycle()
        }
    }

    fun getSharpnessToleranceBandPercent(): Int = controlPrefs.getSharpnessToleranceBandPercent()

    fun setSharpnessToleranceBandPercent(value: Int) = controlPrefs.setSharpnessToleranceBandPercent(value)

    fun getAutoRetryEnabled(): Boolean = controlPrefs.getAutoRetryEnabled()

    fun setAutoRetryEnabled(value: Boolean) = controlPrefs.setAutoRetryEnabled(value)

    /** Anzahl aktuell angeschlossener (attached, nicht notwendigerweise geoeffneter)
     * USB-Kamera-Geraete — fuer das Start-Gate (App blockiert unter 2 Kameras). */
    private val attachedDevices = mutableSetOf<String>()

    /** Anzahl der aktuell per USB angeschlossenen Kamera-Geraete (Start-Gate-Kriterium). */
    fun getAttachedCameraCount(): Int = attachedDevices.size

    private val deviceConnectListener = object : OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.v(TAG, "onAttach: $device")
            attachedDevices.add(device.deviceName)
            mainHandler.post { listener.onAttachedCameraCountChanged(attachedDevices.size) }
            // Nur fuer noch freie Kamera-Slots (L/R) um Permission fragen — sonst wuerde bei
            // jedem beliebigen dritten USB-Geraet unnoetig ein Dialog aufpoppen.
            val freeSlotAvailable = handlerL?.isOpened != true || handlerR?.isOpened != true
            if (freeSlotAvailable) {
                usbMonitor?.requestPermission(device)
            }
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
            Log.d(TAG, "onConnect: $device createNew=$createNew")
            // Ein Slot gilt als "belegt", sobald er entweder schon offen ist, schon einen
            // nachzuholenden ControlBlock wartend haelt, ODER (FIX Bug 1) synchron als
            // "claimed" markiert wurde — Letzteres verhindert die Race Condition: handler.open()
            // ist asynchron, isOpened wuerde erst NACH dem CameraThread-Roundtrip wahr, also
            // MUSS die Slot-Belegung schon HIER, synchron beim Zuweisen, sichtbar werden, sonst
            // sieht die zweite onConnect()-Ausfuehrung (fuer die zweite Kamera) den Slot noch
            // faelschlich als frei an und beide Geraete werden auf LEFT gemapped.
            val leftTaken = handlerL?.isOpened == true || pendingCtrlBlockL != null || slotClaimedL
            val rightTaken = handlerR?.isOpened == true || pendingCtrlBlockR != null || slotClaimedR
            val slot = when {
                !leftTaken -> CameraSelection.LEFT
                !rightTaken -> CameraSelection.RIGHT
                else -> null
            }
            if (slot == null) {
                Log.d(TAG, "onConnect: kein freier Slot mehr, Geraet wird ignoriert")
                return
            }
            // Synchron VOR jedem weiteren Schritt markieren — das ist der eigentliche Fix.
            setSlotClaimed(slot, true)
            Log.d(TAG, "onConnect: Slot $slot zugewiesen, oeffne Handler")

            ensureHandler(slot)
            val handler = handlerFor(slot)
            if (handler == null) {
                // UvcPreview (bindCameraView) fuer diesen Slot existiert noch nicht (z.B. App
                // steht noch auf CALIBRATION/LOCK) — ControlBlock zwischenspeichern, wird in
                // bindCameraView()/openPendingConnectionIfAny() nachgeholt statt verworfen.
                // Slot bleibt claimed (pendingCtrlBlockX ist ab jetzt ohnehin != null).
                Log.d(TAG, "onConnect: kein Handler fuer Slot $slot, ControlBlock wird zwischengespeichert")
                when (slot) {
                    CameraSelection.LEFT -> pendingCtrlBlockL = ctrlBlock
                    CameraSelection.RIGHT -> pendingCtrlBlockR = ctrlBlock
                }
                return
            }

            // FIX Bug 2 (resolveCameraSizes() liefert "keine Groessen", Timing-Problem):
            // handler.open() ist asynchron (postet MSG_OPEN an den CameraThread, siehe
            // AbstractUVCCameraHandler#open/#handleOpen). Ein direktes mainHandler.post {...}
            // NACH dem open()-Aufruf laeuft auf einer voellig anderen Message-Queue (Main-
            // Looper) und hat KEINE garantierte Reihenfolge zum CameraThread-Abschluss von
            // handleOpen() — resolveCameraSizes() lief dadurch teils BEVOR die UVC-Descriptor-
            // Daten ueberhaupt eingelesen waren, getSupportedSizeList() lieferte dann leer.
            // Die Bibliothek bietet dafuer einen echten Abschluss-Callback: CameraCallback.onOpen()
            // wird von AbstractUVCCameraHandler.CameraThread.handleOpen() erst NACH erfolgreichem
            // camera.open(ctrlBlock) aufgerufen (callOnOpen(), siehe AbstractUVCCameraHandler.java
            // handleOpen()/callOnOpen()) — das ist der zuverlaessige "open completed"-Callback,
            // kein Raten/Polling mehr.
            val cameraCallback = object : CameraCallback {
                override fun onOpen() {
                    mainHandler.post {
                        // Muss VOR startPreview() laufen (siehe [resolveCameraSizes]-Kommentar):
                        // setzt die dynamisch ermittelte Preview-Groesse, die erst nach
                        // abgeschlossenem handler.open() per echtem UVC-Descriptor bekannt ist.
                        resolveCameraSizes(slot, handler)
                        startPreview(slot)
                        // Gespeicherte Einstellungen dieses Slots (falls schon einmal kalibriert/
                        // gespeichert) sofort erneut auf den Treiber schreiben — siehe Arducam-
                        // Recherche (Fokuswert geht bei USB-Reconnect verloren, muss aktiv neu
                        // gesendet werden).
                        applyManualControls(slot, controlPrefs.load(slot))
                        listener.onCameraOpened(slot)
                    }
                    handler.removeCallback(this)
                }

                override fun onClose() {
                    handler.removeCallback(this)
                }

                override fun onStartPreview() {}
                override fun onStopPreview() {}
                override fun onStartRecording() {}
                override fun onStopRecording() {}

                override fun onError(e: Exception?) {
                    mainHandler.post {
                        listener.onError(slot, e ?: IllegalStateException("Unbekannter Kamerafehler ($slot)"))
                    }
                    handler.removeCallback(this)
                }
            }
            handler.addCallback(cameraCallback)

            try {
                handler.open(ctrlBlock)
                Log.d(TAG, "onConnect: Handler.open() fuer $slot aufgerufen (asynchron, Fortsetzung in CameraCallback.onOpen())")
            } catch (e: Exception) {
                Log.e(TAG, "onConnect: Handler.open() fuer $slot fehlgeschlagen", e)
                handler.removeCallback(cameraCallback)
                setSlotClaimed(slot, false)
                listener.onError(slot, e)
                return
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
            val slot = when {
                handlerL?.isEqual(device) == true -> CameraSelection.LEFT
                handlerR?.isEqual(device) == true -> CameraSelection.RIGHT
                else -> null
            } ?: return

            handlerFor(slot)?.close()
            // FIX Bug 1: Slot beim Trennen wieder freigeben, sonst bleibt er nach einem
            // Disconnect faelschlich dauerhaft "claimed" und ein Reconnect bekommt nie mehr
            // diesen Slot zugewiesen.
            setSlotClaimed(slot, false)
            mainHandler.post { listener.onCameraClosed(slot) }
        }

        override fun onDettach(device: UsbDevice) {
            Log.v(TAG, "onDettach: $device")
            attachedDevices.remove(device.deviceName)
            mainHandler.post { listener.onAttachedCameraCountChanged(attachedDevices.size) }
        }

        override fun onCancel(device: UsbDevice) {
            Log.v(TAG, "onCancel: $device")
        }
    }
}
