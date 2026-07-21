package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.serenegiant.widget.UVCCameraTextureView
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.CameraSelection
import de.vif.bookscanner.state.ScannerViewModel

/**
 * Bindet eine echte [UVCCameraTextureView] an [UvcCameraBridge] fuer den angegebenen
 * Kamera-Slot (L/R). Sobald die View erzeugt ist, meldet sie sich bei der Bridge an
 * ([UvcCameraBridge.bindCameraView]) — die Bridge oeffnet/startet die Vorschau, sobald
 * die passende USB-Kamera per USBMonitor verbunden wird (siehe onConnect in der Bridge).
 */
@Composable
fun UvcPreview(
    camera: CameraSelection,
    cameraBridge: UvcCameraBridge,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            UVCCameraTextureView(context).also { view ->
                view.setAspectRatio(4.0 / 3.0)
                cameraBridge.bindCameraView(camera, view)
            }
        },
        update = {
            if (cameraBridge.isOpened(camera)) {
                cameraBridge.startPreview(camera)
            }
        }
    )
}
