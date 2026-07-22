package de.vif.bookscanner.state

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.hardware.UvcControlSet
import de.vif.bookscanner.storage.ScanStorageRepository
import de.vif.bookscanner.util.BookscanFileNamer
import java.io.File

/** Auto- oder manueller Kalibrier-Modus (siehe [CalibrationMode] und CalibrationScreen). */
enum class CalibrationMode { AUTO, MANUAL }

/**
 * Zentrales ViewModel der Buchscanner-App.
 *
 * Kapselt die 6-Zustaende-State-Machine (siehe [ScannerState]) und die Events aus der
 * GUI-Doku (vif-bookscanner-gui-layer.md). Die echte Kamera-Anbindung erfolgt ueber
 * [UvcCameraBridge], die von [de.vif.bookscanner.MainActivity] gesetzt wird (USBMonitor
 * braucht eine Activity, nicht nur Application-Context).
 */
class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val storageRepository = ScanStorageRepository(application)

    /** Wird von MainActivity.onCreate gesetzt, sobald die Activity + Compose-Views bereit sind. */
    var cameraBridge: UvcCameraBridge? = null

    var state by mutableStateOf(ScannerState.CALIBRATION)
        private set

    /** Start-Gate (User-Vorgabe 2026-07-21): Anzahl der aktuell angeschlossenen USB-Kameras.
     * Unter [REQUIRED_CAMERA_COUNT] blockiert die App vollstaendig mit Fehlermeldung
     * (siehe DeviceGateScreen in BookscannerApp) — sie laeuft NICHT mit einer oder null
     * Kameras weiter. */
    var attachedCameraCount by mutableStateOf(0)
        private set

    fun on_attached_camera_count_changed(count: Int) {
        attachedCameraCount = count
    }

    var activeCamera by mutableStateOf(CameraSelection.LEFT)
        private set

    /** 180-Grad-Rotation je Kamera (User-Vorgabe 2026-07-21): Kameras sind physisch nicht
     * per ID unterscheidbar und koennen kopfueber montiert sein. Wird auf Live-Vorschau
     * (View.rotation) und gespeicherte JPEGs (verlustfreies EXIF-Flag) angewandt;
     * persistiert in UvcControlPrefs, hier nur der Compose-State-Spiegel. */
    var rotation180ByCamera by mutableStateOf<Map<CameraSelection, Boolean>>(emptyMap())
        private set

    var projectName by mutableStateOf("buch")
        private set

    var pageNumber by mutableStateOf(1)
        private set

    /** true waehrend des Capture-Mode-Switch, blockiert Doppel-Ausloesung. */
    var captureInProgress by mutableStateOf(false)
        private set

    // --- Capture-Fortschritt fuer die Visualisierung (CaptureScreen) ---

    /** Anzahl Kameras, die in der laufenden Doppelseiten-Runde aufgenommen werden. */
    var captureTotalCount by mutableStateOf(0)
        private set

    /** Anzahl Kameras, deren Aufnahme in der laufenden Runde bereits abgeschlossen ist. */
    var captureCompletedCount by mutableStateOf(0)
        private set

    /** Kameras, deren Aufnahme in der laufenden Runde bereits fertig ist — fuer die
     * Pro-Kamera-Statusanzeige (L fertig, R laeuft noch ...). */
    var captureFinishedCameras by mutableStateOf<Set<CameraSelection>>(emptySet())
        private set

    var lastCapturedFile by mutableStateOf<File?>(null)
        private set

    /** Alle in der letzten Doppelseiten-Aufnahme geschriebenen Dateien (L und/oder R) —
     * fuer die RECHECK-Anzeige. [lastCapturedFile] bleibt als letzte Einzeldatei erhalten. */
    var lastCapturedFiles by mutableStateOf<List<File>>(emptyList())
        private set

    // --- Kalibrier-Architektur (Auto/Manuell, siehe CalibrationScreen) ---

    var calibrationMode by mutableStateOf(CalibrationMode.AUTO)
        private set

    /** Aktueller Parametersatz der aktiven Kamera, wie er im manuellen Modus per Slider
     * angezeigt/veraendert wird. Wird beim Betreten von CALIBRATION/SETUP aus der Bridge
     * (Treiber-Ist-Stand bzw. gespeicherter Stand) nachgeladen. */
    var currentControls by mutableStateOf(UvcControlSet())
        private set

    var calibrationInProgress by mutableStateOf(false)
        private set

    /** Generische UVC-Vollkompatibilitaet (2026-07-22): optionaler eigener Look-Parametersatz
     * (Helligkeit/Kontrast/Schaerfe/Gain/Gamma/Saettigung/Hue/Weissabgleich/Zoom — NICHT Fokus,
     * siehe [UvcCameraBridge.loadCaptureLook]-Kommentar) fuer die Voll-Aufloesungs-Aufnahme,
     * getrennt vom Live-Feed-Parametersatz ([currentControls]). false = Capture nutzt denselben
     * Satz wie Live (Normalfall). */
    var captureLookOverrideEnabled by mutableStateOf(false)
        private set

    var currentCaptureControls by mutableStateOf(UvcControlSet())
        private set

    // --- Automatisierter Test-Capture-Loop (Debug-Modus, siehe Plan Punkt 5) ---

    var testLoopRunning by mutableStateOf(false)
        private set

    var testLoopCurrentIndex by mutableStateOf(0)
        private set

    var testLoopTotalCount by mutableStateOf(20)
        private set

    var testLoopIntervalMs by mutableStateOf(4000L)
        private set

    private val testLoopHandler = Handler(Looper.getMainLooper())

    /** Startet die Kalibrierung, danach automatisch LOCK (Vorgabe State-Machine). */
    fun start_calibration() {
        state = ScannerState.CALIBRATION
        // Kameras oeffnen sich automatisch ueber UvcCameraBridge.onConnect (USBMonitor-Callback),
        // sobald sie am Geraet angeschlossen werden.
        refreshCurrentControls()
    }

    fun refreshCurrentControls() {
        cameraBridge?.let { bridge ->
            currentControls = if (bridge.isOpened(activeCamera)) {
                bridge.getCurrentControls(activeCamera)
            } else {
                bridge.loadControlSet(activeCamera)
            }
            rotation180ByCamera = CameraSelection.entries.associateWith { bridge.getRotation180(it) }
            captureLookOverrideEnabled = bridge.hasCaptureLookOverride(activeCamera)
            currentCaptureControls = bridge.loadCaptureLook(activeCamera)
        }
    }

    /** Schaltet den eigenen Capture-Look-Parametersatz fuer die aktive Kamera an/aus (siehe
     * [captureLookOverrideEnabled]-Kommentar). Beim erstmaligen Einschalten wird der aktuelle
     * Live-Satz als Startpunkt uebernommen (Fokus bleibt so automatisch konsistent, da beide
     * Saetze denselben kalibrierten Fokuswert tragen). Beim Ausschalten wird der gespeicherte
     * Override geloescht (Capture faellt zurueck auf "wie Live"). */
    fun toggle_capture_look_override() {
        val bridge = cameraBridge ?: return
        if (captureLookOverrideEnabled) {
            bridge.clearCaptureLookOverride(activeCamera)
            captureLookOverrideEnabled = false
            currentCaptureControls = bridge.loadCaptureLook(activeCamera)
        } else {
            val startingPoint = currentControls
            bridge.saveCaptureLook(activeCamera, startingPoint)
            captureLookOverrideEnabled = true
            currentCaptureControls = startingPoint
        }
    }

    /** Slider-Aenderung im Capture-Look-Parametersatz — nur persistiert (wird erst beim
     * naechsten Voll-Aufloesungs-Capture angewandt, siehe [UvcCameraBridge]
     * captureFullResolutionThenReturnToPreview), kein Live-Schreiben auf den Treiber wie bei
     * [update_manual_control], da die Kamera gerade auf Preview-Aufloesung steht. */
    fun update_capture_look_control(updated: UvcControlSet) {
        currentCaptureControls = updated
        cameraBridge?.saveCaptureLook(activeCamera, updated)
    }

    /** Schaltet die 180-Grad-Rotation fuer [camera] um (Live-Anzeige + EXIF der Dateien). */
    fun toggle_rotation180(camera: CameraSelection) {
        val bridge = cameraBridge ?: return
        bridge.setRotation180(camera, !bridge.getRotation180(camera))
        rotation180ByCamera = CameraSelection.entries.associateWith { bridge.getRotation180(it) }
    }

    fun isRotated180(camera: CameraSelection): Boolean = rotation180ByCamera[camera] == true

    fun set_calibration_mode(mode: CalibrationMode) {
        calibrationMode = mode
        if (mode == CalibrationMode.MANUAL) refreshCurrentControls()
    }

    /** Automodus-Knopf: Auto-Fokus/Auto-WB fahren lassen, dann auslesen+fixieren
     * (siehe [UvcCameraBridge.calibrateAuto]) — kein Nachlaufen zwischen Aufnahmen. */
    fun run_auto_calibration() {
        val bridge = cameraBridge ?: return
        if (!bridge.isOpened(activeCamera)) {
            Log.w(TAG, "run_auto_calibration: Kamera $activeCamera nicht verbunden")
            return
        }
        calibrationInProgress = true
        bridge.calibrateAuto(activeCamera) { result ->
            currentControls = result
            calibrationInProgress = false
        }
    }

    /** Manueller Modus: einzelner Slider-Wert geaendert -> sofort live auf den Treiber
     * schreiben (Vorgabe: sofortige Anzeige im Live-Feed) + persistieren. */
    fun update_manual_control(updated: UvcControlSet) {
        currentControls = updated
        val bridge = cameraBridge ?: return
        bridge.applyManualControls(activeCamera, updated)
        bridge.saveControlSet(activeCamera, updated)
    }

    /** Startet den automatisierten Test-Capture-Loop (Debug-Modus, NICHT der normale Scan-Flow):
     * nimmt [count] Aufnahmen im Abstand [intervalMs] auf, Projektname fix "autofocus-drift-test",
     * fortlaufende Seitenzahl — fuer die empirische Fokus-Drift-Analyse (siehe Plan). */
    fun start_test_capture_loop(count: Int, intervalMs: Long) {
        if (testLoopRunning) return
        val bridge = cameraBridge
        if (bridge == null || !bridge.isOpened(activeCamera)) {
            Log.w(TAG, "start_test_capture_loop: Kamera $activeCamera nicht verbunden")
            return
        }
        testLoopTotalCount = count
        testLoopIntervalMs = intervalMs
        testLoopCurrentIndex = 0
        testLoopRunning = true
        scheduleNextTestCapture()
    }

    fun stop_test_capture_loop() {
        testLoopRunning = false
        testLoopHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleNextTestCapture() {
        if (!testLoopRunning) return
        if (testLoopCurrentIndex >= testLoopTotalCount) {
            testLoopRunning = false
            return
        }
        val bridge = cameraBridge
        if (bridge == null || !bridge.isOpened(activeCamera)) {
            Log.w(TAG, "scheduleNextTestCapture: Kamera $activeCamera getrennt, Loop wird abgebrochen")
            testLoopRunning = false
            return
        }
        val fileName = BookscanFileNamer.buildFileName(
            projectName = TEST_LOOP_PROJECT_NAME,
            pageNumber = testLoopCurrentIndex + 1,
            camera = activeCamera
        )
        val targetFile = storageRepository.reserveCaptureFile(fileName)
        bridge.captureFullResolutionThenReturnToPreview(activeCamera, targetFile) {
            storageRepository.publishCapturedFile(targetFile)
            testLoopCurrentIndex += 1
            if (testLoopRunning) {
                testLoopHandler.postDelayed({ scheduleNextTestCapture() }, testLoopIntervalMs)
            }
        }
    }

    /**
     * Loest die DOPPELSEITEN-Aufnahme aus (Kern-Workflow des Buchscanners): nimmt BEIDE
     * verbundenen Kameras (LEFT + RIGHT) in voller Aufloesung auf — eine Blaetterung = eine
     * linke + eine rechte Buchseite.
     *
     * GESCHICHTE (2026-07-21, drei Iterationen am selben Tag):
     * 1. Sequenziell: Kamera A komplett (Reopen-hoch -> Capture -> Reopen-runter -> Verify),
     *    DANACH Kamera B. Frueher verworfenes "echtes Parallel-Capture je 0.5 Bandbreite"
     *    lieferte damals nur unvollstaendige Frames — ABER dieser Test lief mit dem damals noch
     *    kaputten In-Place-resize() (siehe [UvcCameraBridge] Root-Cause-Historie), das selbst im
     *    Einzelkamera-Betrieb bei JEDER Bandbreite oberhalb 640x480 nie einen vollstaendigen
     *    Frame lieferte. Die daraus gezogene Schlussfolgerung "zwei simultane Streams sind
     *    physikalisch unmoeglich" war dadurch nicht sauber isoliert.
     * 2. Teil-Ueberlappung: Start von Kamera B haengt an `onImageSecured` (Bild von A geschrieben)
     *    statt an `onDone` (voller Abschluss inkl. Reopen-runter+Verify) — 6,26s -> ~5,4s.
     * 3. ECHTES PARALLEL-CAPTURE (aktueller Stand): nachdem reopenAtSize() sich als zuverlaessig
     *    erwiesen hatte, wurde die "physikalisch unmoeglich"-Annahme erneut getestet — diesmal mit
     *    dem FUNKTIONIERENDEN reopenAtSize()-Mechanismus. Beide Kameras werden jetzt SOFORT
     *    gleichzeitig gestartet (kein Verketten mehr). Bandbreite bleibt bei BEIDEN Kameras
     *    dauerhaft auf [UvcCameraBridge.DUAL_PREVIEW_BANDWIDTH_FACTOR] (0.5) — reopenAtSize()
     *    aendert sie nie (siehe dortiger Kommentar). Live verifiziert, zwei Wiederholungen: 4,03s
     *    und 3,53s Gesamtzeit, beide Dateien je Lauf vollstaendig dekodierbar bei 4656x3496, kein
     *    Absturz, keine "too few scanlines"-Fehler. Die "physikalisch unmoeglich"-Annahme war
     *    schlicht falsch — sie beruhte auf dem konfundierten Test aus Schritt 1.
     *
     * Ist nur EINE Kamera verbunden (z.B. Einzelkamera-Betrieb/Test), wird nur diese
     * aufgenommen — kein Fehler. Die Seitenzahl wird EINMAL pro Doppelseiten-Aufnahme
     * inkrementiert (beide Dateien tragen dieselbe Seitennummer, unterschieden per _L/_R —
     * Namensschema {Projekt}_S{Seite:04d}_{L|R}.jpg).
     *
     * Thread-Sicherheit: die Completion-Callbacks von captureFullResolutionThenReturnToPreview
     * werden via mainHandler auf dem Main-Thread ausgeliefert (siehe UvcCameraBridge) —
     * capturedThisRound/completedCount brauchen deshalb kein Lock, die Callbacks laufen
     * seriell auf dem Main-Looper. Fertigstellungsreihenfolge kann von der Startreihenfolge
     * abweichen (beide Kameras laufen unabhaengig), die Sortierung nach Dateiname am
     * Rundenende macht das irrelevant.
     */
    fun start_capture() {
        if (state != ScannerState.PREVIEW) return
        if (captureInProgress) return

        val bridge = cameraBridge ?: run {
            Log.w(TAG, "start_capture: keine CameraBridge — Capture uebersprungen")
            return
        }
        val connectedCameras = CameraSelection.entries.filter { bridge.isOpened(it) }
        Log.i(TAG, "start_capture: Kamera-Status " +
            CameraSelection.entries.joinToString { "$it=${if (bridge.isOpened(it)) "OFFEN" else "ZU"}" })
        if (connectedCameras.isEmpty()) {
            Log.w(TAG, "start_capture: keine Kamera verbunden — Capture uebersprungen")
            return
        }

        // BUGFIX 2026-07-21 (Root-Cause der 0-Byte-Captures, per nativem Log verifiziert):
        // state = ScannerState.CAPTURE wurde HIER gesetzt, was Compose sofort von
        // PreviewScreen auf CaptureScreen umschaltet — auch wenn beide Screens UvcPreview
        // einbetten, sind es unterschiedliche Stellen im Composable-Baum, Compose disposed
        // also PreviewScreens AndroidView/TextureView und erzeugt eine neue fuer
        // CaptureScreen. Das zerstoert den Surface MITTEN im laufenden Voll-Aufloesungs-
        // Capture. Native Log-Beweis: waehrend Kamera-Thread A den 4656x3496-Capture
        // faehrt, feuert exakt in diesem Fenster ein ZWEITER, konkurrierender
        // startPreview()-Aufruf (640x480) fuer DIESELBE physische Kamera auf einem eigenen
        // nativen Thread (onSurfaceCreated der neuen CaptureScreen-View trifft auf
        // handler.isOpened==true und startet die Preview sofort neu) — zwei gleichzeitige
        // Streams derselben Kamera sorgen dafuer, dass der Capture-Thread nie einen
        // vollstaendigen Frame bekommt ("too few scanlines" endlos, unabhaengig von
        // Aufloesung/Bandbreite/Zweitkamera — all das waren Trugschluesse aus vorherigen
        // Untersuchungen dieser Session). Fix: State bleibt waehrend der gesamten Aufnahme
        // auf PREVIEW (PreviewScreens eigene UvcPreview-Instanzen bleiben unangetastet
        // gebunden), der Fortschritt wird stattdessen als Overlay INNERHALB von
        // PreviewScreen angezeigt (siehe dortiger captureInProgress-Block).
        captureInProgress = true
        val capturedThisRound = mutableListOf<File>()
        captureTotalCount = connectedCameras.size
        captureCompletedCount = 0
        captureFinishedCameras = emptySet()

        val roundStartMs = System.currentTimeMillis()

        // Beide Kameras starten SOFORT gleichzeitig (kein Verketten) — siehe Klassenkommentar
        // oben ("ECHTES PARALLEL-CAPTURE") fuer die Historie/Begruendung.
        fun captureAt(index: Int) {
            if (index >= connectedCameras.size) return
            val camera = connectedCameras[index]
            val fileName = BookscanFileNamer.buildFileName(
                projectName = projectName,
                pageNumber = pageNumber,
                camera = camera
            )
            val targetFile = storageRepository.reserveCaptureFile(fileName)
            bridge.captureFullResolutionThenReturnToPreview(camera, targetFile) {
                storageRepository.publishCapturedFile(targetFile)
                capturedThisRound.add(targetFile)
                captureCompletedCount += 1
                captureFinishedCameras = captureFinishedCameras + camera
                Log.i(TAG, "start_capture: $camera komplett fertig, +${System.currentTimeMillis() - roundStartMs}ms seit Rundenstart ($captureCompletedCount/$captureTotalCount)")
                if (captureCompletedCount >= captureTotalCount) {
                    Log.i(TAG, "start_capture: Runde komplett fertig, Gesamtzeit ${System.currentTimeMillis() - roundStartMs}ms")
                    captureInProgress = false
                    lastCapturedFiles = capturedThisRound.sortedBy { it.name }
                    lastCapturedFile = lastCapturedFiles.lastOrNull()
                    pageNumber += 1
                    state = ScannerState.RECHECK
                }
            }
        }
        connectedCameras.indices.forEach { captureAt(it) }
    }

    /** Nutzer bestaetigt die aufgenommene Seite in RECHECK -> zurueck PREVIEW fuer naechste Seite. */
    fun confirm_and_next_page() {
        if (state != ScannerState.RECHECK) return
        state = ScannerState.PREVIEW
    }

    /** Overlay-Control "Swap Cameras". */
    fun swap_cameras() {
        activeCamera = activeCamera.other()
        cameraBridge?.let { bridge ->
            if (bridge.isOpened(activeCamera)) {
                bridge.startPreview(activeCamera)
            }
        }
        refreshCurrentControls()
    }

    /** Overlay-Control "Recalibrate". */
    fun start_setup() {
        state = ScannerState.SETUP
        refreshCurrentControls()
    }

    /** Verlaesst SETUP zurueck in den PREVIEW-Zustand. */
    fun finish_setup() {
        state = ScannerState.PREVIEW
    }

    /** Setzt den Projektnamen (Datei-Namensschema {Projekt}_S{Seite:04d}_{L|R}.jpg).
     * Ein NEUER Name startet ein neues Buch — Seitenzaehler springt zurueck auf 1.
     * Leerer Name faellt auf den Default "buch" zurueck. */
    fun set_project_name(name: String) {
        val sanitized = name.trim().ifBlank { "buch" }
        if (sanitized != projectName) {
            projectName = sanitized
            pageNumber = 1
        }
    }

    /** Manuelle Korrektur des Seitenzaehlers (z.B. nach verworfener Aufnahme oder beim
     * Fortsetzen eines teilweise gescannten Buchs). Werte < 1 werden auf 1 geklemmt. */
    fun set_page_number(page: Int) {
        pageNumber = page.coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "ScannerViewModel"

        /** Fixer Projektname fuer den Test-Capture-Loop (Debug-Modus, siehe Plan Punkt 5). */
        const val TEST_LOOP_PROJECT_NAME = "autofocus-drift-test"

        /** Start-Gate: Mindestanzahl angeschlossener USB-Kameras, unter der die App
         * vollstaendig blockiert (Doppelseiten-Scanner braucht zwingend beide). */
        const val REQUIRED_CAMERA_COUNT = 2
    }
}
