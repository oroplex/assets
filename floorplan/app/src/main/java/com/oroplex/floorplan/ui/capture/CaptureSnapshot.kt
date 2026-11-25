package com.oroplex.floorplan.ui.capture

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.solve.SolveResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Surviving process death (CAPX-4). Android may kill the app while it is in the background (switching to the
 * camera app for reference photos, a long call). Navigation then restores the capture screen with a new
 * ViewModel and a new AR session, whose coordinate frame has nothing to do with the old one. A small snapshot
 * of the flow (session id, saved session rooms and their scales, the room in progress, the step and the last
 * anchor corrections) lets the new ViewModel explain what happened, apply the old session's corrections to its
 * saved rooms, and offer to save a room in progress that was already solved.
 */

/**
 * Where the capture flow keeps its snapshot across process death. Implementations must never throw.
 * Production uses the navigation entry's SavedStateHandle ([SavedStateSnapshotStore]), so the snapshot lives
 * exactly as long as the restorable capture screen does (a swiped-away task forgets it).
 */
interface CaptureSnapshotStore {
    /** The snapshot saved before the process was killed, or null. */
    fun restore(): String?

    /**
     * Register what to save. [snapshot] is called whenever the platform saves state (cheap: nothing is encoded
     * per change); null means there is nothing worth restoring.
     */
    fun register(snapshot: () -> String?)

    /** Keeps nothing. */
    object None : CaptureSnapshotStore {
        override fun restore(): String? = null
        override fun register(snapshot: () -> String?) = Unit
    }

    /** Kept in memory; [saveNow] plays the platform saving state (tests and previews). */
    class InMemory(var saved: String? = null) : CaptureSnapshotStore {
        private var provider: (() -> String?)? = null

        override fun restore(): String? = saved

        override fun register(snapshot: () -> String?) {
            provider = snapshot
        }

        fun saveNow() {
            saved = provider?.invoke()
        }
    }
}

/** [CaptureSnapshotStore] in a ViewModel's [SavedStateHandle] (saved lazily with the navigation entry's state). */
internal class SavedStateSnapshotStore(private val handle: SavedStateHandle) : CaptureSnapshotStore {
    override fun restore(): String? = try {
        handle.get<Bundle>(KEY)?.getString(JSON_KEY)
    } catch (e: Exception) {
        null
    }

    override fun register(snapshot: () -> String?) {
        try {
            handle.setSavedStateProvider(KEY) {
                Bundle().apply {
                    val json = try {
                        snapshot()
                    } catch (e: Exception) {
                        null
                    }
                    if (json != null) putString(JSON_KEY, json)
                }
            }
        } catch (e: Exception) {
            // Without saved state the flow still works; it just can't be restored after process death.
        }
    }

    private companion object {
        const val KEY = "capture_snapshot"
        const val JSON_KEY = "json"
    }
}

/** A solved room left over from an AR session that ended unexpectedly; it can still be saved as it was. */
data class RestorableRoom(
    val draft: RoomDraft,
    /** The session the room was captured in (its saved neighbours share that frame). */
    val sessionId: String,
    /** The room's anchor correction from that session, applied in the raw frame before saving. */
    val correction: RigidTransform2?,
)

/**
 * The scan was interrupted (the AR session restarted, e.g. after Android closed the app in the background).
 * Shown as a notice until the user dismisses it.
 */
data class InterruptedScan(
    /** Rooms saved in the interrupted session: they are kept, but rooms scanned now won't line up with them. */
    val savedRoomCount: Int,
    /** A room was in progress and can't be restored: it needs scanning again. */
    val lostRoom: Boolean,
    /** A room in progress that was already solved and can be saved as it was. */
    val restorable: RestorableRoom?,
)

private val SnapshotJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    allowSpecialFloatingPointValues = true
}

