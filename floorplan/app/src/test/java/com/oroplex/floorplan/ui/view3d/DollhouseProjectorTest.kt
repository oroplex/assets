package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.render.Dollhouse
import com.oroplex.floorplan.core.render.Face3
import com.oroplex.floorplan.core.render.FaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DollhouseProjectorTest {
    private val settings = PlanSettings(wallThickness = 0.1)
    private val lens = Lens()
    private val width = 1000
    private val height = 1600

    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))

    private val rooms = listOf(
        Room(
            id = "a", name = "Living", corners = rect(0.0, 0.0, 4.0, 3.0), ceilingHeight = 2.5,
            openings = listOf(Opening("d", OpeningType.DOOR, 1, 1.0, 0.8, 2.0), Opening("w", OpeningType.WINDOW, 0, 1.5, 1.0, 1.2, 0.9)),
        ),
        Room(id = "b", name = "Kitchen", corners = rect(4.1, 0.0, 3.0, 3.0), ceilingHeight = 2.5),
    )
    private val mesh = DollhouseMesh.build(Dollhouse.build(rooms, settings), rooms)

    private fun framed() = OrbitCamera.framing(mesh.bounds, width.toDouble(), height.toDouble(), lens, 40.0)

    private fun meanDepth(face: Int, basis: ViewBasis): Double =
        (mesh.faceStart[face] until mesh.faceStart[face + 1]).map { v ->
            basis.toView(Vec3(mesh.vx[v], mesh.vy[v], mesh.vz[v])).z
        }.average()

    private fun isFrontFacing(face: Int, eye: Vec3): Boolean =
        mesh.nx[face] * eye.x + mesh.ny[face] * eye.y + mesh.nz[face] * eye.z - mesh.planeD[face] > 0

    @Test fun paintsFloorsFirstThenFarToNear() {
        val p = DollhouseProjector(mesh, lens)
        val cam = framed()
        assertTrue(p.update(cam, width, height))
        val basis = cam.basis()
        val painted = (0 until p.paintCount).map { p.order[it] }
        assertTrue(painted.isNotEmpty())
        val floors = painted.takeWhile { mesh.isFloor(it) }
        assertEquals(2, floors.size)
        assertTrue(painted.drop(floors.size).none { mesh.isFloor(it) })
        val depths = painted.drop(floors.size).map { meanDepth(it, basis) }
        for (k in 1 until depths.size) assertTrue("depth order at $k", depths[k] <= depths[k - 1] + 1e-4)
    }

    @Test fun backFacesAreCulled() {
        val p = DollhouseProjector(mesh, lens)
        val cam = framed()
        p.update(cam, width, height)
        val eye = cam.eye()
        val painted = (0 until p.paintCount).map { p.order[it] }.toSet()
        for (i in 0 until mesh.faceCount) assertEquals("face $i", isFrontFacing(i, eye), i in painted)
        assertTrue(p.paintCount < mesh.faceCount)
    }

    @Test fun framedModelProjectsInsideTheViewport() {
        val p = DollhouseProjector(mesh, lens)
        p.update(framed(), width, height)
        val end = 2 * p.polyStart[p.paintCount]
        assertTrue(end > 0)
        for (k in 0 until end step 2) {
            assertTrue(p.polyXY[k] in -1f..width + 1f)
            assertTrue(p.polyXY[k + 1] in -1f..height + 1f)
        }
        // Every painted face has a polygon, and outlines came along.
        for (k in 0 until p.paintCount) assertTrue(p.polyStart[k + 1] - p.polyStart[k] >= 3)
        assertTrue(p.lineStart[p.paintCount] > 0)
        val lineEnd = 4 * p.lineStart[p.paintCount]
        for (k in 0 until lineEnd) assertTrue(p.lineXY[k].isFinite())
    }

    @Test fun reprojectsOnlyWhenSomethingChanged() {
        val p = DollhouseProjector(mesh, lens)
        val cam = framed()
        assertTrue(p.update(cam, width, height))
        assertFalse(p.update(cam.copy(), width, height))
        assertTrue(p.update(cam.copy(yaw = cam.yaw + 0.1), width, height))
        assertTrue(p.update(cam.copy(yaw = cam.yaw + 0.1), width, height + 1))
    }

    @Test fun targetProjectsToTheCenter() {
        val p = DollhouseProjector(mesh, lens)
        val label = mesh.labels.first()
        p.update(OrbitCamera(label.anchor, 0.3, 1.2, 8.0), width, height)
        assertEquals(DollhouseProjector.LABEL_VISIBLE, p.labelState[0])
        assertEquals(width / 2f, p.labelX[0], 0.01f)
        assertEquals(height / 2f, p.labelY[0], 0.01f)
    }

    @Test fun labelsBehindWallsFadeAndLabelsBehindTheCameraHide() {
        val p = DollhouseProjector(mesh, lens)
        val anchor = mesh.labels.first().anchor
        // Low, from the north: the sight line into the room's middle (at half wall height) hits the solid north wall.
        p.update(OrbitCamera(anchor, Math.PI, Math.toRadians(15.0), 20.0), width, height)
        assertEquals(DollhouseProjector.LABEL_OCCLUDED, p.labelState[0])
        // From the south the same sight line passes through the window: the room is in view.
        p.update(OrbitCamera(anchor, 0.0, Math.toRadians(15.0), 20.0), width, height)
        assertEquals(DollhouseProjector.LABEL_VISIBLE, p.labelState[0])
        // At a usual 3/4 angle the near wall hides the floor's middle, but not the room: the label stays clear.
        p.update(OrbitCamera(anchor, 0.5, Math.toRadians(45.0), 15.0), width, height)
        assertEquals(DollhouseProjector.LABEL_VISIBLE, p.labelState[0])
        // From above it is in plain sight.
        p.update(OrbitCamera(anchor, 0.0, Math.toRadians(85.0), 10.0), width, height)
        assertEquals(DollhouseProjector.LABEL_VISIBLE, p.labelState[0])
        // Looking away from the model: behind the camera.
        p.update(OrbitCamera(Vec3(2.0, 0.0, -40.0), 0.0, Math.toRadians(15.0), 5.0), width, height)
        assertEquals(DollhouseProjector.LABEL_HIDDEN, p.labelState[0])
    }

    @Test fun facesCrossingTheNearPlaneAreClipped() {
        val floor = Face3(listOf(Vec3(-50.0, 0.0, 50.0), Vec3(50.0, 0.0, 50.0), Vec3(50.0, 0.0, -50.0), Vec3(-50.0, 0.0, -50.0)), FaceKind.FLOOR, null)
        // A wall facing the camera but entirely behind it.
        val behind = Face3(listOf(Vec3(-1.0, 0.0, 30.0), Vec3(-1.0, 1.0, 30.0), Vec3(1.0, 1.0, 30.0), Vec3(1.0, 0.0, 30.0)), FaceKind.WALL_INNER, null)
        val m = DollhouseMesh.build(listOf(floor, behind), emptyList())
        val p = DollhouseProjector(m, lens)
        p.update(OrbitCamera(Vec3.ZERO, 0.0, Math.toRadians(15.0), 1.0), width, height)
        assertEquals(1, p.paintCount)
        assertTrue(m.isFloor(p.order[0]))
        val n = p.polyStart[1] - p.polyStart[0]
        assertTrue(n >= 3)
        for (k in 0 until 2 * n) assertTrue(p.polyXY[k].isFinite())
        // The visible floor ends at the near plane below the camera, so it reaches the bottom of the screen.
        assertTrue((0 until n).maxOf { p.polyXY[2 * it + 1] } > height)
    }

    @Test fun emptyMeshAndEmptyViewportDrawNothing() {
        val p = DollhouseProjector(DollhouseMesh.EMPTY, lens)
        p.update(OrbitCamera(Vec3.ZERO, 0.0, 1.0, 5.0), width, height)
        assertEquals(0, p.paintCount)
        val q = DollhouseProjector(mesh, lens)
        q.update(framed(), 0, height)
        assertEquals(0, q.paintCount)
        assertTrue(q.labelState.all { it == DollhouseProjector.LABEL_HIDDEN })
    }

    @Test fun depthKeysSortFarFirst() {
        val keys = longArrayOf(
            DollhouseProjector.depthKey(1.0, 0),
            DollhouseProjector.depthKey(10.0, 1),
            DollhouseProjector.depthKey(-2.0, 2),
            DollhouseProjector.depthKey(5.5, 3),
            DollhouseProjector.depthKey(5.5, 4),
        )
        keys.sort()
        assertEquals(listOf(1, 3, 4, 0, 2), keys.map { (it and 0xFFFFFFFFL).toInt() })
    }
}
