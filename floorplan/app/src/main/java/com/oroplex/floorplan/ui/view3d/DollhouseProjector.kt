package com.oroplex.floorplan.ui.view3d

import java.util.Arrays

/**
 * The per-frame half of the software 3D pipeline: for a camera and viewport it transforms every vertex of
 * [mesh] into view space, culls back faces, clips faces and outlines against the near plane, projects them to
 * pixels and orders the faces back to front (painter's algorithm: floors first, since nothing in the model is
 * below them, then everything else by mean view depth, farthest first). Room labels get a screen position and
 * an occlusion flag.
 *
 * Results stay valid until the next [update]; an update with the same camera and viewport is free, so the
 * canvas only re-projects when the camera moves. Buffers are reused (grown when needed), so steady-state
 * frames do not allocate. Not thread-safe: use from one thread (the UI thread).
 */
class DollhouseProjector(val mesh: DollhouseMesh, val lens: Lens = Lens()) {
    /** Number of faces to paint, in [order]. */
    var paintCount: Int = 0
        private set

    /** Face indices in paint order (first painted first). */
    val order = IntArray(mesh.faceCount)

    /** Screen polygon of the k-th painted face: x, y pairs at polyXY[2 * polyStart[k]] until [2 * polyStart[k + 1]]. */
    var polyXY = FloatArray(maxOf(16, 2 * mesh.vertexCount))
        private set
    val polyStart = IntArray(mesh.faceCount + 1)

    /** Outline segments of the k-th painted face: x0, y0, x1, y1 quadruples at lineXY[4 * lineStart[k]] until [4 * lineStart[k + 1]]. */
    var lineXY = FloatArray(maxOf(16, 2 * mesh.vertexCount))
        private set
    val lineStart = IntArray(mesh.faceCount + 1)

    /** Per label (same order as mesh.labels): screen position and [LABEL_HIDDEN] / [LABEL_VISIBLE] / [LABEL_OCCLUDED]. */
    val labelX = FloatArray(mesh.labels.size)
    val labelY = FloatArray(mesh.labels.size)
    val labelState = IntArray(mesh.labels.size)

    private val viewX = DoubleArray(mesh.vertexCount)
    private val viewY = DoubleArray(mesh.vertexCount)
    private val viewZ = DoubleArray(mesh.vertexCount)
    private val keys = LongArray(mesh.faceCount)

    /** Near-plane clipping output: a plane cut adds at most one vertex per edge. */
    private val clipCapacity = 2 * (0 until mesh.faceCount).maxOfOrNull { mesh.faceStart[it + 1] - mesh.faceStart[it] }.let { it ?: 0 } + 4
    private val clipX = DoubleArray(clipCapacity)
    private val clipY = DoubleArray(clipCapacity)
    private val clipZ = DoubleArray(clipCapacity)

    private var lastCamera: OrbitCamera? = null
    private var lastWidth = -1
    private var lastHeight = -1

    /** Recompute for [camera] and a [widthPx] × [heightPx] viewport. Returns false when nothing changed. */
    fun update(camera: OrbitCamera, widthPx: Int, heightPx: Int): Boolean {
        if (camera == lastCamera && widthPx == lastWidth && heightPx == lastHeight) return false
        lastCamera = camera
        lastWidth = widthPx
        lastHeight = heightPx
        paintCount = 0
        polyStart[0] = 0
        lineStart[0] = 0
        if (widthPx <= 0 || heightPx <= 0 || mesh.faceCount == 0) {
            labelState.fill(LABEL_HIDDEN)
            return true
        }

        val basis = camera.basis()
        val eye = basis.eye
        val f = lens.focalPx(heightPx.toDouble())
        val cx = widthPx / 2.0
        val cy = heightPx / 2.0
        val near = lens.near
        transformVertices(basis)

        // Cull and key the faces: floors (group 0) before the rest (group 1), far before near within a group.
        var floors = 0
        var count = 0
        val faceStart = mesh.faceStart
        for (i in 0 until mesh.faceCount) {
            val facing = mesh.nx[i] * eye.x + mesh.ny[i] * eye.y + mesh.nz[i] * eye.z - mesh.planeD[i]
            if (facing <= 1e-9) continue
            var maxZ = Double.NEGATIVE_INFINITY
            var sumZ = 0.0
            for (v in faceStart[i] until faceStart[i + 1]) {
                val z = viewZ[v]
                if (z > maxZ) maxZ = z
                sumZ += z
            }
            if (maxZ < near) continue
            val depth = sumZ / (faceStart[i + 1] - faceStart[i])
            val key = depthKey(depth, i)
            if (mesh.isFloor(i)) {
                // Keep floors at the front of the key array, others after them.
                if (count > floors) keys[count] = keys[floors]
                keys[floors] = key
                floors++
            } else {
                keys[count] = key
            }
            count++
        }
        Arrays.sort(keys, 0, floors)
        Arrays.sort(keys, floors, count)

        var polyFloats = 0
        var lineFloats = 0
        for (k in 0 until count) {
            val face = (keys[k] and 0xFFFFFFFFL).toInt()
            order[k] = face
            polyFloats = emitPolygon(face, f, cx, cy, near, polyFloats)
            polyStart[k + 1] = polyFloats / 2
            lineFloats = emitLines(face, f, cx, cy, near, lineFloats)
            lineStart[k + 1] = lineFloats / 4
        }
        paintCount = count
        updateLabels(basis, f, cx, cy, near)
        return true
    }

