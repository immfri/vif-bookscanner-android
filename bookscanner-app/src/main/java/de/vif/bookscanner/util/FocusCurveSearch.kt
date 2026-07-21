package de.vif.bookscanner.util

/**
 * Reine, testbare Suchlogik auf der bei der Kalibrierung aufgenommenen
 * Fokus->Schaerfe-Lookup-Kurve (siehe UvcControlSet.focusSweepCurve).
 *
 * REFACTORING 2026-07-21: aus UvcCameraBridge.correctFocusUsingCurve extrahiert, damit die
 * Korrektur-Logik ohne Android-Abhaengigkeiten (Bridge braucht Activity/USB) per JVM-Unit-
 * Test abgesichert werden kann — die Bridge delegiert hierher.
 */
object FocusCurveSearch {

    /**
     * Sucht in [curve] (Fokuswert -> gemessene Schaerfe) im Fenster von
     * +/-[windowPercent] Fokus-Prozentpunkten um [currentFocus] den Punkt mit der
     * hoechsten Schaerfe und liefert dessen Fokuswert. Ist das Fenster leer, wird auf
     * die GESAMTE Kurve zurueckgefallen (besser eine globale Korrektur als keine).
     * null falls die Kurve leer ist (noch nie ein Sweep gelaufen).
     */
    fun bestFocusNear(curve: List<Pair<Int, Double>>, currentFocus: Int, windowPercent: Int): Int? {
        if (curve.isEmpty()) return null
        val window = curve.filter { kotlin.math.abs(it.first - currentFocus) <= windowPercent }
            .ifEmpty { curve }
        return window.maxByOrNull { it.second }?.first
    }
}
