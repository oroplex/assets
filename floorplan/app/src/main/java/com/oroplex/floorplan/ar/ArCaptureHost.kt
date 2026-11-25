package com.oroplex.floorplan.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.exceptions.CameraNotAvailableException

/**
 * Full-screen AR host. Handles camera permission, ARCore availability / install, session creation and
 * configuration, lifecycle (resume/pause/close), and renders the camera feed. Once the session runs it
 * calls [content] with the live controller, layered above the camera view. Shows friendly full-screen
 * messages (with retry / back) for every failure (no permission, unsupported device, install declined,
 * camera unavailable). [onExit] is invoked when the user backs out of an error screen, including the
 * "Camera unavailable" screen that covers a running scan (see [ArSessionLayers]).
 */
@Composable
fun ArCaptureHost(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    content: @Composable (controller: ArCaptureController) -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        CameraPermissionGate(onExit = onExit) {
            ArSessionGate(onExit = onExit, content = content)
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Step 1: camera permission
// ---------------------------------------------------------------------------------------------------

/**
 * Requests the camera permission once automatically; after a denial shows a rationale with "Grant
 * access", and once Android stops showing the prompt (permanently denied) an "Open settings" path.
 * The permission is re-checked on every resume, so granting it in Settings continues straight away.
 */
@Composable
private fun CameraPermissionGate(onExit: () -> Unit, granted: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        requested = true
        hasPermission = isGranted
        // After a denial, "no rationale" means Android will no longer show the dialog.
        permanentlyDenied = !isGranted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = context.hasCameraPermission()
        hasPermission = now
        if (now) permanentlyDenied = false
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !requested) launcher.launch(Manifest.permission.CAMERA)
    }

    when {
        hasPermission -> granted()
        !requested -> ArProgressScreen(message = "Waiting for camera permission…", onBack = onExit)
        permanentlyDenied -> CameraBlockedScreen(onOpenSettings = { context.openAppSettings() }, onBack = onExit)
        else -> CameraRationaleScreen(onGrant = { launcher.launch(Manifest.permission.CAMERA) }, onBack = onExit)
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------------------------------------------
// Step 2: ARCore availability / install, session creation
// ---------------------------------------------------------------------------------------------------

private sealed interface SetupState {
    data object Checking : SetupState

    /** ARCore's install flow is showing; re-check when the activity resumes. */
    data object AwaitingInstall : SetupState
    data class Failed(val error: ArSetupError) : SetupState
    class Ready(val holder: ArSessionHolder) : SetupState
}

@Composable
private fun ArSessionGate(onExit: () -> Unit, content: @Composable (ArCaptureController) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var state by remember { mutableStateOf<SetupState>(SetupState.Checking) }
    var attempt by remember { mutableIntStateOf(0) }
    // Survives the trip through the Play Store (and process death while there).
    var installRequested by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state is SetupState.AwaitingInstall) attempt++
    }

    LaunchedEffect(attempt) {
        if (activity == null) {
            state = SetupState.Failed(ArSetupError.SESSION_FAILED)
            return@LaunchedEffect
        }
        state = SetupState.Checking
        state = when (val availability = ArAvailability.prepare(activity, userRequestedInstall = !installRequested)) {
            ArAvailabilityResult.Ready -> when (val created = ArSessionFactory.create(activity)) {
                is SessionCreateResult.Created -> SetupState.Ready(created.holder)
                is SessionCreateResult.Failed -> SetupState.Failed(created.error)
            }
            ArAvailabilityResult.InstallRequested -> {
                installRequested = true
                SetupState.AwaitingInstall
            }
            is ArAvailabilityResult.Unavailable -> SetupState.Failed(availability.error)
        }
    }

    // Covers the window between creating a session and composing ArSessionContent. Once a GL view owns
    // the session only that view may close it (it must park its GL thread first).
    DisposableEffect(Unit) {
        onDispose {
            (state as? SetupState.Ready)?.holder?.takeUnless { it.isOwnedByView }?.close()
        }
    }

    when (val s = state) {
        SetupState.Checking -> ArProgressScreen(message = "Starting AR…", onBack = onExit)
        SetupState.AwaitingInstall ->
            ArProgressScreen(message = "Waiting for Google Play Services for AR to install…", onBack = onExit)
        is SetupState.Failed -> ArSetupErrorScreen(
            error = s.error,
            onPrimary = {
                // An explicit Install tap must show ARCore's prompt again even after a decline.
                if (s.error.action == ArSetupAction.INSTALL) installRequested = false
                attempt++
            },
            onBack = onExit,
        )
        is SetupState.Ready -> ArSessionContent(holder = s.holder, onExit = onExit, content = content)
    }
}

// ---------------------------------------------------------------------------------------------------
// Step 3: running session (lifecycle + GL view)
// ---------------------------------------------------------------------------------------------------

