package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.CameraSelection
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Screens fuer die 6 State-Machine-Zustaende. PreviewScreen nutzt bereits die echte
 * UVC-Kamera-Vorschau ([UvcPreview]); CaptureScreen/RecheckScreen zeigen den realen
 * Capture-Fortschritt bzw. den Dateipfad der zuletzt aufgenommenen Seite.
 */

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
fun PreviewScreen(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
    PlaceholderScaffold(
        title = "Vorschau (PREVIEW)",
        description = "Projekt \"${viewModel.projectName}\" — nächste Aufnahme: Seite " +
            "%04d".format(viewModel.pageNumber) +
            " (beide verbundenen Kameras L+R). Live-Bild: ${viewModel.activeCamera}."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UvcPreview(
                camera = viewModel.activeCamera,
                cameraBridge = cameraBridge,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
            Button(
                onClick = { viewModel.start_capture() },
                enabled = !viewModel.captureInProgress
            ) {
                Text(if (viewModel.captureInProgress) "Capture laeuft ..." else "Capture")
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

/**
 * Aufnahme-Screen (State CAPTURE) mit klarer Nutzer-Signalisierung (User-Vorgabe 2026-07-21):
 * grosser "NICHT BEWEGEN"-Hinweis + Fortschrittsbalken + Pro-Kamera-Status, solange die
 * (parallele) Doppelseiten-Aufnahme laeuft. Sobald beide Kameras fertig sind, wechselt die
 * State-Machine automatisch zu RECHECK — dort steht das explizite "Fertig, jetzt
 * umblaettern"-Signal (siehe [RecheckScreen]).
 */
@Composable
fun CaptureScreen(viewModel: ScannerViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AUFNAHME LÄUFT — NICHT BEWEGEN",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text("Seite ${"%04d".format(viewModel.pageNumber)} wird in voller Auflösung aufgenommen.")

        val total = viewModel.captureTotalCount
        val done = viewModel.captureCompletedCount
        if (total > 1) {
            // Determinierter Balken: springt pro fertiger Kamera (0 -> 0,5 -> 1,0).
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Einzelkamera: keine Zwischenschritte messbar -> laufender Balken.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("$done von $total Kameras fertig")

        CameraSelection.entries.forEach { camera ->
            val finished = camera in viewModel.captureFinishedCameras
            Text(
                text = if (finished) "Kamera $camera: ✓ fertig" else "Kamera $camera: läuft ...",
                color = if (finished) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun RecheckScreen(viewModel: ScannerViewModel) {
    val files = viewModel.lastCapturedFiles
    PlaceholderScaffold(
        title = "Aufnahme abgeschlossen ✓",
        description = if (files.isEmpty()) {
            "Keine Aufnahmen in dieser Runde."
        } else {
            "Live-Vorschau ist wieder aktiv — Seite jetzt umblättern, dann \"Next Page\"."
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            files.forEach { file ->
                Text("${file.name}  (${file.length() / 1024} KB)")
            }
            Button(onClick = { viewModel.confirm_and_next_page() }) {
                Text("Next Page")
            }
        }
    }
}

@Composable
private fun PlaceholderScaffold(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    // verticalScroll: ohne das ragte der PreviewScreen-Inhalt (UvcPreview erzwingt per
    // setAspectRatio eine groessere Hoehe als die angeforderten 240dp) unten aus dem
    // Bildschirm heraus — "Rotate 180°" und "Recalibrate / Setup" waren dann zwar in der
    // Compose-Baumreihenfolge vorhanden, aber ausserhalb des sichtbaren/antippbaren Bereichs
    // (live gefunden: ein Tap auf die vermutete "Capture"-Position traf tatsaechlich
    // "Recalibrate / Setup", weil nur die LETZTEN beiden Buttons ueberhaupt im Viewport lagen).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(text = description)
        content()
    }
}
