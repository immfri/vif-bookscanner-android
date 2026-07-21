package de.vif.bookscanner.hardware

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min

/**
 * Misst die Bildschaerfe eines Live-Frames rein in Kotlin (kein OpenCV noetig) ueber die
 * Varianz eines diskreten Laplace-Filters auf dem Graustufenbild — Standardmass fuer
 * Autofokus-/Schaerfe-Beurteilung ("Variance of Laplacian").
 *
 * Verwendung: (1) bei der Kalibrierung einen Referenzwert auf dem frisch fixierten Fokus
 * messen, (2) vor JEDER Aufnahme die aktuelle Schaerfe erneut messen und gegen den
 * Referenzwert mit Toleranzband vergleichen (siehe [UvcCameraBridge.verifyFocusBeforeCapture]).
 *
 * Downscale auf max. 240px Kantenlaenge vor der Berechnung — die absolute Schaerfe ist fuer
 * den Vergleich irrelevant (nur relativ zum eigenen Referenzwert), Downscale haelt die
 * Pro-Capture-Messung schnell genug fuer den Live-Pfad.
 */
object SharpnessAnalyzer {

    private const val MAX_EDGE_PX = 240

    /**
     * Liefert die Laplace-Varianz von [bitmap] als Schaerfemass (hoeher = schaerfer).
     *
     * @param cropRect optionaler Bildausschnitt in Bitmap-Pixelkoordinaten (z.B. der vom User
     * im Live-Feed herangezoomte/ausgewaehlte Bereich, siehe [de.vif.bookscanner.hardware.UvcCameraBridge]
     * setFocusRegion). Wird VOR dem Downscale angewendet und robust gegen die Bitmap-Grenzen
     * geclampt. null = Vollbild (bisheriges Verhalten).
     */
    fun laplacianVariance(bitmap: Bitmap, cropRect: Rect? = null): Double {
        val cropped = cropRect?.let { crop(bitmap, it) } ?: bitmap
        val scaled = downscale(cropped)
        val gray = toGrayscale(scaled)
        val width = scaled.width
        val height = scaled.height

        if (scaled !== cropped) scaled.recycle()
        if (cropped !== bitmap) cropped.recycle()

        if (width < 3 || height < 3) return 0.0

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Diskreter Laplace-Kernel [[0,1,0],[1,-4,1],[0,1,0]], klassischer 4-Nachbarn-Kernel.
        for (y in 1 until height - 1) {
            val rowUp = (y - 1) * width
            val row = y * width
            val rowDown = (y + 1) * width
            for (x in 1 until width - 1) {
                val lap = gray[rowUp + x] + gray[rowDown + x] +
                    gray[row + x - 1] + gray[row + x + 1] - 4 * gray[row + x]
                sum += lap
                sumSq += lap.toDouble() * lap.toDouble()
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /**
     * Vergleicht [measured] gegen [reference] mit einem prozentualen Toleranzband.
     * Beispiel: reference=500, toleranceBandPercent=30 -> Bereich [350, 650] gilt als "in
     * Toleranz" (Schaerfe darf leicht schwanken, aber nicht signifikant einbrechen).
     * Nur die UNTERE Grenze wird tatsaechlich als Fehlerfall gewertet — schaerfer als die
     * Referenz ist nie ein Problem.
     */
    fun isWithinTolerance(measured: Double, reference: Double, toleranceBandPercent: Int): Boolean {
        if (reference <= 0.0) return true // noch nie kalibriert -> nichts zu vergleichen
        val lowerBound = reference * (1.0 - toleranceBandPercent / 100.0)
        return measured >= lowerBound
    }

    /** Schneidet [rect] aus [bitmap] aus, robust gegen Rechtecke die (teilweise) ausserhalb
     * der Bitmap liegen — wird geclampt statt zu crashen. Liefert [bitmap] unveraendert
     * zurueck falls das geclampte Rechteck degeneriert (Breite/Hoehe <= 0). */
    private fun crop(bitmap: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toDouble() / longestEdge
        val newWidth = maxOf(3, (bitmap.width * scale).toInt())
        val newHeight = maxOf(3, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun toGrayscale(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Standard-Luminanzgewichtung (Rec. 601), min() nur zur defensiven Array-Sicherheit.
            gray[i] = min(255, (r * 299 + g * 587 + b * 114) / 1000)
        }
        return gray
    }
}