    private fun transformVertices(b: ViewBasis) {
        val ex = b.eye.x
        val ey = b.eye.y
        val ez = b.eye.z
        val rx = b.right.x; val ry = b.right.y; val rz = b.right.z
        val ux = b.up.x; val uy = b.up.y; val uz = b.up.z
        val fx = b.forward.x; val fy = b.forward.y; val fz = b.forward.z
        for (v in 0 until mesh.vertexCount) {
            val px = mesh.vx[v] - ex
            val py = mesh.vy[v] - ey
            val pz = mesh.vz[v] - ez
            viewX[v] = px * rx + py * ry + pz * rz
            viewY[v] = px * ux + py * uy + pz * uz
            viewZ[v] = px * fx + py * fy + pz * fz
        }
    }

    /** Appends the projected (near-clipped) polygon of [face] to [polyXY] at float index [at]; returns the new end. */
    private fun emitPolygon(face: Int, f: Double, cx: Double, cy: Double, near: Double, at: Int): Int {
        val start = mesh.faceStart[face]
        val end = mesh.faceStart[face + 1]
        var clipped = false
        for (v in start until end) if (viewZ[v] < near) { clipped = true; break }
        var pos = at
        if (!clipped) {
            ensurePoly(pos + 2 * (end - start))
            for (v in start until end) {
                polyXY[pos++] = (cx + f * viewX[v] / viewZ[v]).toFloat()
                polyXY[pos++] = (cy - f * viewY[v] / viewZ[v]).toFloat()
            }
            return pos
        }
        val n = clipPolygonToNear(start, end, near)
        ensurePoly(pos + 2 * n)
        for (k in 0 until n) {
            polyXY[pos++] = (cx + f * clipX[k] / clipZ[k]).toFloat()
            polyXY[pos++] = (cy - f * clipY[k] / clipZ[k]).toFloat()
        }
        return pos
    }

    /** Sutherland–Hodgman against z >= near into clipX/Y/Z; returns the vertex count (0 when fully behind). */
    private fun clipPolygonToNear(start: Int, end: Int, near: Double): Int {
        var n = 0
        val count = end - start
        for (k in 0 until count) {
            val a = start + k
            val b = start + (k + 1) % count
            val za = viewZ[a]
            val zb = viewZ[b]
            val aIn = za >= near
            val bIn = zb >= near
            if (aIn && n < clipX.size) {
                clipX[n] = viewX[a]; clipY[n] = viewY[a]; clipZ[n] = za; n++
            }
            if (aIn != bIn && n < clipX.size) {
                val t = (near - za) / (zb - za)
                clipX[n] = viewX[a] + (viewX[b] - viewX[a]) * t
                clipY[n] = viewY[a] + (viewY[b] - viewY[a]) * t
                clipZ[n] = near
                n++
            }
        }
        return if (n >= 3) n else 0
    }

