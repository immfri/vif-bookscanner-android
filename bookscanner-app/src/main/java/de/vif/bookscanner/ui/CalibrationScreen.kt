package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.vif.bookscanner.hardware.UvcControlSet
import de.vif.bookscanner.state.CalibrationMode
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Kalibrier-Screen (State CALIBRATION): Umschalter Auto/Manuell.
 *
 * Automodus: ein Knopf loest [ScannerViewModel.run_auto_calibration] aus (Auto-Fokus/Auto-WB
 * fahren lassen, kurz warten, auslesen, Auto abschalten, Wert fixieren + Referenz-Schaerfe
 * speichern — siehe [de.vif.bookscanner.hardware.UvcCameraBridge.calibrateAuto]).
 *
 * Manueller Modus: Slider fuer den kompletten UVC-Parametersatz, jede Aenderung wird sofort
 * live auf den Treiber geschrieben (Vorgabe: Live-Vorschau reagiert sofort).
 *
 * UNGETESTET: Kalibrier-Verhalten (Einschwingzeit, tatsaechliche Wertebereiche) ist mit
 * echter Hardware zu verifizieren.
 */
@Composable
fun CalibrationScreen(viewModel: ScannerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Kalibrierung", style = MaterialTheme.typography.headlineSmall)
        Text("Kamera: ${viewModel.activeCamera}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.set_calibration_mode(CalibrationMode.AUTO) },
                enabled = viewModel.calibrationMode != CalibrationMode.AUTO
            ) { Text("Automodus") }
            Button(
                onClick = { viewModel.set_calibration_mode(CalibrationMode.MANUAL) },
                enabled = viewModel.calibrationMode != CalibrationMode.MANUAL
            ) { Text("Manueller Modus") }
        }

        if (viewModel.calibrationMode == CalibrationMode.AUTO) {
            AutoCalibrationSection(viewModel)
        } else {
            ManualCalibrationSection(viewModel)
        }

        Button(onClick = { viewModel.start_setup() }) {
            Text("Weiter zu LOCK")
        }
    }
}

@Composable
private fun AutoCalibrationSection(viewModel: ScannerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Automodus fährt Auto-Fokus und Auto-Weißabgleich, wartet auf das Einschwingen, " +
                "liest die erreichten Werte aus und fixiert sie fest (keine Drift zwischen Aufnahmen)."
        )
        Button(
            onClick = { viewModel.run_auto_calibration() },
            enabled = !viewModel.calibrationInProgress
        ) {
            Text(if (viewModel.calibrationInProgress) "Kalibriere ..." else "Kalibrieren")
        }
        val referenceSharpness = viewModel.currentControls.referenceSharpness
        if (referenceSharpness > 0.0) {
            Text("Referenz-Schärfe: ${"%.1f".format(referenceSharpness)}")
        }
    }
}

@Composable
private fun ManualCalibrationSection(viewModel: ScannerViewModel) {
    val controls = viewModel.currentControls
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlSlider("Fokus", controls.focus) { viewModel.update_manual_control(controls.copy(focus = it, focusAuto = false)) }
        ControlSlider("Weißabgleich", controls.whiteBalance) { viewModel.update_manual_control(controls.copy(whiteBalance = it, whiteBalanceAuto = false)) }
        ControlSlider("Helligkeit", controls.brightness) { viewModel.update_manual_control(controls.copy(brightness = it)) }
        ControlSlider("Kontrast", controls.contrast) { viewModel.update_manual_control(controls.copy(contrast = it)) }
        ControlSlider("Schärfe", controls.sharpness) { viewModel.update_manual_control(controls.copy(sharpness = it)) }
        ControlSlider("Gain", controls.gain) { viewModel.update_manual_control(controls.copy(gain = it)) }
        ControlSlider("Gamma", controls.gamma) { viewModel.update_manual_control(controls.copy(gamma = it)) }
        ControlSlider("Sättigung", controls.saturation) { viewModel.update_manual_control(controls.copy(saturation = it)) }
        ControlSlider("Hue", controls.hue) { viewModel.update_manual_control(controls.copy(hue = it)) }
        ControlSlider("Zoom", controls.zoom) { viewModel.update_manual_control(controls.copy(zoom = it)) }

        Text(
            "Hinweis: Der Pro-Aufnahme-Schärfe-Check vergleicht gegen die Referenz aus der " +
                "letzten Automodus-Kalibrierung. Im manuellen Modus läuft der Check nur, wenn " +
                "vorher schon einmal automatisch kalibriert wurde.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ControlSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
