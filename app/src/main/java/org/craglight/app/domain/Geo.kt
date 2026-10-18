package org.craglight.app.domain

import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.*

data class PathEstimate(
    val geometry: List<GeoPoint>,
    val distanceM: Double,
    val timeMin: Double,
    val snapDistanceM: Double,
    val estimated: Boolean = true,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double = distanceMeters(from.lat, from.lon, to.lat, to.lon)

    /** Great-circle distance. This is never, by itself, a walk-in distance. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        require(valid(GeoPoint(lat1, lon1)) && valid(GeoPoint(lat2, lon2)))
        val latitude1 = Math.toRadians(lat1)
        val latitude2 = Math.toRadians(lat2)
        val dLat = latitude2 - latitude1
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(latitude1) * cos(latitude2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceIn(0.0, 1.0)))
    }

    fun polylineDistanceM(points: List<GeoPoint>): Double = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Unknown access is retained as unknown. Conditional or explicitly restricted paths are excluded. */
    fun usableForEstimate(approach: Approach): Boolean {
        if (!approach.accessConditional.isNullOrBlank() || !approach.footConditional.isNullOrBlank()) return false
        val acceptable = setOf("", "yes", "designated", "permissive", "public")
        return approach.access.orEmpty().trim().lowercase(Locale.ROOT) in acceptable &&
            approach.foot.orEmpty().trim().lowercase(Locale.ROOT) in acceptable
    }

    /**
     * Dijkstra on shared mapped vertices only. Crossing lines without a shared vertex do not connect.
     * Endpoints may snap to an existing segment within maxSnapM; that connection is explicitly an
     * estimate, not evidence of a legal approach. No disconnected gap is filled with a straight line.
     */
    fun shortestPath(from: GeoPoint, to: GeoPoint, approaches: List<Approach>, maxSnapM: Double = 80.0): PathEstimate? {
        require(valid(from) && valid(to) && maxSnapM.isFinite() && maxSnapM >= 0)
        val points = mutableListOf<GeoPoint>()
        val vertexIds = mutableMapOf<String, Int>()
        val edges = mutableListOf<MutableList<Edge>>()
        fun node(point: GeoPoint, sourceNodeId: Long? = null): Int = vertexIds.getOrPut(sourceNodeId?.let { "osm:$it" } ?: "coordinate:${key(point)}") {
            points.add(point)
            edges.add(mutableListOf())
            points.lastIndex
        }
        fun connect(a: Int, b: Int, distance: Double) {
            if (a == b) return
            edges[a].add(Edge(b, distance))
            edges[b].add(Edge(a, distance))
        }
        val segments = mutableListOf<Segment>()
        approaches.filter(::usableForEstimate).forEach { approach ->
            approach.geometry.zipWithNext().forEachIndexed { index, (a, b) ->
                if (valid(a) && valid(b)) {
                    val aId = node(a, approach.nodeIds.getOrNull(index))
                    val bId = node(b, approach.nodeIds.getOrNull(index + 1))
                    val length = distanceMeters(a, b)
                    if (aId != bId && length > 0.0) {
                        connect(aId, bId, length)
                        segments.add(Segment(aId, bId, a, b, length))
                    }
                }
            }
        }
        if (segments.isEmpty()) return null
        fun snap(point: GeoPoint): Snap? {
            return segments.mapIndexed { index, segment ->
                val fraction = projectFraction(point, segment.a, segment.b)
                val projected = GeoPoint(segment.a.lat + fraction * (segment.b.lat - segment.a.lat), segment.a.lon + fraction * (segment.b.lon - segment.a.lon))
                Snap(index, fraction, projected, distanceMeters(point, projected))
            }.minByOrNull { it.distance }?.takeIf { it.distance <= maxSnapM }
        }
        val startSnap = snap(from) ?: return null
        val endSnap = snap(to) ?: return null
        fun insert(snap: Snap): Int {
            val id = node(snap.point)
            val segment = segments[snap.segment]
            connect(id, segment.aId, segment.length * snap.fraction)
            connect(id, segment.bId, segment.length * (1.0 - snap.fraction))
            return id
        }
        val start = insert(startSnap)
        val end = insert(endSnap)
        if (startSnap.segment == endSnap.segment) {
            connect(start, end, segments[startSnap.segment].length * abs(startSnap.fraction - endSnap.fraction))
        }
        val distances = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(points.size) { -1 }
        val pending = PriorityQueue<Visit>(compareBy { it.distance })
        distances[start] = 0.0
        pending.add(Visit(start, 0.0))
        while (pending.isNotEmpty()) {
            val visit = pending.remove()
            if (visit.distance > distances[visit.id]) continue
            if (visit.id == end) break
            edges[visit.id].forEach { edge ->
                val candidate = visit.distance + edge.distance
                if (candidate < distances[edge.to]) {
                    distances[edge.to] = candidate
                    previous[edge.to] = visit.id
                    pending.add(Visit(edge.to, candidate))
                }
            }
        }
        if (!distances[end].isFinite()) return null
        val reversed = mutableListOf<GeoPoint>()
        var cursor = end
        while (cursor >= 0) {
            reversed.add(points[cursor])
            if (cursor == start) break
            cursor = previous[cursor]
        }
        val geometry = (listOf(from) + reversed.asReversed() + to).fold(mutableListOf<GeoPoint>()) { result, point ->
            if (result.lastOrNull() != point) result.add(point)
            result
        }
        val snapDistance = startSnap.distance + endSnap.distance
        val distance = distances[end] + snapDistance
        return PathEstimate(geometry, distance, distance / (4_000.0 / 60.0), snapDistance)
    }

    private fun valid(point: GeoPoint) = point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0
    private fun key(point: GeoPoint) = Pair((point.lat * 1e7).roundToLong(), (point.lon * 1e7).roundToLong())
    private fun projectFraction(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val longitudeScale = cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val x = (b.lon - a.lon) * longitudeScale
        val y = b.lat - a.lat
        val px = (point.lon - a.lon) * longitudeScale
        val py = point.lat - a.lat
        val lengthSquared = x * x + y * y
        return if (lengthSquared == 0.0) 0.0 else ((px * x + py * y) / lengthSquared).coerceIn(0.0, 1.0)
    }
    private data class Edge(val to: Int, val distance: Double)
    private data class Visit(val id: Int, val distance: Double)
    private data class Segment(val aId: Int, val bId: Int, val a: GeoPoint, val b: GeoPoint, val length: Double)
    private data class Snap(val segment: Int, val fraction: Double, val point: GeoPoint, val distance: Double)
}
