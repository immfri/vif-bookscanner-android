package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Color
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

/**
 * Haupt-Scan-Ansicht (State PREVIEW) — UI-Redesign nach User-Vorgabe 2026-07-21:
 * das KOMPLETTE Display zeigt links + rechts die beiden Live-Streams (L/R, unverzerrt im
 * 4:3-Seitenverhaeltnis skaliert, jeweils inkl. per-Kamera-180-Grad-Rotation). In der
 * Mitte entsteht durch die 4:3-Kacheln auf dem 20:9-Display ein freier vertikaler Balken —
 * dort sitzt als Overlay der grosse Capture-Button, darueber App-Name + Projekt/Seiten-Info,
 * darunter der Einstellungen-Button (oeffnet den Settings-Screen mit L/R-Tab-Struktur, die
 * Live-View dieses Screens wird dabei ersetzt).
 */
@Composable
fun PreviewScreen(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Beide Live-Streams nebeneinander, je halbe Breite, 4:3 unverzerrt.
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            CameraSelection.entries.forEach { camera ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    UvcPreview(
                        camera = camera,
                        cameraBridge = cameraBridge,
                        viewModel = viewModel,
                        rotated180 = viewModel.isRotated180(camera),
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    )
                }
            }
        }

        // Overlay-Spalte im mittleren freien Balken.
        Column(
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "VIF Bookscanner",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "\"${viewModel.projectName}\" — Seite ${"%04d".format(viewModel.pageNumber)}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { viewModel.start_capture() },
                enabled = !viewModel.captureInProgress
            ) {
                Text(if (viewModel.captureInProgress) "..." else "Capture")
            }
            Button(onClick = { viewModel.start_setup() }) {
                Text("Einstellungen")
            }

            // BUGFIX 2026-07-21 (siehe ScannerViewModel.start_capture-Kommentar): Fortschritt
            // als Overlay HIER statt als eigener CaptureScreen — ein Screen-Wechsel wuerde
            // die gerade aktiven UvcPreview-Surfaces mitten im Voll-Aufloesungs-Capture
            // zerstoeren und den nativen Stream abbrechen lassen.
            if (viewModel.captureInProgress) {
                Text(
                    "AUFNAHME LÄUFT — NICHT BEWEGEN",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall
                )
                val total = viewModel.captureTotalCount
                val done = viewModel.captureCompletedCount
                if (total > 1) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("$done von $total Kameras fertig", color = Color.White)
                CameraSelection.entries.forEach { camera ->
                    val finished = camera in viewModel.captureFinishedCameras
                    Text(
                        text = if (finished) "Kamera $camera: ✓ fertig" else "Kamera $camera: läuft ...",
                        color = if (finished) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
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
 *
 * KRITISCH (Root-Cause-Fix, live gefunden 2026-07-21): dieser Screen MUSS die UvcPreview-
 * Views BEIDER Kameras eingebettet behalten. Ohne sie disposed Compose beim State-Wechsel
 * PREVIEW -> CAPTURE die AndroidView des PreviewScreens -> deren TextureView-Surface wird
 * mitten im laufenden Capture zerstoert ("onSurfaceDestroy" exakt zwischen handleResize und
 * handleCaptureStill im Log) -> der native UVC-Stream bricht ab -> weder der Raw-Frame-
 * Callback noch der TextureView-Fallback bekommen je einen Frame (leere 0-KB-Datei trotz
 * korrekt durchlaufender State-Machine). Die Mini-Live-Feeds unten halten die Surfaces am
 * Leben UND zeigen dem Nutzer live, was gerade aufgenommen wird.
 */
@Composable
fun CaptureScreen(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
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

        // Surfaces beider Kameras am Leben halten (siehe Funktions-Kommentar) + Live-Blick
        // auf das, was gerade aufgenommen wird.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CameraSelection.entries.forEach { camera ->
                UvcPreview(
                    camera = camera,
                    cameraBridge = cameraBridge,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).height(160.dp)
                )
            }
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
