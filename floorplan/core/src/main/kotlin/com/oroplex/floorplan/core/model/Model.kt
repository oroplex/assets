package com.oroplex.floorplan.core.model

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import kotlinx.serialization.Serializable

/*
 * Persistent data model. All lengths are METERS, all plan coordinates use the plan frame
 * documented on Vec2 (plan.x = world X, plan.y = -world Z). Angles are radians.
 * Everything here is immutable; edits produce copies.
 */

@Serializable
enum class UnitSystem { IMPERIAL, METRIC }

@Serializable
data class PlanSettings(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    /** Imperial display rounding: 1 = whole inches, 2 = 1/2", 4 = 1/4", 8 = 1/8". */
    val imperialDenominator: Int = 2,
    /** Wall thickness drawn on the plan (interior partition default 4.5" = 0.1143 m). */
    val wallThickness: Double = 0.1143,
    /** Snap nearly-square corners to exactly 90 degrees when solving rooms. */
    val squareAngles: Boolean = true,
    /** Walls within this many degrees of the room's dominant axis (mod 90) are snapped. */
    val squareToleranceDeg: Double = 5.0,
    val showWallLengths: Boolean = true,
    val showRoomAreas: Boolean = true,
    val defaultCeilingHeight: Double = 2.4384, // 8 ft
    val defaultDoorHeight: Double = 2.032, // 80 in
    val defaultWindowHeight: Double = 1.2192, // 48 in
    val defaultWindowSill: Double = 0.9144, // 36 in
)

@Serializable
enum class RoomType(val displayName: String) {
    LIVING("Living Room"),
    FAMILY("Family Room"),
    KITCHEN("Kitchen"),
    DINING("Dining Room"),
    BEDROOM("Bedroom"),
    PRIMARY_BEDROOM("Primary Bedroom"),
    BATHROOM("Bathroom"),
    HALF_BATH("Half Bath"),
    HALLWAY("Hallway"),
    ENTRY("Entry"),
    CLOSET("Closet"),
    LAUNDRY("Laundry"),
    OFFICE("Office"),
    GARAGE("Garage"),
    UTILITY("Utility"),
    STORAGE("Storage"),
    STAIRS("Stairs"),
    OTHER("Room"),
}

@Serializable
enum class OpeningType { DOOR, WINDOW, OPENING }

/** Door hinge side / swing direction relative to walking INTO the room through the wall edge. */
@Serializable
enum class DoorSwing { LEFT_IN, RIGHT_IN, LEFT_OUT, RIGHT_OUT }

/**
 * A door / window / open passage placed on one wall edge of a room.
 * The edge is corners[wallIndex] -> corners[(wallIndex + 1) % n].
 * [offset] is the distance from corners[wallIndex] along that edge to the opening's near side.
 */
@Serializable
data class Opening(
    val id: String,
    val type: OpeningType,
    val wallIndex: Int,
    val offset: Double,
    val width: Double,
    val height: Double,
    val sillHeight: Double = 0.0,
    val swing: DoorSwing = DoorSwing.LEFT_IN,
)

@Serializable
enum class CaptureMethod { WALLS, CORNERS, MANUAL }

/** How a wall observation was obtained. */
@Serializable
enum class WallSource { DETECTED_PLANE, DEPTH_FIT, BASELINE_TAPS, MANUAL }

/**
 * One measured wall from the AR capture (raw data kept so rooms can be re-solved later).
 * [line] is the infinite line of the wall's interior face in plan coordinates.
 * [extentStart]/[extentEnd] are the observed part of the wall (projected onto [line]).
 * [viewer] is the plan position of the phone when the wall was captured — it is on the room's
 * interior side, which disambiguates orientation.
 */
@Serializable
data class WallObservation(
    val line: Line2,
    val extentStart: Vec2,
    val extentEnd: Vec2,
    val source: WallSource,
    /** RMS distance of the inlier points from [line], meters (0 for taps/manual). */
    val rmsResidual: Double,
    val pointCount: Int,
    /** 0..1 heuristic quality score. */
    val confidence: Double,
    val viewer: Vec2,
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val type: RoomType = RoomType.OTHER,
    /** Interior outline, counter-clockwise, plan meters, no repeated closing vertex. */
    val corners: List<Vec2>,
    /** Floor-to-ceiling height in meters, null if not measured. */
    val ceilingHeight: Double? = null,
    val openings: List<Opening> = emptyList(),
    val captureMethod: CaptureMethod = CaptureMethod.MANUAL,
    /** Raw capture data (walls mode), in the same order the user captured them. */
    val observations: List<WallObservation> = emptyList(),
    /** Raw corner taps (corners mode). */
    val cornerTaps: List<Vec2> = emptyList(),
    /** AR session the room was captured in; rooms from one session share a coordinate frame. */
    val sessionId: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Scale calibration applied to a floor from a tape-measured reference length. */
@Serializable
data class Calibration(
    val factor: Double,
    val roomId: String,
    val wallIndex: Int,
    val measuredLength: Double,
    val trueLength: Double,
    val appliedAt: Long,
)

@Serializable
data class Floor(
    val id: String,
    val name: String,
    val rooms: List<Room> = emptyList(),
    val calibration: Calibration? = null,
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val address: String = "",
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val floors: List<Floor> = emptyList(),
    val settings: PlanSettings = PlanSettings(),
    val schemaVersion: Int = 1,
)