    private fun emitLines(face: Int, f: Double, cx: Double, cy: Double, near: Double, at: Int): Int {
        var pos = at
        val s0 = mesh.lineStart[face]
        val s1 = mesh.lineStart[face + 1]
        ensureLines(pos + 4 * (s1 - s0))
        for (s in s0 until s1) {
            val a = mesh.lineVertex[2 * s]
            val b = mesh.lineVertex[2 * s + 1]
            var ax = viewX[a]; var ay = viewY[a]; var az = viewZ[a]
            var bx = viewX[b]; var by = viewY[b]; var bz = viewZ[b]
            if (az < near && bz < near) continue
            if (az < near) {
                val t = (near - az) / (bz - az)
                ax += (bx - ax) * t; ay += (by - ay) * t; az = near
            } else if (bz < near) {
                val t = (near - bz) / (az - bz)
                bx += (ax - bx) * t; by += (ay - by) * t; bz = near
            }
            lineXY[pos++] = (cx + f * ax / az).toFloat()
            lineXY[pos++] = (cy - f * ay / az).toFloat()
            lineXY[pos++] = (cx + f * bx / bz).toFloat()
            lineXY[pos++] = (cy - f * by / bz).toFloat()
        }
        return pos
    }

    private fun updateLabels(b: ViewBasis, f: Double, cx: Double, cy: Double, near: Double) {
        for ((i, label) in mesh.labels.withIndex()) {
            val view = b.toView(label.anchor)
            if (view.z < near) {
                labelState[i] = LABEL_HIDDEN
                continue
            }
            labelX[i] = (cx + f * view.x / view.z).toFloat()
            labelY[i] = (cy - f * view.y / view.z).toFloat()
            val onScreen = labelX[i] in 0f..lastWidth.toFloat() && labelY[i] in 0f..lastHeight.toFloat()
            labelState[i] = when {
                !onScreen -> LABEL_HIDDEN
                isOccluded(b, label.probe.x, label.probe.y, label.probe.z) -> LABEL_OCCLUDED
                else -> LABEL_VISIBLE
            }
        }
    }

    /** True when the sight line from the eye to (px, py, pz) passes through any non-floor face. */
    internal fun isOccluded(b: ViewBasis, px: Double, py: Double, pz: Double): Boolean {
        val ex = b.eye.x
        val ey = b.eye.y
        val ez = b.eye.z
        val dx = px - ex
        val dy = py - ey
        val dz = pz - ez
        for (i in 0 until mesh.faceCount) {
            if (mesh.isFloor(i)) continue
            val nx = mesh.nx[i]
            val ny = mesh.ny[i]
            val nz = mesh.nz[i]
            val denom = nx * dx + ny * dy + nz * dz
            if (denom > -1e-12 && denom < 1e-12) continue
            val s = (mesh.planeD[i] - (nx * ex + ny * ey + nz * ez)) / denom
            if (s <= 1e-6 || s >= 1 - 1e-6) continue
            val hx = ex + dx * s
            val hy = ey + dy * s
            val hz = ez + dz * s
            val u = hx * mesh.axisU[3 * i] + hy * mesh.axisU[3 * i + 1] + hz * mesh.axisU[3 * i + 2]
            val v = hx * mesh.axisV[3 * i] + hy * mesh.axisV[3 * i + 1] + hz * mesh.axisV[3 * i + 2]
            if (insidePlanePolygon(i, u, v)) return true
        }
        return false
    }

    /** Even-odd test of (u, v) against face [i]'s in-plane polygon. */
    private fun insidePlanePolygon(i: Int, u: Double, v: Double): Boolean {
        val start = mesh.faceStart[i]
        val end = mesh.faceStart[i + 1]
        var inside = false
        var j = end - 1
        for (k in start until end) {
            val ui = mesh.planeU[k]
            val vi = mesh.planeV[k]
            val uj = mesh.planeU[j]
            val vj = mesh.planeV[j]
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) inside = !inside
            j = k
        }
        return inside
    }

    private fun ensurePoly(size: Int) {
        if (size > polyXY.size) polyXY = polyXY.copyOf(maxOf(size, polyXY.size * 2))
    }

    private fun ensureLines(size: Int) {
        if (size > lineXY.size) lineXY = lineXY.copyOf(maxOf(size, lineXY.size * 2))
    }

    companion object {
        const val LABEL_HIDDEN = 0
        const val LABEL_VISIBLE = 1
        const val LABEL_OCCLUDED = 2

        /**
         * Sort key putting larger [depth] first, ties by [index]: the order-preserving integer image of -depth
         * in the high 32 bits, the face index in the low 32 bits.
         */
        internal fun depthKey(depth: Double, index: Int): Long {
            val bits = java.lang.Float.floatToRawIntBits((-depth).toFloat())
            val ordered = if (bits >= 0) bits else bits xor 0x7FFFFFFF
            return (ordered.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
        }
    }
}
