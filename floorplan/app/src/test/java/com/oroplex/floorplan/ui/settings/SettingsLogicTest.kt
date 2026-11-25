package com.oroplex.floorplan.ui.settings

import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the exterior wall field shows the thickness in effect`() {
        assertEquals(0.1651, SettingsRules.exteriorWallThickness(PlanSettings()), 0.0)
        assertEquals(0.30, SettingsRules.exteriorWallThickness(PlanSettings(exteriorWallThickness = 0.30)), 0.0)
        // Not set, or not a usable thickness: overall dimensions use the interior wall thickness.
        val interior = PlanSettings(wallThickness = 0.12, exteriorWallThickness = null)
        assertEquals(0.12, SettingsRules.exteriorWallThickness(interior), 0.0)
        assertEquals(0.12, SettingsRules.exteriorWallThickness(interior.copy(exteriorWallThickness = 0.0)), 0.0)
        assertEquals(0.12, SettingsRules.exteriorWallThickness(interior.copy(exteriorWallThickness = Double.NaN)), 0.0)
    }

    @Test
    fun `the exterior wall thickness is explained by what it is used for`() {
        assertTrue(SettingsRules.EXTERIOR_WALL_HELP.startsWith("Used for overall dimensions"))
        assertTrue(SettingsRules.EXTERIOR_WALL_HELP.contains("outside of the exterior walls"))
    }

    @Test
    fun `the interior wall help quotes a typical thickness in the user's units`() {
        assertTrue(SettingsRules.interiorWallHelp(UnitSystem.IMPERIAL).contains("4 1/2\""))
        assertTrue(SettingsRules.interiorWallHelp(UnitSystem.METRIC).contains("10 cm"))
        // Neither mixes in the other system (the metric screen used to say 4 1/2" next to a 10 cm field).
        assertTrue(!SettingsRules.interiorWallHelp(UnitSystem.METRIC).contains("\""))
        assertTrue(!SettingsRules.interiorWallHelp(UnitSystem.IMPERIAL).contains("cm"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(parseSettingLength("tall", UnitSystem.METRIC, allowZero = true))
        assertNull(parseSettingLength("", UnitSystem.IMPERIAL, allowZero = true))
        assertNull(parseSettingLength("-3 ft", UnitSystem.IMPERIAL, allowZero = true))
    }
}
