package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.ScannerState
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Wurzel-Composable: routet je nach [ScannerState] auf den passenden Screen.
 * [cameraBridge] wird bis zu den Screens durchgereicht, die echte Live-Vorschau brauchen
 * (Preview/Settings) — CAPTURE/RECHECK zeigen nur noch das Ergebnis.
 *
 * START-GATE (User-Vorgabe 2026-07-21): VOR jedem Screen wird geprueft, ob beide
 * USB-Kameras angeschlossen sind. Mit weniger als 2 Kameras laeuft die App NICHT weiter —
 * es erscheint ausschliesslich der [DeviceGateScreen] mit der Fehlermeldung. Das Gate
 * greift auch mitten im Betrieb (Kamera abgezogen -> App blockiert sofort wieder), die
 * Freigabe erfolgt automatisch, sobald beide Kameras (wieder) erkannt sind.
 */
@Composable
fun BookscannerApp(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
    if (viewModel.attachedCameraCount < ScannerViewModel.REQUIRED_CAMERA_COUNT) {
        DeviceGateScreen(found = viewModel.attachedCameraCount)
        return
    }
    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        when (viewModel.state) {
            ScannerState.CALIBRATION -> CalibrationScreen(viewModel, cameraBridge)
            ScannerState.LOCK -> LockScreen(viewModel)
            ScannerState.PREVIEW -> PreviewScreen(viewModel, cameraBridge)
            // KORREKTUR 2026-07-21: der fruehere "Root-Cause-Fix" (CaptureScreen behaelt
            // eigene UvcPreviews) loeste das Problem NICHT — ein Screen-WECHSEL selbst
            // zerstoert bereits PreviewScreens AndroidView/TextureView, unabhaengig davon
            // was der neue Screen rendert (native Log bewies: konkurrierender zweiter
            // startPreview()-Aufruf waehrend des Voll-Aufloesungs-Captures). Tatsaechlicher
            // Fix: ScannerViewModel.start_capture() wechselt state nicht mehr auf CAPTURE,
            // dieser Zweig wird dadurch nie mehr erreicht (Fortschritt zeigt PreviewScreen
            // jetzt selbst als Overlay). CaptureScreen bleibt nur als exhaustive-when-
            // Pflichtzweig bestehen.
            ScannerState.CAPTURE -> CaptureScreen(viewModel, cameraBridge)
            ScannerState.RECHECK -> RecheckScreen(viewModel)
            ScannerState.SETUP -> SettingsScreen(viewModel, cameraBridge)
        }
    }
}

/**
 * Blockier-Screen des Start-Gates: erscheint solange weniger als 2 USB-Kameras
 * angeschlossen sind (beim Start UND bei Kamera-Verlust im Betrieb). Kein Weiter-Button —
 * die App gibt sich erst frei, wenn beide Kameras erkannt wurden (USB-Attach-Events).
 */
@Composable
private fun DeviceGateScreen(found: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Kameras nicht gefunden",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "Es wurden $found von ${ScannerViewModel.REQUIRED_CAMERA_COUNT} benötigten " +
                "USB-Kameras erkannt. Der Buchscanner benötigt zwingend beide Kameras " +
                "(linke und rechte Buchseite).\n\n" +
                "Bitte beide Kameras über den USB-Hub anschließen — die App startet " +
                "automatisch, sobald beide erkannt wurden."
        )
    }
}
