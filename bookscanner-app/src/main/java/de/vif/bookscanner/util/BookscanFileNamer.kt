package de.vif.bookscanner.util

import de.vif.bookscanner.state.CameraSelection

/**
 * Erzeugt Dateinamen nach dem Schema {Projektname}_S{Seitenzahl:04d}_{L|R}.jpg.
 *
 * Zeitstempel gehoert bewusst NICHT in den Dateinamen (Vorgabe), sondern in EXIF beim
 * eigentlichen Schreiben der Datei (siehe TODO in ScannerViewModel/StorageRepository).
 */
object BookscanFileNamer {

    fun buildFileName(projectName: String, pageNumber: Int, camera: CameraSelection): String {
        require(projectName.isNotBlank()) { "projectName darf nicht leer sein" }
        require(pageNumber >= 0) { "pageNumber darf nicht negativ sein" }

        val cameraLetter = when (camera) {
            CameraSelection.LEFT -> "L"
            CameraSelection.RIGHT -> "R"
        }
        val paddedPage = pageNumber.toString().padStart(4, '0')
        return "${projectName}_S${paddedPage}_${cameraLetter}.jpg"
    }
}
