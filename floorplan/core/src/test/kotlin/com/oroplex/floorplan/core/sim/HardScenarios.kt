package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A vertical face a capture can be aimed at: a wall of a room, or a face of a free-standing column.
 * [a] -> [b] with the visible side (the interior) on the LEFT.
 */
internal class SimFace(val label: String, val a: Vec2, val b: Vec2) {
    val length: Double get() = a.distanceTo(b)
    val direction: Vec2 get() = (b - a).normalized()
    val normal: Vec2 get() = direction.perp()
    val line: Line2 get() = Line2.through(a, b)
    fun pointOn(s: Double): Vec2 = Vec2.lerp(a, b, s)

    /**
     * Strict classification of an accepted observation: it IS this face when it faces the same way within 5°
     * (the squaring tolerance: beyond it the solver keeps the error) and its line passes within 5 cm of the
     * face at the observed middle, which itself lies on the face (± 15 cm). Anything else is a wrong wall.
     */
    fun matches(o: WallObservation): Boolean {
        if ((o.line.direction dot direction) < cos(Math.toRadians(MAX_ANGLE_DEG))) return false
        val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
        if (line.distance(mid) > MAX_OFFSET) return false
        val t = line.param(mid)
        if (t < -0.15 || t > length + 0.15) return false
        return o.line.distance(pointOn(0.5)) <= MAX_OFFSET
    }

    companion object {
        const val MAX_ANGLE_DEG = 5.0
        const val MAX_OFFSET = 0.05
    }
}

/** Outcome counts of aimed captures at one kind of face. */
internal class FaceRow(
    val room: String,
    val face: String,
    val incidenceDeg: Double,
    val range: Double,
    val onTarget: Int,
    val wrong: Int,
    val failed: Int,
    /** Accepted on target: |offset| at the face middle (m) and |angle| error (deg). */
    val offsets: List<Double>,
    val angles: List<Double>,
    val failures: Map<WallFitFailure, Int>,
    /** One line per wrong observation (what it was fitted to). */
    val wrongDetails: List<String>,
) {
    val trials: Int get() = onTarget + wrong + failed
}

/** Outcome of the tall-wardrobe experiment for one ceilingY policy. */
internal class WardrobeRow(val config: String, val ceilingY: Double?, val onTarget: Int, val wrong: Int, val failed: Int, val failures: Map<WallFitFailure, Int>)

/** Mean signed offset (m, + = toward the viewer) of head-on captures for one plane-hit policy and range. */
internal class BiasRow(val label: String, val range: Double, val meanOffset: Double, val accepted: Int, val trials: Int)

/**
 * Review round 2 (MATH-1, MATH-4, MATH-5, MATH-7, CAPX-8): scenarios the round-2 suite never had, because the
 * sample house has no short walls and the noise model was the estimator's own prior.
 *
 * * Rooms with 0.3–0.6 m closet returns, a chimney breast (0.45 m sides) and a free-standing column, scanned
 *   from the room's pole (the short faces are then seen at 40–65° incidence).
 * * Capture-level stress: every short face aimed at from 40°, 50° and 65° incidence, 2.0 and 2.6 m away, and
 *   square-on from 0.6–2.2 m (what the estimator's hint asks the user to do).
 * * Noise stress: the per-ray noise at 1.5× and 2× the model, a 0.3 % per-frame range error, a smooth 0.3 %
 *   error across the image (a patch of wall off by centimetres together), and ARCore plane hits mixed in
 *   (depth-first, as the AR layer does since MATH-5; nearest-first kept for the record).
 * * A 2.7 m-ceiling room with a 2.2 m wardrobe, aimed at just above it with an unmeasured ceiling (CAPX-8).
 *
 * The strict rule everywhere: a failed capture is acceptable (the user is told what to do), a wrong
 * observation is not.
 */
internal object HardScenarios {
    val SEED_SETS = 1..5

    /** Ceiling the capture flow passes when the ceiling was not measured: floorY + max(default, 2.4) + 0.3 (CAPX-8). */
    fun assumedBandCeiling(defaultCeiling: Double): Double = SIM_FLOOR_Y + max(defaultCeiling, 2.4) + 0.3

