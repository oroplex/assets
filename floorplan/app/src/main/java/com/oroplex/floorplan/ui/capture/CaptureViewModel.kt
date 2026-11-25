package com.oroplex.floorplan.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.oroplex.floorplan.ar.ArCaptureController
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.MeasureRequest
import com.oroplex.floorplan.ar.MeasureResult
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.io.Ids
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomNames
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.RoomSolver
import com.oroplex.floorplan.core.solve.SolveResult
import com.oroplex.floorplan.core.solve.SolverOptions
import com.oroplex.floorplan.data.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Steps of capturing one room (DESIGN.md "Capture flow"). */
enum class CaptureStep { FIND_FLOOR, CEILING, WALLS, CORNERS, OPENINGS, REVIEW }

/** A one-shot message for the user (shown once as a snackbar, then acknowledged by id). */
data class UserMessage(val id: Long, val text: String)

/**
 * Baseline fallback in progress: the user marks two floor points along the base of a wall
 * (where it meets the floor) instead of letting the depth fit find the wall. [firstTap] is the first
 * world point once marked.
 */
data class BaselineCapture(val firstTap: Vec3? = null)

/** One undoable change of a room's walls / corners: the captures before it, and what undoing it says. */
data class CaptureEdit(
    val observations: List<WallObservation>,
    val cornerTaps: List<Vec2>,
    val undoMessage: String,
)

enum class FeedbackKind {
    /** A measurement succeeded: confirmation haptic and a check mark on the reticle. */
    CAPTURED,

    /** A step was completed (room closed, opening placed, room saved): confirmation haptic only. */
    CONFIRMED,

    /** A measurement failed: reject haptic and a red flash on the reticle. */
    FAILED,
}

/** Outcome feedback the UI plays once (haptic + reticle animation), then acknowledges by [id]. */
data class CaptureFeedback(val id: Long, val kind: FeedbackKind)

/**
 * Everything captured so far for the room being scanned. Immutable; every change produces a copy.
 *
 * [solve] is recomputed after every capture change (walls / corners) once there are at least three;
 * it is frozen while openings are placed and during review because openings reference its edges by index.
 */
data class RoomDraft(
    val id: String,
    /** WALLS or CORNERS; can only be switched before the first capture. */
    val method: CaptureMethod = CaptureMethod.WALLS,
    val observations: List<WallObservation> = emptyList(),
    val cornerTaps: List<Vec2> = emptyList(),
    /**
     * Floor-to-ceiling height. Measured when [ceilingMeasured]; otherwise an assumption (another room's
     * height) or null when the step was skipped, in which case the project default is saved. Either way an
     * unmeasured height is saved with `Room.ceilingEstimated = true`.
     */
    val ceilingHeight: Double? = null,
    val ceilingMeasured: Boolean = false,
    /** World Y of a measured ceiling, so a floor re-lock can recompute [ceilingHeight] (the ceiling didn't move). */
    val ceilingY: Double? = null,
    val openings: List<Opening> = emptyList(),
    val openingType: OpeningType = OpeningType.DOOR,
    /** First (left) edge of the opening being marked, plan meters. */
    val pendingOpeningEdge: Vec2? = null,
    /** Non-null while the user is marking a wall by its base instead of the depth fit. */
    val baseline: BaselineCapture? = null,
    /** The last depth wall capture failed: suggest the baseline fallback. */
    val offerBaseline: Boolean = false,
    val solve: SolveResult? = null,
    val name: String = "",
    /** True once the user typed a name, so changing the type no longer renames the room. */
    val nameEdited: Boolean = false,
    val type: RoomType = RoomType.OTHER,
    /** The ARCore anchor for this room was requested. */
    val anchorCreated: Boolean = false,
    /** Index of the wall (WALLS) or corner (CORNERS) being re-captured: the next capture replaces it. */
    val recaptureIndex: Int? = null,
    /** Doors and windows marked before the room was reopened; put back on the nearest wall when it is closed. */
    val parkedOpenings: List<ParkedOpening> = emptyList(),
    /** Undo history of wall / corner edits (add, re-capture, delete), newest last. */
    val history: List<CaptureEdit> = emptyList(),
) {
    /** Walls (WALLS) or corners (CORNERS) captured so far. */
    val captureCount: Int
        get() = if (method == CaptureMethod.CORNERS) cornerTaps.size else observations.size

    /** The capture step of this draft's method. */
    val captureStep: CaptureStep
        get() = if (method == CaptureMethod.CORNERS) CaptureStep.CORNERS else CaptureStep.WALLS

    /** True when discarding this draft would lose user work. */
    val hasCaptures: Boolean
        get() = observations.isNotEmpty() || cornerTaps.isNotEmpty() || openings.isNotEmpty() ||
            parkedOpenings.isNotEmpty() || baseline?.firstTap != null || pendingOpeningEdge != null

    /**
     * The draft as a [Room] (null until it has a valid solved outline), in the raw AR frame. Used for the AR
     * overlay, opening placement and saving. A ceiling that was not measured here is saved as an estimate:
     * the assumed height (or the project default when skipped) with `ceilingEstimated = true`.
     */
    fun toRoom(sessionId: String?, settings: PlanSettings): Room? {
        val solved = solve ?: return null
        val height = ceilingHeight?.takeIf { it.isFinite() && it > 0.0 }
        val measured = ceilingMeasured && height != null
        return Room(
            id = id,
            name = name.trim(),
            type = type,
            corners = solved.corners,
            ceilingHeight = height ?: settings.defaultCeilingHeight,
            ceilingEstimated = !measured,
            openings = openings,
            captureMethod = method,
            observations = if (method == CaptureMethod.WALLS) observations else emptyList(),
            cornerTaps = if (method == CaptureMethod.CORNERS) cornerTaps else emptyList(),
            sessionId = sessionId,
            warnings = solved.warnings,
        )
    }
}

data class CaptureUiState(
    val step: CaptureStep = CaptureStep.FIND_FLOOR,
    val draft: RoomDraft,
    /** Settings of the project being scanned (defaults until it has loaded). */
    val settings: PlanSettings = PlanSettings(),
    val projectLoaded: Boolean = false,
    /** Non-null when the project can't be opened; the screen then only offers to go back. */
    val loadError: String? = null,
    /** Latest rooms of the target floor as stored (rooms saved this session included). */
    val floorRooms: List<Room> = emptyList(),
    /** Tape calibration of the target floor; applied to rooms saved into it. */
    val floorCalibration: Calibration? = null,
    /** AR session the floor lock and [sessionRoomIds] belong to. */
    val sessionId: String? = null,
    /** Floor height (world Y) locked for this AR session; kept for following rooms until re-locked. */
    val floorY: Double? = null,
    /**
     * Auto-lock the floor once the AR layer reports a floor it can lock by itself (`floorAutoLockable`) that
     * stays stable for a second. Turned off for the rest of the AR session when the user taps "Re-lock floor":
     * they rejected what was found, so only their explicit "Lock floor" counts from then on.
     */
    val autoLockFloor: Boolean = true,
    /** Floor height the user rejected with "Re-lock floor" (a manual lock near it asks for a second tap). */
    val rejectedFloorY: Double? = null,
    /** Rooms saved during the current AR session (they share its coordinate frame), in save order. */
    val sessionRoomIds: Set<String> = emptySet(),
    /** Scale each session room was saved with (floor calibration), so anchor corrections can match it. */
    val sessionRoomScales: Map<String, AppliedScale> = emptyMap(),
    /** The session's squaring axis (from its first saved room), shared by every later room. */
    val sessionAxis: SessionAxis? = null,
    /** Ceiling height of the last session room whose ceiling was measured (offered as "Same as last room"). */
    val lastMeasuredCeiling: Double? = null,
    /** A measurement is running ("Hold steady…"). */
    val measuring: Boolean = false,
    /** A save is running. */
    val saving: Boolean = false,
    /** Pending one-shot messages, oldest first. */
    val messages: List<UserMessage> = emptyList(),
    /** Latest capture outcome; the UI plays it once when its id is newer than [feedbackPlayedId]. */
    val feedback: CaptureFeedback? = null,
    val feedbackPlayedId: Long = 0L,
    /** The "Discard this room?" prompt is showing. */
    val confirmDiscard: Boolean = false,
    /** The "End this scan?" prompt is showing (leaving between rooms ends the session's shared frame). */
    val confirmEndSession: Boolean = false,
    /** The scan was interrupted (AR session restarted): a notice the user dismisses. */
    val interrupted: InterruptedScan? = null,
    /** The "How to scan" sheet is showing (automatically on the very first scan, or from the help button). */
    val showTips: Boolean = false,
    /** The tips were opened from the help button (rather than automatically on the first scan). */
    val tipsFromHelp: Boolean = false,
    /** The session is over: the UI navigates back (onDone). */
    val finished: Boolean = false,
) {
    val busy: Boolean get() = measuring || saving

    /** True when leaving now would discard work on the current room. */
    val hasRoomInProgress: Boolean
        get() = draft.hasCaptures || step == CaptureStep.OPENINGS || step == CaptureStep.REVIEW

    /** Ending the scan now would give up its shared frame: rooms were saved in it, or a floor is locked. */
    val sessionInProgress: Boolean
        get() = sessionRoomIds.isNotEmpty() || floorY != null

    /** Rooms saved in this AR session other than the draft, in save order. */
    val sessionRooms: List<Room>
        get() {
            val byId = floorRooms.associateBy { it.id }
            return sessionRoomIds.filter { it != draft.id }.mapNotNull { byId[it] }
        }

    /** Rooms already on the floor that were captured in another AR session (or drawn by hand). */
    val otherSessionRoomCount: Int
        get() = floorRooms.count { it.id != draft.id && it.id !in sessionRoomIds && (sessionId == null || it.sessionId != sessionId) }

    /** "Re-lock floor" is offered (before anything of this room is captured). */
    val canRelockFloor: Boolean
        get() = step == CaptureStep.CEILING && !draft.hasCaptures

    /** The draft as a room in the raw AR frame (for the AR overlay), when it has a solved outline. */
    val draftRoom: Room? get() = draft.toRoom(sessionId, settings)

    /** The floor's tape calibration as applied to a room saved now (null = none). */
    val savedScale: AppliedScale? get() = AppliedScale.of(floorCalibration)

    /** A raw AR length as it will be saved (scaled by the floor's calibration). */
    fun savedLength(meters: Double): Double = meters * (savedScale?.factor ?: 1.0)
}

