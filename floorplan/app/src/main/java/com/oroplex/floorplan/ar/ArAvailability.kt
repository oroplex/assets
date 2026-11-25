package com.oroplex.floorplan.ar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.delay

/** What the primary button of a setup error screen does. */
internal enum class ArSetupAction { RETRY, INSTALL, NONE }

/** Every way AR setup can fail, with the copy shown on its full-screen message. */
internal enum class ArSetupError(val title: String, val message: String, val action: ArSetupAction) {
    DEVICE_NOT_SUPPORTED(
        title = "AR isn't supported on this phone",
        message = "Room scanning needs Google Play Services for AR (ARCore), which isn't available for this " +
            "device. You can still draw rooms by hand in the plan editor.",
        action = ArSetupAction.NONE,
    ),
    ARCORE_NOT_INSTALLED(
        title = "Install Google Play Services for AR",
        message = "Room scanning uses Google Play Services for AR. Install it from the Play Store, then come " +
            "back to start scanning.",
        action = ArSetupAction.INSTALL,
    ),
    INSTALL_DECLINED(
        title = "Google Play Services for AR is required",
        message = "Scanning can't start without Google Play Services for AR. Tap Install to get it from the " +
            "Play Store.",
        action = ArSetupAction.INSTALL,
    ),
    ARCORE_TOO_OLD(
        title = "Update Google Play Services for AR",
        message = "The installed Google Play Services for AR is too old for this app. Update it from the " +
            "Play Store to continue.",
        action = ArSetupAction.INSTALL,
    ),
    APP_TOO_OLD(
        title = "Update FloorPlan Scan",
        message = "This version of FloorPlan Scan is too old for the installed Google Play Services for AR. " +
            "Update the app to keep scanning.",
        action = ArSetupAction.NONE,
    ),
    CHECK_FAILED(
        title = "Couldn't check AR support",
        message = "Checking whether this phone supports AR didn't finish. Make sure you're online, then " +
            "try again.",
        action = ArSetupAction.RETRY,
    ),
    CAMERA_UNAVAILABLE(
        title = "Camera unavailable",
        message = "The camera couldn't be opened. Close other apps that use the camera, check that camera " +
            "access is on in Quick settings, then try again.",
        action = ArSetupAction.RETRY,
    ),
    SESSION_FAILED(
        title = "Couldn't start AR",
        message = "Something went wrong while starting the AR session. Try again; if it keeps happening, " +
            "restart your phone.",
        action = ArSetupAction.RETRY,
    ),
}

/** Outcome of making sure ARCore is present and current on this device. */
internal sealed interface ArAvailabilityResult {
    /** ARCore is installed and up to date: a session can be created. */
    data object Ready : ArAvailabilityResult

    /**
     * ARCore started its install / update flow (our activity is about to pause). Check again when the
     * activity resumes, this time with `userRequestedInstall = false`.
     */
    data object InstallRequested : ArAvailabilityResult

    data class Unavailable(val error: ArSetupError) : ArAvailabilityResult
}

/** ARCore availability check and install flow. Everything here must run on the main thread. */
internal object ArAvailability {
    private const val POLL_INTERVAL_MS = 200L

    /** How long to keep polling while ARCore reports a transient (still checking) state. */
    private const val MAX_TRANSIENT_WAIT_MS = 10_000L

    /**
     * Checks ARCore availability and, when ARCore is missing or outdated, asks it to install itself.
     *
     * [userRequestedInstall] is passed to [ArCoreApk.requestInstall]: true shows the install prompt even if
     * the user declined before (first attempt, or an explicit "Install" tap); false (the re-check after
     * returning from the install flow) reports a decline as [ArSetupError.INSTALL_DECLINED] instead of
     * prompting again.
     */
    suspend fun prepare(activity: Activity, userRequestedInstall: Boolean): ArAvailabilityResult {
        val availability = awaitAvailability(activity)
        ArLog.i("ARCore availability: $availability")
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailabilityResult.Ready
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            -> requestInstall(activity, userRequestedInstall)
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                ArAvailabilityResult.Unavailable(ArSetupError.DEVICE_NOT_SUPPORTED)
            // UNKNOWN_ERROR, UNKNOWN_TIMED_OUT (typically offline on first launch), or still checking.
            else -> ArAvailabilityResult.Unavailable(ArSetupError.CHECK_FAILED)
        }
    }

    /**
     * [ArCoreApk.checkAvailability] answers UNKNOWN_CHECKING while it queries the device-support list in
     * the background; poll until the answer settles or [MAX_TRANSIENT_WAIT_MS] passes.
     */
    private suspend fun awaitAvailability(context: Context): ArCoreApk.Availability {
        val apk = ArCoreApk.getInstance()
        val deadline = SystemClock.elapsedRealtime() + MAX_TRANSIENT_WAIT_MS
        while (true) {
            val availability = try {
                apk.checkAvailability(context)
            } catch (e: Exception) {
                ArLog.w("ARCore availability check failed", e)
                ArCoreApk.Availability.UNKNOWN_ERROR
            } ?: ArCoreApk.Availability.UNKNOWN_ERROR
            if (!availability.isTransient || SystemClock.elapsedRealtime() >= deadline) return availability
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun requestInstall(activity: Activity, userRequestedInstall: Boolean): ArAvailabilityResult = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALLED -> ArAvailabilityResult.Ready
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArAvailabilityResult.InstallRequested
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        ArAvailabilityResult.Unavailable(ArSetupError.INSTALL_DECLINED)
    } catch (e: UnavailableDeviceNotCompatibleException) {
        ArAvailabilityResult.Unavailable(ArSetupError.DEVICE_NOT_SUPPORTED)
    } catch (e: Exception) {
        ArLog.w("ARCore install request failed", e)
        ArAvailabilityResult.Unavailable(ArSetupError.CHECK_FAILED)
    }
}

/** The hosting Activity of a (possibly wrapped) Compose context, or null outside an Activity. */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Opens this app's system settings page (where a permanently denied permission can be granted). */
internal fun Context.openAppSettings() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(details)
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: ActivityNotFoundException) {
            ArLog.w("no settings activity available", e2)
        }
    }
}
