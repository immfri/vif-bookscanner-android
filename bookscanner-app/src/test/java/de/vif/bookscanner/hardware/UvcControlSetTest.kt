package de.vif.bookscanner.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UvcControlSetTest {

    @Test
    fun `Roundtrip Serialisierung erhaelt alle Felder`() {
        val original = UvcControlSet(
            focus = 42, focusAuto = false,
            brightness = 60, contrast = 55, sharpness = 70,
            gain = 30, gamma = 45, saturation = 65, hue = 40,
            whiteBalance = 52, whiteBalanceAuto = false, zoom = 10,
            referenceSharpness = 1234.56,
            referenceSharpnessPreview = 78.9,
            focusSweepCurve = listOf(0 to 10.5, 50 to 200.25, 100 to 5.0)
        )
        val restored = UvcControlSet.fromPrefsString(original.toPrefsString())
        assertEquals(original, restored)
    }

    @Test
    fun `Default-Instanz uebersteht Roundtrip`() {
        val original = UvcControlSet()
        assertEquals(original, UvcControlSet.fromPrefsString(original.toPrefsString()))
    }

    @Test
    fun `null und Leerstring liefern null`() {
        assertNull(UvcControlSet.fromPrefsString(null))
        assertNull(UvcControlSet.fromPrefsString(""))
        assertNull(UvcControlSet.fromPrefsString("   "))
    }

    @Test
    fun `fehlende Felder fallen auf Defaults zurueck`() {
        val restored = UvcControlSet.fromPrefsString("focus=33;whiteBalance=77")
        val defaults = UvcControlSet()
        assertEquals(33, restored!!.focus)
        assertEquals(77, restored.whiteBalance)
        assertEquals(defaults.brightness, restored.brightness)
        assertEquals(defaults.focusSweepCurve, restored.focusSweepCurve)
    }

    @Test
    fun `korrupter Zahlenwert liefert null statt Crash`() {
        assertNull(UvcControlSet.fromPrefsString("focus=abc;brightness=50"))
    }

    @Test
    fun `korrupte Kurveneintraege werden uebersprungen statt zu crashen`() {
        val restored = UvcControlSet.fromPrefsString("focus=10;focusSweepCurve=5:1.5,kaputt,10:2.5")
        assertEquals(listOf(5 to 1.5, 10 to 2.5), restored!!.focusSweepCurve)
    }
}