    private fun room(name: String, vararg xy: Double, ceilingY: Double = SIM_CEILING_Y): SimRoom =
        SimRoom(name, (xy.indices step 2).map { Vec2(xy[it], xy[it + 1]) }, ceilingY = ceilingY)

    val closet40 = room("Closet return 40", 0.0, 0.0, 5.0, 0.0, 5.0, 4.0, 1.4, 4.0, 1.4, 3.6, 0.0, 3.6)
    val closet30 = room("Closet return 30", 0.0, 0.0, 4.6, 0.0, 4.6, 3.8, 1.2, 3.8, 1.2, 3.5, 0.0, 3.5)
    val closet60 = room("Closet return 60", 0.0, 0.0, 5.2, 0.0, 5.2, 4.2, 1.5, 4.2, 1.5, 3.6, 0.0, 3.6)
    val bumpOut50 = room("Bump-out 50", 0.0, 0.0, 4.8, 0.0, 4.8, 3.4, 3.6, 3.4, 3.6, 3.9, 0.0, 3.9)
    val chimney = room("Chimney breast", 0.0, 0.0, 6.0, 0.0, 6.0, 4.2, 2.2, 4.2, 2.2, 3.75, 0.8, 3.75, 0.8, 4.2, 0.0, 4.2)
    val columnRoom = room("Column", 0.0, 0.0, 5.5, 0.0, 5.5, 4.5, 0.0, 4.5)
        .withFurniture(listOf(FurnitureBox.column(Vec2(1.6, 2.9), 0.4, SIM_CEILING_Y)))

    val rooms: List<SimRoom> = listOf(closet40, closet30, closet60, bumpOut50, chimney, columnRoom)

    /** The short faces of each room (walls shorter than 0.7 m, and the column's faces). */
    private fun shortFaces(room: SimRoom): List<SimFace> {
        val walls = (0 until room.size).filter { room.length(it) < 0.7 }.map { SimFace("wall ${it + 1}", room.start(it), room.end(it)) }
        val columnFaces = room.columns.flatMap { c ->
            val f = c.footprint
            // The column's faces seen from the room: outward normals, i.e. traversed clockwise around it.
            (0 until 4).map { k -> SimFace("column face ${k + 1}", f[(k + 1) % 4], f[k]) }
        }
        return walls + columnFaces
    }

    /** Home stations for full scans: the pole of inaccessibility and the bounds center (when well inside). */
    fun stations(room: SimRoom): List<Pair<String, Vec2>> {
        val pole = room.pole
        val bounds = com.oroplex.floorplan.core.geometry.Bounds.of(room.corners).center
        val center = bounds.takeIf {
            room.clearance(it) >= 0.8 && it.distanceTo(pole) > 0.3 && room.furniture.none { b -> b.footprintContains(it, 0.3) }
        }
        return listOfNotNull("pole" to pole, center?.let { "center" to it })
    }

    // ---------------------------------------------------------------------------------------------
    // Full scans from the room centre

    /** Full scans of every hard room from the pole / center, production defaults, nominal noise. */
    val scans: List<RoomScan> by lazy { scanRooms(family = 700L) { SimUser(it) } }

    /** The same with pessimistic noise and depth-first plane hits. */
    val scansStress: List<RoomScan> by lazy {
        scanRooms(family = 710L) { SimUser(it, noise = DepthNoise.PESSIMISTIC, planes = STRESS_PLANES) }
    }

