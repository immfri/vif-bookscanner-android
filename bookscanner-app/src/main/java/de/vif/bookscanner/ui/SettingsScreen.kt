package de.vif.bookscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.state.CameraSelection
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Settings-Screen gemaess genauer Vorgabe (vif-bookscanner-gui-layer.md):
 * - Oben: Kamera-Auswahl-Dropdown + Switch-Button (KEINE zwei Tabs L/R).
 * - Darunter: Split-Layout — Einstellungen links, Live-Feed rechts.
 * - Live-Feed in Capture-Aufloesung mit Pinch-Zoom (Default 5x, Bildmitte) + 1-Finger-Pan.
 *
 * TODO(libuvccamera): den Mock-Preview-Block unten rechts durch den echten Live-Frame-Stream
 * der aktuell ausgewaehlten Kamera ersetzen, sobald der Treiber-Fix steht.
 */
@Composable
fun SettingsScreen(viewModel: ScannerViewModel) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // --- Oben: Kamera-Auswahl-Dropdown + Switch-Button ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box {
                OutlinedButton(onClick = { dropdownExpanded = true }) {
                    Text("Kamera: ${viewModel.activeCamera.label()}")
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    CameraSelection.entries.forEach { camera ->
                        DropdownMenuItem(
                            text = { Text(camera.label()) },
                            onClick = {
                                if (camera != viewModel.activeCamera) viewModel.swap_cameras()
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Button(onClick = { viewModel.swap_cameras() }) {
                Text("Switch")
            }

            Button(onClick = { viewModel.finish_setup() }) {
                Text("Fertig")
            }
        }

        // --- Split-Layout: Einstellungen links, Live-Feed rechts ---
        Row(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Einstellungen", style = MaterialTheme.typography.titleMedium)
                Text("Orientierung: ${viewModel.orientation}")
                OutlinedButton(onClick = { viewModel.set_orientation() }) {
                    Text("Rotate 180°")
                }
                // TODO: weitere Kamera-Parameter (Belichtung, Weissabgleich, Fokus) ergaenzen,
                // sobald der Treiber entsprechende Steuer-APIs bereitstellt.
            }

            LiveFeedPinchZoom(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Mock-Live-Feed mit Pinch-Zoom (Default 5x, zentriert auf die Bildmitte) und 1-Finger-Pan.
 * Ersetzt eine echte Kamera-Textur nur durch eine farbige Flaeche, bis der UVC-Treiber steht.
 */
@Composable
private fun LiveFeedPinchZoom(modifier: Modifier = Modifier) {
    val defaultZoom = 5f
    var scale by remember { mutableStateOf(defaultZoom) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 10f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Live-Feed (Capture-Aufloesung, Mock)\nZoom: ${"%.1f".format(scale)}x",
            color = Color.White,
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        )
    }
}

private fun CameraSelection.label(): String = when (this) {
    CameraSelection.LEFT -> "Links (L)"
    CameraSelection.RIGHT -> "Rechts (R)"
}
