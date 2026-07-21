package de.vif.bookscanner.ui

import android.graphics.RectF
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.CameraSelection
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Settings-Screen gemaess genauer Vorgabe (vif-bookscanner-gui-layer.md):
 * - Oben: Kamera-Auswahl-Dropdown + Switch-Button (KEINE zwei Tabs L/R).
 * - Darunter: Split-Layout — Einstellungen links, Live-Feed rechts.
 * - Live-Feed mit Pinch-Zoom (Default 5x, Bildmitte) + 1-Finger-Pan ueber der echten
 *   UVC-Kamera-Vorschau ([UvcPreview]) der aktuell ausgewaehlten Kamera.
 */
@Composable
fun SettingsScreen(viewModel: ScannerViewModel, cameraBridge: UvcCameraBridge) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showTestLoopDialog by remember { mutableStateOf(false) }

    if (showTestLoopDialog) {
        TestCaptureLoopDialog(viewModel = viewModel, onDismiss = { showTestLoopDialog = false })
    }

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
            // verticalScroll (Fix 2026-07-21): ohne Scroll ragte das Toleranzband-Eingabefeld
            // samt Auto-Retry-Checkbox (SharpnessCheckSettings) im Landscape-Layout unten aus
            // dem Bildschirm — live beobachtet als unerreichbare/abgeschnittene Elemente.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Einstellungen", style = MaterialTheme.typography.titleMedium)

                // Projekt/Buch-Verwaltung: Name geht 1:1 ins Dateinamensschema
                // {Projekt}_S{Seite:04d}_{L|R}.jpg; neuer Name = neues Buch (Seite 1).
                ProjectSettings(viewModel)

                Text("Orientierung: ${viewModel.orientation}")
                OutlinedButton(onClick = { viewModel.set_orientation() }) {
                    Text("Rotate 180°")
                }
                // Vollstaendiger UVC-Parametersatz (Auto/Manuell) lebt im eigenen
                // CalibrationScreen (State CALIBRATION, "Recalibrate"-Button in PREVIEW).
                OutlinedButton(onClick = { viewModel.start_calibration() }) {
                    Text("Kalibrierung öffnen")
                }
                // Eigener Debug-Einstiegspunkt (NICHT der normale Scan-Flow, siehe Plan Punkt 5):
                // automatisierte Test-Aufnahmeserie zur empirischen Fokus-Drift-Analyse.
                OutlinedButton(onClick = { showTestLoopDialog = true }) {
                    Text("Test: Autofokus-Drift-Serie")
                }

                SharpnessCheckSettings(cameraBridge)
            }

            LiveFeedPinchZoom(
                camera = viewModel.activeCamera,
                cameraBridge = cameraBridge,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Echte UVC-Kamera-Vorschau ([UvcPreview]) mit Pinch-Zoom (Default 5x, zentriert auf die
 * Bildmitte) und 1-Finger-Pan via graphicsLayer-Transform auf der TextureView selbst.
 */
@Composable
private fun LiveFeedPinchZoom(
    camera: CameraSelection,
    cameraBridge: UvcCameraBridge,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    val defaultZoom = 5f
    var scale by remember { mutableStateOf(defaultZoom) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }

    fun publishFocusRegion() {
        cameraBridge.setFocusRegion(
            camera,
            approximateFocusRegionCrop(boxSizePx, scale, offsetX, offsetY)
        )
    }

    Box(
        modifier = modifier
            .background(Color.DarkGray)
            // clipToBounds: ohne das malt die skalierte Vorschau (bis 10x) ueber die
            // Box-Grenzen hinaus und ueberdeckt den ganzen Bildschirm inkl. Settings-Panel
            // und Kamera-Auswahl-Leiste links (live beobachtet: 5x-Zoom fuellte den kompletten
            // Screen statt nur die rechte Haelfte des Split-Layouts).
            .clipToBounds()
            .onSizeChanged { boxSizePx = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 10f)
                    offsetX += pan.x
                    offsetY += pan.y
                    // Regionsbezogene Schaerfemessung (Punkt 2): jede Zoom/Pan-Geste aktualisiert
                    // den Fokus-Bildausschnitt fuer Sweep + Pro-Aufnahme-Checks sofort mit.
                    publishFocusRegion()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        UvcPreview(
            camera = camera,
            cameraBridge = cameraBridge,
            viewModel = viewModel,
            zoomState = UvcPreviewZoomState(
                scale = scale,
                translationX = offsetX,
                translationY = offsetY
            )
        )
        Text(
            text = "Zoom: ${"%.1f".format(scale)}x",
            color = Color.White
        )
    }
}

/**
 * Projekt/Buch-Einstellungen: Projektname (Dateinamensschema) + Seitenzaehler mit
 * manueller Korrekturmoeglichkeit (z.B. Fortsetzen eines halb gescannten Buchs).
 */
@Composable
private fun ProjectSettings(viewModel: ScannerViewModel) {
    var nameText by remember { mutableStateOf(viewModel.projectName) }
    var pageText by remember(viewModel.pageNumber) { mutableStateOf(viewModel.pageNumber.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = nameText,
            onValueChange = { input ->
                nameText = input
                viewModel.set_project_name(input)
            },
            label = { Text("Projekt / Buchname") }
        )
        OutlinedTextField(
            value = pageText,
            onValueChange = { input ->
                pageText = input.filter { it.isDigit() }
                pageText.toIntOrNull()?.let { viewModel.set_page_number(it) }
            },
            label = { Text("Nächste Seitennummer") }
        )
    }
}

/**
 * Naeherungsweise Umrechnung des Pinch-Zoom/Pan-Zustands des Live-Feeds (Compose-Pixel-
 * Transform auf der TextureView) in einen FRAKTIONALEN Bildausschnitt (0f..1f je Achse,
 * siehe [UvcCameraBridge.setFocusRegion]) fuer die regionsbezogene Schaerfemessung/den
 * Fokus-Sweep (Punkt 2). Fraktional statt Pixel-Rect, weil derselbe Ausschnitt sowohl auf
 * die Preview- als auch auf die Capture-Aufloesung angewendet werden muss (Punkt 3,
 * zweistufige Messung) — [UvcCameraBridge] rechnet ihn pro Messung auf die tatsaechliche
 * Bitmap-Groesse um.
 *
 * ANNAHME/VEREINFACHUNG (dokumentiert wie vom Auftrag gefordert, da keine exakte 1:1-Umrechnung
 * ohne echtes Geraet moeglich ist): die TextureView zeigt den Kamera-Stream verzerrungsfrei und
 * ohne zusaetzliches Letterboxing durch die zugrundeliegende AspectRatio-View — das
 * Sichtfenster (boxSizePx) entspricht direkt dem sichtbaren Bruchteil des Kamerabilds.
 * scale=1 => volles Bild sichtbar, scale=5 (Default-Zoom) => 1/5 der Breite/Hoehe sichtbar,
 * zentriert auf die per Pan verschobene Bildmitte. Falls die reale AspectRatio-View tatsaechlich
 * Letterboxing/Cropping macht (abhaengig vom Seitenverhaeltnis des Kamerastreams vs. Box), ist
 * das Ergebnis nur eine Naeherung — fuer den Zweck (Sweep/Check auf "ungefaehr den betrachteten
 * Bereich" statt Vollbild) ausreichend, siehe Auftrag ("kein Overengineering").
 */
private fun approximateFocusRegionCrop(
    boxSizePx: IntSize,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): RectF? {
    if (boxSizePx.width <= 0 || boxSizePx.height <= 0 || scale <= 1f) return null

    val visibleFraction = (1f / scale).coerceIn(0.01f, 1f)

    // offsetX/offsetY sind Bildschirm-Pixel-Translation der BEREITS skalierten Vorschau ->
    // zurueck auf unskalierte Box-Pixel-Verschiebung rechnen, dann als Bruchteil der Box.
    val centerFracX = (0.5f - (offsetX / scale) / boxSizePx.width).coerceIn(visibleFraction / 2f, 1f - visibleFraction / 2f)
    val centerFracY = (0.5f - (offsetY / scale) / boxSizePx.height).coerceIn(visibleFraction / 2f, 1f - visibleFraction / 2f)

    val left = (centerFracX - visibleFraction / 2f).coerceIn(0f, 1f - visibleFraction)
    val top = (centerFracY - visibleFraction / 2f).coerceIn(0f, 1f - visibleFraction)

    return RectF(left, top, left + visibleFraction, top + visibleFraction)
}

private fun CameraSelection.label(): String = when (this) {
    CameraSelection.LEFT -> "Links (L)"
    CameraSelection.RIGHT -> "Rechts (R)"
}

/**
 * Einstellungen fuer den Pro-Aufnahme-Schaerfe-Check (KORREKTUR 2026-07-21, ersetzt das
 * urspruenglich geplante feste "alle N Aufnahmen neu kalibrieren"-Feld): Toleranzband in
 * Prozent gegen den Kalibrier-Referenzwert + Umschalter fuer automatischen Retry.
 */
@Composable
private fun SharpnessCheckSettings(cameraBridge: UvcCameraBridge) {
    var toleranceText by remember { mutableStateOf(cameraBridge.getSharpnessToleranceBandPercent().toString()) }
    var autoRetry by remember { mutableStateOf(cameraBridge.getAutoRetryEnabled()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Schärfe-Check (pro Aufnahme)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = toleranceText,
            onValueChange = { input ->
                toleranceText = input.filter { it.isDigit() }
                toleranceText.toIntOrNull()?.let { cameraBridge.setSharpnessToleranceBandPercent(it) }
            },
            label = { Text("Toleranzband (%)") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autoRetry,
                onCheckedChange = {
                    autoRetry = it
                    cameraBridge.setAutoRetryEnabled(it)
                }
            )
            Text("Automatischer Retry bei zu geringer Schärfe")
        }
    }
}

// TestCaptureLoopDialog lebt in einer eigenen Datei: ui/TestCaptureLoopDialog.kt
