package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable

/** Axis-aligned bounds in plan meters. */
@Serializable
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val center: Vec2 get() = Vec2((minX + maxX) / 2, (minY + maxY) / 2)
    val isEmpty: Boolean get() = maxX < minX || maxY < minY

    fun union(o: Bounds): Bounds = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> Bounds(minOf(minX, o.minX), minOf(minY, o.minY), maxOf(maxX, o.maxX), maxOf(maxY, o.maxY))
    }

    fun include(p: Vec2): Bounds =
        if (isEmpty) Bounds(p.x, p.y, p.x, p.y)
        else Bounds(minOf(minX, p.x), minOf(minY, p.y), maxOf(maxX, p.x), maxOf(maxY, p.y))

    fun expanded(margin: Double) = Bounds(minX - margin, minY - margin, maxX + margin, maxY + margin)

    fun contains(p: Vec2) = p.x in minX..maxX && p.y in minY..maxY

    companion object {
        val EMPTY = Bounds(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        fun of(points: Iterable<Vec2>): Bounds = points.fold(EMPTY) { b, p -> b.include(p) }
    }
}
