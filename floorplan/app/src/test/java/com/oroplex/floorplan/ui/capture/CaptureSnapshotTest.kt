package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.solve.SolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-death snapshot: what survives, what is dropped, and that bad input never crashes. */
class CaptureSnapshotTest {

    private val wall = WallObservation(
        line = Line2.of(Vec2(0.0, 2.0), Vec2(-1.0, 0.0)),
        extentStart = Vec2(1.0, 2.0),
        extentEnd = Vec2(-1.0, 2.0),
        source = WallSource.DEPTH_FIT,
        rmsResidual = 0.012,
        pointCount = 240,
        confidence = 0.8,
        viewer = Vec2.ZERO,
    )

    private val solve = SolveResult(
        corners = listOf(Vec2(-2.0, -1.5), Vec2(2.0, -1.5), Vec2(2.0, 1.5), Vec2(-2.0, 1.5)),
        edgeSources = listOf(0, 1, 2, 3),
        dominantAngle = 0.01,
        warnings = listOf("Check wall 2"),
    )

    private val door = Opening("o", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0)

    private fun state() = CaptureUiState(
        step = CaptureStep.REVIEW,
        draft = RoomDraft(
            id = "d",
            observations = List(4) { wall },
            ceilingHeight = 2.6,
            ceilingMeasured = true,
            ceilingY = 2.6,
            openings = listOf(door),
            parkedOpenings = listOf(ParkedOpening(door, Vec2(0.0, 0.0), Vec2(0.8, 0.0))),
            solve = solve,
            name = "Den",
            nameEdited = true,
            type = RoomType.DEN,
            anchorCreated = true,
            // Transient: not kept.
            recaptureIndex = 2,
            pendingOpeningEdge = Vec2(1.0, 1.0),
            history = listOf(CaptureEdit(emptyList(), emptyList(), "undo")),
        ),
        sessionId = "s1",
        floorY = -0.02,
        autoLockFloor = false,
        rejectedFloorY = 0.5,
        sessionRoomIds = linkedSetOf("r1", "r2"),
        sessionRoomScales = mapOf("r1" to AppliedScale(1.02, Vec2(1.0, 1.0))),
        sessionAxis = SessionAxis(0.01, "r1"),
        lastMeasuredCeiling = 2.5,
    )

    @Test
    fun roundTripKeepsTheFlowAndDropsTransientModes() {
        val corrections = mapOf("r1" to RigidTransform2(0.01, Vec2(0.1, 0.0)), "bad" to RigidTransform2(Double.NaN, Vec2.ZERO))
        val json = CaptureSnapshot.of("p", "f", state(), corrections).encode()
        val snapshot = requireNotNull(CaptureSnapshot.decode(json))
        assertEquals("p", snapshot.projectId)
        assertEquals("f", snapshot.floorId)
        assertEquals(setOf("r1"), snapshot.corrections.keys)

        val restored = snapshot.restoreInto(CaptureUiState(draft = RoomDraft(id = "fresh")))
        val original = state()
        assertEquals(CaptureStep.REVIEW, restored.step)
        assertEquals("s1", restored.sessionId)
        assertEquals(-0.02, restored.floorY ?: Double.NaN, 0.0)
        assertEquals(false, restored.autoLockFloor)
        assertEquals(0.5, restored.rejectedFloorY ?: Double.NaN, 0.0)
        assertEquals(listOf("r1", "r2"), restored.sessionRoomIds.toList())
        assertEquals(original.sessionRoomScales, restored.sessionRoomScales)
        assertEquals(original.sessionAxis, restored.sessionAxis)
        assertEquals(2.5, restored.lastMeasuredCeiling ?: Double.NaN, 0.0)

        val d = restored.draft
        val o = original.draft
        assertEquals(o.copy(recaptureIndex = null, pendingOpeningEdge = null, history = emptyList()), d)
        assertEquals(solve, d.solve)
    }

    @Test
    fun aReviewStepWithoutASolvedRoomFallsBackToCapturing() {
        val s = state().let { it.copy(draft = it.draft.copy(solve = null, method = CaptureMethod.CORNERS)) }
        val restored = requireNotNull(CaptureSnapshot.decode(CaptureSnapshot.of("p", "f", s, emptyMap()).encode()))
            .restoreInto(CaptureUiState(draft = RoomDraft(id = "fresh")))
        assertEquals(CaptureStep.CORNERS, restored.step)
        // Without a locked floor every capture step needs the floor first.
        val noFloor = requireNotNull(CaptureSnapshot.decode(CaptureSnapshot.of("p", "f", state().copy(floorY = null), emptyMap()).encode()))
            .restoreInto(CaptureUiState(draft = RoomDraft(id = "fresh")))
        assertEquals(CaptureStep.FIND_FLOOR, noFloor.step)
    }

    @Test
    fun theInterruptedNoticeSurvivesToo() {
        val notice = InterruptedScan(2, lostRoom = false, restorable = RestorableRoom(state().draft.copy(history = emptyList(), recaptureIndex = null, pendingOpeningEdge = null), "s0", RigidTransform2(0.02, Vec2.ZERO)))
        val s = state().copy(interrupted = notice)
        val restored = requireNotNull(CaptureSnapshot.decode(CaptureSnapshot.of("p", "f", s, emptyMap()).encode()))
            .restoreInto(CaptureUiState(draft = RoomDraft(id = "fresh")))
        assertEquals(notice, restored.interrupted)
    }

    @Test
    fun badInputNeverCrashes() {
        assertNull(CaptureSnapshot.decode(null))
        assertNull(CaptureSnapshot.decode(""))
        assertNull(CaptureSnapshot.decode("{not json"))
        assertNull(CaptureSnapshot.decode("""{"version":99,"projectId":"p","floorId":"f"}"""))
        // Unknown fields from a newer build are ignored.
        val minimal = CaptureSnapshot.decode("""{"version":1,"projectId":"p","floorId":"f","future":true}""")
        assertEquals("p", minimal?.projectId)
    }

    @Test
    fun anOversizedSnapshotDropsTheRoomInProgressRatherThanFail() {
        val huge = state().let { it.copy(draft = it.draft.copy(observations = List(2_000) { wall })) }
        val json = CaptureSnapshot.of("p", "f", huge, emptyMap()).encode()
        assertTrue(json.length <= CaptureSnapshot.MAX_CHARS)
        val snapshot = requireNotNull(CaptureSnapshot.decode(json))
        assertNull(snapshot.draft)
        assertEquals(listOf("r1", "r2"), snapshot.sessionRoomIds)
    }
}
