package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Platzhalter-Screens fuer die 6 State-Machine-Zustaende. Reine UI-Struktur — echte
 * Kamera-Vorschau/-Capture kommt erst, sobald der libuvccamera-Treiber-Fix steht.
 */

@Composable
fun CalibrationScreen(viewModel: ScannerViewModel) {
    PlaceholderScaffold(
        title = "Kalibrierung",
        description = "TODO: beide Kameras oeffnen, Belichtung/Weissabgleich angleichen."
    ) {
        Button(onClick = { viewModel.start_setup() }) {
            Text("Weiter zu LOCK")
        }
    }
}

@Composable
fun LockScreen(viewModel: ScannerViewModel) {
    PlaceholderScaffold(
        title = "Gesperrt (LOCK)",
        description = "Kamera-Parameter fixiert. Buch einlegen und Vorschau starten."
    ) {
        Button(onClick = { viewModel.finish_setup() }) {
            Text("Zur Vorschau (PREVIEW)")
        }
    }
}

@Composable
fun PreviewScreen(viewModel: ScannerViewModel) {
    PlaceholderScaffold(
        title = "Vorschau (PREVIEW, 320x240 MJPEG)",
        description = "TODO: Live-Vorschau des libuvccamera-Streams."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.start_capture() }) {
                Text("Capture")
            }
            Button(onClick = { viewModel.swap_cameras() }) {
                Text("Swap Cameras (aktuell: ${viewModel.activeCamera})")
            }
            Button(onClick = { viewModel.set_orientation() }) {
                Text("Rotate 180° (aktuell: ${viewModel.orientation})")
            }
            Button(onClick = { viewModel.start_setup() }) {
                Text("Recalibrate / Setup")
            }
        }
    }
}

@Composable
fun CaptureScreen(viewModel: ScannerViewModel) {
    PlaceholderScaffold(
        title = "Aufnahme (CAPTURE, volle Aufloesung)",
        description = "TODO: Pflicht-Mode-Switch, 1 Frame binaer schreiben, kein Decode/Encode."
    ) {
        Text("Seite ${viewModel.pageNumber} wird aufgenommen ...")
    }
}

@Composable
fun RecheckScreen(viewModel: ScannerViewModel) {
    PlaceholderScaffold(
        title = "Pruefung (RECHECK)",
        description = "TODO: zuletzt aufgenommenes Bild anzeigen."
    ) {
        Button(onClick = { viewModel.confirm_and_next_page() }) {
            Text("Next Page")
        }
    }
}

@Composable
private fun PlaceholderScaffold(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(text = description)
        content()
    }
}
