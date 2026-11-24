package com.oroplex.floorplan.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
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
    /** Measured floor-to-ceiling height; null when skipped (the project default is used on save). */
    val ceilingHeight: Double? = null,
    val ceilingMeasured: Boolean = false,
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
) {
    /** Walls (WALLS) or corners (CORNERS) captured so far. */
    val captureCount: Int
        get() = if (method == CaptureMethod.CORNERS) cornerTaps.size else observations.size

    /** True when discarding this draft would lose user work. */
    val hasCaptures: Boolean
        get() = observations.isNotEmpty() || cornerTaps.isNotEmpty() || openings.isNotEmpty() ||
            baseline?.firstTap != null || pendingOpeningEdge != null

    /**
     * The draft as a [Room] (null until it has a valid solved outline). Used for the overlay, the mini-map,
     * opening placement and saving.
     */
    fun toRoom(sessionId: String?, settings: PlanSettings): Room? {
        val solved = solve ?: return null
        return Room(
            id = id,
            name = name.trim(),
            type = type,
            corners = solved.corners,
            ceilingHeight = ceilingHeight ?: settings.defaultCeilingHeight,
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
    /** AR session the floor lock and [sessionRoomIds] belong to. */
    val sessionId: String? = null,
    /** Floor height (world Y) locked for this AR session; kept for every following room. */
    val floorY: Double? = null,
    /** Rooms saved during the current AR session (they share its coordinate frame). */
    val sessionRoomIds: Set<String> = emptySet(),
    /** A measurement is running ("Hold steady…"). */
    val measuring: Boolean = false,
    /** A save is running. */
    val saving: Boolean = false,
    /** Pending one-shot messages, oldest first. */
    val messages: List<UserMessage> = emptyList(),
    /** A capture just succeeded: the UI should play a confirmation haptic, then call [CaptureViewModel.onHapticPlayed]. */
    val hapticPending: Boolean = false,
    /** The session is over: the UI navigates back (onDone). */
    val finished: Boolean = false,
) {
    val busy: Boolean get() = measuring || saving

    /** True when leaving now would discard work on the current room. */
    val hasRoomInProgress: Boolean
        get() = draft.hasCaptures || step == CaptureStep.OPENINGS || step == CaptureStep.REVIEW

    val sessionRooms: List<Room> get() = floorRooms.filter { it.id in sessionRoomIds && it.id != draft.id }

    /** Rooms already on the floor that were captured in another AR session (or drawn by hand). */
    val otherSessionRoomCount: Int
        get() = floorRooms.count { it.id != draft.id && it.id !in sessionRoomIds && (sessionId == null || it.sessionId != sessionId) }

    /** The draft as a room, when it has a solved outline. */
    val draftRoom: Room? get() = draft.toRoom(sessionId, settings)
}

/**
 * State machine of the room-capture flow: FIND_FLOOR → CEILING → WALLS | CORNERS → OPENINGS → REVIEW,
 * then the next room (same AR session, floor stays locked) or finish.
 *
 * Measurement functions take the live [ArCaptureController] as a parameter instead of holding it, so the
 * ViewModel never outlives or leaks an AR session. All public functions are main-thread only and ignore
 * calls that don't fit the current step (e.g. a double tap racing a step change), so the UI can wire
 * buttons directly.
 */
class CaptureViewModel(
    private val repository: ProjectRepository,
    private val projectId: String,
    private val floorId: String,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CaptureUiState(draft = RoomDraft(id = Ids.newId())))
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    private var nextMessageId = 1L

    // Auto floor lock: a confident floor whose height stays within tolerance for AUTO_LOCK_STABLE_NS.
    private var floorCandidateY: Double? = null
    private var floorCandidateSinceNs = 0L

    init {
        viewModelScope.launch { loadProject() }
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

    /** Take settings and the floor's rooms from a freshly loaded / saved project. */
    private fun applyProject(project: Project) {
        mutableState.update { s ->
            val settingsChanged = s.settings != project.settings
            val floorRooms = project.floors.firstOrNull { it.id == floorId }?.rooms.orEmpty()
            val draft = if (settingsChanged && s.step.isCaptureStep) {
                s.draft.copy(solve = solve(s.draft, project.settings))
            } else {
                s.draft
            }
            s.copy(settings = project.settings, projectLoaded = true, floorRooms = floorRooms, draft = draft)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // AR session / frames
    // ---------------------------------------------------------------------------------------------

    /**
     * Bind to the running AR session. Rooms, the floor lock and anchors only make sense within one session
     * (one coordinate frame), so if the session was recreated, the floor is unlocked and the room in
     * progress restarts.
     */
    fun attach(controller: ArCaptureController) {
        val sid = controller.sessionId
        val s = mutableState.value
        if (s.sessionId == sid) return
        if (s.sessionId == null) {
            mutableState.update { it.copy(sessionId = sid) }
            return
        }
        val lostWork = s.hasRoomInProgress
        resetFloorCandidate()
        mutableState.update {
            it.copy(
                sessionId = sid,
                floorY = null,
                step = CaptureStep.FIND_FLOOR,
                draft = freshDraft(it.draft.method),
                sessionRoomIds = emptySet(),
            )
        }
        if (lostWork) postMessage("The AR session restarted, so this room needs to be scanned again.")
    }

    /**
     * Called for every published AR frame. Auto-locks the floor once a confident floor
     * (area ≥ [AUTO_LOCK_MIN_AREA]) has kept the same height for about a second, and creates the room
     * anchor as soon as the camera pose is available.
     */
    fun onFrame(frame: ArFrameState, controller: ArCaptureController, nowNs: Long = System.nanoTime()) {
        val s = mutableState.value
        if (s.step.isCaptureStep && !s.draft.anchorCreated) ensureAnchor(controller, frame)

        if (s.step != CaptureStep.FIND_FLOOR || s.floorY != null || s.busy || s.loadError != null) {
            resetFloorCandidate()
            return
        }
        val y = frame.floorY
        val confident = frame.tracking == TrackingStatus.TRACKING && frame.problem == TrackingProblem.NONE &&
            y != null && y.isFinite() && frame.floorArea >= AUTO_LOCK_MIN_AREA
        if (!confident) {
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
            acceptFloor(y, frame)
        }
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
                is MeasureResult.FloorLocked -> acceptFloor(result.floorY, controller.frameState.value)
                is MeasureResult.Failed -> postMessage(result.reason)
                else -> postMessage(MSG_FLOOR_FAILED)
            }
        }
    }

    /**
     * Validate and lock floor height [y]. Rejects heights at or above the phone, and (for a manual lock)
     * raised surfaces such as a table when a confident, lower floor is known.
     */
    private fun acceptFloor(y: Double, frame: ArFrameState) {
        if (!y.isFinite()) {
            postMessage(MSG_FLOOR_FAILED)
            return
        }
        val camera = frame.cameraPosition
        if (camera != null && camera.isFinite() && camera.y - y < MIN_CAMERA_ABOVE_FLOOR) {
            postMessage("That surface is too close to the phone's height. Aim at the floor.")
            return
        }
        val bestFloor = frame.floorY
        if (bestFloor != null && bestFloor.isFinite() && frame.floorArea >= AUTO_LOCK_MIN_AREA &&
            y - bestFloor > MAX_ABOVE_BEST_FLOOR
        ) {
            postMessage("That looks like a raised surface, not the floor. Aim at open floor.")
            return
        }
        mutableState.update { it.copy(floorY = y, step = CaptureStep.CEILING, hapticPending = true) }
        postMessage("Floor locked.")
    }

    /** Go back to finding the floor (e.g. it locked onto a rug or step). Only before any capture. */
    fun relockFloor() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.CEILING || s.draft.hasCaptures) return
        resetFloorCandidate()
        mutableState.update { it.copy(floorY = null, step = CaptureStep.FIND_FLOOR) }
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
                        postMessage("That ceiling reading looks wrong. Aim straight up at a clear spot and try again.")
                    } else {
                        mutableState.update {
                            it.copy(draft = it.draft.copy(ceilingHeight = h, ceilingMeasured = true), hapticPending = true)
                        }
                        val s = mutableState.value
                        postMessage("Ceiling: ${formatLengthSafe(h, s.settings)}")
                        enterCaptureStep(controller)
                    }
                }
                is MeasureResult.Failed -> postMessage(result.reason)
                else -> postMessage("Couldn't measure the ceiling. Try again or tap Skip.")
            }
        }
    }

    /** Skip the ceiling: the project default height is used and shown as estimated. */
    fun skipCeiling(controller: ArCaptureController) {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.CEILING) return
        mutableState.update { it.copy(draft = it.draft.copy(ceilingHeight = null, ceilingMeasured = false)) }
        enterCaptureStep(controller)
    }

    private fun enterCaptureStep(controller: ArCaptureController) {
        mutableState.update { s ->
            s.copy(step = if (s.draft.method == CaptureMethod.CORNERS) CaptureStep.CORNERS else CaptureStep.WALLS)
        }
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
                draft = it.draft.copy(method = method, baseline = null, offerBaseline = false, solve = null),
            )
        }
    }

    fun captureWall(controller: ArCaptureController) {
        val s = mutableState.value
        val floorY = s.floorY ?: return
        if (s.draft.baseline != null) return
        ensureAnchor(controller, controller.frameState.value)
        // Only a measured ceiling is passed; otherwise the estimator assumes a typical height for filtering.
        val ceilingY = s.draft.ceilingHeight?.takeIf { s.draft.ceilingMeasured }?.let { floorY + it }
        launchMeasurement(CaptureStep.WALLS) { draftId ->
            val result = measureSafely(controller, MeasureRequest.Wall(floorY, ceilingY))
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
        postMessage(reason)
    }

    private fun addObservation(observation: WallObservation) {
        mutateCapture { it.copy(observations = it.observations + observation, baseline = null, offerBaseline = false) }
        mutableState.update { it.copy(hapticPending = true) }
        postMessage("Wall ${mutableState.value.draft.observations.size} captured.")
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
                        postMessage(MSG_POINT_FAILED)
                        return@launchMeasurement
                    }
                    val first = baseline.firstTap
                    if (first == null) {
                        mutableState.update {
                            it.copy(draft = it.draft.copy(baseline = BaselineCapture(result.world)), hapticPending = true)
                        }
                    } else {
                        completeBaseline(first, result.world, controller.frameState.value.cameraPosition)
                    }
                }
                is MeasureResult.Failed -> postMessage(result.reason)
                else -> postMessage(MSG_POINT_FAILED)
            }
        }
    }

    private fun completeBaseline(a: Vec3, b: Vec3, viewer: Vec3?) {
        if (viewer == null || !viewer.isFinite()) {
            postMessage("Lost track of the phone's position. Hold steady and mark the second point again.")
            return
        }
        val observation = try {
            WallEstimator.fromBaseline(a, b, viewer)
        } catch (e: Exception) {
            null
        }
        if (observation == null || !observation.isUsable()) {
            postMessage("Couldn't make a wall from those points. Mark both ends of the wall base, well apart, from inside the room.")
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
                is MeasureResult.Failed -> postMessage(result.reason)
                else -> postMessage(MSG_POINT_FAILED)
            }
        }
    }

    private fun addCornerTap(world: Vec3) {
        if (!world.isFinite()) {
            postMessage(MSG_POINT_FAILED)
            return
        }
        val p = world.toPlan()
        val taps = mutableState.value.draft.cornerTaps
        taps.lastOrNull()?.let { last ->
            if (last.distanceTo(p) < MIN_CORNER_SPACING) {
                postMessage("That's the same corner as before. Aim at the next corner.")
                return
            }
        }
        if (taps.size >= 3 && taps.first().distanceTo(p) < CLOSE_TO_FIRST_CORNER) {
            postMessage("That's your first corner. Tap Close room to finish.")
            return
        }
        mutateCapture { it.copy(cornerTaps = it.cornerTaps + p) }
        mutableState.update { it.copy(hapticPending = true) }
    }

    /** Remove the last capture of the current step (baseline point, wall, corner, opening edge or opening). */
    fun undo() {
        val s = mutableState.value
        if (s.busy) return
        val d = s.draft
        when (s.step) {
            CaptureStep.WALLS -> when {
                d.baseline?.firstTap != null ->
                    mutableState.update { it.copy(draft = it.draft.copy(baseline = BaselineCapture())) }
                d.observations.isNotEmpty() -> {
                    mutateCapture { it.copy(observations = it.observations.dropLast(1)) }
                    postMessage("Removed wall ${d.observations.size}.")
                }
            }
            CaptureStep.CORNERS -> if (d.cornerTaps.isNotEmpty()) {
                mutateCapture { it.copy(cornerTaps = it.cornerTaps.dropLast(1)) }
                postMessage("Removed corner ${d.cornerTaps.size}.")
            }
            CaptureStep.OPENINGS -> when {
                d.pendingOpeningEdge != null ->
                    mutableState.update { it.copy(draft = it.draft.copy(pendingOpeningEdge = null)) }
                d.openings.isNotEmpty() -> {
                    mutableState.update { it.copy(draft = it.draft.copy(openings = it.draft.openings.dropLast(1))) }
                    postMessage("Removed the last opening.")
                }
                else -> reopenRoom()
            }
            else -> Unit
        }
    }

    /** Back from OPENINGS (with no openings yet) to keep capturing walls / corners. */
    private fun reopenRoom() {
        mutableState.update { s ->
            val step = if (s.draft.method == CaptureMethod.CORNERS) CaptureStep.CORNERS else CaptureStep.WALLS
            s.copy(step = step, draft = s.draft.copy(solve = solve(s.draft, s.settings)))
        }
        postMessage("Room reopened. Keep capturing.")
    }

    /** "Close room": needs ≥ 3 captures and a valid solved outline. Moves on to doors & windows. */
    fun closeRoom() {
        val s = mutableState.value
        if (s.busy || !s.step.isCaptureStep) return
        val d = s.draft
        val noun = if (d.method == CaptureMethod.CORNERS) "corners" else "walls"
        if (d.captureCount < 3) {
            postMessage("Capture at least 3 $noun first.")
            return
        }
        val solved = d.solve
        if (solved == null) {
            postMessage("These $noun don't form a closed room yet. Capture the missing ones or undo the last one.")
            return
        }
        mutableState.update {
            it.copy(
                step = CaptureStep.OPENINGS,
                draft = it.draft.copy(baseline = null, offerBaseline = false, pendingOpeningEdge = null),
                hapticPending = true,
            )
        }
        val warnings = solved.warnings.size
        postMessage(
            if (warnings == 0) {
                "Room closed. Mark doors and windows, or tap Done."
            } else {
                "Room closed with $warnings ${if (warnings == 1) "warning" else "warnings"}. Check before saving."
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
                    if (result.world.isFinite()) onOpeningPoint(result.world.toPlan(), type) else postMessage(MSG_POINT_FAILED)
                is MeasureResult.Failed -> postMessage(result.reason)
                else -> postMessage(MSG_POINT_FAILED)
            }
        }
    }

    private fun onOpeningPoint(p: Vec2, type: OpeningType) {
        val s = mutableState.value
        if (s.draft.openingType != type) return
        val first = s.draft.pendingOpeningEdge
        if (first == null) {
            mutableState.update { it.copy(draft = it.draft.copy(pendingOpeningEdge = p), hapticPending = true) }
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
            postMessage("Couldn't place that on a wall. Mark both edges within 0.5 m of a wall, at least 20 cm apart.")
            return
        }
        if (overlapsExisting(opening, room.openings)) {
            postMessage("That overlaps an opening already marked. Undo it first or mark a different spot.")
            return
        }
        mutableState.update {
            it.copy(
                draft = it.draft.copy(openings = it.draft.openings + opening, pendingOpeningEdge = null),
                hapticPending = true,
            )
        }
        postMessage("${openingLabel(type)} added (${formatLengthSafe(opening.width, settings)} wide).")
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
            val name = if (d.nameEdited && d.name.isNotBlank()) d.name else defaultRoomName(d.type, it.floorRooms, d.id)
            it.copy(step = CaptureStep.REVIEW, draft = d.copy(pendingOpeningEdge = null, name = name))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REVIEW
    // ---------------------------------------------------------------------------------------------

    fun setName(name: String) {
        if (mutableState.value.step != CaptureStep.REVIEW) return
        mutableState.update { it.copy(draft = it.draft.copy(name = name.take(MAX_NAME_LENGTH), nameEdited = name.isNotBlank())) }
    }

    /** Change the room type; renames the room to the type's default unless the user typed a name. */
    fun setType(type: RoomType) {
        if (mutableState.value.step != CaptureStep.REVIEW) return
        mutableState.update {
            val d = it.draft
            val name = if (d.nameEdited) d.name else defaultRoomName(type, it.floorRooms, d.id)
            it.copy(draft = d.copy(type = type, name = name))
        }
    }

    /** Discard the captured walls / corners / openings of this room and capture it again. */
    fun redoRoom() {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.REVIEW) return
        mutableState.update {
            val d = it.draft
            it.copy(
                step = if (d.method == CaptureMethod.CORNERS) CaptureStep.CORNERS else CaptureStep.WALLS,
                // Same id: the room anchor stays valid; the measured ceiling is kept.
                draft = RoomDraft(
                    id = d.id,
                    method = d.method,
                    ceilingHeight = d.ceilingHeight,
                    ceilingMeasured = d.ceilingMeasured,
                    type = d.type,
                    name = d.name,
                    nameEdited = d.nameEdited,
                    anchorCreated = d.anchorCreated,
                ),
            )
        }
        postMessage("Room cleared. Capture it again.")
    }

    /** "Save & scan next room": save, then start a new room in the same session (floor stays locked). */
    fun saveAndScanNext(controller: ArCaptureController) {
        val room = roomToSave(controller) ?: return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = true) { FloorOps.upsertRoom(it, room) }
                }
                if (saved == null) {
                    postMessage(MSG_PROJECT_MISSING)
                    return@launch
                }
                applyProject(saved)
                mutableState.update { s ->
                    s.copy(
                        sessionRoomIds = if (s.sessionId == room.sessionId) s.sessionRoomIds + room.id else s.sessionRoomIds,
                        draft = freshDraft(s.draft.method),
                        step = if (s.floorY != null) CaptureStep.CEILING else CaptureStep.FIND_FLOOR,
                        hapticPending = true,
                    )
                }
                postMessage("${room.name} saved. Walk into the next room.")
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

    /** Leave the capture screen without a room in progress (or after the user confirmed discarding it). */
    fun exit(controller: ArCaptureController) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        if (s.loadError != null) {
            mutableState.update { it.copy(finished = true) }
            return
        }
        finishSession(controller, room = null)
    }

    private fun roomToSave(controller: ArCaptureController): Room? {
        val s = mutableState.value
        if (s.busy || s.step != CaptureStep.REVIEW || s.finished) return null
        if (s.loadError != null) {
            postMessage(MSG_PROJECT_MISSING)
            return null
        }
        val d = s.draft
        val name = d.name.trim().ifBlank { defaultRoomName(d.type, s.floorRooms, d.id) }
        val room = d.copy(name = name).toRoom(controller.sessionId, s.settings)
        if (room == null) {
            postMessage("This room has no valid outline. Tap Redo room.")
            return null
        }
        return room
    }

    /**
     * Save [room] (if any) and re-position every room of this session by its anchor's refined pose
     * (ARCore map corrections) in one atomic update, then mark the flow finished. Without a room to save,
     * a failure is not worth blocking the exit for: the rooms are already saved, just uncorrected.
     */
    private fun finishSession(controller: ArCaptureController, room: Room?) {
        val s = mutableState.value
        if (s.saving || s.finished) return
        val roomIds = s.sessionRoomIds + listOfNotNull(room?.id)
        val corrections = safeCorrections(controller).filterKeys { it in roomIds }
        if (room == null && corrections.isEmpty()) {
            mutableState.update { it.copy(finished = true) }
            return
        }
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            var done = false
            try {
                val saved = repository.update(projectId) { project ->
                    project.withFloor(floorId, createIfMissing = room != null) { floor ->
                        val withRoom = if (room != null) FloorOps.upsertRoom(floor, room) else floor
                        applyCorrections(withRoom, corrections)
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
    // Messages / haptics
    // ---------------------------------------------------------------------------------------------

    /**
     * The UI showed (or replaced) message [id]. Older messages are dropped too: the UI always shows the
     * newest one, so anything queued before it is stale.
     */
    fun onMessageShown(id: Long) {
        mutableState.update { s -> s.copy(messages = s.messages.filter { it.id > id }) }
    }

    fun onHapticPlayed() {
        mutableState.update { it.copy(hapticPending = false) }
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
                postMessage(MSG_MEASURE_FAILED)
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
            s.copy(draft = d.copy(solve = solve(d, s.settings)))
        }
    }

    private fun freshDraft(method: CaptureMethod) = RoomDraft(id = Ids.newId(), method = method)

    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String,
        private val floorId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return CaptureViewModel(repository, projectId, floorId) as T
        }
    }

    internal companion object {
        const val AUTO_LOCK_MIN_AREA = 1.0
        const val AUTO_LOCK_TOLERANCE = 0.03
        const val AUTO_LOCK_STABLE_NS = 1_000_000_000L

        /** The phone must be at least this far above a floor candidate. */
        const val MIN_CAMERA_ABOVE_FLOOR = 0.3

        /** A manual lock this far above a confident floor is a table / bed, not the floor. */
        const val MAX_ABOVE_BEST_FLOOR = 0.2

        const val MIN_CEILING = 1.5
        const val MAX_CEILING = 8.0
        const val MIN_CORNER_SPACING = 0.05
        const val CLOSE_TO_FIRST_CORNER = 0.15
        const val MAX_QUEUED_MESSAGES = 4
        const val MAX_NAME_LENGTH = 60

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

private val CaptureStep.isCaptureStep: Boolean
    get() = this == CaptureStep.WALLS || this == CaptureStep.CORNERS

/** Solve the draft's outline from its walls or corners; null with fewer than 3 or when no valid room forms. */
internal fun solve(draft: RoomDraft, settings: PlanSettings): SolveResult? {
    val options = SolverOptions(squareAngles = settings.squareAngles, squareToleranceDeg = settings.squareToleranceDeg)
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

internal fun openingLabel(type: OpeningType): String = when (type) {
    OpeningType.DOOR -> "Door"
    OpeningType.WINDOW -> "Window"
    OpeningType.OPENING -> "Opening"
}

/** True when [opening] overlaps (beyond a small tolerance) an opening already on the same wall edge. */
internal fun overlapsExisting(opening: Opening, existing: List<Opening>): Boolean = existing.any { o ->
    o.wallIndex == opening.wallIndex &&
        min(o.offset + o.width, opening.offset + opening.width) - max(o.offset, opening.offset) >
        CaptureViewModel.OPENING_OVERLAP_TOLERANCE
}

/**
 * Default room name for [type]: its display name ("Bedroom"), or numbered ("Bedroom 2", "Bedroom 3", …)
 * when the floor already has a room of that type or name. Never returns a name already used on the floor.
 */
internal fun defaultRoomName(type: RoomType, floorRooms: List<Room>, excludeRoomId: String?): String {
    val base = type.displayName
    val others = floorRooms.filter { it.id != excludeRoomId }
    val usedNames = others.map { it.name.trim().lowercase() }.toSet()
    val numbered = Regex("^${Regex.escape(base.lowercase())}( \\d+)?$")
    val sameKind = others.count { it.type == type || numbered.matches(it.name.trim().lowercase()) }
    if (sameKind == 0 && base.lowercase() !in usedNames) return base
    var n = max(2, sameKind + 1)
    while ("${base.lowercase()} $n" in usedNames) n++
    return "$base $n"
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
