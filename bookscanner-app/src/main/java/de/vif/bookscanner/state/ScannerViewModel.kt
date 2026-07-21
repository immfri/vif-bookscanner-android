package de.vif.bookscanner.state

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.storage.ScanStorageRepository
import de.vif.bookscanner.util.BookscanFileNamer
import java.io.File

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

    var lastCapturedFile by mutableStateOf<File?>(null)
        private set

    /** Startet die Kalibrierung, danach automatisch LOCK (Vorgabe State-Machine). */
    fun start_calibration() {
        state = ScannerState.CALIBRATION
        // Kameras oeffnen sich automatisch ueber UvcCameraBridge.onConnect (USBMonitor-Callback),
        // sobald sie am Geraet angeschlossen werden. Belichtung/Weissabgleich-Angleichung ist
        // ueber die UVCCamera-Value-APIs (setValue/getValue) moeglich, aber noch nicht verdrahtet.
    }

    /** Loest den Wechsel PREVIEW -> CAPTURE -> zurueck PREVIEW aus (1 Frame, volle Aufloesung). */
    fun start_capture() {
        if (state != ScannerState.PREVIEW) return
        if (captureInProgress) return

        val bridge = cameraBridge
        val fileName = BookscanFileNamer.buildFileName(
            projectName = projectName,
            pageNumber = pageNumber,
            camera = activeCamera
        )

        if (bridge == null || !bridge.isOpened(activeCamera)) {
            Log.w(TAG, "start_capture: Kamera $activeCamera nicht verbunden — Capture uebersprungen")
            return
        }

        state = ScannerState.CAPTURE
        captureInProgress = true

        // Pflicht-Mode-Switch auf volle Sensor-Aufloesung (USB2-Bandbreite erlaubt keinen
        // simultanen Preview+Capture-High-Res), 1 Frame binaer schreiben (kein Decode/Encode
        // im App-Code, das erledigt UVCCameraHandler#captureStill nativ), danach zurueck PREVIEW.
        val targetFile = storageRepository.reserveCaptureFile(fileName)
        bridge.captureFullResolutionThenReturnToPreview(activeCamera, targetFile) {
            captureInProgress = false
            storageRepository.publishCapturedFile(targetFile)
            lastCapturedFile = targetFile
            pageNumber += 1
            state = ScannerState.RECHECK
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
    }

    /** Overlay-Control "Rotate 180°". */
    fun set_orientation(newOrientation: PageOrientation = orientation.toggled()) {
        orientation = newOrientation
    }

    /** Overlay-Control "Recalibrate". */
    fun start_setup() {
        state = ScannerState.SETUP
    }

    /** Verlaesst SETUP zurueck in den PREVIEW-Zustand. */
    fun finish_setup() {
        state = ScannerState.PREVIEW
    }

    fun set_project_name(name: String) {
        projectName = name
    }

    companion object {
        private const val TAG = "ScannerViewModel"
    }
}
