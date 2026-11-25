package com.oroplex.floorplan.ui.onboarding

import com.oroplex.floorplan.core.model.UnitSystem

/** Which illustration a page shows. */
enum class OnboardingArt { PLAN, SCAN, ACCURACY, READY }

/** One page of the first-launch tour: a title, a short paragraph and up to three key points. */
data class OnboardingPage(
    val art: OnboardingArt,
    val title: String,
    val body: String,
    val points: List<String> = emptyList(),
    /** The page also asks which units to measure in (see [Onboarding.UNITS_TITLE]). */
    val asksUnits: Boolean = false,
)

/**
 * Content and navigation rules of the four-page welcome tour (pure, unit-tested). The tour is shown
 * once at first launch (until [FLAG] is set through BrandingRepository.setFlag) and can be replayed
 * from Settings.
 */
object Onboarding {
    /** One-time flag set when the tour is finished or skipped. */
    const val FLAG = "onboarding_done"

    val pages: List<OnboardingPage> = listOf(
        OnboardingPage(
            art = OnboardingArt.PLAN,
            title = "Floor plans from a walk-through",
            body = "Walk through the home with your phone. FloorPlan Scan measures each room in AR and " +
                "draws a dimensioned floor plan you can share as PDF, image or CAD.",
            points = listOf(
                "Room sizes, areas and wall lengths",
                "Doors, windows and openings",
                "Branded PDFs with a room schedule",
            ),
        ),
        OnboardingPage(
            art = OnboardingArt.SCAN,
            title = "How scanning works",
            body = "Each room takes about a minute.",
            points = listOf(
                "Point at the floor and move slowly until it locks.",
                "Aim at each wall above the furniture and tap Capture. Go around the room in order.",
                "Close the room, then tap “Scan next room”. Rooms from one session line up automatically.",
            ),
        ),
        OnboardingPage(
            art = OnboardingArt.ACCURACY,
            title = "Tips for accuracy",
            body = "Careful scans land within about 1–3 %. These make the difference:",
            points = listOf(
                "Bright light and textured wall areas. Blank or glossy walls are harder.",
                "Stay 1–4 m (3–13 ft) from the wall you're capturing and hold steady.",
                "Calibrate with a tape: measure one long wall and enter it in the editor. The whole floor is corrected.",
            ),
        ),
        OnboardingPage(
            art = OnboardingArt.READY,
            title = "You're ready",
            body = "Create a project, then tap Scan room. You can also draw rooms by hand or type exact sizes, " +
                "and fix any wall in the editor.",
            points = listOf(
                "Add your company name and logo in Settings",
                "Replay this tour anytime from Settings",
            ),
            asksUnits = true,
        ),
    )

    /**
     * The units question on the last page. The answer starts out as the device's country suggests and is
     * written to the app-wide defaults (SettingsRepository.update), which only new projects copy: projects
     * that already exist keep their own units, also when the tour is replayed from Settings.
     */
    const val UNITS_TITLE = "Measure in"
    const val UNITS_HINT = "For new projects. You can change it anytime in Settings."

    /** The two choices in the order they are offered. */
    val unitChoices: List<UnitSystem> = listOf(UnitSystem.IMPERIAL, UnitSystem.METRIC)

    /** Button label of a units choice (the same words as the editor's project settings). */
    fun unitLabel(system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "Feet & inches"
        UnitSystem.METRIC -> "Metric"
    }

    val lastIndex: Int get() = pages.lastIndex

    fun isLast(index: Int): Boolean = index >= lastIndex

    /** Label of the main button on page [index]. */
    fun primaryActionLabel(index: Int): String = if (isLast(index)) "Get started" else "Next"

    /** "Skip" is offered on every page except the last, where the main button already finishes. */
    fun showSkip(index: Int): Boolean = !isLast(index)

    /** Page after [index], or null when the main button should finish the tour. */
    fun nextPage(index: Int): Int? = if (isLast(index)) null else (index + 1).coerceAtLeast(0)

    /** Page before [index], or null on the first page (back then leaves the tour as usual). */
    fun previousPage(index: Int): Int? = if (index <= 0) null else (index - 1).coerceAtMost(lastIndex)

    /** Accessibility description of the page indicator. */
    fun progressDescription(index: Int): String = "Page ${index.coerceIn(0, lastIndex) + 1} of ${pages.size}"
}
