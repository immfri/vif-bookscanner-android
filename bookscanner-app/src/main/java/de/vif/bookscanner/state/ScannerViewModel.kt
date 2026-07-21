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

    var activeCamera by mutableStateOf(CameraSelection.LEFT)
        private set

    var orientation by mutableStateOf(PageOrientation.NORMAL)
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
        }
    }

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

    /** Debug-Diagnose (Plan Punkt 4): stoesst [UvcCameraBridge.diagnoseModeSwitchSettleTime]
     * fuer die aktive Kamera an — loggt Schaerfe+Zeitstempel pro Frame nach dem Moduswechsel,
     * um den echten minimalen CAPTURE_SETTLE_DELAY_MS-Wert empirisch zu bestimmen statt zu
     * raten. Ergebnis wird nur ins Logcat geschrieben (kein UI-Feedback noetig, User wertet
     * das Log beim naechsten Live-Test aus). */
    fun run_settle_diagnosis() {
        val bridge = cameraBridge ?: return
        if (!bridge.isOpened(activeCamera)) {
            Log.w(TAG, "run_settle_diagnosis: Kamera $activeCamera nicht verbunden")
            return
        }
        bridge.diagnoseModeSwitchSettleTime(activeCamera)
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
     * Loest die DOPPELSEITEN-Aufnahme aus (Kern-Workflow des Buchscanners): nimmt
     * PARALLEL BEIDE verbundenen Kameras (LEFT + RIGHT) in voller Aufloesung auf —
     * eine Blaetterung = eine linke + eine rechte Buchseite.
     *
     * PARALLEL statt sequenziell (User-Vorgabe, 2026-07-21): jede Kamera laeuft ohnehin auf
     * ihrem EIGENEN CameraThread (UVCCameraHandler.createHandler spawnt je einen), und der
     * dominante Zeitanteil pro Aufnahme sind WARTEZEITEN (Mode-Switch-Settle ~2s + Warten
     * auf den ersten Voll-Aufloesungs-Frame ~1,9s), nicht die Datenuebertragung — diese
     * Wartezeiten ueberlappen sich bei paralleler Ausfuehrung fast vollstaendig (~halbierte
     * Gesamtzeit pro Doppelseite). Bandbreite: beide Handler sind mit bandwidthFactor 0.5
     * konfiguriert, reservieren also je die halbe USB2-Bandbreite — ein gleichzeitiger
     * Betrieb ist damit von vornherein eingeplant. Hinweis USB-Topologie: auch an einem
     * USB3-Hub teilen sich USB2-Geraete upstream EINEN gemeinsamen 480-Mbit/s-USB2-Baum
     * (USB2-Signale laufen nicht ueber die USB3-Lanes) — der Gewinn kommt aus der
     * Wartezeit-Ueberlappung, nicht aus doppelter Bandbreite.
     *
     * Ist nur EINE Kamera verbunden (z.B. Einzelkamera-Betrieb/Test), wird nur diese
     * aufgenommen — kein Fehler. Die Seitenzahl wird EINMAL pro Doppelseiten-Aufnahme
     * inkrementiert (beide Dateien tragen dieselbe Seitennummer, unterschieden per _L/_R —
     * Namensschema {Projekt}_S{Seite:04d}_{L|R}.jpg).
     *
     * Thread-Sicherheit: die Completion-Callbacks von captureFullResolutionThenReturnToPreview
     * werden via mainHandler auf dem Main-Thread ausgeliefert (siehe UvcCameraBridge) —
     * capturedThisRound/completedCount brauchen deshalb kein Lock, die Callbacks laufen
     * seriell auf dem Main-Looper.
     */
    fun start_capture() {
        if (state != ScannerState.PREVIEW) return
        if (captureInProgress) return

        val bridge = cameraBridge ?: run {
            Log.w(TAG, "start_capture: keine CameraBridge — Capture uebersprungen")
            return
        }
        val connectedCameras = CameraSelection.entries.filter { bridge.isOpened(it) }
        if (connectedCameras.isEmpty()) {
            Log.w(TAG, "start_capture: keine Kamera verbunden — Capture uebersprungen")
            return
        }

        state = ScannerState.CAPTURE
        captureInProgress = true
        val capturedThisRound = mutableListOf<File>()
        captureTotalCount = connectedCameras.size
        captureCompletedCount = 0
        captureFinishedCameras = emptySet()

        connectedCameras.forEach { camera ->
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
                if (captureCompletedCount >= captureTotalCount) {
                    captureInProgress = false
                    // Stabile L-vor-R-Reihenfolge fuer die Anzeige, unabhaengig davon,
                    // welche Kamera zuerst fertig wurde.
                    lastCapturedFiles = capturedThisRound.sortedBy { it.name }
                    lastCapturedFile = lastCapturedFiles.lastOrNull()
                    pageNumber += 1
                    state = ScannerState.RECHECK
                }
            }
        }
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

    /** Overlay-Control "Rotate 180°". */
    fun set_orientation(newOrientation: PageOrientation = orientation.toggled()) {
        orientation = newOrientation
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
    }
}
