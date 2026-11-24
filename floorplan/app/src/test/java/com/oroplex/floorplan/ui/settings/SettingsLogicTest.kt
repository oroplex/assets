package com.oroplex.floorplan.ui.settings

import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun `lengths parse in the user's units`() {
        assertEquals(0.1143, parseSettingLength("4 1/2\"", UnitSystem.IMPERIAL, allowZero = false)!!, 1e-9)
        assertEquals(2.44, parseSettingLength("244 cm", UnitSystem.METRIC, allowZero = false)!!, 1e-9)
        assertEquals(2.4384, parseSettingLength("8'", UnitSystem.IMPERIAL, allowZero = false)!!, 1e-9)
    }

    @Test
    fun `zero is accepted only where allowed`() {
        assertNull(parseSettingLength("0", UnitSystem.IMPERIAL, allowZero = false))
        for (text in listOf("0", "0 in", "0.0 m", "0,0", "00\"")) {
            assertEquals(text, 0.0, parseSettingLength(text, UnitSystem.METRIC, allowZero = true)!!, 0.0)
        }
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(parseSettingLength("tall", UnitSystem.METRIC, allowZero = true))
        assertNull(parseSettingLength("", UnitSystem.IMPERIAL, allowZero = true))
        assertNull(parseSettingLength("-3 ft", UnitSystem.IMPERIAL, allowZero = true))
    }
}
