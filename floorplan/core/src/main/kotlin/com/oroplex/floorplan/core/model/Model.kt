package com.oroplex.floorplan.core.model

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Segment2
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
    val defaultDoorWidth: Double = 0.8128, // 32 in
    val defaultWindowWidth: Double = 0.9144, // 36 in
    /** Draw overall exterior dimension lines (width / depth of the whole floor) around the plan. */
    val showOverallDimensions: Boolean = true,
    /**
     * Thickness drawn for EXTERIOR walls (room walls with no other room's wall facing them across a partition
     * gap), and therefore what overall dimensions measure to. null = use [wallThickness] for every wall.
     */
    val exteriorWallThickness: Double? = null,
)

/** What a room's floor area counts toward in area summaries (see Measurements.areaSummary). */
@Serializable
enum class AreaCategory(val displayName: String) {
    /** Finished, heated interior space ("net living area"). */
    LIVING("Living area"),
    /** Enclosed but not living space: garage, unfinished storage / utility, basement. */
    NON_LIVING("Garage & unfinished"),
    /** Outdoor space: porch, deck, balcony, patio. */
    EXTERIOR("Outdoor"),
    /** Not counted in any total. */
    EXCLUDED("Not counted"),
}

@Serializable
enum class RoomType(val displayName: String, val defaultArea: AreaCategory = AreaCategory.LIVING) {
    // Serialized by NAME: entries may be reordered or added freely, never renamed.
    LIVING("Living Room"),
    FAMILY("Family Room"),
    DEN("Den"),
    KITCHEN("Kitchen"),
    DINING("Dining Room"),
    PANTRY("Pantry"),
    BEDROOM("Bedroom"),
    PRIMARY_BEDROOM("Primary Bedroom"),
    BATHROOM("Bathroom"),
    HALF_BATH("Half Bath"),
    OFFICE("Office"),
    BONUS("Bonus Room"),
    SUNROOM("Sunroom"),
    HALLWAY("Hallway"),
    ENTRY("Entry"),
    MUDROOM("Mudroom"),
    STAIRS("Stairs"),
    CLOSET("Closet"),
    LAUNDRY("Laundry"),
    UTILITY("Utility", AreaCategory.NON_LIVING),
    STORAGE("Storage", AreaCategory.NON_LIVING),
    GARAGE("Garage", AreaCategory.NON_LIVING),
    WORKSHOP("Workshop", AreaCategory.NON_LIVING),
    BASEMENT("Basement", AreaCategory.NON_LIVING),
    PORCH("Porch", AreaCategory.EXTERIOR),
    DECK("Deck", AreaCategory.EXTERIOR),
    BALCONY("Balcony", AreaCategory.EXTERIOR),
    PATIO("Patio", AreaCategory.EXTERIOR),
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
    /**
     * True when the width / position were typed or defaulted in the editor rather than marked edge-to-edge in
     * AR. Tape calibration keeps a typed opening's width and only moves its centre with the scaled wall.
     */
    val typed: Boolean = false,
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
    /** Floor-to-ceiling height in meters, null if unknown. */
    val ceilingHeight: Double? = null,
    /**
     * True when [ceilingHeight] is an assumed default (ceiling step skipped) rather than measured or typed.
     * UI and exports must mark such heights as estimated, and calibration must not rescale them.
     */
    val ceilingEstimated: Boolean = false,
    /** True when [ceilingHeight] was typed by the user (e.g. tape-measured): calibration must not rescale it. */
    val ceilingTyped: Boolean = false,
    val openings: List<Opening> = emptyList(),
    val captureMethod: CaptureMethod = CaptureMethod.MANUAL,
    /** Raw capture data (walls mode), in the same order the user captured them. */
    val observations: List<WallObservation> = emptyList(),
    /** Raw corner taps (corners mode). */
    val cornerTaps: List<Vec2> = emptyList(),
    /** AR session the room was captured in; rooms from one session share a coordinate frame. */
    val sessionId: String? = null,
    val warnings: List<String> = emptyList(),
    /** What this room's area counts toward; null = its type's default ([RoomType.defaultArea]). */
    val areaCategory: AreaCategory? = null,
    /** Walls the user set by hand in the editor (typed length, dragged / inserted / deleted corner). */
    val handSetWalls: List<HandSetWall> = emptyList(),
)

/** The area category this room counts toward: [Room.areaCategory], or its type's default. */
val Room.effectiveAreaCategory: AreaCategory get() = areaCategory ?: type.defaultArea

/**
 * A wall edge the user set by hand in the editor, stored as the edge's plan [segment] so it survives corner
 * index shifts. An outline edge MATCHES a hand-set wall when it is parallel within 15° and its midpoint lies
 * within 15 cm of the segment's line and within its extent (the same test RoomQuality uses for observations).
 *
 * [typedLength] is the length the user typed (a tape measurement), or null when the wall was only dragged.
 * RoomOps.transform and calibration transform [segment] with the room, and calibration restores [typedLength]
 * on the matching edge after scaling; re-solving a room from its observations clears the list.
 */
@Serializable
data class HandSetWall(val segment: Segment2, val typedLength: Double? = null)

/** Scale calibration applied to a floor from a tape-measured reference length. */
@Serializable
data class Calibration(
    val factor: Double,
    val roomId: String,
    val wallIndex: Int,
    val measuredLength: Double,
    val trueLength: Double,
    val appliedAt: Long,
    /** Plan point the floor was scaled about; rooms added later are scaled about the same point. */
    val origin: Vec2 = Vec2.ZERO,
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
