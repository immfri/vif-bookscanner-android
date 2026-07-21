package de.vif.bookscanner.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
 *
 * WICHTIG: `update` wird von Compose bei JEDER Rekomposition aufgerufen (bei einer
 * TextureView, die pro eintreffendem Frame invalidiert, kann das mehrfach pro Sekunde
 * sein) — startPreview() dort unbedingt aufzurufen war reine Verschwendung (natives
 * `handleStartPreview` feuerte alle ~15ms). Native Seite hat zwar einen mIsPreviewing-
 * Guard, aber das Muster ist trotzdem falsch: startPreview() gehoert genau EINMAL
 * ausgeloest (onSurfaceCreated in der Bridge deckt das schon ab), nicht in update().
 *
 * WICHTIG 2 (live gefunden, 2026-07-21): `factory` erfasst `camera` nur beim ERSTEN
 * Erzeugen der AndroidView. Ohne den `key(camera)`-Wrapper unten blieb die TextureView
 * beim Kamera-Wechsel (Switch LEFT<->RIGHT) dauerhaft an die zuerst gebundene Kamera
 * gebunden — sichtbar als eingefrorenes altes Bild UND `bindCameraView()` wurde fuer die
 * neue Kamera nie aufgerufen (Kalibrieren fuer RIGHT schlug deshalb still fehl, weil
 * `viewR` nie gesetzt wurde). `key(camera)` zwingt Compose, die AndroidView bei jedem
 * Kamera-Wechsel zu verwerfen und die `factory` frisch auszufuehren.
 *
 * WICHTIG 3 (live gefunden, 2026-07-21): Zoom/Pan fuer den Pinch-Zoom-Livefeed
 * (SettingsScreen) darf NICHT ueber Compose `Modifier.graphicsLayer(scaleX=..., clip=true)`
 * laufen — das ist ein RenderNode-Transform, und eine hardwarebeschleunigte TextureView
 * (eigene SurfaceTexture, eigenes Compositing-Fenster) wird darin nicht korrekt erfasst:
 * live beobachtet als komplett grauer Kasten (nur der `Color.DarkGray`-Hintergrund der
 * Box, kein Kamerabild) statt eines vergroesserten Bilds. Native View-Properties
 * (`View.scaleX/scaleY/translationX/translationY`, gesetzt im `update`-Block) werden von
 * TextureView dagegen korrekt beruecksichtigt. `zoomState` deckt genau diesen Fall ab;
 * bleibt er null (Standard, z. B. CalibrationScreen), bleibt die View unskaliert 1:1.
 */
data class UvcPreviewZoomState(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f
)

@Composable
fun UvcPreview(
    camera: CameraSelection,
    cameraBridge: UvcCameraBridge,
    viewModel: ScannerViewModel,
    modifier: Modifier = Modifier,
    zoomState: UvcPreviewZoomState? = null
) {
    key(camera) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                UVCCameraTextureView(context).also { view ->
                    view.setAspectRatio(4.0 / 3.0)
                    cameraBridge.bindCameraView(camera, view)
                }
            },
            update = { view ->
                val z = zoomState ?: UvcPreviewZoomState()
                view.scaleX = z.scale
                view.scaleY = z.scale
                view.translationX = z.translationX
                view.translationY = z.translationY
            }
        )
    }
}
