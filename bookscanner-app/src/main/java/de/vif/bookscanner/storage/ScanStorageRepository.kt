package de.vif.bookscanner.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Kapselt das Speichern der gescannten Buchseiten im normalen Android-Speicherpfad
 * (direkt im Datei-Browser sichtbar, kein SSH/Remote noetig — Vorgabe).
 *
 * Annahme (dokumentiert, siehe root build.gradle versionMinSdk = 24): Ab API 29 (Android 10)
 * wird ausschliesslich MediaStore mit Scoped Storage verwendet (RELATIVE_PATH), da das
 * Zielgeraet Xiaomi 11T Pro 5G auf Android 12+ laeuft und WRITE_EXTERNAL_STORAGE ab API 29
 * fuer eigene MediaStore-Eintraege nicht mehr noetig ist. Der SAF-Zweig (API < 29) ist als
 * Stub belassen, da minSdk 24 nur aus Kompatibilitaetsgruenden im Root-build.gradle steht und
 * auf dem Testgeraet nie durchlaufen wird — TODO falls ein Geraet < Android 10 relevant wird.
 */
class ScanStorageRepository(private val context: Context) {

    /** Unterordner unterhalb von Pictures/, in dem alle Scans landen. */
    private val relativeSubfolder = "VifBookscanner"

    /**
     * Reserviert eine Ziel-Datei fuer [de.vif.bookscanner.hardware.UvcCameraBridge]#captureStill.
     *
     * UVCCameraHandler#captureStill(path) schreibt die JPEG-Bytes des Kamera-Treibers selbst
     * direkt auf einen Dateipfad (kein manuelles Decode/Encode im App-Code) — dafuer braucht es
     * einen echten File-Pfad statt eines MediaStore-Uri/OutputStream. App-eigener externer
     * Speicher (getExternalFilesDir) ist ab API 29 ohne Extra-Berechtigung beschreibbar und
     * braucht keine Scoped-Storage-Klammer (IS_PENDING) fuer den Schreibvorgang selbst.
     */
    fun reserveCaptureFile(fileName: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), relativeSubfolder)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    /**
     * Macht eine per [reserveCaptureFile] geschriebene Datei im normalen Android-Datei-Browser
     * sichtbar (Vorgabe: kein SSH/Remote noetig). MediaScannerConnection triggert einen
     * MediaStore-Reindex, ohne die Bytes erneut zu lesen/zu kopieren.
     */
    fun publishCapturedFile(file: File) {
        if (!file.exists()) return
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    /**
     * Schreibt die rohen MJPEG-Bytes eines einzelnen Frames binaer in eine neue Bilddatei.
     * Kein Decode/Encode im Capture-Pfad (Vorgabe) — die Bytes werden 1:1 durchgereicht.
     *
     * @param fileName Ergebnis von [de.vif.bookscanner.util.BookscanFileNamer.buildFileName].
     * @param jpegBytes rohe MJPEG/JPEG-Frame-Daten aus dem UVC-Treiber.
     * @return die entstandene Content-Uri, oder null falls das Anlegen fehlschlug.
     */
    fun saveFrame(fileName: String, jpegBytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(fileName, jpegBytes)
        } else {
            // TODO(SAF/Legacy < API 29): auf dem Testgeraet (Android 12+) nicht relevant,
            // siehe Klassen-Kommentar. Bei Bedarf: SAF-DocumentFile oder Legacy-File-API.
            null
        }
    }

    private fun saveViaMediaStore(fileName: String, jpegBytes: ByteArray): Uri? {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$relativeSubfolder"
            )
            // TODO(EXIF): Zeitstempel gehoert in EXIF, nicht in den Dateinamen (Vorgabe).
            // Sobald echte Frame-Bytes vorliegen: ExifInterface auf dem OutputStream/Datei
            // setzen (DATETIME_ORIGINAL), bevor IS_PENDING zurueckgesetzt wird.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        val stream: OutputStream? = resolver.openOutputStream(itemUri)
        stream?.use { it.write(jpegBytes) }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        return itemUri
    }
}
