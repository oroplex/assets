package com.oroplex.floorplan.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import com.oroplex.floorplan.core.model.OpeningType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a measurement of the current step usually takes, so the progress ring can fill at a natural
 * pace (frames requested by MeasureRequest at ~30 fps camera frames, plus scheduling overhead). The ring
 * stops just short of full until the result arrives, so a slow measurement never looks finished.
 */
internal fun expectedMeasureMillis(step: CaptureStep, draft: RoomDraft): Int = when (step) {
    CaptureStep.FIND_FLOOR -> 250 // LockFloor: one frame
    CaptureStep.CEILING -> 700 // CeilingHeight: 15 frames
    CaptureStep.WALLS -> if (draft.baseline != null) 350 else 700 // FloorPoint 6 / Wall 15 frames
    CaptureStep.CORNERS -> 350 // FloorPoint: 6 frames
    CaptureStep.OPENINGS -> if (draft.openingType == OpeningType.WINDOW) 400 else 350 // SurfacePoint 8 / FloorPoint 6
    CaptureStep.REVIEW -> 0
}

/**
 * Animation state of the reticle feedback, read by the overlay in its draw phase: [ring] is the
 * measurement progress (0..1), [check] the success check mark (0 hidden … 1 shown), [fail] a red flash.
 */
@Stable
internal class ReticleFx {
    val ring = Animatable(0f)
    val check = Animatable(0f)
    val fail = Animatable(0f)

    /** A measurement started: fill the ring over about [expectedMs], easing out just before full. */
    suspend fun measuring(expectedMs: Int) {
        fail.snapTo(0f)
        check.snapTo(0f)
        ring.snapTo(0f)
        ring.animateTo(RING_WAIT_LEVEL, tween(durationMillis = expectedMs.coerceAtLeast(120), easing = LinearOutSlowInEasing))
    }

    /** The measurement ended without a result to show (e.g. the user moved on): let the ring go. */
    suspend fun idle() {
        delay(IDLE_GRACE_MS)
        ring.animateTo(0f, tween(180))
    }

    /** Play the outcome of a capture. */
    suspend fun play(kind: FeedbackKind) = coroutineScope {
        when (kind) {
            FeedbackKind.CAPTURED -> {
                fail.snapTo(0f)
                ring.animateTo(1f, tween(120))
                launch { check.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
                delay(CHECK_HOLD_MS)
                launch { ring.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
                check.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            }
            FeedbackKind.FAILED -> {
                check.snapTo(0f)
                ring.snapTo(0f)
                fail.snapTo(1f)
                delay(FAIL_HOLD_MS)
                fail.animateTo(0f, tween(300))
            }
            FeedbackKind.CONFIRMED -> Unit
        }
    }

    private companion object {
        /** The ring waits here until the result arrives. */
        const val RING_WAIT_LEVEL = 0.92f
        const val CHECK_HOLD_MS = 520L
        const val FAIL_HOLD_MS = 350L

        /** Longer than a feedback animation, so a result arriving with the end of a measurement wins. */
        const val IDLE_GRACE_MS = 1_200L
    }
}
