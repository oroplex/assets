package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/** TEMPORARY: inlier composition of evaluated surfaces on short faces (removed before commit). */
class TiltProbeTest {
    @Test
    fun probe() {
        val out = StringBuilder()
        val room = HardScenarios.closet40
        val edge = 3
        val face = SimFace("ret", room.start(edge), room.end(edge))
        val cfg = WallEstimatorConfig()
        for (inc in listOf(40.0, 50.0, 65.0)) {
            for (range in listOf(2.0, 2.6)) {
                val mid = face.pointOn(0.5)
                val a = Math.toRadians(inc)
                val s = listOf(1.0, -1.0).map { sd -> mid + (face.normal * cos(a) + face.direction * (sd * sin(a))) * range }.first { room.clearance(it) > 0.3 }
                for (k in 0 until 6) {
                    val rng = Random(seedOf(1L, inc.toLong(), (range * 10).toLong(), k.toLong()))
                    val aim = face.pointOn(0.2 + 0.6 * rng.nextDouble())
                    val lines = ArrayList<Map<String, Double>>()
                    WallEstimator.diagnostics = { m -> lines += m }
                    val stages = ArrayList<Pair<String, List<Pair<Line2, Int>>>>()

                    val cap = ScanSimulator.capture(room, Vec3.fromPlan(s, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.0 + 0.8 * rng.nextDouble()), rng)
                    WallEstimator.diagnostics = null
                    val o = cap.fit.observation
                    out.append(String.format(Locale.ROOT, "inc %.0f range %.1f k %d station (%.2f, %.2f): result %s %s\n", inc, range, k, s.x, s.y,
                        if (o == null) "FAIL ${cap.fit.failure} ${cap.fit.hint ?: ""}" else if (face.matches(o)) "ON" else "WRONG",
                        o?.let { String.format(Locale.ROOT, "ang %.1f", Math.toDegrees(Math.asin(abs(it.line.direction cross face.direction).coerceAtMost(1.0)))) } ?: ""))

                    val viewer = cap.viewer.toPlan()
                    fun comp(line: Line2): String {
                        val comp = HashMap<Int, Int>()
                        val resid = HashMap<Int, Double>()
                        for ((i, p) in cap.points.withIndex()) {
                            if (p.y < 0.2 || p.y > 2.29) continue
                            val q = p.toPlan()
                            val r = (q - viewer).normalized()
                            val sig = cfg.expectedSigma(q.distanceTo(viewer)) * abs(line.normal dot r)
                            val d = line.signedDistance(q)
                            if (abs(d) > (2.5 * sig).coerceIn(0.01, cfg.inlierThresholdAt(2.3))) continue
                            val surf = cap.pointSurfaces[i]
                            comp.merge(surf, 1, Int::plus)
                            resid.merge(surf, d, Double::plus)
                        }
                        return comp.entries.sortedByDescending { it.value }.joinToString(" ") { (sf, c) -> String.format(Locale.ROOT, "w%d:%d(%.1fmm)", sf + 1, c, 1000 * (resid[sf] ?: 0.0) / c) }
                    }
                    for ((st, l) in stages) {
                        for ((line, cnt) in l) {
                            val ang = Math.toDegrees(Math.atan2(line.direction.y, line.direction.x))
                            out.append(String.format(Locale.ROOT, "      [%s] dir %.1f count %d | %s\n", st, ang, cnt, comp(line)))
                        }
                    }
                    for (m in lines) {
                        val line = Line2(Vec2(m.getValue("lx"), m.getValue("ly")), Vec2(m.getValue("ux"), m.getValue("uy")))
                        val angErr = Math.toDegrees(Math.asin(abs(line.direction cross face.direction).coerceAtMost(1.0)))
                        // Inliers ~ samples within 2.5 sigma_perp of the line (sigma at the sample's range).
                        val comp = HashMap<Int, Int>()
                        val resid = HashMap<Int, Double>()
                        for ((i, p) in cap.points.withIndex()) {
                            if (p.y < 0.2 || p.y > 2.29) continue
                            val q = p.toPlan()
                            val r = (q - viewer).normalized()
                            val sig = cfg.expectedSigma(q.distanceTo(viewer)) * abs(line.normal dot r)
                            val d = line.signedDistance(q)
                            if (abs(d) > (2.5 * sig).coerceIn(0.01, cfg.inlierThresholdAt(2.3))) continue
                            val surf = cap.pointSurfaces[i]
                            comp.merge(surf, 1, Int::plus)
                            resid.merge(surf, d, Double::plus)
                        }
                        out.append(String.format(Locale.ROOT, "   surface ang %.1f count %.0f lofZ %.2f lofDev %.4f ratio %.2f span %.1f | ", angErr, m.getValue("count"), m.getValue("lofZ"), m.getValue("lofDev"), m.getValue("lofRatio"), m.getValue("spanDeg")))
                        out.append(comp.entries.sortedByDescending { it.value }.joinToString(" ") { (sf, c) -> String.format(Locale.ROOT, "w%d:%d(%.1fmm)", sf + 1, c, 1000 * (resid[sf] ?: 0.0) / c) })
                        out.append("\n")
                    }
                }
            }
        }
        File("build/tilt.txt").writeText(out.toString())
    }
}