@Composable
private fun ArSessionContent(
    holder: ArSessionHolder,
    onExit: () -> Unit,
    content: @Composable (ArCaptureController) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runner = remember(holder) { ArSessionRunner(context, holder) }

    // Declared first so it is disposed last: the lifecycle observer below is removed before shutdown.
    DisposableEffect(runner) {
        onDispose { runner.dispose() }
    }
    DisposableEffect(lifecycleOwner, runner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runner.resume()
                Lifecycle.Event.ON_PAUSE -> runner.pause()
                else -> Unit
            }
        }
        // Registering while already RESUMED delivers ON_RESUME immediately.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ArSessionLayers(
        cameraView = { AndroidView(factory = { runner.view }, modifier = Modifier.fillMaxSize()) },
        showContent = runner.started,
        cameraUnavailable = runner.cameraUnavailable,
        onRetry = {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runner.resume()
        },
        onExit = onExit,
        content = { content(holder.controller) },
    )
}

/**
 * The layers of a session, bottom to top: the camera view, the capture UI ([content], composed once the
 * session has run and then kept, with its state, even while the camera is unavailable), and the
 * full-screen "Camera unavailable" overlay with Retry and Back.
 *
 * Back on that overlay, the button and system Back alike, calls [onExit], which is the capture flow's
 * "leave the scan" (it asks before discarding a room in progress or ending a multi-room session, and applies
 * the anchor corrections). It must NOT reach the capture UI's own Back handling: that steps back through
 * the capture flow (out of review, out of a re-capture, dropping a half-marked door), and those steps would
 * happen invisibly underneath the overlay while the visible Back appeared to do nothing (ARX-5 / CAPX-10).
 * The overlay is composed after [content], so its back handler is registered later and takes priority for
 * as long as it is shown; the capture flow's prompts are dialogs composed outside the host, above it.
 */
@Composable
internal fun ArSessionLayers(
    cameraView: @Composable () -> Unit,
    showContent: Boolean,
    cameraUnavailable: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        cameraView()
        if (showContent) content()
        if (cameraUnavailable) {
            ArSetupErrorScreen(
                error = ArSetupError.CAMERA_UNAVAILABLE,
                onPrimary = onRetry,
                onBack = onExit,
                handleSystemBack = true,
            )
        }
    }
}

/**
 * Owns the GLSurfaceView of one session and sequences resume / pause so the GL thread and the session
 * lifecycle never overlap: resume = Session.resume() then GL resume; pause = GL pause (blocks until the
 * GL thread is idle) then Session.pause(). Main thread only.
 */
@Stable
private class ArSessionRunner(context: Context, private val sessionHolder: ArSessionHolder) {
    init {
        sessionHolder.markOwnedByView()
    }

    private val displayRotation = DisplayRotationHelper(context)

    val view: GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(
            ArSessionRenderer(sessionHolder.session, sessionHolder.controller, displayRotation) {
                // GL thread: hop to the main thread, where the lifecycle is sequenced.
                post { onCameraLost() }
            },
        )
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
        // Scanning involves long stretches without touching the screen.
        keepScreenOn = true
    }

    /** Session.resume() failed (camera taken by another app, ...): shows a Retry screen. */
    var cameraUnavailable by mutableStateOf(false)
        private set

    /** The session has run at least once; from then on the capture UI stays composed across pauses. */
    var started by mutableStateOf(false)
        private set

    private var resumed = false

    fun resume() {
        if (resumed || sessionHolder.isClosed) return
        try {
            sessionHolder.session.resume()
        } catch (e: CameraNotAvailableException) {
            ArLog.w("session resume failed: camera not available (in use by another app?)", e)
            cameraUnavailable = true
            return
        } catch (e: Exception) {
            // SecurityException (permission revoked), FatalException, IllegalStateException, ...
            ArLog.e("session resume failed", e)
            cameraUnavailable = true
            return
        }
        resumed = true
        cameraUnavailable = false
        sessionHolder.controller.onSessionResumed()
        displayRotation.onResume(view)
        view.onResume()
        started = true
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        displayRotation.onPause()
        view.onPause() // returns once the GL thread finished its frame and is paused
        try {
            sessionHolder.session.pause()
        } catch (e: Exception) {
            ArLog.w("Session.pause failed", e)
        }
        sessionHolder.controller.onSessionPaused()
    }

    /** The camera stayed unavailable while running: stop and offer Retry (which resumes again). */
    private fun onCameraLost() {
        if (!resumed || sessionHolder.isClosed) return
        pause()
        cameraUnavailable = true
    }

    fun dispose() {
        pause()
        // Also parks a GL thread that was never resumed, before the session is closed underneath it.
        view.onPause()
        displayRotation.release()
        sessionHolder.close()
    }
}
