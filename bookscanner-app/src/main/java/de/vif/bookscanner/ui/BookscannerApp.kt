package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.ScannerState
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Wurzel-Composable: routet je nach [ScannerState] auf den passenden Screen.
 * [cameraBridge] wird bis zu den Screens durchgereicht, die echte Live-Vorschau brauchen
 * (Preview/Settings) — CAPTURE/RECHECK zeigen nur noch das Ergebnis.
 */
@Composable
fun BookscannerApp(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when (viewModel.state) {
            ScannerState.CALIBRATION -> CalibrationScreen(viewModel, cameraBridge)
            ScannerState.LOCK -> LockScreen(viewModel)
            ScannerState.PREVIEW -> PreviewScreen(viewModel, cameraBridge)
            // cameraBridge auch hier: CaptureScreen MUSS die UvcPreviews beider Kameras
            // eingebettet behalten, sonst stirbt die TextureView-Surface mitten im Capture
            // (Root-Cause-Fix 2026-07-21, siehe CaptureScreen-Kommentar).
            ScannerState.CAPTURE -> CaptureScreen(viewModel, cameraBridge)
            ScannerState.RECHECK -> RecheckScreen(viewModel)
            ScannerState.SETUP -> SettingsScreen(viewModel, cameraBridge)
        }
    }
}
