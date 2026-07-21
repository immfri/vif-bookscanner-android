package de.vif.bookscanner.hardware

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandler
import com.serenegiant.widget.CameraViewInterface
import de.vif.bookscanner.state.CameraSelection
import java.io.File

/**
 * Kapselt die komplette libuvccamera-Anbindung fuer die zwei physischen UVC-Kameras
 * (links/rechts). Angelehnt an das Code-Muster von usbCameraTest7/MainActivity.java
 * (USBMonitor + UVCCameraHandler pro Kamera-Slot), auf den Zwei-Zustaende-Bedarf
 * (Preview 320x240 MJPEG / Capture volle Aufloesung mit Pflicht-Mode-Switch) reduziert.
 *
 * UNGETESTET: Diese Klasse wurde ohne angeschlossene Kamera geschrieben. Sie kompiliert
 * gegen die echte libuvccamera-API, aber das tatsaechliche Geraete-Verhalten (Attach-
 * Reihenfolge, Mode-Switch-Timing) ist mit echter Hardware zu verifizieren.
 */
class UvcCameraBridge(
    private val activity: Activity,
    private val previewWidth: Int = 320,
    private val previewHeight: Int = 240,
    private val listener: Listener
) {

    interface Listener {
        /** Wird aufgerufen sobald eine Kamera einem Slot (L/R) zugeordnet und geoeffnet wurde. */
        fun onCameraOpened(camera: CameraSelection)

        /** Wird aufgerufen wenn eine Kamera getrennt/geschlossen wurde. */
        fun onCameraClosed(camera: CameraSelection)

        fun onError(camera: CameraSelection, error: Exception)
    }

    companion object {
        private const val TAG = "UvcCameraBridge"

        /** Volle Sensor-Aufloesung fuer den Capture-Modus (USB2-Bandbreite erzwingt Mode-Switch). */
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720

        private const val CAPTURE_SETTLE_DELAY_MS = 400L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var usbMonitor: USBMonitor? = null

    private var handlerL: UVCCameraHandler? = null
    private var handlerR: UVCCameraHandler? = null

    private var viewL: CameraViewInterface? = null
    private var viewR: CameraViewInterface? = null

    private var pendingSurfaceL: Surface? = null
    private var pendingSurfaceR: Surface? = null

    /** Muss vor [register] aufgerufen werden, sobald die Compose-AndroidView bereit ist. */
    fun bindCameraView(camera: CameraSelection, view: CameraViewInterface) {
        when (camera) {
            CameraSelection.LEFT -> viewL = view
            CameraSelection.RIGHT -> viewR = view
        }
        ensureHandler(camera)
    }

    private fun ensureHandler(camera: CameraSelection) {
        val view = when (camera) {
            CameraSelection.LEFT -> viewL
            CameraSelection.RIGHT -> viewR
        } ?: return

        val existing = handlerFor(camera)
        if (existing != null) return

        val bandwidthFactor = 0.5f // zwei Kameras teilen sich die USB2-Bandbreite (wie usbCameraTest7).
        val newHandler = UVCCameraHandler.createHandler(
            activity, view, previewWidth, previewHeight, bandwidthFactor
        )
        when (camera) {
            CameraSelection.LEFT -> handlerL = newHandler
            CameraSelection.RIGHT -> handlerR = newHandler
        }
    }

    private fun handlerFor(camera: CameraSelection): UVCCameraHandler? = when (camera) {
        CameraSelection.LEFT -> handlerL
        CameraSelection.RIGHT -> handlerR
    }

    /** Registriert den USBMonitor. In Activity.onStart aufrufen. */
    fun register() {
        if (usbMonitor == null) {
            usbMonitor = USBMonitor(activity, deviceConnectListener)
        }
        usbMonitor?.register()
    }

    /** Deregistriert den USBMonitor + schliesst beide Handler. In Activity.onStop aufrufen. */
    fun unregister() {
        handlerL?.close()
        handlerR?.close()
        usbMonitor?.unregister()
    }

    /** Gibt alle nativen Ressourcen frei. In Activity.onDestroy aufrufen. */
    fun release() {
        handlerL = null
        handlerR = null
        usbMonitor?.destroy()
        usbMonitor = null
    }

    fun isOpened(camera: CameraSelection): Boolean = handlerFor(camera)?.isOpened == true

    /** Startet die Live-Vorschau (320x240 MJPEG) fuer den angegebenen Kamera-Slot. */
    fun startPreview(camera: CameraSelection) {
        val handler = handlerFor(camera) ?: return
        val view = when (camera) {
            CameraSelection.LEFT -> viewL
            CameraSelection.RIGHT -> viewR
        } ?: return
        val texture = view.surfaceTexture ?: return
        val surface = Surface(texture)
        when (camera) {
            CameraSelection.LEFT -> pendingSurfaceL = surface
            CameraSelection.RIGHT -> pendingSurfaceR = surface
        }
        handler.startPreview(surface)
    }

    fun stopPreview(camera: CameraSelection) {
        handlerFor(camera)?.stopPreview()
    }

    /**
     * Pflicht-Mode-Switch + Capture: schaltet kurz auf volle Sensor-Aufloesung, nimmt genau
     * einen Frame binaer auf (via UVCCameraHandler#captureStill, kein manuelles Decode/Encode
     * im App-Code) und schaltet danach zurueck auf die 320x240-Vorschau.
     *
     * USB2-Bandbreite erlaubt keinen simultanen Preview+Capture in voller Aufloesung, deshalb
     * der explizite resize()-Umweg (siehe UVCCameraHandler#resize -> AbstractUVCCameraHandler).
     *
     * @param targetFile Zieldatei (Dateiname bereits via BookscanFileNamer gebaut).
     * @param onDone Callback (Main-Thread) nach Abschluss des Mode-Switch-Zyklus.
     */
    fun captureFullResolutionThenReturnToPreview(
        camera: CameraSelection,
        targetFile: File,
        onDone: () -> Unit
    ) {
        val handler = handlerFor(camera)
        if (handler == null || !handler.isOpened) {
            listener.onError(camera, IllegalStateException("Kamera $camera nicht geoeffnet"))
            onDone()
            return
        }

        try {
            handler.resize(CAPTURE_WIDTH, CAPTURE_HEIGHT)
        } catch (e: Exception) {
            Log.w(TAG, "resize auf Capture-Aufloesung fehlgeschlagen, capture mit aktueller Aufloesung", e)
        }

        // Kurze Verzoegerung damit der native Mode-Switch (neues UVC-Streaming-Setup) greift,
        // bevor der Still-Capture ausgeloest wird. Zeitwert ungetestet, mit echter Kamera pruefen.
        mainHandler.postDelayed({
            try {
                handler.captureStill(targetFile.absolutePath)
            } catch (e: Exception) {
                listener.onError(camera, e)
            }

            mainHandler.postDelayed({
                try {
                    handler.resize(previewWidth, previewHeight)
                } catch (e: Exception) {
                    Log.w(TAG, "resize zurueck auf Preview-Aufloesung fehlgeschlagen", e)
                }
                onDone()
            }, CAPTURE_SETTLE_DELAY_MS)
        }, CAPTURE_SETTLE_DELAY_MS)
    }

    private val deviceConnectListener = object : OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.v(TAG, "onAttach: $device")
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
            // Erste noch nicht geoeffnete Kamera wird LEFT, die naechste RIGHT — wie usbCameraTest7.
            val slot = when {
                handlerL?.isOpened != true -> CameraSelection.LEFT
                handlerR?.isOpened != true -> CameraSelection.RIGHT
                else -> null
            } ?: return

            ensureHandler(slot)
            val handler = handlerFor(slot) ?: return
            handler.open(ctrlBlock)
            mainHandler.post {
                startPreview(slot)
                listener.onCameraOpened(slot)
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
            val slot = when {
                handlerL?.isEqual(device) == true -> CameraSelection.LEFT
                handlerR?.isEqual(device) == true -> CameraSelection.RIGHT
                else -> null
            } ?: return

            handlerFor(slot)?.close()
            mainHandler.post { listener.onCameraClosed(slot) }
        }

        override fun onDettach(device: UsbDevice) {
            Log.v(TAG, "onDettach: $device")
        }

        override fun onCancel(device: UsbDevice) {
            Log.v(TAG, "onCancel: $device")
        }
    }
}
