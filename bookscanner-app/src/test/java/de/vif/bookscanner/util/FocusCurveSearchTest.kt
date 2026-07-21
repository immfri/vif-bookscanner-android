package de.vif.bookscanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusCurveSearchTest {

    private val curve = listOf(
        0 to 10.0, 10 to 30.0, 20 to 80.0, 30 to 150.0,
        40 to 220.0, 50 to 180.0, 60 to 90.0, 70 to 40.0, 80 to 15.0
    )

    @Test
    fun `leere Kurve liefert null`() {
        assertNull(FocusCurveSearch.bestFocusNear(emptyList(), 50, 15))
    }

    @Test
    fun `findet lokalen Peak im Fenster um den aktuellen Fokus`() {
        // Fenster 50 +/- 15 -> Kandidaten 40..60, Peak dort ist 40 (220.0).
        assertEquals(40, FocusCurveSearch.bestFocusNear(curve, 50, 15))
    }

    @Test
    fun `Fenster schneidet globalen Peak ab und bleibt lokal`() {
        // Fenster 70 +/- 10 -> Kandidaten 60..80, lokaler Peak 60 (90.0), NICHT global 40.
        assertEquals(60, FocusCurveSearch.bestFocusNear(curve, 70, 10))
    }

    @Test
    fun `leeres Fenster faellt auf gesamte Kurve zurueck`() {
        // currentFocus weit ausserhalb aller Kurvenpunkte mit winzigem Fenster ->
        // Fallback auf globalen Peak (40).
        assertEquals(40, FocusCurveSearch.bestFocusNear(curve, 100, 5))
    }

    @Test
    fun `einzelner Kurvenpunkt wird geliefert`() {
        assertEquals(35, FocusCurveSearch.bestFocusNear(listOf(35 to 42.0), 0, 1))
    }
}