@Serializable
internal data class SolveSnapshot(
    val corners: List<Vec2>,
    val edgeSources: List<Int> = emptyList(),
    val dominantAngle: Double = 0.0,
    val warnings: List<String> = emptyList(),
) {
    fun toSolve(): SolveResult? =
        if (corners.size >= 3 && corners.all { it.isFinite() }) SolveResult(corners, edgeSources, dominantAngle, warnings) else null

    companion object {
        fun of(r: SolveResult) = SolveSnapshot(r.corners, r.edgeSources, r.dominantAngle, r.warnings)
    }
}

/** The lasting parts of a [RoomDraft]; transient modes (re-capture, half-marked edges, undo history) are dropped. */
@Serializable
internal data class DraftSnapshot(
    val id: String,
    val method: CaptureMethod = CaptureMethod.WALLS,
    val observations: List<WallObservation> = emptyList(),
    val cornerTaps: List<Vec2> = emptyList(),
    val ceilingHeight: Double? = null,
    val ceilingMeasured: Boolean = false,
    val ceilingY: Double? = null,
    val openings: List<Opening> = emptyList(),
    val openingType: OpeningType = OpeningType.DOOR,
    val parkedOpenings: List<ParkedOpening> = emptyList(),
    val solve: SolveSnapshot? = null,
    val name: String = "",
    val nameEdited: Boolean = false,
    val type: RoomType = RoomType.OTHER,
    val anchorCreated: Boolean = false,
) {
    fun toDraft(): RoomDraft = RoomDraft(
        id = id,
        method = if (method == CaptureMethod.CORNERS) CaptureMethod.CORNERS else CaptureMethod.WALLS,
        observations = observations,
        cornerTaps = cornerTaps.filter { it.isFinite() },
        ceilingHeight = ceilingHeight?.takeIf { it.isFinite() && it > 0.0 },
        ceilingMeasured = ceilingMeasured && ceilingHeight != null,
        ceilingY = ceilingY?.takeIf { it.isFinite() },
        openings = openings,
        openingType = openingType,
        parkedOpenings = parkedOpenings,
        solve = solve?.toSolve(),
        name = name,
        nameEdited = nameEdited,
        type = type,
        anchorCreated = anchorCreated,
    )

    companion object {
        fun of(d: RoomDraft) = DraftSnapshot(
            id = d.id,
            method = d.method,
            observations = d.observations,
            cornerTaps = d.cornerTaps,
            ceilingHeight = d.ceilingHeight,
            ceilingMeasured = d.ceilingMeasured,
            ceilingY = d.ceilingY,
            openings = d.openings,
            openingType = d.openingType,
            parkedOpenings = d.parkedOpenings,
            solve = d.solve?.let(SolveSnapshot::of),
            name = d.name,
            nameEdited = d.nameEdited,
            type = d.type,
            anchorCreated = d.anchorCreated,
        )
    }
}

@Serializable
internal data class RestorableSnapshot(val draft: DraftSnapshot, val sessionId: String, val correction: RigidTransform2? = null)

@Serializable
internal data class InterruptedSnapshot(val savedRoomCount: Int = 0, val lostRoom: Boolean = false, val restorable: RestorableSnapshot? = null)

