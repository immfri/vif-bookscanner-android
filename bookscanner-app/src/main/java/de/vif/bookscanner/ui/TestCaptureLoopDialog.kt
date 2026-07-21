package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Debug-Feature "Test-Capture-Loop" (Plan Punkt 6, NICHT Teil des normalen Scan-Flows):
 * nimmt eine konfigurierbare automatisierte Aufnahmeserie auf (Default 10 Aufnahmen,
 * 4 Sekunden Abstand), Projektname fix "autofocus-drift-test" — fuer die empirische
 * Fokus-Drift-Analyse aus dem Plan (Rechercheergebnis: Fokus wird primaer durch
 * USB-Reconnect zurueckgesetzt, dieser Loop hilft das ueber viele Aufnahmen zu beobachten).
 */
@Composable
fun TestCaptureLoopDialog(viewModel: ScannerViewModel, onDismiss: () -> Unit) {
    var countText by remember { mutableStateOf("10") }
    var intervalSecText by remember { mutableStateOf("4") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test-Capture-Loop (Debug, kein normaler Scan)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Automatisierte Aufnahmeserie fuer die Fokus-Drift-Analyse. " +
                    "Projekt: autofocus-drift-test.")
                if (!viewModel.testLoopRunning) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter { c -> c.isDigit() } },
                        label = { Text("Anzahl Aufnahmen") }
                    )
                    OutlinedTextField(
                        value = intervalSecText,
                        onValueChange = { intervalSecText = it.filter { c -> c.isDigit() } },
                        label = { Text("Abstand (Sekunden)") }
                    )
                } else {
                    Text("Aufnahme ${viewModel.testLoopCurrentIndex} von ${viewModel.testLoopTotalCount}")
                }
            }
        },
        confirmButton = {
            if (viewModel.testLoopRunning) {
                Button(onClick = { viewModel.stop_test_capture_loop() }) { Text("Stop") }
            } else {
                Button(onClick = {
                    val count = countText.toIntOrNull()?.coerceAtLeast(1) ?: 10
                    val intervalMs = ((intervalSecText.toLongOrNull() ?: 4L).coerceAtLeast(1L)) * 1000L
                    viewModel.start_test_capture_loop(count, intervalMs)
                }) { Text("Start") }
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Schließen") }
        }
    )
}
