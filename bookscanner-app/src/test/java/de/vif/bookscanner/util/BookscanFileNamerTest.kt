package de.vif.bookscanner.util

import de.vif.bookscanner.state.CameraSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookscanFileNamerTest {

    @Test
    fun `linke Kamera erzeugt korrektes Schema`() {
        val name = BookscanFileNamer.buildFileName("goethe-faust", 7, CameraSelection.LEFT)
        assertEquals("goethe-faust_S0007_L.jpg", name)
    }

    @Test
    fun `rechte Kamera erzeugt korrektes Schema`() {
        val name = BookscanFileNamer.buildFileName("goethe-faust", 12, CameraSelection.RIGHT)
        assertEquals("goethe-faust_S0012_R.jpg", name)
    }

    @Test
    fun `Seitenzahl wird auf vier Stellen gepaddet`() {
        val name = BookscanFileNamer.buildFileName("projekt", 1, CameraSelection.LEFT)
        assertEquals("projekt_S0001_L.jpg", name)
    }

    @Test
    fun `Seitenzahl ueber 9999 wird nicht abgeschnitten`() {
        val name = BookscanFileNamer.buildFileName("projekt", 12345, CameraSelection.LEFT)
        assertEquals("projekt_S12345_L.jpg", name)
    }

    @Test
    fun `leerer Projektname wirft Exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            BookscanFileNamer.buildFileName("", 1, CameraSelection.LEFT)
        }
    }

    @Test
    fun `negative Seitenzahl wirft Exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            BookscanFileNamer.buildFileName("projekt", -1, CameraSelection.LEFT)
        }
    }
}
