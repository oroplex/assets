package com.oroplex.floorplan

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oroplex.floorplan.ui.capture.CaptureScreen
import com.oroplex.floorplan.ui.editor.PlanEditorScreen
import com.oroplex.floorplan.ui.export.ExportScreen
import com.oroplex.floorplan.ui.projects.ProjectListScreen
import com.oroplex.floorplan.ui.settings.SettingsScreen
import com.oroplex.floorplan.ui.theme.FloorPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloorPlanTheme {
                FloorPlanNavHost()
            }
        }
    }
}

/** String routes; ids are URL-encoded when building a route (navigation decodes path arguments). */
private object Routes {
    const val ARG_PROJECT = "projectId"
    const val ARG_FLOOR = "floorId"

    const val PROJECTS = "projects"
    const val EDITOR = "editor/{$ARG_PROJECT}"
    const val CAPTURE = "capture/{$ARG_PROJECT}/{$ARG_FLOOR}"
    const val EXPORT = "export/{$ARG_PROJECT}"
    const val SETTINGS = "settings"

    fun editor(projectId: String) = "editor/${Uri.encode(projectId)}"
    fun capture(projectId: String, floorId: String) = "capture/${Uri.encode(projectId)}/${Uri.encode(floorId)}"
    fun export(projectId: String) = "export/${Uri.encode(projectId)}"
}

@Composable
private fun FloorPlanNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
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
            SettingsScreen(onBack = { nav.popFrom(entry) })
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
 * Pops [entry] only if it is still on top of the back stack, so repeated back taps or a late
 * "done" callback cannot pop the screen underneath it.
 */
private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id && previousBackStackEntry != null) {
        popBackStack()
    }
}
