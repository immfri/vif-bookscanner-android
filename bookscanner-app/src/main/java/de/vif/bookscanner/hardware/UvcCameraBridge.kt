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

    // Falls onConnect() feuert BEVOR die Compose-UvcPreview (und damit bindCameraView) fuer
    // diesen Slot existiert (z.B. waehrend die App noch auf CALIBRATION/LOCK steht): das
    // UsbControlBlock zwischenspeichern und beim spaeteren bindCameraView() nachholen, statt
    // die Verbindung stillschweigend zu verlieren.
    private var pendingCtrlBlockL: UsbControlBlock? = null
    private var pendingCtrlBlockR: UsbControlBlock? = null

    /** Muss vor [register] aufgerufen werden, sobald die Compose-AndroidView bereit ist. */
    fun bindCameraView(camera: CameraSelection, view: CameraViewInterface) {
        when (camera) {
            CameraSelection.LEFT -> viewL = view
            CameraSelection.RIGHT -> viewR = view
        }
        ensureHandler(camera)
        openPendingConnectionIfAny(camera)
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

        // WICHTIG: view.surfaceTexture ist direkt nach View-Erzeugung meist noch null (die
        // TextureView-Surface wird erst asynchron nach dem Attach/Layout verfuegbar). Ohne
        // diesen Callback bricht startPreview() beim ersten Versuch (Kamera-open-Zeitpunkt)
        // still ab und wird nie erneut aufgerufen -> Preview bleibt dauerhaft ein grauer Block.
        view.setCallback(object : CameraViewInterface.Callback {
            override fun onSurfaceCreated(v: CameraViewInterface, surface: Surface) {
                Log.d(TAG, "onSurfaceCreated fuer $camera — starte Preview falls Kamera offen")
                when (camera) {
                    CameraSelection.LEFT -> pendingSurfaceL = surface
                    CameraSelection.RIGHT -> pendingSurfaceR = surface
                }
                if (handlerFor(camera)?.isOpened == true) {
                    startPreview(camera)
                }
            }

            override fun onSurfaceChanged(v: CameraViewInterface, surface: Surface, width: Int, height: Int) {}

            override fun onSurfaceDestroy(v: CameraViewInterface, surface: Surface) {
                Log.d(TAG, "onSurfaceDestroy fuer $camera")
            }
        })
    }

    private fun openPendingConnectionIfAny(camera: CameraSelection) {
        val ctrlBlock = when (camera) {
            CameraSelection.LEFT -> pendingCtrlBlockL
            CameraSelection.RIGHT -> pendingCtrlBlockR
        } ?: return
        val handler = handlerFor(camera) ?: return
        if (handler.isOpened) return

        Log.d(TAG, "openPendingConnectionIfAny: hole nachgeholte Verbindung fuer $camera nach")
        when (camera) {
            CameraSelection.LEFT -> pendingCtrlBlockL = null
            CameraSelection.RIGHT -> pendingCtrlBlockR = null
        }
        try {
            handler.open(ctrlBlock)
            mainHandler.post {
                startPreview(camera)
                listener.onCameraOpened(camera)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openPendingConnectionIfAny: Handler.open() fuer $camera fehlgeschlagen", e)
            listener.onError(camera, e)
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
        // view.getSurface() ist die vom CameraViewInterface.Callback (onSurfaceCreated)
        // bereitgestellte Surface — robuster als getSurfaceTexture(), das direkt nach
        // View-Erzeugung meist noch null ist (siehe ensureHandler-Kommentar).
        val surface = if (view.hasSurface()) view.surface else null
        if (surface == null) {
            Log.d(TAG, "startPreview($camera): Surface noch nicht bereit, warte auf onSurfaceCreated")
            return
        }
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
            // Nur fuer noch freie Kamera-Slots (L/R) um Permission fragen — sonst wuerde bei
            // jedem beliebigen dritten USB-Geraet unnoetig ein Dialog aufpoppen.
            val freeSlotAvailable = handlerL?.isOpened != true || handlerR?.isOpened != true
            if (freeSlotAvailable) {
                usbMonitor?.requestPermission(device)
            }
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
            Log.d(TAG, "onConnect: $device createNew=$createNew")
            // Ein Slot gilt als "belegt", sobald er entweder schon offen ist ODER schon einen
            // nachzuholenden ControlBlock wartend haelt (verhindert, dass zwei Geraete beide
            // auf LEFT gemapped werden, wenn onConnect fuer beide feuert bevor irgendeine
            // UvcPreview-View existiert).
            val leftTaken = handlerL?.isOpened == true || pendingCtrlBlockL != null
            val rightTaken = handlerR?.isOpened == true || pendingCtrlBlockR != null
            val slot = when {
                !leftTaken -> CameraSelection.LEFT
                !rightTaken -> CameraSelection.RIGHT
                else -> null
            }
            if (slot == null) {
                Log.d(TAG, "onConnect: kein freier Slot mehr, Geraet wird ignoriert")
                return
            }
            Log.d(TAG, "onConnect: Slot $slot zugewiesen, oeffne Handler")

            ensureHandler(slot)
            val handler = handlerFor(slot)
            if (handler == null) {
                // UvcPreview (bindCameraView) fuer diesen Slot existiert noch nicht (z.B. App
                // steht noch auf CALIBRATION/LOCK) — ControlBlock zwischenspeichern, wird in
                // bindCameraView()/openPendingConnectionIfAny() nachgeholt statt verworfen.
                Log.d(TAG, "onConnect: kein Handler fuer Slot $slot, ControlBlock wird zwischengespeichert")
                when (slot) {
                    CameraSelection.LEFT -> pendingCtrlBlockL = ctrlBlock
                    CameraSelection.RIGHT -> pendingCtrlBlockR = ctrlBlock
                }
                return
            }
            try {
                handler.open(ctrlBlock)
                Log.d(TAG, "onConnect: Handler.open() fuer $slot aufgerufen")
            } catch (e: Exception) {
                Log.e(TAG, "onConnect: Handler.open() fuer $slot fehlgeschlagen", e)
                listener.onError(slot, e)
                return
            }
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
