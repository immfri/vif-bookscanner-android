package de.vif.bookscanner

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import de.vif.bookscanner.hardware.UvcCameraBridge
import de.vif.bookscanner.state.CameraSelection
import de.vif.bookscanner.state.ScannerViewModel
import de.vif.bookscanner.ui.BookscannerApp

/**
 * Erzeugt die echte [UvcCameraBridge] (braucht eine Activity fuer USBMonitor/UVCCameraHandler,
 * siehe usbCameraTest7/MainActivity.java als Referenzmuster) und haengt sie an das ViewModel.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ScannerViewModel by viewModels()

    private lateinit var cameraBridge: UvcCameraBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraBridge = UvcCameraBridge(
            activity = this,
            listener = object : UvcCameraBridge.Listener {
                override fun onCameraOpened(camera: CameraSelection) {
                    Toast.makeText(
                        this@MainActivity, "Kamera $camera verbunden", Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onCameraClosed(camera: CameraSelection) {
                    Toast.makeText(
                        this@MainActivity, "Kamera $camera getrennt", Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(camera: CameraSelection, error: Exception) {
                    Toast.makeText(
                        this@MainActivity, "Kamera-Fehler ($camera): ${error.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        viewModel.cameraBridge = cameraBridge

        setContent {
            MaterialTheme {
                Surface {
                    BookscannerApp(viewModel = viewModel, cameraBridge = cameraBridge)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cameraBridge.register()
    }

    override fun onStop() {
        cameraBridge.unregister()
        super.onStop()
    }

    override fun onDestroy() {
        cameraBridge.release()
        super.onDestroy()
    }
}
