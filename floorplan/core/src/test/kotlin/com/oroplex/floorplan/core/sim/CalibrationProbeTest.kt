package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/** TEMPORARY threshold calibration probe (removed before commit). */
class CalibrationProbeTest {
    private class Stat(val good: Boolean, val m: Map<String, Double>)

    private fun obsOf(m: Map<String, Double>): WallObservation {
        val line = Line2(Vec2(m.getValue("lx"), m.getValue("ly")), Vec2(m.getValue("ux"), m.getValue("uy")))
        val mid = Vec2(m.getValue("mx"), m.getValue("my"))
        val half = m.getValue("extent") / 2
        return WallObservation(line, mid - line.direction * half, mid + line.direction * half, WallSource.DEPTH_FIT, 0.0, 0, 1.0, Vec2.ZERO)
    }

    private fun faceMatch(face: SimFace, m: Map<String, Double>): Boolean {
        val o = obsOf(m)
        val oo = if ((o.line.direction dot face.direction) < 0) o.copy(line = o.line.flipped()) else o
        return face.matches(oo)
    }

    @Test
    fun probe() {
        val stats = ArrayList<Pair<String, Stat>>()
        var current: SimFace? = null
        var tag = ""
        WallEstimator.diagnostics = { m ->
            current?.let { f ->
                val o = obsOf(m)
                val dir = if ((o.line.direction dot f.direction) < 0) -o.line.direction else o.line.direction
                val extra = mapOf(
                    "angErr" to Math.toDegrees(Math.asin(kotlin.math.abs(dir cross f.direction).coerceAtMost(1.0))),
                    "offMid" to o.line.distance(f.pointOn(0.5)),
                    "midOff" to f.line.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)),
                    "faceLen" to f.length,
                )
                stats += tag to Stat(faceMatch(f, m), m + extra)
            }
        }
        try {
            // Nominal: sample house walls from the stations, like scenario (a).
            for ((noiseName, noise) in listOf("nominal" to DepthNoise(), "x2" to DepthNoise(scale = 2.0), "pess" to DepthNoise.PESSIMISTIC)) {
                for ((i, room) in SimScenarios.truthRooms.withIndex()) {
                    for ((si, st) in SimScenarios.stations(room, i, 1).withIndex()) {
                        for (e in 0 until room.size) {
                            for (k in 0 until 3) {
                                val rng = Random(seedOf(5L, i.toLong(), si.toLong(), e.toLong(), k.toLong()))
                                val aim = room.pointOn(e, 0.2 + 0.6 * rng.nextDouble())
                                var station = st.second
                                if (room.frontDistance(e, station) < 0.3 || !room.lineOfSight(station, aim, e) || station.distanceTo(aim) > 4.0) {
                                    station = aim + room.inwardNormal(e) * minOf(1.8, 0.5 * room.depthInFront(e, aim))
                                }
                                current = SimFace("w", room.start(e), room.end(e))
                                tag = "house-$noiseName"
                                ScanSimulator.capture(
                                    room, Vec3.fromPlan(station, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.0 + 0.8 * rng.nextDouble()), rng,
                                    noise = noise, planes = if (noiseName == "pess") HardScenarios.STRESS_PLANES else PlaneHits.NONE,
                                )
                            }
                        }
                    }
                }
            }
            // Hard rooms: short faces at incidences.
            for ((noiseName, noise) in listOf("nominal" to DepthNoise(), "pess" to DepthNoise.PESSIMISTIC)) {
                for ((ri, room) in HardScenarios.rooms.withIndex()) {
                    val faces = (0 until room.size).map { SimFace("wall ${it + 1}", room.start(it), room.end(it)) }
                    for ((fi, face) in faces.withIndex()) {
                        for (inc in listOf(0.0, 25.0, 40.0, 50.0, 65.0)) {
                            for (range in listOf(1.2, 2.0, 2.6)) {
                                val mid = face.pointOn(0.5)
                                val a = Math.toRadians(inc)
                                for (side in listOf(1.0, -1.0)) {
                                    val s = mid + (face.normal * cos(a) + face.direction * (side * sin(a))) * range
                                    if (room.clearance(s) < 0.3 || room.furniture.any { it.footprintContains(s, 0.3) }) continue
                                    if (!room.lineOfSight(s, mid + face.normal * 0.01, fi)) continue
                                    for (k in 0 until 4) {
                                        val rng = Random(seedOf(6L, ri.toLong(), fi.toLong(), inc.toLong(), (range * 10).toLong(), side.toLong(), k.toLong()))
                                        val aim = face.pointOn(0.2 + 0.6 * rng.nextDouble())
                                        current = face
                                        tag = "hard-$noiseName-" + (if (face.length < 0.7) "short" else "long")
                                        ScanSimulator.capture(
                                            room, Vec3.fromPlan(s, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.0 + 0.8 * rng.nextDouble()), rng,
                                            noise = noise, planes = if (noiseName == "pess") HardScenarios.STRESS_PLANES else PlaneHits.NONE,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            WallEstimator.diagnostics = null
        }
        val out = StringBuilder()
        val keys = listOf("aimMissRatio", "turnDeg", "lofZ", "lofDev", "lofDevSigmas", "lofZin", "lofDevIn", "lofIdx", "lofRatio", "count", "extent")
        for ((t, list) in stats.groupBy({ it.first }, { it.second })) {
            for (good in listOf(true, false)) {
                val sub = list.filter { it.good == good }
                out.append("$t good=$good n=${sub.size}\n")
                for (k in keys) {
                    val v = sub.map { s -> if (k == "aimMissRatio") s.m.getValue("aimMiss") / s.m.getValue("window") else s.m.getValue(k) }.sorted()
                    if (v.isEmpty()) continue
                    fun p(q: Double) = v[((v.size - 1) * q).toInt()]
                    out.append(String.format("   %-13s p50 %8.4f p90 %8.4f p99 %8.4f max %8.4f | p05 %8.4f p10 %8.4f\n", k, p(0.5), p(0.9), p(0.99), v.last(), p(0.05), p(0.1)))
                }
            }
        }
        // Joint: bad candidates that pass all current checks, good ones that fail.
        fun passes(s: Stat) = s.m.getValue("aimMiss").let { it < 0 || it <= s.m.getValue("window") } && s.m.getValue("turnDeg") <= 4.0 &&
            !(s.m.getValue("lofZ") > 4.0 && s.m.getValue("lofDev") > 0.004 && s.m.getValue("lofDevSigmas") > 0.2)
        for ((t, list) in stats.groupBy({ it.first }, { it.second })) {
            out.append("$t: good failing ${list.count { it.good && !passes(it) }}/${list.count { it.good }}, bad passing ${list.count { !it.good && passes(it) }}/${list.count { !it.good }}\n")
        }
        for ((t, list) in stats.groupBy({ it.first }, { it.second })) {
            if (!t.contains("short")) continue
            for (st in list.filter { !it.good }.take(40)) out.append("BAD  $t " + st.m.filterKeys { it !in setOf("lx","ly","ux","uy","mx","my") }.map { "${it.key}=${String.format("%.3f", it.value)}" }.joinToString(" ") + "\n")
            for (st in list.filter { it.good }.take(40)) out.append("GOOD $t " + st.m.filterKeys { it !in setOf("lx","ly","ux","uy","mx","my") }.map { "${it.key}=${String.format("%.3f", it.value)}" }.joinToString(" ") + "\n")
        }
        File("build/probe.txt").writeText(out.toString())
        val keysAll = stats.first().second.m.keys.toList()
        File("build/probe.csv").writeText("tag,good," + keysAll.joinToString(",") + "\n" + stats.joinToString("\n") { (t, st) -> "$t,${st.good}," + keysAll.joinToString(",") { st.m.getValue(it).toString() } })
    }
}
