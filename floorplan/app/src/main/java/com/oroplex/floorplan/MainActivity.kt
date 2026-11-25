package com.oroplex.floorplan

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        // Before the first frame: edge-to-edge with bar icons matching the night mode at launch.
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            // The manifest handles uiMode changes without recreating the Activity (a recreation would tear
            // down the AR session), so Compose re-themes in place. enableEdgeToEdge only looks at the night
            // mode when it is called; without re-applying it here, the status-bar clock and battery would
            // keep their launch-time colour and vanish against the new background after a dark-mode switch.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
                )
                onDispose {}
            }
            FloorPlanTheme(darkTheme = dark) {
                FloorPlanNavHost()
            }
        }
    }

    private companion object {
        // androidx.activity's own default scrims behind a 3-button navigation bar.
        val LIGHT_NAV_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
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

    /**
     * Key in the editor entry's SavedStateHandle: the id of a room the editor should select and zoom to when
     * it comes back (set by the export screen's pre-flight list); null once the editor has handled it.
     */
    const val KEY_FOCUS_ROOM = "focusRoom"

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
            // A room another screen asked the editor to show (see Routes.KEY_FOCUS_ROOM). Cleared by writing
            // null rather than removing the key: remove() detaches the StateFlow, which would then miss the
            // next request.
            val focusRoomFlow = remember(entry) { entry.savedStateHandle.getStateFlow<String?>(Routes.KEY_FOCUS_ROOM, null) }
            val focusRoomId by focusRoomFlow.collectAsStateWithLifecycle()
            PlanEditorScreen(
                projectId = projectId,
                onScanRoom = { p, floorId -> nav.navigateFrom(entry, Routes.capture(p, floorId)) },
                onExport = { p -> nav.navigateFrom(entry, Routes.export(p)) },
                onBack = { nav.popFrom(entry) },
                onOpen3d = { p, floorId -> nav.navigateFrom(entry, Routes.view3d(p, floorId)) },
                focusRoomId = focusRoomId,
                onFocusRoomConsumed = { entry.savedStateHandle[Routes.KEY_FOCUS_ROOM] = null },
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
            ExportScreen(
                projectId = projectId,
                onBack = { nav.popFrom(entry) },
                onOpenRoom = { roomId -> nav.showRoomInEditor(entry, roomId) },
            )
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
 * Leaves [from] (the export screen) for the editor underneath it and asks the editor to select and zoom to
 * [roomId]: the request travels in the editor entry's SavedStateHandle ([Routes.KEY_FOCUS_ROOM]), so it
 * survives process death, and the editor clears it once handled. Does nothing unless [from] is still on top
 * with the editor right below it; the plan itself is never changed from here.
 */
private fun NavHostController.showRoomInEditor(from: NavBackStackEntry, roomId: String) {
    val editor = previousBackStackEntry ?: return
    if (currentBackStackEntry?.id != from.id || editor.destination.route != Routes.EDITOR) return
    editor.savedStateHandle[Routes.KEY_FOCUS_ROOM] = roomId
    popBackStack()
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
