package com.oroplex.floorplan.ui.onboarding

import com.oroplex.floorplan.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    @Test
    fun `four pages in the documented order`() {
        assertEquals(
            listOf(OnboardingArt.PLAN, OnboardingArt.SCAN, OnboardingArt.ACCURACY, OnboardingArt.READY),
            Onboarding.pages.map { it.art },
        )
        assertEquals(3, Onboarding.lastIndex)
    }

    @Test
    fun `copy is short and covers the essentials`() {
        Onboarding.pages.forEach { page ->
            assertTrue(page.title, page.title.length <= 40)
            assertTrue(page.title, page.body.length <= 220)
            assertTrue(page.title, page.points.size <= 3)
            page.points.forEach { assertTrue(it, it.length <= 120) }
        }
        val scanning = Onboarding.pages[1].points.joinToString(" ")
        assertTrue(scanning.contains("in order"))
        assertTrue(scanning.contains("Scan next room"))
        val accuracy = Onboarding.pages[2].points.joinToString(" ")
        assertTrue(accuracy.contains("tape"))
        assertTrue(accuracy.contains("1–4 m"))
    }

    @Test
    fun `skip and next on every page but the last, which gets started`() {
        for (i in 0 until Onboarding.lastIndex) {
            assertTrue(Onboarding.showSkip(i))
            assertEquals("Next", Onboarding.primaryActionLabel(i))
            assertEquals(i + 1, Onboarding.nextPage(i))
        }
        assertFalse(Onboarding.showSkip(Onboarding.lastIndex))
        assertEquals("Get started", Onboarding.primaryActionLabel(Onboarding.lastIndex))
        assertNull(Onboarding.nextPage(Onboarding.lastIndex))
    }

    @Test
    fun `back steps through the pages and leaves from the first`() {
        assertNull(Onboarding.previousPage(0))
        assertEquals(0, Onboarding.previousPage(1))
        assertEquals(Onboarding.lastIndex - 1, Onboarding.previousPage(Onboarding.lastIndex))
        assertEquals(Onboarding.lastIndex, Onboarding.previousPage(99)) // out of range clamps
        assertNull(Onboarding.previousPage(-3))
    }

    @Test
    fun `progress description is one based and clamped`() {
        assertEquals("Page 1 of 4", Onboarding.progressDescription(0))
        assertEquals("Page 4 of 4", Onboarding.progressDescription(3))
        assertEquals("Page 4 of 4", Onboarding.progressDescription(10))
    }

    @Test
    fun `first launch starts with the tour, later launches with the projects`() {
        assertEquals("onboarding_done", Onboarding.FLAG)
        assertEquals(Routes.ONBOARDING, Routes.start(onboardingDone = false))
        assertEquals(Routes.PROJECTS, Routes.start(onboardingDone = true))
    }

    @Test
    fun `3d route matches its builder`() {
        assertEquals("view3d/{projectId}/{floorId}", Routes.VIEW_3D)
    }
}
