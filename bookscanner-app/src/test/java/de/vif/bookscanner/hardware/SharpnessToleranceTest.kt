package de.vif.bookscanner.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testet die reine Toleranzband-Logik des Pro-Aufnahme-Schaerfe-Checks
 * ([SharpnessAnalyzer.isWithinTolerance]) — kein Android-API noetig. */
class SharpnessToleranceTest {

    @Test
    fun `ohne Kalibrierung (Referenz 0) ist alles innerhalb Toleranz`() {
        assertTrue(SharpnessAnalyzer.isWithinTolerance(measured = 0.0, reference = 0.0, toleranceBandPercent = 30))
        assertTrue(SharpnessAnalyzer.isWithinTolerance(measured = 999.0, reference = -1.0, toleranceBandPercent = 30))
    }

    @Test
    fun `Messwert exakt auf der Untergrenze ist noch innerhalb`() {
        // Referenz 100, Band 30% -> Untergrenze 70.
        assertTrue(SharpnessAnalyzer.isWithinTolerance(70.0, 100.0, 30))
    }

    @Test
    fun `Messwert knapp unter der Untergrenze ist ausserhalb`() {
        assertFalse(SharpnessAnalyzer.isWithinTolerance(69.9, 100.0, 30))
    }

    @Test
    fun `Messwert ueber der Referenz ist immer innerhalb`() {
        assertTrue(SharpnessAnalyzer.isWithinTolerance(150.0, 100.0, 30))
    }

    @Test
    fun `Band 0 Prozent verlangt mindestens die Referenz selbst`() {
        assertTrue(SharpnessAnalyzer.isWithinTolerance(100.0, 100.0, 0))
        assertFalse(SharpnessAnalyzer.isWithinTolerance(99.9, 100.0, 0))
    }

    @Test
    fun `Band 100 Prozent laesst alles ab 0 durch`() {
        assertTrue(SharpnessAnalyzer.isWithinTolerance(0.0, 100.0, 100))
    }
}