/** A persisted "already seen" flag (e.g. the first-scan tips). Implementations must never throw. */
interface OneTimeFlag {
    fun isSet(): Boolean
    fun set()

    /** Not persisted; used by tests and previews. */
    class InMemory(private var value: Boolean = false) : OneTimeFlag {
        override fun isSet(): Boolean = value
        override fun set() {
            value = true
        }
    }
}

/**
 * State machine of the room-capture flow: FIND_FLOOR → CEILING → WALLS | CORNERS → OPENINGS → REVIEW,
 * then the next room (same AR session, floor stays locked) or finish.
 *
 * Measurement functions take the live [ArCaptureController] as a parameter instead of holding it, so the
 * ViewModel never outlives or leaks an AR session. All public functions are main-thread only and ignore
 * calls that don't fit the current step (e.g. a double tap racing a step change), so the UI can wire
 * buttons directly.
 *
 * @param tipsFlag remembers that the first-scan "How to scan" sheet was seen.
 * @param calibrateRoom scales a room added to a tape-calibrated floor (FloorOps.applyCalibration); also used
 *   to show the review exactly as the room will be saved.
 * @param snapshotStore keeps a snapshot of the flow across process death (see [CaptureSnapshot]).
 */
class CaptureViewModel(
    private val repository: ProjectRepository,
    private val projectId: String,
    private val floorId: String,
    private val tipsFlag: OneTimeFlag = OneTimeFlag.InMemory(),
    private val calibrateRoom: (Floor, Room) -> Room = FloorOps::applyCalibration,
    private val snapshotStore: CaptureSnapshotStore = CaptureSnapshotStore.None,
) : ViewModel() {

    /** A snapshot restored after process death; consumed by the first [attach]. */
    private var pendingRestore: CaptureSnapshot? = try {
        CaptureSnapshot.decode(snapshotStore.restore())?.takeIf { it.projectId == projectId && it.floorId == floorId }
    } catch (e: Exception) {
        null
    }

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    private val mutableCoaching = MutableStateFlow<CoachingHint?>(null)

    /** Live coaching hint derived from the AR frames (changes rarely; separate from [state] for that reason). */
    val coaching: StateFlow<CoachingHint?> = mutableCoaching.asStateFlow()

    private val coach = CaptureCoach()
    private var nextMessageId = 1L
    private var nextFeedbackId = 1L

    // Auto floor lock: a lockable floor whose height stays within tolerance for AUTO_LOCK_STABLE_NS.
    private var floorCandidateY: Double? = null
    private var floorCandidateSinceNs = 0L

    /**
     * Height of the last manual lock that was questioned (raised surface, or the height just re-locked from).
     * A second Lock floor tap at about the same height keeps it.
     */
    private var floorWarnedY: Double? = null

    /**
     * Latest anchor corrections of the current session (room id → raw correction), refreshed from the frames
     * about once a second and when the app goes to the background. Used for the session axis in the current
     * tracking frame and persisted in the snapshot.
     */
    private var latestCorrections: Map<String, RigidTransform2> = emptyMap()
    private var correctionsRefreshedNs: Long? = null

    /** Raw anchor corrections of an AR session that ended (activity recreated) before the flow finished. */
    private var orphanedSession: OrphanedSession? = null

    private class OrphanedSession(val sessionId: String, val corrections: Map<String, RigidTransform2>)

    init {
        snapshotStore.register(::snapshotJson)
        viewModelScope.launch { loadProject() }
    }

    private fun initialState(): CaptureUiState {
        val base = CaptureUiState(draft = RoomDraft(id = Ids.newId()), showTips = !tipsFlag.isSet())
        val restore = pendingRestore ?: return base
        return try {
            restore.restoreInto(base)
        } catch (e: Exception) {
            pendingRestore = null
            base
        }
    }

    /** What [snapshotStore] saves: null once there is nothing worth restoring. Never throws. */
    private fun snapshotJson(): String? {
        val s = mutableState.value
        if (s.finished || s.loadError != null) return null
        if (s.sessionId == null && !s.draft.hasCaptures && s.interrupted == null) return null
        val ids = s.sessionRoomIds + s.draft.id
        val corrections = (pendingRestore?.corrections.orEmpty() + latestCorrections).filterKeys { it in ids }
        return try {
            CaptureSnapshot.of(projectId, floorId, s, corrections).encode()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadProject() {
        val project = try {
            repository.get(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(loadError = "Couldn't open this project. Go back and try again.") }
            return
        }
        if (project == null) {
            mutableState.update { it.copy(loadError = "This project no longer exists.") }
            return
        }
        applyProject(project)
    }

    /** Take settings, calibration and the floor's rooms from a freshly loaded / saved project. */
    private fun applyProject(project: Project) {
        mutableState.update { s ->
            val settingsChanged = s.settings != project.settings
            val floor = project.floors.firstOrNull { it.id == floorId }
            val next = s.copy(
                settings = project.settings,
                projectLoaded = true,
                floorRooms = floor?.rooms.orEmpty(),
                floorCalibration = floor?.calibration,
            )
            if (settingsChanged && s.step.isCaptureStep) next.copy(draft = next.draft.copy(solve = solveDraft(next.draft, next))) else next
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AR session / frames
    // ---------------------------------------------------------------------------------------------

    /**
     * Bind to the running AR session. Rooms, the floor lock and anchors only make sense within one session
     * (one coordinate frame), so if the session was recreated (activity recreated, or the process was killed
     * in the background and restored), the floor is unlocked and the room in progress restarts. Rooms saved in
     * the previous session get the anchor corrections captured when it ended or went to the background, and a
     * notice explains what happened ([CaptureUiState.interrupted]), offering to save a room in progress that
     * was already solved.
     */
    fun attach(controller: ArCaptureController) {
        val sid = controller.sessionId
        val s = mutableState.value
        val restore = pendingRestore
        pendingRestore = null
        if (s.sessionId == sid) {
            orphanedSession = null // the same session is back (e.g. recomposition); nothing was lost
            return
        }
        if (s.sessionId == null) {
            mutableState.update { it.copy(sessionId = sid) }
            return
        }
        val oldSession = s.sessionId
        val oldCorrections = orphanedSession?.takeIf { it.sessionId == oldSession }?.corrections
            ?: restore?.takeIf { it.sessionId == oldSession }?.corrections.orEmpty()
        orphanedSession = null
        val saved = s.sessionRoomIds
        val restorable = restorableRoom(s, oldSession, oldCorrections[s.draft.id])
        val lostWork = s.hasRoomInProgress
        resetFloorCandidate()
        floorWarnedY = null
        latestCorrections = emptyMap()
        correctionsRefreshedNs = null
        mutableState.update {
            it.copy(
                sessionId = sid,
                floorY = null,
                autoLockFloor = true,
                rejectedFloorY = null,
                step = CaptureStep.FIND_FLOOR,
                draft = freshDraft(it.draft.method),
                sessionRoomIds = emptySet(),
                sessionRoomScales = emptyMap(),
                sessionAxis = null,
                lastMeasuredCeiling = null,
                confirmDiscard = false,
                confirmEndSession = false,
                interrupted = if (saved.isNotEmpty() || lostWork) {
                    InterruptedScan(saved.size, lostRoom = lostWork && restorable == null, restorable = restorable)
                } else {
                    it.interrupted
                },
            )
        }
        val roomCorrections = oldCorrections.filterKeys { it in saved }
        if (roomCorrections.isNotEmpty()) persistCorrections(roomCorrections, s.sessionRoomScales)
    }

    /** The draft of [s] as a room that can still be saved after its session ended, or null when not solved. */
    private fun restorableRoom(s: CaptureUiState, sessionId: String, correction: RigidTransform2?): RestorableRoom? {
        val d = s.draft
        if (d.solve == null || d.toRoom(sessionId, s.settings) == null) return null
        val c = correction?.takeIf { it.angle.isFinite() && it.translation.isFinite() }
        val clean = d.copy(history = emptyList(), recaptureIndex = null, baseline = null, pendingOpeningEdge = null, offerBaseline = false)
        return RestorableRoom(clean, sessionId, c)
    }

    /**
     * The UI is letting go of [controller] (its AR session is ending, e.g. the activity is being recreated).
     * Snapshot the anchor corrections of this session's saved rooms (and the draft's) while the controller can
     * still answer, so a following [attach] to a new session (or [exit]) can apply them.
     */
    fun onSessionEnded(controller: ArCaptureController) {
        val s = mutableState.value
        if (s.finished || s.sessionId == null || controller.sessionId != s.sessionId) return
        if (s.sessionRoomIds.isEmpty() && !s.draft.anchorCreated) return
        val corrections = safeCorrections(controller).filterKeys { it in s.sessionRoomIds || it == s.draft.id }
        orphanedSession = OrphanedSession(s.sessionId, corrections)
    }

    /**
     * The app is going to the background (ON_PAUSE / ON_STOP): refresh the anchor corrections now, so the
     * process-death snapshot carries them (Android may kill the process without any further callback).
     */
    fun onBackground(controller: ArCaptureController) {
        refreshCorrections(controller, nowNs = 0L, force = true)
    }

    private fun refreshCorrections(controller: ArCaptureController, nowNs: Long, force: Boolean = false) {
        val s = mutableState.value
        if (s.sessionId == null || controller.sessionId != s.sessionId) return
        val last = correctionsRefreshedNs
        if (!force && last != null && nowNs - last in 0 until CORRECTIONS_REFRESH_NS) return
        correctionsRefreshedNs = nowNs
        if (s.sessionRoomIds.isEmpty() && !s.draft.anchorCreated) return
        latestCorrections = safeCorrections(controller)
    }

    private fun persistCorrections(raw: Map<String, RigidTransform2>, scales: Map<String, AppliedScale>) {
        val corrections = raw.mapValues { (id, t) -> t.forScaledRoom(scales[id]) }
        viewModelScope.launch {
            try {
                repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = false) { applyCorrections(it, corrections) }
                }?.let(::applyProject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the rooms are saved, just not re-aligned by the old session's map refinements.
            }
        }
    }

    /**
     * Called for every published AR frame. Tells the AR layer the locked floor, refreshes the anchor
     * corrections now and then, updates the live coaching hint, auto-locks the floor once the AR layer reports
     * one it can lock by itself ([autoLockHeight]) at the same height for about a second, and creates the room
     * anchor as soon as the camera pose is available.
     */
    fun onFrame(frame: ArFrameState, controller: ArCaptureController, nowNs: Long = System.nanoTime()) {
        val s = mutableState.value
        try {
            controller.setReferenceFloor(if (controller.sessionId == s.sessionId) s.floorY else null)
        } catch (e: Exception) {
            // Cosmetic only (the reticle's floor fallback).
        }
        refreshCorrections(controller, nowNs)
        updateCoaching(frame, s)
        if (s.step.isCaptureStep && !s.draft.anchorCreated) ensureAnchor(controller, frame)

        if (s.step != CaptureStep.FIND_FLOOR || s.floorY != null || s.busy || s.loadError != null ||
            !s.autoLockFloor || s.showTips || s.finished || s.interrupted != null || s.confirmDiscard || s.confirmEndSession
        ) {
            resetFloorCandidate()
            return
        }
        val y = autoLockHeight(frame, s.rejectedFloorY)
        if (y == null) {
            resetFloorCandidate()
            return
        }
        val candidate = floorCandidateY
        if (candidate == null || abs(candidate - y) > AUTO_LOCK_TOLERANCE) {
            floorCandidateY = y
            floorCandidateSinceNs = nowNs
            return
        }
        if (nowNs - floorCandidateSinceNs >= AUTO_LOCK_STABLE_NS) {
            resetFloorCandidate()
            acceptFloor(y, frame, manual = false)
        }
    }

    private fun updateCoaching(frame: ArFrameState, s: CaptureUiState) {
        val d = s.draft
        val markingFloor = when (s.step) {
            CaptureStep.WALLS -> d.baseline != null
            CaptureStep.CORNERS -> true
            CaptureStep.OPENINGS -> d.openingType != OpeningType.WINDOW
            else -> false
        }
        val floorY = s.floorY
        val input = CoachInput(
            step = s.step,
            floorY = floorY,
            bandTopY = floorY?.let { wallBandTopY(d, it, s.settings) },
            ceilingMeasured = d.ceilingMeasured,
            measuring = s.measuring,
            markingFloorPoints = markingFloor,
            canRelockFloor = s.canRelockFloor,
            units = s.settings.unitSystem,
        )
        val hint = try {
            coach.update(frame, input)
        } catch (e: Exception) {
            null
        }
        mutableCoaching.value = if (s.showTips || s.finished) null else hint
    }

    private fun resetFloorCandidate() {
        floorCandidateY = null
        floorCandidateSinceNs = 0L
    }

    // ---------------------------------------------------------------------------------------------
    // FIND_FLOOR / CEILING
    // ---------------------------------------------------------------------------------------------

    /** "Lock floor": lock the plane under the reticle (or the current best floor). */
    fun lockFloor(controller: ArCaptureController) {
        launchMeasurement(CaptureStep.FIND_FLOOR) { draftId ->
            val result = measureSafely(controller, MeasureRequest.LockFloor)
            if (isStale(draftId, CaptureStep.FIND_FLOOR)) return@launchMeasurement
            when (result) {
                is MeasureResult.FloorLocked -> acceptFloor(result.floorY, controller.frameState.value, manual = true)
                is MeasureResult.Failed -> measurementFailed(result.reason)
                else -> measurementFailed(MSG_FLOOR_FAILED)
            }
        }
    }

    /**
     * Validate and lock floor height [y]. Rejects heights at or above the phone. A manual lock that looks
     * wrong ([manualLockWarning]: a raised surface above the floor under the user, or the height just rejected
     * with "Re-lock floor") is questioned once; a second Lock floor tap at about the same height keeps it, so
     * the user is never trapped (split levels, step-down rooms). A measured ceiling is kept at its height in
     * the world, i.e. its floor-to-ceiling height follows the new floor.
     */
    private fun acceptFloor(y: Double, frame: ArFrameState, manual: Boolean) {
        if (!y.isFinite()) {
            measurementFailed(MSG_FLOOR_FAILED)
            return
        }
        val camera = frame.cameraPosition
        if (camera != null && camera.isFinite() && camera.y - y < MIN_CAMERA_ABOVE_FLOOR) {
            measurementFailed("That surface is too close to the phone's height. Aim at the floor.")
            return
        }
        val s = mutableState.value
        if (manual) {
            val warning = manualLockWarning(y, frame, s.rejectedFloorY, s.settings.unitSystem)
            val warned = floorWarnedY
            if (warning != null && (warned == null || abs(warned - y) > REJECTED_FLOOR_TOLERANCE)) {
                floorWarnedY = y
                measurementFailed(warning)
                return
            }
        }
        floorWarnedY = null
        mutableState.update { st ->
            st.copy(floorY = y, step = CaptureStep.CEILING, draft = st.draft.withFloorAt(y))
        }
        feedback(FeedbackKind.CAPTURED)
        postMessage("Floor locked.")
    }

    /**
     * Go back to finding the floor (it locked onto a rug, step, bed or table, or this room is at another level).
     * Only before any capture of the room. The session and its saved rooms are kept. Auto-lock is turned off
     * for the rest of this AR session, so the same surface can't lock again by itself before the user aims at
     * the real floor and taps Lock floor.
     */
    fun relockFloor() {
        val s = mutableState.value
        if (s.busy || !s.canRelockFloor) return
        resetFloorCandidate()
        floorWarnedY = null
        mutableState.update {
            it.copy(floorY = null, step = CaptureStep.FIND_FLOOR, autoLockFloor = false, rejectedFloorY = it.floorY ?: it.rejectedFloorY)
        }
        postMessage("Floor unlocked. Aim at open floor, away from furniture, and tap Lock floor.")
    }

    fun measureCeiling(controller: ArCaptureController) {
        val floorY = mutableState.value.floorY ?: return
        launchMeasurement(CaptureStep.CEILING) { draftId ->
            val result = measureSafely(controller, MeasureRequest.CeilingHeight(floorY))
            if (isStale(draftId, CaptureStep.CEILING)) return@launchMeasurement
            when (result) {
                is MeasureResult.Height -> {
                    val h = result.height
                    if (!h.isFinite() || h < MIN_CEILING || h > MAX_CEILING) {
                        measurementFailed("That ceiling reading looks off. Aim straight up at a clear spot and try again.")
                    } else {
                        mutableState.update {
                            it.copy(draft = it.draft.copy(ceilingHeight = h, ceilingMeasured = true, ceilingY = floorY + h))
                        }
                        feedback(FeedbackKind.CAPTURED)
                        val s = mutableState.value
                        postMessage("Ceiling: ${formatLengthSafe(s.savedLength(h), s.settings)}.")
                        enterCaptureStep(controller)
                    }
                }
                is MeasureResult.Failed -> measurementFailed(result.reason)
                else -> measurementFailed("Couldn't measure the ceiling. Try again or tap Skip.")
            }
        }
    }

    /** Skip the ceiling: the project default height is saved, marked as estimated. */
    fun skipCeiling(controller: ArCaptureController) {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.CEILING) return
        mutableState.update { it.copy(draft = it.draft.copy(ceilingHeight = null, ceilingMeasured = false, ceilingY = null)) }
        enterCaptureStep(controller)
    }

    /** Use the last measured ceiling of this session for this room (saved as an estimate, not a measurement). */
    fun useLastCeiling(controller: ArCaptureController) {
        val s = mutableState.value
        val h = s.lastMeasuredCeiling ?: return
        if (s.busy || s.step != CaptureStep.CEILING) return
        mutableState.update { it.copy(draft = it.draft.copy(ceilingHeight = h, ceilingMeasured = false, ceilingY = null)) }
        postMessage("Using ${formatLengthSafe(h, s.settings)} from the last room (marked as estimated).")
        enterCaptureStep(controller)
    }

    /** Back in CEILING with a height already chosen for this room (Back from walls): keep it and go on. */
    fun keepCeiling(controller: ArCaptureController) {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.CEILING || s.draft.ceilingHeight == null) return
        enterCaptureStep(controller)
    }

    private fun enterCaptureStep(controller: ArCaptureController) {
        mutableState.update { s -> s.copy(step = s.draft.captureStep) }
        ensureAnchor(controller, controller.frameState.value)
    }

    /**
     * Request the room's ARCore anchor (on the floor below the camera) once per draft. Deferred until the
     * camera pose is known; retried from [onFrame] and before each capture.
     */
    private fun ensureAnchor(controller: ArCaptureController, frame: ArFrameState) {
        val s = mutableState.value
        if (s.draft.anchorCreated || !s.step.isCaptureStep) return
        val floorY = s.floorY ?: return
        val camera = frame.cameraPosition?.takeIf { it.isFinite() } ?: return
        val created = try {
            controller.createRoomAnchor(s.draft.id, Vec3(camera.x, floorY, camera.z))
            true
        } catch (e: Exception) {
            false
        }
        if (created) mutableState.update { it.copy(draft = it.draft.copy(anchorCreated = true)) }
    }

    // ---------------------------------------------------------------------------------------------
    // WALLS / CORNERS
    // ---------------------------------------------------------------------------------------------

    /** Switch between wall and corner capture; only before the first capture of the room. */
    fun setMethod(method: CaptureMethod) {
        val s = mutableState.value
        if (s.busy || !s.step.isCaptureStep || s.draft.captureCount > 0 || method == CaptureMethod.MANUAL) return
        mutableState.update {
            it.copy(
                step = if (method == CaptureMethod.CORNERS) CaptureStep.CORNERS else CaptureStep.WALLS,
                draft = it.draft.copy(method = method, baseline = null, offerBaseline = false, solve = null, recaptureIndex = null),
            )
        }
    }

    fun captureWall(controller: ArCaptureController) {
        val s = mutableState.value
        val floorY = s.floorY ?: return
        if (s.draft.baseline != null) return
        ensureAnchor(controller, controller.frameState.value)
        val bandTop = wallBandTopY(s.draft, floorY, s.settings)
        launchMeasurement(CaptureStep.WALLS) { draftId ->
            val result = measureSafely(controller, MeasureRequest.Wall(floorY, bandTop))
            if (isStale(draftId, CaptureStep.WALLS)) return@launchMeasurement
            when (result) {
                is MeasureResult.WallMeasured -> {
                    val observation = result.fit.observation
                    if (observation != null && observation.isUsable()) {
                        addObservation(observation)
                    } else {
                        wallFailed(result.fit.failure?.message ?: MSG_WALL_FAILED)
                    }
                }
                is MeasureResult.Failed -> wallFailed(result.reason)
                else -> wallFailed(MSG_WALL_FAILED)
            }
        }
    }

    private fun wallFailed(reason: String) {
        mutableState.update { it.copy(draft = it.draft.copy(offerBaseline = true)) }
        measurementFailed(reason)
    }

    /** Append [observation], or replace the wall being re-captured. */
    private fun addObservation(observation: WallObservation) {
        val d = mutableState.value.draft
        val replace = d.recaptureIndex?.takeIf { it in d.observations.indices }
        val grade = wallGrade(observation)
        if (replace != null) {
            val n = replace + 1
            recordEdit("Wall $n is back to its earlier capture.") {
                it.copy(observations = it.observations.replaced(replace, observation), recaptureIndex = null, baseline = null, offerBaseline = false)
            }
            feedback(FeedbackKind.CAPTURED)
            postMessage(if (grade == CaptureGrade.POOR) "Wall $n re-captured, but the reading is still rough." else "Wall $n re-captured.")
            return
        }
        val n = d.observations.size + 1
        recordEdit("Removed wall $n.") {
            it.copy(observations = it.observations + observation, recaptureIndex = null, baseline = null, offerBaseline = false)
        }
        feedback(FeedbackKind.CAPTURED)
        postMessage(
            when (grade) {
                CaptureGrade.GOOD -> "Wall $n captured."
                CaptureGrade.FAIR -> "Wall $n captured (fair reading)."
                CaptureGrade.POOR -> "Wall $n captured, but the reading is rough. Tap it in the wall list to re-capture."
            },
        )
    }

    /** Start the baseline fallback: mark two points where the wall meets the floor. */
    fun startBaseline() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.WALLS || s.draft.baseline != null) return
        mutableState.update { it.copy(draft = it.draft.copy(baseline = BaselineCapture(), offerBaseline = false)) }
    }

    fun cancelBaseline() {
        val s = mutableState.value
        if (s.busy || s.draft.baseline == null) return
        mutableState.update { it.copy(draft = it.draft.copy(baseline = null)) }
    }

    /** Mark one baseline point (floor ray); the second point completes the wall. */
    fun markBaselinePoint(controller: ArCaptureController) {
        val s = mutableState.value
        val floorY = s.floorY ?: return
        if (s.draft.baseline == null) return
        ensureAnchor(controller, controller.frameState.value)
        launchMeasurement(CaptureStep.WALLS) { draftId ->
            val result = measureSafely(controller, MeasureRequest.FloorPoint(floorY))
            if (isStale(draftId, CaptureStep.WALLS)) return@launchMeasurement
            val baseline = mutableState.value.draft.baseline ?: return@launchMeasurement
            when (result) {
                is MeasureResult.Point -> {
                    if (!result.world.isFinite()) {
                        measurementFailed(MSG_POINT_FAILED)
                        return@launchMeasurement
                    }
                    val first = baseline.firstTap
                    if (first == null) {
                        mutableState.update { it.copy(draft = it.draft.copy(baseline = BaselineCapture(result.world))) }
                        feedback(FeedbackKind.CAPTURED)
                    } else {
                        completeBaseline(first, result.world, controller.frameState.value.cameraPosition)
                    }
                }
                is MeasureResult.Failed -> measurementFailed(result.reason)
                else -> measurementFailed(MSG_POINT_FAILED)
            }
        }
    }

    private fun completeBaseline(a: Vec3, b: Vec3, viewer: Vec3?) {
        if (viewer == null || !viewer.isFinite()) {
            measurementFailed("Lost track of the phone's position. Hold steady and mark the second point again.")
            return
        }
        val observation = try {
            WallEstimator.fromBaseline(a, b, viewer)
        } catch (e: Exception) {
            null
        }
        if (observation == null || !observation.isUsable()) {
            measurementFailed("Couldn't make a wall from those points. Mark both ends of the wall base, well apart, from inside the room.")
            return
        }
        addObservation(observation)
    }

    fun addCorner(controller: ArCaptureController) {
        val floorY = mutableState.value.floorY ?: return
        ensureAnchor(controller, controller.frameState.value)
        launchMeasurement(CaptureStep.CORNERS) { draftId ->
            val result = measureSafely(controller, MeasureRequest.FloorPoint(floorY))
            if (isStale(draftId, CaptureStep.CORNERS)) return@launchMeasurement
            when (result) {
                is MeasureResult.Point -> addCornerTap(result.world)
                is MeasureResult.Failed -> measurementFailed(result.reason)
                else -> measurementFailed(MSG_POINT_FAILED)
            }
        }
    }

    private fun addCornerTap(world: Vec3) {
        if (!world.isFinite()) {
            measurementFailed(MSG_POINT_FAILED)
            return
        }
        val p = world.toPlan()
        val d = mutableState.value.draft
        val taps = d.cornerTaps
        val replace = d.recaptureIndex?.takeIf { it in taps.indices }
        if (replace != null) {
            if (taps.withIndex().any { (i, t) -> i != replace && t.distanceTo(p) < MIN_CORNER_SPACING }) {
                measurementFailed("That's another corner you already marked. Aim at corner ${replace + 1}.")
                return
            }
            recordEdit("Corner ${replace + 1} is back to its earlier position.") {
                it.copy(cornerTaps = it.cornerTaps.replaced(replace, p), recaptureIndex = null)
            }
            feedback(FeedbackKind.CAPTURED)
            postMessage("Corner ${replace + 1} re-captured.")
            return
        }
        taps.lastOrNull()?.let { last ->
            if (last.distanceTo(p) < MIN_CORNER_SPACING) {
                measurementFailed("That's the same corner as before. Aim at the next corner.")
                return
            }
        }
        if (taps.size >= 3 && taps.first().distanceTo(p) < CLOSE_TO_FIRST_CORNER) {
            postMessage("That's your first corner. Tap Close room to finish.")
            return
        }
        recordEdit("Removed corner ${taps.size + 1}.") { it.copy(cornerTaps = it.cornerTaps + p, recaptureIndex = null) }
        feedback(FeedbackKind.CAPTURED)
    }

    /**
     * "Re-capture" wall (WALLS) or corner (CORNERS) [index]: the next capture replaces it in place, so the
     * capture order the solver relies on is kept. Calling it for the same index again cancels.
     */
    fun startRecapture(index: Int) {
        val s = mutableState.value
        if (s.busy || !s.step.isCaptureStep) return
        val d = s.draft
        val count = if (d.method == CaptureMethod.CORNERS) d.cornerTaps.size else d.observations.size
        if (index !in 0 until count) return
        if (d.recaptureIndex == index) {
            cancelRecapture()
            return
        }
        mutableState.update { it.copy(draft = it.draft.copy(recaptureIndex = index, baseline = null, offerBaseline = false)) }
    }

    fun cancelRecapture() {
        val s = mutableState.value
        if (s.busy || s.draft.recaptureIndex == null) return
        mutableState.update { it.copy(draft = it.draft.copy(recaptureIndex = null, baseline = null)) }
    }

    /** Delete wall (WALLS) or corner (CORNERS) [index]; later ones move up by one. Undo restores it. */
    fun deleteCapture(index: Int) {
        val s = mutableState.value
        if (s.busy || !s.step.isCaptureStep) return
        val d = s.draft
        val corners = d.method == CaptureMethod.CORNERS
        val count = if (corners) d.cornerTaps.size else d.observations.size
        if (index !in 0 until count) return
        val noun = if (corners) "Corner" else "Wall"
        val n = index + 1
        recordEdit("$noun $n restored.") {
            if (corners) {
                it.copy(cornerTaps = it.cornerTaps.removedAt(index), recaptureIndex = null)
            } else {
                it.copy(observations = it.observations.removedAt(index), recaptureIndex = null, baseline = null)
            }
        }
        postMessage(if (index == count - 1) "$noun $n deleted." else "$noun $n deleted. The ones after it moved up a number.")
    }

    /**
     * Undo, most specific first: the first baseline mark, then a pending re-capture, then the last wall /
     * corner edit (add, re-capture or delete); in OPENINGS the pending edge, then the last opening, then
     * reopen the room; in REVIEW, go back to doors & windows.
     */
    fun undo() {
        val s = mutableState.value
        if (s.busy) return
        val d = s.draft
        when (s.step) {
            CaptureStep.WALLS, CaptureStep.CORNERS -> when {
                d.baseline?.firstTap != null ->
                    mutableState.update { it.copy(draft = it.draft.copy(baseline = BaselineCapture())) }
                d.recaptureIndex != null -> cancelRecapture()
                d.history.isNotEmpty() -> {
                    val edit = d.history.last()
                    mutateCapture {
                        it.copy(
                            observations = edit.observations,
                            cornerTaps = edit.cornerTaps,
                            history = it.history.dropLast(1),
                            recaptureIndex = null,
                        )
                    }
                    postMessage(edit.undoMessage)
                }
            }
            CaptureStep.OPENINGS -> when {
                d.pendingOpeningEdge != null ->
                    mutableState.update { it.copy(draft = it.draft.copy(pendingOpeningEdge = null)) }
                d.openings.isNotEmpty() -> {
                    val removed = d.openings.last()
                    mutableState.update { it.copy(draft = it.draft.copy(openings = it.draft.openings.dropLast(1))) }
                    postMessage("${openingLabel(removed.type)} removed.")
                }
                else -> reopenRoom()
            }
            CaptureStep.REVIEW -> backToOpenings()
            else -> Unit
        }
    }

    /** "Edit walls" / "Back to walls": reopen the room for capturing, keeping walls, corners and openings. */
    fun backToWalls() {
        val s = mutableState.value
        if (s.busy || (s.step != CaptureStep.OPENINGS && s.step != CaptureStep.REVIEW)) return
        reopenRoom()
    }

    /**
     * Back to WALLS / CORNERS with every capture kept. Openings are parked at their plan position (edge
     * indices change when walls are edited) and put back on the nearest wall when the room is closed again.
     */
    private fun reopenRoom() {
        val s = mutableState.value
        val parked = s.draftRoom?.let(::parkOpenings).orEmpty()
        mutableState.update {
            val draft = it.draft.copy(
                openings = emptyList(),
                parkedOpenings = it.draft.parkedOpenings + parked,
                pendingOpeningEdge = null,
                recaptureIndex = null,
            )
            it.copy(step = draft.captureStep, draft = draft.copy(solve = solveDraft(draft, it)))
        }
        val noun = if (s.draft.method == CaptureMethod.CORNERS) "corner" else "wall"
        postMessage(
            if (parked.isEmpty()) {
                "Room reopened. Tap a $noun in the list to fix it, or keep capturing."
            } else {
                "Room reopened. Your doors and windows are kept. Tap a $noun to fix it."
            },
        )
    }

    /** "Close room": needs ≥ 3 captures and a valid solved outline. Moves on to doors & windows. */
    fun closeRoom() {
        val s = mutableState.value
        if (s.busy || !s.step.isCaptureStep) return
        val d = s.draft
        val corners = d.method == CaptureMethod.CORNERS
        val noun = if (corners) "corners" else "walls"
        if (d.captureCount < 3) {
            postMessage("Capture at least 3 $noun first (you have ${d.captureCount}).")
            return
        }
        val solved = d.solve
        val room = d.copy(openings = emptyList()).toRoom(s.sessionId, s.settings)
        if (solved == null || room == null) {
            postMessage(
                if (corners) {
                    "These corners don't form a room yet. Mark the missing ones in order, or delete the odd one out."
                } else {
                    "These walls don't close into a room yet. Capture the missing walls in order, or delete the odd one out."
                },
            )
            return
        }
        val restored = restoreOpenings(room, d.parkedOpenings)
        mutableState.update {
            it.copy(
                step = CaptureStep.OPENINGS,
                draft = it.draft.copy(
                    openings = restored.openings,
                    parkedOpenings = emptyList(),
                    baseline = null,
                    offerBaseline = false,
                    pendingOpeningEdge = null,
                    recaptureIndex = null,
                ),
            )
        }
        feedback(FeedbackKind.CONFIRMED)
        val warnings = solved.warnings.size
        postMessage(
            when {
                restored.dropped.isNotEmpty() -> {
                    val n = restored.dropped.size
                    "Room closed. ${if (n == 1) "1 door or window no longer fits a wall and was" else "$n doors or windows no longer fit a wall and were"} removed. Mark it again if needed."
                }
                warnings > 0 -> "Room closed with $warnings ${if (warnings == 1) "warning" else "warnings"}. Check before saving."
                restored.openings.isNotEmpty() -> "Room closed. Your doors and windows are back in place."
                else -> "Room closed. Mark doors and windows, or tap Done."
            },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // OPENINGS
    // ---------------------------------------------------------------------------------------------

    fun setOpeningType(type: OpeningType) {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.OPENINGS || s.draft.openingType == type) return
        // The two edges of one opening must be measured the same way (floor vs. wall surface).
        mutableState.update { it.copy(draft = it.draft.copy(openingType = type, pendingOpeningEdge = null)) }
    }

    /**
     * Mark one side of the opening: a floor point for doors / openings, a surface point (window frame)
     * for windows. The second mark places the opening on the nearest wall.
     */
    fun markOpeningEdge(controller: ArCaptureController) {
        val s = mutableState.value
        val floorY = s.floorY ?: return
        if (s.draftRoom == null) return
        val type = s.draft.openingType
        val request = if (type == OpeningType.WINDOW) MeasureRequest.SurfacePoint() else MeasureRequest.FloorPoint(floorY)
        launchMeasurement(CaptureStep.OPENINGS) { draftId ->
            val result = measureSafely(controller, request)
            if (isStale(draftId, CaptureStep.OPENINGS)) return@launchMeasurement
            when (result) {
                is MeasureResult.Point ->
                    if (result.world.isFinite()) onOpeningPoint(result.world.toPlan(), type) else measurementFailed(MSG_POINT_FAILED)
                is MeasureResult.Failed -> measurementFailed(result.reason)
                else -> measurementFailed(MSG_POINT_FAILED)
            }
        }
    }

    private fun onOpeningPoint(p: Vec2, type: OpeningType) {
        val s = mutableState.value
        if (s.draft.openingType != type) return
        val first = s.draft.pendingOpeningEdge
        if (first == null) {
            mutableState.update { it.copy(draft = it.draft.copy(pendingOpeningEdge = p)) }
            feedback(FeedbackKind.CAPTURED)
            return
        }
        val room = s.draftRoom ?: return
        val settings = s.settings
        val (height, sill) = when (type) {
            OpeningType.WINDOW -> settings.defaultWindowHeight to settings.defaultWindowSill
            OpeningType.DOOR, OpeningType.OPENING -> settings.defaultDoorHeight to 0.0
        }
        val opening = try {
            RoomOps.placeOpening(room, first, p, type, height, sill, Ids.newId())
        } catch (e: Exception) {
            null
        }
        if (opening == null) {
            measurementFailed("Couldn't place that on a wall. Mark both edges within 0.5 m (20 in) of a wall, at least 20 cm (8 in) apart.")
            return
        }
        if (overlapsExisting(opening, room.openings)) {
            measurementFailed("That overlaps an opening already marked. Undo it first or mark a different spot.")
            return
        }
        mutableState.update { it.copy(draft = it.draft.copy(openings = it.draft.openings + opening, pendingOpeningEdge = null)) }
        feedback(FeedbackKind.CAPTURED)
        postMessage("${openingLabel(type)} added (${formatLengthSafe(s.savedLength(opening.width), settings)} wide).")
    }

    fun deleteOpening(openingId: String) {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.OPENINGS) return
        val removed = s.draft.openings.firstOrNull { it.id == openingId } ?: return
        mutableState.update { it.copy(draft = it.draft.copy(openings = it.draft.openings.filterNot { o -> o.id == openingId })) }
        postMessage("${openingLabel(removed.type)} removed.")
    }

    /** Done with doors and windows → review. */
    fun finishOpenings() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.OPENINGS || s.draft.solve == null) return
        mutableState.update {
            val d = it.draft
            val name = if (d.nameEdited && d.name.isNotBlank()) d.name else RoomNames.unique(d.type, it.floorRooms, d.id)
            it.copy(step = CaptureStep.REVIEW, draft = d.copy(pendingOpeningEdge = null, name = name))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REVIEW
    // ---------------------------------------------------------------------------------------------

    /**
     * The draft exactly as it would be saved now into the floor: [CaptureUiState.draftRoom] passed through the
     * same calibration as the save, so the review's preview, sizes, stats and quality match the saved room.
     * Null without a solved outline. The raw [CaptureUiState.draftRoom] stays the one for the AR overlay.
     */
    fun roomAsSaved(ui: CaptureUiState = mutableState.value): Room? {
        val room = ui.draftRoom ?: return null
        if (ui.savedScale == null) return room
        val floor = Floor(id = floorId, name = "", rooms = ui.floorRooms, calibration = ui.floorCalibration)
        return calibrated(floor, room).room
    }

    fun setName(name: String) {
        if (mutableState.value.step != CaptureStep.REVIEW) return
        mutableState.update { it.copy(draft = it.draft.copy(name = name.take(MAX_NAME_LENGTH), nameEdited = name.isNotBlank())) }
    }

    /** Change the room type; renames the room to the type's default unless the user typed a name. */
    fun setType(type: RoomType) {
        if (mutableState.value.step != CaptureStep.REVIEW) return
        mutableState.update {
            val d = it.draft
            val name = if (d.nameEdited) d.name else RoomNames.unique(type, it.floorRooms, d.id)
            it.copy(draft = d.copy(type = type, name = name))
        }
    }

    /** REVIEW → OPENINGS with everything kept (system Back, "Doors & windows"). */
    fun backToOpenings() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.REVIEW) return
        mutableState.update { it.copy(step = CaptureStep.OPENINGS) }
    }

    /** Discard the captured walls / corners / openings of this room and capture it again. */
    fun redoRoom() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.REVIEW) return
        mutableState.update {
            val d = it.draft
            it.copy(
                step = d.captureStep,
                // Same id: the room anchor stays valid; the ceiling is kept.
                draft = RoomDraft(
                    id = d.id,
                    method = d.method,
                    ceilingHeight = d.ceilingHeight,
                    ceilingMeasured = d.ceilingMeasured,
                    ceilingY = d.ceilingY,
                    type = d.type,
                    name = d.name,
                    nameEdited = d.nameEdited,
                    anchorCreated = d.anchorCreated,
                ),
            )
        }
        postMessage("Room cleared. Capture it again, starting with wall 1.")
    }

    /** "Save & scan next room": save, then start a new room in the same session (floor stays locked). */
    fun saveAndScanNext(controller: ArCaptureController) {
        val room = roomToSave(controller) ?: return
        val squaredTo = mutableState.value.draft.solve?.dominantAngle?.takeIf { it.isFinite() }
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                var outcome = SaveOutcome(room, null, calibrationFailed = false)
                val saved = repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = true) { floor ->
                        outcome = calibrated(floor, room)
                        FloorOps.upsertRoom(floor, outcome.room)
                    }
                }
                if (saved == null) {
                    postMessage(MSG_PROJECT_MISSING)
                    return@launch
                }
                applyProject(saved)
                val stored = saved.floors.firstOrNull { it.id == floorId }?.rooms?.firstOrNull { it.id == room.id } ?: outcome.room
                val scale = outcome.scale
                mutableState.update { s ->
                    val sameSession = s.sessionId == room.sessionId
                    val measuredCeiling = stored.ceilingHeight?.takeIf { !stored.ceilingEstimated && it.isFinite() && it > 0.0 }
                    // The first room of the session sets the squaring axis every later room shares.
                    val axis = s.sessionAxis ?: squaredTo?.takeIf { sameSession }?.let { SessionAxis(canonicalAxis(it), room.id) }
                    s.copy(
                        sessionRoomIds = if (sameSession) s.sessionRoomIds + room.id else s.sessionRoomIds,
                        sessionRoomScales = if (sameSession && scale != null) s.sessionRoomScales + (room.id to scale) else s.sessionRoomScales,
                        sessionAxis = axis,
                        lastMeasuredCeiling = if (sameSession) measuredCeiling ?: s.lastMeasuredCeiling else s.lastMeasuredCeiling,
                        draft = freshDraft(s.draft.method),
                        step = if (s.floorY != null) CaptureStep.CEILING else CaptureStep.FIND_FLOOR,
                    )
                }
                feedback(FeedbackKind.CONFIRMED)
                postMessage(
                    when {
                        outcome.calibrationFailed -> "${room.name} saved, but the floor's tape calibration couldn't be applied. Check it in the editor."
                        outcome.scale != null -> "${room.name} saved and scaled to the floor's tape calibration. Walk into the next room."
                        else -> "${room.name} saved. Walk into the next room with the camera up."
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postMessage(MSG_SAVE_FAILED)
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    /** "Save & finish": save this room, apply anchor corrections to the session's rooms, then finish. */
    fun saveAndFinish(controller: ArCaptureController) {
        val room = roomToSave(controller) ?: return
        finishSession(controller, room)
    }

    // ---------------------------------------------------------------------------------------------
    // Leaving
    // ---------------------------------------------------------------------------------------------

    /**
     * X button, system Back with nothing to step back from, and Back on the AR host's error screens. Asks
     * first whenever leaving would cost something: "Discard this room?" with a room in progress, "End this
     * scan?" when rooms were saved in this session or a floor is locked (rooms scanned later won't line up);
     * otherwise leaves right away. [controller] is the live (or last) AR controller, null when no session
     * ever started.
     */
    fun requestExit(controller: ArCaptureController?) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        when {
            s.loadError != null -> exit(controller)
            s.hasRoomInProgress -> mutableState.update { it.copy(confirmDiscard = true, confirmEndSession = false) }
            s.sessionInProgress -> mutableState.update { it.copy(confirmEndSession = true, confirmDiscard = false) }
            else -> exit(controller)
        }
    }

    /**
     * System Back: first leave whatever transient mode is active (re-capture, wall-base marking, a half-marked
     * opening), step back out of REVIEW to doors & windows, from WALLS / CORNERS with nothing captured back
     * to the ceiling step (where the floor can be re-locked), or from finding the floor right after "Re-lock
     * floor" back to the floor locked before; none of which loses work. Otherwise [requestExit]. While a
     * measurement runs, Back goes straight to [requestExit].
     */
    fun onBack(controller: ArCaptureController?) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        val d = s.draft
        when {
            s.busy -> requestExit(controller)
            s.step == CaptureStep.REVIEW -> backToOpenings()
            s.step.isCaptureStep && d.recaptureIndex != null -> cancelRecapture()
            s.step == CaptureStep.WALLS && d.baseline != null -> cancelBaseline()
            s.step == CaptureStep.OPENINGS && d.pendingOpeningEdge != null ->
                mutableState.update { it.copy(draft = it.draft.copy(pendingOpeningEdge = null)) }
            s.step.isCaptureStep && !d.hasCaptures && s.floorY != null ->
                mutableState.update { it.copy(step = CaptureStep.CEILING, draft = it.draft.copy(offerBaseline = false, solve = null)) }
            s.step == CaptureStep.FIND_FLOOR && s.floorY == null && s.rejectedFloorY != null && !d.hasCaptures -> cancelRelock()
            else -> requestExit(controller)
        }
    }

    /** Back right after "Re-lock floor": keep the floor that was locked before (the height just unlocked). */
    private fun cancelRelock() {
        val previous = mutableState.value.rejectedFloorY ?: return
        resetFloorCandidate()
        floorWarnedY = null
        mutableState.update {
            it.copy(floorY = previous, rejectedFloorY = null, step = CaptureStep.CEILING, draft = it.draft.withFloorAt(previous))
        }
        postMessage("Kept the floor you had locked.")
    }

    fun dismissDiscard() {
        mutableState.update { it.copy(confirmDiscard = false) }
    }

    /** The user confirmed "Discard this room?" and chose to end the scan. */
    fun discardAndExit(controller: ArCaptureController?) {
        mutableState.update { it.copy(confirmDiscard = false) }
        exit(controller)
    }

    /**
     * "Discard room, keep scanning": drop the room in progress but stay in the AR session (its frame, floor lock
     * and saved rooms are kept), ready to scan this room again or the next one.
     */
    fun discardRoom() {
        val s = mutableState.value
        if (s.saving || s.finished) return
        mutableState.update {
            it.copy(
                confirmDiscard = false,
                draft = freshDraft(it.draft.method),
                step = if (it.floorY != null) CaptureStep.CEILING else CaptureStep.FIND_FLOOR,
            )
        }
        postMessage("Room discarded. Scan it again, or walk into the next room.")
    }

    fun dismissEndSession() {
        mutableState.update { it.copy(confirmEndSession = false) }
    }

    /** The user confirmed "End this scan?". */
    fun endSession(controller: ArCaptureController?) {
        mutableState.update { it.copy(confirmEndSession = false) }
        exit(controller)
    }

    /**
     * Leave the capture screen without saving the room in progress. Rooms saved in this session still get
     * their anchor corrections (from [controller], or from the snapshot taken when the session ended).
     */
    fun exit(controller: ArCaptureController?) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        if (s.loadError != null) {
            mutableState.update { it.copy(finished = true, confirmDiscard = false, confirmEndSession = false) }
            return
        }
        finishSession(controller, room = null)
    }

    // ---------------------------------------------------------------------------------------------
    // Interrupted scan
    // ---------------------------------------------------------------------------------------------

    /** Close the "scan interrupted" notice (the restorable room, if any, is dropped). */
    fun dismissInterrupted() {
        mutableState.update { it.copy(interrupted = null) }
    }

    /**
     * Save the room that was in progress when the scan was interrupted, as it was: in its old session's frame
     * (with its anchor correction), so it lines up with the rooms saved before the interruption.
     */
    fun saveInterruptedRoom() {
        val s = mutableState.value
        val restorable = s.interrupted?.restorable ?: return
        if (s.busy || s.finished) return
        if (s.loadError != null) {
            postMessage(MSG_PROJECT_MISSING)
            return
        }
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                var savedName: String? = null
                val saved = repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = true) { floor ->
                        val d = restorable.draft
                        val name = d.name.trim().takeIf { d.nameEdited && it.isNotEmpty() } ?: RoomNames.unique(d.type, floor.rooms, d.id)
                        val raw = d.copy(name = name).toRoom(restorable.sessionId, s.settings) ?: return@withFloor floor
                        val corrected = restorable.correction?.let { c ->
                            try {
                                RoomOps.transform(raw, c)
                            } catch (e: Exception) {
                                null
                            }
                        } ?: raw
                        savedName = name
                        FloorOps.upsertRoom(floor, calibrated(floor, corrected).room)
                    }
                }
                val name = savedName
                when {
                    saved == null -> postMessage(MSG_PROJECT_MISSING)
                    name == null -> postMessage(MSG_SAVE_FAILED)
                    else -> {
                        applyProject(saved)
                        mutableState.update { it.copy(interrupted = null) }
                        feedback(FeedbackKind.CONFIRMED)
                        postMessage("$name saved as it was scanned. Check its position in the editor.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postMessage(MSG_SAVE_FAILED)
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    private fun roomToSave(controller: ArCaptureController): Room? {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.REVIEW || s.finished) return null
        if (s.loadError != null) {
            postMessage(MSG_PROJECT_MISSING)
            return null
        }
        val d = s.draft
        val name = d.name.trim().ifBlank { RoomNames.unique(d.type, s.floorRooms, d.id) }
        val room = d.copy(name = name).toRoom(controller.sessionId, s.settings)
        if (room == null) {
            postMessage("This room has no valid outline. Tap Back to walls to fix it, or Redo room.")
            return null
        }
        return room
    }

    /** A room as it is saved into a floor, with the scale it got from the floor's calibration (if any). */
    private data class SaveOutcome(val room: Room, val scale: AppliedScale?, val calibrationFailed: Boolean)

    /**
     * [room] adjusted for [floor]'s tape calibration (FloorOps.applyCalibration). An uncalibrated floor
     * leaves it unchanged; if applying fails, the room is saved as captured and the caller tells the user.
     */
    private fun calibrated(floor: Floor, room: Room): SaveOutcome {
        val scale = AppliedScale.of(floor.calibration) ?: return SaveOutcome(room, null, calibrationFailed = false)
        return try {
            SaveOutcome(calibrateRoom(floor, room), scale, calibrationFailed = false)
        } catch (e: Exception) {
            SaveOutcome(room, null, calibrationFailed = true)
        }
    }

    /**
     * Save [room] (if any) and re-position every room of this session by its anchor's refined pose
     * (ARCore map corrections) in one atomic update, then mark the flow finished. Corrections are adjusted
     * for rooms that were scaled by the floor's calibration ([forScaledRoom]). When every room on the floor
     * comes from this session (a first scan: no rooms from other sessions, no drawn rooms), the floor is then
     * squared to the page ([straightenedIfOnlySession]) in the same update. Without a room to save, a failure
     * is not worth blocking the exit for: the rooms are already saved, just uncorrected.
     */
    private fun finishSession(controller: ArCaptureController?, room: Room?) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        val roomIds = s.sessionRoomIds + listOfNotNull(room?.id)
        if (roomIds.isEmpty()) {
            mutableState.update { it.copy(finished = true, confirmDiscard = false, confirmEndSession = false) }
            return
        }
        // A snapshot taken when the session ended (or restored after process death, when the user leaves before
        // a new session attached) beats asking a controller that has been closed since.
        val ended = orphanedSession?.takeIf { it.sessionId == s.sessionId }?.corrections
            ?: pendingRestore?.takeIf { it.sessionId == s.sessionId }?.corrections
        val raw = (ended ?: controller?.takeIf { it.sessionId == s.sessionId }?.let(::safeCorrections).orEmpty())
            .filterKeys { it in roomIds }
        val sessionId = room?.sessionId ?: s.sessionId
        mutableState.update { it.copy(saving = true, confirmDiscard = false, confirmEndSession = false) }
        viewModelScope.launch {
            var done = false
            try {
                val saved = repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = room != null) { floor ->
                        var scales = s.sessionRoomScales
                        val withRoom = if (room != null) {
                            val outcome = calibrated(floor, room)
                            outcome.scale?.let { scales = scales + (room.id to it) }
                            FloorOps.upsertRoom(floor, outcome.room)
                        } else {
                            floor
                        }
                        val corrected = applyCorrections(withRoom, raw.mapValues { (id, t) -> t.forScaledRoom(scales[id]) })
                        straightenedIfOnlySession(corrected, sessionId)
                    }
                }
                if (saved == null && room != null) {
                    postMessage(MSG_PROJECT_MISSING)
                } else {
                    done = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (room != null) postMessage(MSG_SAVE_FAILED) else done = true
            } finally {
                if (done) {
                    orphanedSession = null
                    pendingRestore = null
                }
                mutableState.update { it.copy(saving = false, finished = it.finished || done) }
            }
        }
    }

    private fun safeCorrections(controller: ArCaptureController): Map<String, RigidTransform2> = try {
        controller.anchorCorrections().filterValues { it.angle.isFinite() && it.translation.isFinite() }
    } catch (e: Exception) {
        emptyMap()
    }

    // ---------------------------------------------------------------------------------------------
    // Tips / messages / feedback
    // ---------------------------------------------------------------------------------------------

    /** Show the "How to scan" sheet (help button). */
    fun openTips() {
        mutableState.update { it.copy(showTips = true, tipsFromHelp = true) }
    }

    /** The "How to scan" sheet was closed: it won't open by itself again. */
    fun dismissTips() {
        if (!mutableState.value.showTips) return
        tipsFlag.set()
        mutableState.update { it.copy(showTips = false, tipsFromHelp = false) }
    }

    /**
     * The UI showed (or replaced) message [id]. Older messages are dropped too: the UI always shows the
     * newest one, so anything queued before it is stale.
     */
    fun onMessageShown(id: Long) {
        mutableState.update { s -> s.copy(messages = s.messages.filter { it.id > id }) }
    }

    /** The UI played feedback [id] (haptic + animation); it is not played again, e.g. after rotation. */
    fun onFeedbackPlayed(id: Long) {
        mutableState.update { if (id > it.feedbackPlayedId) it.copy(feedbackPlayedId = id) else it }
    }

    private fun feedback(kind: FeedbackKind) {
        val f = CaptureFeedback(nextFeedbackId++, kind)
        mutableState.update { it.copy(feedback = f) }
    }

    private fun measurementFailed(reason: String) {
        feedback(FeedbackKind.FAILED)
        postMessage(reason.ifBlank { MSG_MEASURE_FAILED })
    }

    private fun postMessage(text: String) {
        if (text.isBlank()) return
        val message = UserMessage(nextMessageId++, text)
        // Keep the queue short: stale guidance is worse than none.
        mutableState.update { s -> s.copy(messages = (s.messages + message).takeLast(MAX_QUEUED_MESSAGES)) }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Run a measurement for [expectedStep] unless something else is running. [block] receives the draft id
     * at launch so it can drop results that arrive after the user moved on ([isStale]).
     */
    private fun launchMeasurement(expectedStep: CaptureStep, block: suspend (draftId: String) -> Unit) {
        val s = mutableState.value
        if (s.busy || s.finished || s.step != expectedStep || s.loadError != null) return
        val draftId = s.draft.id
        mutableState.update { it.copy(measuring = true) }
        viewModelScope.launch {
            try {
                block(draftId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                measurementFailed(MSG_MEASURE_FAILED)
            } finally {
                mutableState.update { it.copy(measuring = false) }
            }
        }
    }

    private suspend fun measureSafely(controller: ArCaptureController, request: MeasureRequest): MeasureResult = try {
        controller.measure(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MeasureResult.Failed(MSG_MEASURE_FAILED)
    }

    private fun isStale(draftId: String, step: CaptureStep): Boolean {
        val s = mutableState.value
        return s.draft.id != draftId || s.step != step || s.finished
    }

    /** Change walls / corners and re-solve. */
    private fun mutateCapture(transform: (RoomDraft) -> RoomDraft) {
        mutableState.update { s ->
            val d = transform(s.draft)
            s.copy(draft = d.copy(solve = solveDraft(d, s)))
        }
    }

    /** [mutateCapture] that can be undone: the walls / corners before it go on the undo history. */
    private fun recordEdit(undoMessage: String, transform: (RoomDraft) -> RoomDraft) {
        mutateCapture { d ->
            val edit = CaptureEdit(d.observations, d.cornerTaps, undoMessage)
            transform(d).copy(history = (d.history + edit).takeLast(MAX_UNDO_HISTORY))
        }
    }

    /**
     * Solve with the session's shared squaring axis ([sessionAxisHint], in this draft's tracking frame), so
     * neighbouring rooms come out parallel.
     */
    private fun solveDraft(d: RoomDraft, s: CaptureUiState): SolveResult? =
        solve(d, s.settings, sessionAxisHint(s.sessionAxis, s.sessionRooms, latestCorrections, d.id))

    private fun freshDraft(method: CaptureMethod) = RoomDraft(id = Ids.newId(), method = method)

    /**
     * Creates the ViewModel with a [SavedStateHandle][androidx.lifecycle.SavedStateHandle] from the navigation
     * entry's [CreationExtras], so the capture flow survives process death. Without saved-state support in the
     * extras it still works, just without the snapshot.
     */
    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String,
        private val floorId: String,
        private val tipsFlag: OneTimeFlag = OneTimeFlag.InMemory(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            val store = try {
                SavedStateSnapshotStore(extras.createSavedStateHandle())
            } catch (e: Exception) {
                CaptureSnapshotStore.None
            }
            return CaptureViewModel(repository, projectId, floorId, tipsFlag, snapshotStore = store) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return CaptureViewModel(repository, projectId, floorId, tipsFlag) as T
        }
    }

    internal companion object {
        const val AUTO_LOCK_MIN_AREA = 1.0
        const val AUTO_LOCK_TOLERANCE = 0.03
        const val AUTO_LOCK_STABLE_NS = 1_000_000_000L

        /**
         * Auto-lock only takes surfaces at least this far below the phone. A handheld phone is ~1.1–1.6 m
         * above the floor, while beds, tables and kitchen islands are only ~0.5–0.85 m below it. A crouching
         * user can still lock manually.
         */
        const val AUTO_LOCK_MIN_CAMERA_DROP = 1.0

        /** The phone must be at least this far above a floor candidate. */
        const val MIN_CAMERA_ABOVE_FLOOR = 0.3

        /** A manual lock this far above the floor under the user is a table / bed, not the floor (asks first). */
        const val MAX_ABOVE_BEST_FLOOR = 0.2

        /** A manual lock this close to a questioned height (rejected, or warned about) counts as the same height. */
        const val REJECTED_FLOOR_TOLERANCE = 0.10

        /** Anchor corrections are re-read from the AR layer at most this often while scanning. */
        const val CORRECTIONS_REFRESH_NS = 1_000_000_000L

        /** Assumed ceilings are taken as at least this tall for the wall band top (meters). */
        const val MIN_ASSUMED_CEILING = 2.4

        /** Margin above an assumed (not measured) ceiling for the wall band top (meters). */
        const val ASSUMED_CEILING_MARGIN = 0.3

        const val MIN_CEILING = 1.5
        const val MAX_CEILING = 8.0
        const val MIN_CORNER_SPACING = 0.05
        const val CLOSE_TO_FIRST_CORNER = 0.15
        const val MAX_QUEUED_MESSAGES = 4
        const val MAX_NAME_LENGTH = 60
        const val MAX_UNDO_HISTORY = 50

        /** Openings on the same wall overlapping by more than this are rejected. */
        const val OPENING_OVERLAP_TOLERANCE = 0.02

        const val MSG_FLOOR_FAILED = "Couldn't lock the floor. Point at the floor and try again."
        const val MSG_WALL_FAILED = "Couldn't measure that wall. Hold steady and try again."
        const val MSG_POINT_FAILED = "Couldn't measure that point. Hold steady and try again."
        const val MSG_MEASURE_FAILED = "Measurement failed. Hold steady and try again."
        const val MSG_SAVE_FAILED = "Couldn't save the room. Please try again."
        const val MSG_PROJECT_MISSING = "Couldn't save: this project no longer exists."
    }
}

internal val CaptureStep.isCaptureStep: Boolean
    get() = this == CaptureStep.WALLS || this == CaptureStep.CORNERS

/**
 * The floor height auto-lock may take from [frame], or null. Only a floor the AR layer itself calls lockable
 * ([ArFrameState.floorAutoLockable]: the floor under the user, large, never vetoed as furniture) at
 * [ArFrameState.localFloorY], with good tracking, at least [CaptureViewModel.AUTO_LOCK_MIN_CAMERA_DROP] below
 * the phone (not a table or bed), and not at the height the user rejected with "Re-lock floor".
 */
internal fun autoLockHeight(frame: ArFrameState, rejectedFloorY: Double? = null): Double? {
    if (frame.tracking != TrackingStatus.TRACKING || frame.problem != TrackingProblem.NONE || !frame.floorAutoLockable) return null
    val y = frame.localFloorY?.takeIf { it.isFinite() } ?: return null
    val camera = frame.cameraPosition?.takeIf { it.isFinite() } ?: return null
    if (camera.y - y < CaptureViewModel.AUTO_LOCK_MIN_CAMERA_DROP) return null
    if (rejectedFloorY != null && abs(y - rejectedFloorY) <= CaptureViewModel.REJECTED_FLOOR_TOLERANCE) return null
    return y
}

/**
 * Why a manual floor lock at height [y] looks wrong, or null when it looks fine:
 * * a raised surface: more than [CaptureViewModel.MAX_ABOVE_BEST_FLOOR] above the floor under the user
 *   ([ArFrameState.localFloorY]; without one, the tracker's confident [ArFrameState.floorY]);
 * * the height just rejected with "Re-lock floor".
 * Either is only a question: a second Lock floor tap keeps the lock.
 */
internal fun manualLockWarning(y: Double, frame: ArFrameState, rejectedFloorY: Double?, units: UnitSystem): String? {
    val reference = frame.localFloorY?.takeIf { it.isFinite() }
        ?: frame.floorY?.takeIf { it.isFinite() && frame.floorArea >= CaptureViewModel.AUTO_LOCK_MIN_AREA }
    if (reference != null && y - reference > CaptureViewModel.MAX_ABOVE_BEST_FLOOR) {
        return "That looks like a raised surface, ${approxDistance(y - reference, units)} above the floor. " +
            "Aim at open floor, or tap Lock floor again to keep it."
    }
    if (rejectedFloorY != null && abs(y - rejectedFloorY) <= CaptureViewModel.REJECTED_FLOOR_TOLERANCE) {
        return "That's the height you just re-locked from. Aim at open floor, or tap Lock floor again to keep it."
    }
    return null
}

/**
 * Top of the height band (world Y) that wall points are sampled in: MeasureRequest.Wall.ceilingY, and the
 * coach's "aim lower" line. A measured ceiling is the ceiling itself. Otherwise it is a generous band top, not
 * a claim about the ceiling: the assumed height ("Same as last room", else the project default), at least
 * [CaptureViewModel.MIN_ASSUMED_CEILING], plus [CaptureViewModel.ASSUMED_CEILING_MARGIN]. A wall seen above
 * tall furniture (wardrobes ~2.2 m) then stays in band in rooms taller than the default; ceiling hits above a
 * wall don't form a line, so a generous band costs nothing.
 */
internal fun wallBandTopY(draft: RoomDraft, floorY: Double, settings: PlanSettings): Double {
    val measured = draft.ceilingHeight?.takeIf { draft.ceilingMeasured && it.isFinite() && it > 0.0 }
    if (measured != null) return floorY + measured
    val assumed = draft.ceilingHeight?.takeIf { it.isFinite() && it > 0.0 }
        ?: settings.defaultCeilingHeight.takeIf { it.isFinite() && it > 0.0 }
        ?: CaptureViewModel.MIN_ASSUMED_CEILING
    return floorY + max(assumed, CaptureViewModel.MIN_ASSUMED_CEILING) + CaptureViewModel.ASSUMED_CEILING_MARGIN
}

/**
 * This draft after the floor was locked at [floorY]: a measured ceiling keeps its height in the world, so its
 * floor-to-ceiling height follows the new floor; a result outside the plausible range drops the measurement.
 */
private fun RoomDraft.withFloorAt(floorY: Double): RoomDraft {
    val top = ceilingY?.takeIf { ceilingMeasured && it.isFinite() } ?: return this
    val h = top - floorY
    return if (h.isFinite() && h >= CaptureViewModel.MIN_CEILING && h <= CaptureViewModel.MAX_CEILING) {
        copy(ceilingHeight = h)
    } else {
        copy(ceilingHeight = null, ceilingMeasured = false, ceilingY = null)
    }
}

/**
 * Solve the draft's outline from its walls or corners; null with fewer than 3 or when no valid room forms.
 * [axisHint] is the session's shared squaring axis ([sessionAxisHint]), if any.
 */
internal fun solve(draft: RoomDraft, settings: PlanSettings, axisHint: Double? = null): SolveResult? {
    val options = SolverOptions(
        squareAngles = settings.squareAngles,
        squareToleranceDeg = settings.squareToleranceDeg,
        axisHint = axisHint?.takeIf { it.isFinite() },
    )
    val result = try {
        when (draft.method) {
            CaptureMethod.CORNERS ->
                if (draft.cornerTaps.size >= 3) RoomSolver.solveCorners(draft.cornerTaps, options) else null
            else ->
                if (draft.observations.size >= 3) RoomSolver.solveWalls(draft.observations, options) else null
        }
    } catch (e: Exception) {
        null
    }
    return result?.takeIf { r -> r.corners.size >= 3 && r.corners.all { it.isFinite() } }
}

/** Guard against corrupt AR output before it enters the draft. */
private fun WallObservation.isUsable(): Boolean =
    line.point.isFinite() && line.direction.isFinite() && line.direction.length > 0.5 &&
        extentStart.isFinite() && extentEnd.isFinite() && viewer.isFinite()

private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

private fun <T> List<T>.removedAt(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

internal fun openingLabel(type: OpeningType): String = when (type) {
    OpeningType.DOOR -> "Door"
    OpeningType.WINDOW -> "Window"
    OpeningType.OPENING -> "Opening"
}

/** True when [opening] overlaps (beyond a small tolerance) an opening already on the same wall edge. */
internal fun overlapsExisting(opening: Opening, existing: List<Opening>): Boolean = existing.any { o ->
    o.id != opening.id && o.wallIndex == opening.wallIndex &&
        min(o.offset + o.width, opening.offset + opening.width) - max(o.offset, opening.offset) >
        CaptureViewModel.OPENING_OVERLAP_TOLERANCE
}

/**
 * Apply each room's anchor correction (keyed by room id) with [RoomOps.transform]; rooms without a
 * correction, or whose transform fails, are left as they are.
 */
internal fun applyCorrections(floor: Floor, corrections: Map<String, RigidTransform2>): Floor {
    if (corrections.isEmpty()) return floor
    return floor.copy(
        rooms = floor.rooms.map { room ->
            val t = corrections[room.id] ?: return@map room
            try {
                RoomOps.transform(room, t)
            } catch (e: Exception) {
                room
            }
        },
    )
}

/**
 * Replace floor [floorId] with [transform] of it. A missing floor (deleted meanwhile) is recreated when
 * [createIfMissing] so freshly scanned rooms are never lost; otherwise the project is returned unchanged.
 */
internal fun Project.withFloor(floorId: String, createIfMissing: Boolean, transform: (Floor) -> Floor): Project {
    val index = floors.indexOfFirst { it.id == floorId }
    if (index >= 0) return copy(floors = floors.toMutableList().also { it[index] = transform(it[index]) })
    if (!createIfMissing) return this
    val name = if (floors.isEmpty()) "Main Floor" else "Floor ${floors.size + 1}"
    return copy(floors = floors + transform(Floor(id = floorId, name = name)))
}
