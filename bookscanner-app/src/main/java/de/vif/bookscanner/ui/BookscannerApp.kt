package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import de.vif.bookscanner.state.ScannerState
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Wurzel-Composable: routet je nach [ScannerState] auf den passenden Screen.
 * Alle Screens (ausser Settings) sind reine Platzhalter, bis die Kamera-Anbindung
 * (libuvccamera-Treiber-Fix, paralleler Vorgang) steht.
 */
@Composable
fun BookscannerApp(viewModel: ScannerViewModel) {
    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when (viewModel.state) {
            ScannerState.CALIBRATION -> CalibrationScreen(viewModel)
            ScannerState.LOCK -> LockScreen(viewModel)
            ScannerState.PREVIEW -> PreviewScreen(viewModel)
            ScannerState.CAPTURE -> CaptureScreen(viewModel)
            ScannerState.RECHECK -> RecheckScreen(viewModel)
            ScannerState.SETUP -> SettingsScreen(viewModel)
        }
    }
}
