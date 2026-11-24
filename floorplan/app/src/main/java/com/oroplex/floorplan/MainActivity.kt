package com.oroplex.floorplan

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.ui.capture.CaptureScreen
import com.oroplex.floorplan.ui.editor.PlanEditorScreen
import com.oroplex.floorplan.ui.export.ExportScreen
import com.oroplex.floorplan.ui.onboarding.Onboarding
import com.oroplex.floorplan.ui.onboarding.OnboardingScreen
import com.oroplex.floorplan.ui.projects.ProjectListScreen
import com.oroplex.floorplan.ui.settings.SettingsScreen
import com.oroplex.floorplan.ui.theme.FloorPlanTheme
import com.oroplex.floorplan.ui.view3d.DollhouseScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SystemBarStyle.auto (the default) picks light / dark bar icons from the system night mode,
        // which is also what FloorPlanTheme follows.
        enableEdgeToEdge()
        setContent {
            FloorPlanTheme {
                FloorPlanNavHost()
            }
        }
    }
}

/** String routes; ids are URL-encoded when building a route (navigation decodes path arguments). */
internal object Routes {
    const val ARG_PROJECT = "projectId"
    const val ARG_FLOOR = "floorId"

    /** First-launch welcome tour; leads to [PROJECTS] and is removed from the back stack. */
    const val ONBOARDING = "onboarding"

    /** The same tour replayed from Settings; returns to Settings. */
    const val TUTORIAL = "tutorial"
    const val PROJECTS = "projects"
    const val EDITOR = "editor/{$ARG_PROJECT}"
    const val CAPTURE = "capture/{$ARG_PROJECT}/{$ARG_FLOOR}"
    const val EXPORT = "export/{$ARG_PROJECT}"
    const val VIEW_3D = "view3d/{$ARG_PROJECT}/{$ARG_FLOOR}"
    const val SETTINGS = "settings"

    fun editor(projectId: String) = "editor/${Uri.encode(projectId)}"
    fun capture(projectId: String, floorId: String) = "capture/${Uri.encode(projectId)}/${Uri.encode(floorId)}"
    fun export(projectId: String) = "export/${Uri.encode(projectId)}"
    fun view3d(projectId: String, floorId: String) = "view3d/${Uri.encode(projectId)}/${Uri.encode(floorId)}"

    /** Start with the welcome tour until it has been finished or skipped once. */
    fun start(onboardingDone: Boolean): String = if (onboardingDone) PROJECTS else ONBOARDING
}

@Composable
private fun FloorPlanNavHost() {
    val nav = rememberNavController()
    val branding = appContainer().branding
    // Decided once per activity instance (and kept across recreation), so finishing the tour never
    // swaps the graph's start destination underneath a restored back stack.
    val start = rememberSaveable { Routes.start(branding.isFlagSet(Onboarding.FLAG)) }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) { entry ->
            OnboardingScreen(
                onFinish = {
                    branding.setFlag(Onboarding.FLAG)
                    nav.replaceFrom(entry, Routes.PROJECTS)
                },
            )
        }
        composable(Routes.TUTORIAL) { entry ->
            OnboardingScreen(
                onFinish = {
                    branding.setFlag(Onboarding.FLAG)
                    nav.popFrom(entry)
                },
            )
        }
        composable(Routes.PROJECTS) { entry ->
            ProjectListScreen(
                onOpenProject = { projectId -> nav.navigateFrom(entry, Routes.editor(projectId)) },
                onOpenSettings = { nav.navigateFrom(entry, Routes.SETTINGS) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument(Routes.ARG_PROJECT) { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.stringArg(Routes.ARG_PROJECT)
            if (projectId == null) {
                LeaveInvalidRoute(nav, entry)
                return@composable
            }
            PlanEditorScreen(
                projectId = projectId,
                onScanRoom = { p, floorId -> nav.navigateFrom(entry, Routes.capture(p, floorId)) },
                onExport = { p -> nav.navigateFrom(entry, Routes.export(p)) },
                onBack = { nav.popFrom(entry) },
                onOpen3d = { p, floorId -> nav.navigateFrom(entry, Routes.view3d(p, floorId)) },
            )
        }
        composable(
            Routes.CAPTURE,
            arguments = listOf(
                navArgument(Routes.ARG_PROJECT) { type = NavType.StringType },
                navArgument(Routes.ARG_FLOOR) { type = NavType.StringType },
            ),
        ) { entry ->
            val projectId = entry.stringArg(Routes.ARG_PROJECT)
            val floorId = entry.stringArg(Routes.ARG_FLOOR)
            if (projectId == null || floorId == null) {
                LeaveInvalidRoute(nav, entry)
                return@composable
            }
            CaptureScreen(projectId = projectId, floorId = floorId, onDone = { nav.popFrom(entry) })
        }
        composable(
            Routes.VIEW_3D,
            arguments = listOf(
                navArgument(Routes.ARG_PROJECT) { type = NavType.StringType },
                navArgument(Routes.ARG_FLOOR) { type = NavType.StringType },
            ),
        ) { entry ->
            val projectId = entry.stringArg(Routes.ARG_PROJECT)
            val floorId = entry.stringArg(Routes.ARG_FLOOR)
            if (projectId == null || floorId == null) {
                LeaveInvalidRoute(nav, entry)
                return@composable
            }
            DollhouseScreen(projectId = projectId, floorId = floorId, onBack = { nav.popFrom(entry) })
        }
        composable(
            Routes.EXPORT,
            arguments = listOf(navArgument(Routes.ARG_PROJECT) { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.stringArg(Routes.ARG_PROJECT)
            if (projectId == null) {
                LeaveInvalidRoute(nav, entry)
                return@composable
            }
            ExportScreen(projectId = projectId, onBack = { nav.popFrom(entry) })
        }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                onBack = { nav.popFrom(entry) },
                onShowTutorial = { nav.navigateFrom(entry, Routes.TUTORIAL) },
            )
        }
    }
}

private fun NavBackStackEntry.stringArg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

/** A destination reached without its arguments (should not happen) simply goes back. */
@Composable
private fun LeaveInvalidRoute(nav: NavHostController, entry: NavBackStackEntry) {
    LaunchedEffect(entry.id) { nav.popFrom(entry) }
}

/**
 * Navigates only while [from] is the resumed destination, so a double tap (or a tap during a
 * transition) cannot push the same screen twice.
 */
private fun NavHostController.navigateFrom(from: NavBackStackEntry, route: String) {
    if (from.lifecycle.currentState == Lifecycle.State.RESUMED) {
        navigate(route) { launchSingleTop = true }
    }
}

/**
 * Replaces [from] with [route] (used to leave the first-launch tour: back from the project list then
 * exits the app instead of returning to the tour). Guarded like [navigateFrom].
 */
private fun NavHostController.replaceFrom(from: NavBackStackEntry, route: String) {
    if (from.lifecycle.currentState == Lifecycle.State.RESUMED) {
        navigate(route) {
            popUpTo(from.destination.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * Pops [entry] only if it is still on top of the back stack, so repeated back taps or a late
 * "done" callback cannot pop the screen underneath it.
 */
private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id && previousBackStackEntry != null) {
        popBackStack()
    }
}