/** The persisted part of [CaptureUiState] for one project floor, plus the session's last anchor corrections. */
@Serializable
internal data class CaptureSnapshot(
    val version: Int = VERSION,
    val projectId: String,
    val floorId: String,
    val sessionId: String? = null,
    val step: CaptureStep = CaptureStep.FIND_FLOOR,
    val floorY: Double? = null,
    val autoLockFloor: Boolean = true,
    val rejectedFloorY: Double? = null,
    val sessionRoomIds: List<String> = emptyList(),
    val sessionRoomScales: Map<String, AppliedScale> = emptyMap(),
    val sessionAxis: SessionAxis? = null,
    val lastMeasuredCeiling: Double? = null,
    val draft: DraftSnapshot? = null,
    /** Raw anchor corrections (session rooms and the draft) taken when the app went to the background. */
    val corrections: Map<String, RigidTransform2> = emptyMap(),
    val interrupted: InterruptedSnapshot? = null,
) {
    /** JSON text; a snapshot too large for saved state drops the room in progress rather than fail. */
    fun encode(): String {
        val json = SnapshotJson.encodeToString(CaptureSnapshot.serializer(), this)
        return if (json.length <= MAX_CHARS || draft == null) json else SnapshotJson.encodeToString(CaptureSnapshot.serializer(), copy(draft = null))
    }

    /**
     * [base] (a fresh state) with this snapshot's flow restored. A REVIEW / OPENINGS step without a solved room
     * falls back to capturing; corrupt values are dropped.
     */
    fun restoreInto(base: CaptureUiState): CaptureUiState {
        val d = draft?.toDraft() ?: base.draft
        val floor = floorY?.takeIf { it.isFinite() }
        val restoredStep = when {
            floor == null -> CaptureStep.FIND_FLOOR
            (step == CaptureStep.REVIEW || step == CaptureStep.OPENINGS) && d.solve == null -> d.captureStep
            else -> step
        }
        return base.copy(
            step = restoredStep,
            draft = d,
            sessionId = sessionId,
            floorY = floor,
            autoLockFloor = autoLockFloor,
            rejectedFloorY = rejectedFloorY?.takeIf { it.isFinite() },
            sessionRoomIds = LinkedHashSet(sessionRoomIds),
            sessionRoomScales = sessionRoomScales.filterValues { it.factor.isFinite() && it.factor > 0.0 && it.origin.isFinite() },
            sessionAxis = sessionAxis?.takeIf { it.angle.isFinite() },
            lastMeasuredCeiling = lastMeasuredCeiling?.takeIf { it.isFinite() && it > 0.0 },
            interrupted = interrupted?.let { i ->
                InterruptedScan(
                    savedRoomCount = i.savedRoomCount.coerceAtLeast(0),
                    lostRoom = i.lostRoom,
                    restorable = i.restorable?.let { r -> RestorableRoom(r.draft.toDraft(), r.sessionId, r.correction) },
                )
            },
        )
    }

    companion object {
        const val VERSION = 1

        /** Saved state shares a ~1 MB binder budget with the whole activity: stay well below it. */
        const val MAX_CHARS = 200_000

        /** The snapshot in [text], or null when there is none or it can't be read (never throws). */
        fun decode(text: String?): CaptureSnapshot? {
            if (text.isNullOrBlank()) return null
            return try {
                SnapshotJson.decodeFromString(CaptureSnapshot.serializer(), text).takeIf { it.version == VERSION }
            } catch (e: Exception) {
                null
            }
        }

        /** Snapshot of [s] for floor [floorId] of project [projectId], with [corrections] of its session. */
        fun of(projectId: String, floorId: String, s: CaptureUiState, corrections: Map<String, RigidTransform2>) = CaptureSnapshot(
            projectId = projectId,
            floorId = floorId,
            sessionId = s.sessionId,
            step = s.step,
            floorY = s.floorY,
            autoLockFloor = s.autoLockFloor,
            rejectedFloorY = s.rejectedFloorY,
            sessionRoomIds = s.sessionRoomIds.toList(),
            sessionRoomScales = s.sessionRoomScales,
            sessionAxis = s.sessionAxis,
            lastMeasuredCeiling = s.lastMeasuredCeiling,
            draft = DraftSnapshot.of(s.draft),
            corrections = corrections.filterValues { it.angle.isFinite() && it.translation.isFinite() },
            interrupted = s.interrupted?.let { i ->
                InterruptedSnapshot(
                    savedRoomCount = i.savedRoomCount,
                    lostRoom = i.lostRoom,
                    restorable = i.restorable?.let { r -> RestorableSnapshot(DraftSnapshot.of(r.draft), r.sessionId, r.correction) },
                )
            },
        )
    }
}
