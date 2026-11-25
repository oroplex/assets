package com.oroplex.floorplan.ui.projects

import com.oroplex.floorplan.core.measure.AreaSummary
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.data.ProjectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectCardTest {
    private fun summary(area: Double) = ProjectSummary("p", "House", "", 0L, 3, 1, area)

    @Test
    fun theCardShowsLivingAreaWhenSomeRoomsAreNotLivingSpace() {
        val all = summary(120.0)
        assertEquals("120.0 m²", areaDetail(all, UnitSystem.METRIC, AreaSummary(120.0, 0.0, 0.0, 0.0)))
        assertEquals("Living area 80.0 m²", areaDetail(all, UnitSystem.METRIC, AreaSummary(80.0, 30.0, 10.0, 0.0)))
        assertEquals("Living area 80.0 m²", areaDetail(all, UnitSystem.METRIC, AreaSummary(80.0, 0.0, 0.0, 5.0)))
        // While the project is still loading, the stored net total is shown.
        assertEquals("120.0 m²", areaDetail(all, UnitSystem.METRIC, null))
        assertNull(areaDetail(summary(0.0), UnitSystem.METRIC, null))
        assertNull(areaDetail(summary(Double.NaN), UnitSystem.METRIC, AreaSummary.ZERO))
    }
}