    private fun scanRooms(family: Long, userOf: (SimRoom) -> SimUser): List<RoomScan> = SEED_SETS.flatMap { k ->
        rooms.withIndex().flatMap { (i, room) ->
            val user = userOf(room)
            stations(room).mapIndexed { si, (label, home) ->
                scanRoom(user, i, si, "s$k/$label", home, (0 until room.size).toList(), family * 100 + k)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Capture-level stress: short faces at 40-65 degrees

    val INCIDENCES = listOf(40.0, 50.0, 65.0)
    val RANGES = listOf(2.0, 2.6)
    private const val FACE_TRIALS = 10

    /** Every short face, aimed at from each incidence / range that fits in the room, [FACE_TRIALS] captures each. */
    val faces: List<FaceRow> by lazy { faceRows(DepthNoise(), PlaneHits.NONE, 800L) }

    /** Same with pessimistic noise and depth-first plane hits. */
    val facesStress: List<FaceRow> by lazy { faceRows(DepthNoise.PESSIMISTIC, STRESS_PLANES, 810L) }

    /** One aimed capture of the short-face stress: where it was taken from and what came out. */
    class FaceTrial(val room: SimRoom, val face: SimFace, val incidenceDeg: Double, val range: Double, val station: Vec2, val aim: Vec3, val capture: SimCapture)

    /** Every short face, aimed at from each incidence / range that fits in the room, [FACE_TRIALS] captures each. */
    fun faceTrials(noise: DepthNoise, planes: PlaneHits, family: Long, trials: Int = FACE_TRIALS): Sequence<FaceTrial> = sequence {
        for ((ri, room) in rooms.withIndex()) {
            for ((fi, face) in shortFaces(room).withIndex()) {
                for (inc in INCIDENCES) {
                    for (range in RANGES) {
                        val station = stationFor(room, face, inc, range) ?: continue
                        for (k in 0 until trials) {
                            val rng = Random(seedOf(family, ri.toLong(), fi.toLong(), inc.toLong(), (range * 10).toLong(), k.toLong()))
                            val aim = Vec3.fromPlan(face.pointOn(0.2 + 0.6 * rng.nextDouble()), 1.0 + 0.8 * rng.nextDouble())
                            val cap = ScanSimulator.capture(room, Vec3.fromPlan(station, CAMERA_HEIGHT), aim, rng, noise = noise, planes = planes)
                            yield(FaceTrial(room, face, inc, range, station, aim, cap))
                        }
                    }
                }
            }
        }
    }

    private fun faceRows(noise: DepthNoise, planes: PlaneHits, family: Long): List<FaceRow> =
        faceTrials(noise, planes, family).groupBy { listOf(it.room.name, it.face.label, it.incidenceDeg, it.range) }.values.map { group ->
            val first = group.first()
            var on = 0
            var wrong = 0
            var failed = 0
            val offsets = ArrayList<Double>()
            val angles = ArrayList<Double>()
            val failures = HashMap<WallFitFailure, Int>()
            val details = ArrayList<String>()
            for (trial in group) {
                val face = trial.face
                val o = trial.capture.fit.observation
                when {
                    o == null -> {
                        failed++
                        trial.capture.fit.failure?.let { failures.merge(it, 1, Int::plus) }
                    }
                    face.matches(o) -> {
                        on++
                        offsets += face.line.distance(o.line.project(face.pointOn(0.5)))
                        angles += Math.toDegrees(Math.asin(abs(o.line.direction cross face.direction).coerceAtMost(1.0)))
                    }
                    else -> {
                        wrong++
                        details += describe(trial.room, o)
                    }
                }
            }
            FaceRow(first.room.name, first.face.label, first.incidenceDeg, first.range, on, wrong, failed, offsets, angles, failures, details)
        }

    /**
     * A station [range] from the face's middle, [incidenceDeg] off its normal (either side), inside the room
     * with 0.3 m clearance, clear of furniture, and with a free line of sight to the face. Null when none fits.
     */
    private fun stationFor(room: SimRoom, face: SimFace, incidenceDeg: Double, range: Double): Vec2? {
        val mid = face.pointOn(0.5)
        val inc = Math.toRadians(incidenceDeg)
        for (side in listOf(1.0, -1.0)) {
            val s = mid + (face.normal * cos(inc) + face.direction * (side * sin(inc))) * range
            if (room.clearance(s) < 0.3) continue
            if (room.furniture.any { it.footprintContains(s, 0.3) }) continue
            val clear = listOf(0.2, 0.5, 0.8).all { f ->
                val target = face.pointOn(f) + face.normal * 0.01
                (0 until room.size).none { e -> SimRoom.rayHitsSegment(s, target - s, room.start(e), room.end(e))?.let { it > 1e-6 && it < 1 - 1e-6 } == true } &&
                    room.furniture.none { it.blocks(s, target) }
            }
            if (clear) return s
        }
        return null
    }

    /** What a wrong observation was fitted to: the nearest true wall / column face, with its angle and offset. */
    private fun describe(room: SimRoom, o: WallObservation): String {
        val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
        val candidates = (0 until room.size).map { SimFace("wall ${it + 1}", room.start(it), room.end(it)) } +
            room.columns.flatMap { c -> c.footprint.let { f -> (0 until 4).map { k -> SimFace("column face ${k + 1}", f[(k + 1) % 4], f[k]) } } }
        val best = candidates.minBy { f ->
            val ang = Math.toDegrees(Math.acos((o.line.direction dot f.direction).coerceIn(-1.0, 1.0)))
            f.line.distance(mid) + ang / 30.0
        }
        val ang = Math.toDegrees(Math.acos((o.line.direction dot best.direction).coerceIn(-1.0, 1.0)))
        return String.format(Locale.ROOT, "near %s: %.0f deg, %.0f mm off", best.label, ang, 1000 * best.line.distance(mid))
    }

    // ---------------------------------------------------------------------------------------------
    // Noise stress on a flat wall

    /** Depth-first plane mixture of the stress scenarios: half the wall rays also hit a plane, 15 % depth holes. */
    val STRESS_PLANES = PlaneHits(coverage = 0.5, depthDropout = 0.15, planeOffsetSigma = 0.01, policy = HitPolicy.DEPTH_FIRST)

    /** Noise variants of the head-on distance sweep. */
    val NOISE_VARIANTS: List<Pair<String, DepthNoise>> = listOf(
        "x1.5" to DepthNoise(scale = 1.5),
        "x2.0" to DepthNoise(scale = 2.0),
        "x1.5+frame0.3%" to DepthNoise(scale = 1.5, frameRangeBias = 0.003),
        "x1+field0.3%" to DepthNoise.SMOOTH_FIELD,
    )

    private val sweepRoom = SimRoom("Sweep 8x8", listOf(Vec2(0.0, 0.0), Vec2(8.0, 0.0), Vec2(8.0, 8.0), Vec2(0.0, 8.0)))

    /** Head-on sweep (as [SimScenarios.sweep]) for each noise variant. */
    val sweepStress: List<Pair<String, List<SweepRow>>> by lazy {
        NOISE_VARIANTS.mapIndexed { vi, (name, noise) -> name to sweep(noise, PlaneHits.NONE, 920L + vi) }
    }

    fun sweep(noise: DepthNoise, planes: PlaneHits, family: Long, distances: List<Double> = SimScenarios.SWEEP_DISTANCES): List<SweepRow> {
        val truth = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        return distances.map { d ->
            val failures = HashMap<WallFitFailure, Int>()
            val offsets = ArrayList<Double>()
            val angles = ArrayList<Double>()
            val trials = 30
            for (k in 0 until trials) {
                val rng = Random(seedOf(family, 91L, k.toLong()))
                val aimX = 3.0 + 2.0 * rng.nextDouble()
                val aim = Vec3.fromPlan(Vec2(aimX, 0.0), 1.0 + 0.8 * rng.nextDouble())
                val station = Vec3.fromPlan(Vec2(aimX, d), CAMERA_HEIGHT)
                val fit = ScanSimulator.capture(sweepRoom, station, aim, Random(seedOf(family, 93L, (d * 1000).toLong(), k.toLong())), noise = noise, planes = planes).fit
                fit.failure?.let { failures.merge(it, 1, Int::plus) }
                fit.observation?.let { o ->
                    offsets += abs(o.line.signedDistance(Vec2(aimX, 0.0)))
                    angles += Math.toDegrees(Math.asin(o.line.sinAngleTo(truth).coerceIn(0.0, 1.0)))
                }
            }
            SweepRow(d, trials, failures, offsets, angles)
        }
    }

    /** MATH-5: mean signed offset toward the viewer with a plane hit on every wall ray, per hit policy. */
    val planeBias: List<BiasRow> by lazy {
        val truth = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        listOf(
            "depth only" to PlaneHits.NONE,
            "depth-first, planes on 100 %" to PlaneHits(coverage = 1.0, depthDropout = 0.0, planeOffsetSigma = 0.0),
            "depth-first, 100 % + 15 % holes" to PlaneHits(coverage = 1.0, depthDropout = 0.15, planeOffsetSigma = 0.0),
            "NEAREST (round 2), planes on 100 %" to PlaneHits(coverage = 1.0, planeOffsetSigma = 0.0, policy = HitPolicy.NEAREST),
        ).flatMap { (label, planes) ->
            listOf(1.5, 2.5, 3.5).map { d ->
                var sum = 0.0
                var n = 0
                val trials = 40
                for (k in 0 until trials) {
                    // Identical rays and noise for every policy: only the hit selection differs.
                    val rng = Random(seedOf(940L, (d * 1000).toLong(), k.toLong()))
                    val aimX = 3.0 + 2.0 * rng.nextDouble()
                    val o = ScanSimulator.capture(
                        sweepRoom, Vec3.fromPlan(Vec2(aimX, d), CAMERA_HEIGHT), Vec3.fromPlan(Vec2(aimX, 0.0), 1.0 + 0.8 * rng.nextDouble()),
                        Random(seedOf(941L, (d * 1000).toLong(), k.toLong())), planes = planes,
                    ).fit.observation ?: continue
                    sum += truth.signedDistance(o.line.project(Vec2(aimX, 0.0)))
                    n++
                }
                BiasRow(label, d, if (n > 0) sum / n else Double.NaN, n, trials)
            }
        }
    }

    /** (a) clean walls of the sample house with pessimistic noise and depth-first plane hits. */
    val cleanStress: List<RoomScan> by lazy {
        SimScenarios.SEED_SETS.flatMap { k ->
            SimScenarios.truthRooms.withIndex().flatMap { (i, room) ->
                val user = SimUser(room, noise = DepthNoise.PESSIMISTIC, planes = STRESS_PLANES)
                SimScenarios.stations(room, i, k).mapIndexed { si, (label, home) ->
                    scanRoom(user, i, si, "s$k/$label", home, (0 until room.size).toList(), 7300L + k)
                }
            }
        }
    }

    /** (a) clean walls of the sample house with the nominal per-ray noise plus a smooth 0.3 % field error. */
    val cleanField: List<RoomScan> by lazy {
        SimScenarios.SEED_SETS.flatMap { k ->
            SimScenarios.truthRooms.withIndex().flatMap { (i, room) ->
                val user = SimUser(room, noise = DepthNoise.SMOOTH_FIELD)
                SimScenarios.stations(room, i, k).mapIndexed { si, (label, home) ->
                    scanRoom(user, i, si, "s$k/$label", home, (0 until room.size).toList(), 7400L + k)
                }
            }
        }
    }

    /** Half-angle (degrees) of the narrow sampling grid of [cleanNarrowGrid]. */
    const val NARROW_GRID_DEG = 8.0

    /**
     * (a) clean walls of the sample house, nominal noise, sampled over ± [NARROW_GRID_DEG]° instead of ± 12°. The
     * app samples a square of 25 % of the short side of the view: about ± 7° to ± 10° depending on the camera's
     * field of view, which is not known from here. A narrower grid sees less of each wall, so directions are
     * known less well.
     */
    val cleanNarrowGrid: List<RoomScan> by lazy {
        SimScenarios.SEED_SETS.flatMap { k ->
            SimScenarios.truthRooms.withIndex().flatMap { (i, room) ->
                val user = SimUser(room, rig = CaptureRig(halfAngleDeg = NARROW_GRID_DEG))
                SimScenarios.stations(room, i, k).mapIndexed { si, (label, home) ->
                    scanRoom(user, i, si, "s$k/$label", home, (0 until room.size).toList(), 100L + k)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Short walls square-on: what the hint asks for

    val SQUARE_DISTANCES = listOf(0.6, 0.8, 1.0, 1.2, 1.5, 1.8, 2.2)
    private const val SQUARE_TRIALS = 20

    /** Outcome counts of square-on captures of one short wall from one distance. */
    class SquareRow(val room: String, val length: Double, val distance: Double, val stress: Boolean, val onTarget: Int, val wrong: Int, val failed: Int, val maxAngle: Double, val maxOffset: Double) {
        val trials: Int get() = onTarget + wrong + failed
    }

    /**
     * The short wall of each closet-return / chimney room (its wall 4), aimed at from square in front of it at
     * [SQUARE_DISTANCES], [SQUARE_TRIALS] captures each, with nominal and with pessimistic noise.
     */
    val squareOn: List<SquareRow> by lazy {
        listOf(false, true).flatMap { stress ->
            listOf(closet30, closet40, closet60, chimney).flatMap { room ->
                val face = SimFace("wall 4", room.start(3), room.end(3))
                SQUARE_DISTANCES.map { d ->
                    var on = 0
                    var wrong = 0
                    var failed = 0
                    var maxAngle = 0.0
                    var maxOffset = 0.0
                    for (k in 0 until SQUARE_TRIALS) {
                        val rng = Random(seedOf(980L, if (stress) 1L else 0L, (face.length * 100).toLong(), (d * 10).toLong(), k.toLong()))
                        val aim = face.pointOn(0.2 + 0.6 * rng.nextDouble())
                        val cap = ScanSimulator.capture(
                            room, Vec3.fromPlan(aim + face.normal * d, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.0 + 0.8 * rng.nextDouble()), rng,
                            noise = if (stress) DepthNoise.PESSIMISTIC else DepthNoise(), planes = if (stress) STRESS_PLANES else PlaneHits.NONE,
                        )
                        val o = cap.fit.observation
                        when {
                            o == null -> failed++
                            face.matches(o) -> {
                                on++
                                maxAngle = max(maxAngle, Math.toDegrees(Math.asin(abs(o.line.direction cross face.direction).coerceAtMost(1.0))))
                                maxOffset = max(maxOffset, face.line.distance(o.line.project(face.pointOn(0.5))))
                            }
                            else -> wrong++
                        }
                    }
                    SquareRow(room.name, face.length, d, stress, on, wrong, failed, maxAngle, maxOffset)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CAPX-8: tall wardrobe under an unmeasured 2.7 m ceiling

    private const val WARDROBE_HEIGHT = 2.2
    private const val WARDROBE_DEPTH = 0.6

    /** 4.6 x 3.8 m bedroom, 2.7 m ceiling, a 2.2 m tall, 0.6 m deep wardrobe along wall 1 (x 0.6..3.4). */
    val wardrobeRoom: SimRoom = room("Wardrobe 2.7 m", 0.0, 0.0, 4.6, 0.0, 4.6, 3.8, 0.0, 3.8, ceilingY = 2.7).let { r ->
        r.withFurniture(listOf(FurnitureBox(0, r.start(0), r.direction(0), r.inwardNormal(0), 0.6, 3.4, WARDROBE_DEPTH, WARDROBE_HEIGHT)))
    }

    /**
     * Wall 1 aimed at just above the wardrobe as the user sees it (3–10 cm above where the wall shows over its
     * top, about 2.4-2.5 m high; x 0.9..3.1) from 2.8–3.4 m away, for each ceilingY policy of the capture flow:
     * null (round 2: the estimator assumes 2.4 m, band top 2.25 m), the round-3 assumed band ceiling for an
     * 8 ft and a 9 ft project default, and the measured ceiling.
     */
    val wardrobe: List<WardrobeRow> by lazy {
        val wall = SimFace("wall 1", wardrobeRoom.start(0), wardrobeRoom.end(0))
        listOf(
            "ceilingY = null (round 2)" to null,
            "assumed, 8 ft default" to assumedBandCeiling(2.44),
            "assumed, 9 ft default" to assumedBandCeiling(2.74),
            "measured 2.7 m" to 2.7,
        ).map { (label, ceilingY) ->
            var on = 0
            var wrong = 0
            var failed = 0
            val failures = HashMap<WallFitFailure, Int>()
            for (k in 0 until 30) {
                val rng = Random(seedOf(960L, k.toLong()))
                val x = 0.9 + 2.2 * rng.nextDouble()
                val station = Vec2(x + 0.6 * (rng.nextDouble() - 0.5), 2.8 + 0.6 * rng.nextDouble())
                // Just above the wardrobe as the user sees it: the wall shows from H + D·(H − eye) / (d − D) up.
                val visibleFrom = WARDROBE_HEIGHT + WARDROBE_DEPTH * (WARDROBE_HEIGHT - CAMERA_HEIGHT) / (station.y - WARDROBE_DEPTH)
                val aim = Vec3.fromPlan(Vec2(x, 0.0), visibleFrom + 0.03 + 0.07 * rng.nextDouble())
                val cap = ScanSimulator.capture(wardrobeRoom, Vec3.fromPlan(station, CAMERA_HEIGHT), aim, Random(seedOf(961L, k.toLong())), ceilingY = ceilingY)
                val o = cap.fit.observation
                when {
                    o == null -> {
                        failed++
                        cap.fit.failure?.let { failures.merge(it, 1, Int::plus) }
                    }
                    wall.matches(o) -> on++
                    else -> wrong++
                }
            }
            WardrobeRow(label, ceilingY, on, wrong, failed, failures)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Report

    fun report(): String = buildString {
        appendLine("== hard rooms: closet returns 0.3-0.6 m, a bump-out, a chimney breast (0.45 m sides), a free-standing column")
        appendLine(
            "  Full scans from the room's pole / bounds center (short faces at 40-65 deg incidence). The simulated user does " +
                "what a failure's message says: stands square to the wall, closer each time (4 tries; after that the app's " +
                "'tap the wall base' is what is left, which the simulation does not do). WRONG counts STRICT wrong walls " +
                "(accepted, but > 5 deg or > 5 cm off the aimed face); miss = walls not captured in 4 tries.",
        )
        append(SimReport.section("(h) hard rooms, nominal noise", scans))
        append(SimReport.section("(h2) hard rooms, noise x1.5 + 0.3 % per-frame range error + depth-first plane hits", scansStress))
        appendLine()
        appendLine("== short faces from the room: aimed captures at 40 / 50 / 65 deg incidence, 2.0 and 2.6 m away ($FACE_TRIALS each)")
        appendLine("  A short wall seen at an angle from across the room is refused unless it is seen flat over enough of the view; the")
        appendLine("  message asks the user to stand square to it or to tap its base. A refusal is fine; a WRONG observation is not.")
        appendLine("  room              face            inc  range |  on wrong fail | |off| max mm  |ang| max deg | failures / wrong fits")
        for (r in faces) appendLine(faceLine(r))
        appendLine(faceTotals("nominal", faces))
        appendLine(faceTotals("stress (x1.5 + frame bias + planes)", facesStress))
        for (r in facesStress.filter { it.wrong > 0 }) appendLine("  stress " + faceLine(r).trim())
        appendLine()
        appendLine("== short walls square-on (what the message asks for): accepted on target / WRONG / refused of $SQUARE_TRIALS, by distance")
        appendLine("  wall                      noise    |" + SQUARE_DISTANCES.joinToString("") { String.format(Locale.ROOT, " %7.1f m", it) } + " | max |ang| deg, |off| mm")
        for (group in squareOn.groupBy { it.room to it.stress }.values) {
            val first = group.first()
            appendLine(
                String.format(
                    Locale.ROOT, "  %-17s %4.2f m  %-8s |%s | %.2f, %.1f",
                    first.room.take(17), first.length, if (first.stress) "stress" else "nominal",
                    group.joinToString("") { String.format(Locale.ROOT, " %9s", "${it.onTarget}/${it.wrong}/${it.failed}") },
                    group.maxOf { it.maxAngle }, mm(group.maxOf { it.maxOffset }),
                ),
            )
        }
        appendLine()
        appendLine("== noise stress: flat wall head-on, 30 captures per distance (the estimator assumes the nominal model)")
        for ((name, rows) in sweepStress) {
            appendLine("  noise $name")
            appendLine("    dist  ok%  failures         | |off| med / max mm | |ang| p95 / max deg")
            for (row in rows) {
                val fails = row.failures.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}x${it.value}" }
                appendLine(
                    String.format(
                        Locale.ROOT, "    %4.1f  %3.0f  %-16s | %6s / %6s     | %5s / %5s",
                        row.distance, 100 * row.successRate, fails.ifEmpty { "-" },
                        q(row.offsets, 0.5, true), q(row.offsets, 1.0, true), q(row.angles, 0.95, false), q(row.angles, 1.0, false),
                    ),
                )
            }
        }
        appendLine()
        appendLine("== plane hits (MATH-5): mean signed line offset toward the viewer, head-on, 40 captures each")
        for (b in planeBias) {
            appendLine(String.format(Locale.ROOT, "  %-36s %3.1f m: %+6.1f mm (%d/%d accepted)", b.label, b.range, 1000 * b.meanOffset, b.accepted, b.trials))
        }
        appendLine()
        append(SimReport.section("(a-stress) sample house, noise x1.5 + 0.3 % per-frame range error + depth-first plane hits", cleanStress))
        appendLine()
        append(
            SimReport.section(
                "(a-field) sample house, nominal per-ray noise + a smooth 0.3 % range error across the image (the same for all frames of a capture)",
                cleanField,
            ),
        )
        appendLine()
        append(
            SimReport.section(
                "(a-narrow) sample house, nominal noise, sampling grid +-${NARROW_GRID_DEG.toInt()} deg instead of +-12 deg (same seeds as (a))",
                cleanNarrowGrid,
            ),
        )
        appendLine()
        appendLine("== tall wardrobe (CAPX-8): 2.7 m ceiling not measured, 2.2 m wardrobe, aim just above it (~2.4-2.5 m high), 30 captures each")
        for (w in wardrobe) {
            val fails = w.failures.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}x${it.value}" }
            appendLine(
                String.format(
                    Locale.ROOT, "  %-26s ceilingY %-5s | on wall %2d | WRONG %2d | failed %2d %s",
                    w.config, w.ceilingY?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "null", w.onTarget, w.wrong, w.failed, fails,
                ),
            )
        }
        appendLine(
            "  (ceilingY = null is the round-2 call, kept for the record: the strip of wall above the wardrobe is outside the " +
                "height band, so the AR layer has no aim point to pass and the estimator fits what is left in the band, the " +
                "wardrobe front. The capture flow now always passes a band top.)",
        )
    }

    private fun faceLine(r: FaceRow): String {
        val fails = r.failures.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name.take(9)}x${it.value}" }
        val wrong = r.wrongDetails.distinct().take(2).joinToString("; ")
        return String.format(
            Locale.ROOT, "  %-17s %-15s %3.0f  %4.1f  | %3d %4d %4d | %12s  %12s | %s%s",
            r.room.take(17), r.face, r.incidenceDeg, r.range, r.onTarget, r.wrong, r.failed,
            q(r.offsets, 1.0, true), q(r.angles, 1.0, false), fails.ifEmpty { "-" }, if (wrong.isEmpty()) "" else " / $wrong",
        )
    }

    private fun faceTotals(label: String, rows: List<FaceRow>): String {
        val on = rows.sumOf { it.onTarget }
        val wrong = rows.sumOf { it.wrong }
        val failed = rows.sumOf { it.failed }
        return String.format(Locale.ROOT, "  TOTAL %-37s captures %d: on target %d, WRONG %d, failed %d", label, on + wrong + failed, on, wrong, failed)
    }

    private fun q(v: List<Double>, p: Double, asMm: Boolean): String {
        if (v.isEmpty()) return "-"
        val x = ErrorSummary.percentile(v.sorted(), p)
        return String.format(Locale.ROOT, if (asMm) "%.1f" else "%.2f", if (asMm) mm(x) else x)
    }
}
