package de.vif.bookscanner.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import de.vif.bookscanner.util.BookscanFileNamer

/**
 * Zentrales ViewModel der Buchscanner-App.
 *
 * Kapselt die 6-Zustaende-State-Machine (siehe [ScannerState]) und die Events aus der
 * GUI-Doku (vif-bookscanner-gui-layer.md). Die eigentliche Kamera-Anbindung (libuvccamera)
 * ist an dieser Stelle bewusst noch NICHT verdrahtet — TODO-Marker zeigen, wo sie reinkommt,
 * sobald der Treiber-Fix (paralleler Vorgang) steht.
 */
class ScannerViewModel : ViewModel() {

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

    /** Startet die Kalibrierung, danach automatisch LOCK (Vorgabe State-Machine). */
    fun start_calibration() {
        state = ScannerState.CALIBRATION
        // TODO(libuvccamera): beide Kameras oeffnen, Belichtung/Weissabgleich angleichen.
    }

    /** Loest den Wechsel PREVIEW -> CAPTURE -> zurueck PREVIEW aus (1 Frame, volle Aufloesung). */
    fun start_capture() {
        if (state != ScannerState.PREVIEW) return
        state = ScannerState.CAPTURE
        // TODO(libuvccamera): Pflicht-Mode-Switch auf volle Sensor-Aufloesung (USB2-Bandbreite
        // erlaubt keinen simultanen Preview+Capture-High-Res), 1 Frame binaer schreiben
        // (kein Decode/Encode), Dateiname via BookscanFileNamer, danach zurueck PREVIEW.
        val fileName = BookscanFileNamer.buildFileName(
            projectName = projectName,
            pageNumber = pageNumber,
            camera = activeCamera
        )
        // TODO(storage): fileName an das StorageRepository uebergeben.
        pageNumber += 1
        state = ScannerState.RECHECK
    }

    /** Nutzer bestaetigt die aufgenommene Seite in RECHECK -> zurueck PREVIEW fuer naechste Seite. */
    fun confirm_and_next_page() {
        if (state != ScannerState.RECHECK) return
        state = ScannerState.PREVIEW
    }

    /** Overlay-Control "Swap Cameras". */
    fun swap_cameras() {
        activeCamera = activeCamera.other()
        // TODO(libuvccamera): aktiven Stream auf die jeweils andere Kamera umschalten.
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
}
