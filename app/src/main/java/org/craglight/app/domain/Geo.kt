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
        return shortestPaths(from, mapOf("destination" to to), approaches, maxSnapM)["destination"]
    }

    /**
     * Builds one graph and runs Dijkstra once for all destinations. Destination projections never
     * become graph vertices, so snapping several destinations cannot join separate paths/bridges.
     * Callers can supply a coroutine cancellation check; it is invoked throughout CPU-heavy work.
     */
    fun shortestPaths(
        from: GeoPoint,
        destinations: Map<String, GeoPoint>,
        approaches: List<Approach>,
        maxSnapM: Double = 80.0,
        checkCancelled: () -> Unit = {},
    ): Map<String, PathEstimate> {
        require(valid(from) && maxSnapM.isFinite() && maxSnapM >= 0)
        checkCancelled()
        if (destinations.isEmpty()) return emptyMap()
        var work = 0
        fun checkpoint() { if ((work++ and 255) == 0) checkCancelled() }
        destinations.values.forEach { checkpoint(); require(valid(it)) }
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
        approaches.forEach { approach ->
            checkpoint()
            if (!usableForEstimate(approach)) return@forEach
            for (index in 0 until approach.geometry.lastIndex) {
                checkpoint()
                val a = approach.geometry[index]
                val b = approach.geometry[index + 1]
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
        if (segments.isEmpty()) return emptyMap()
        fun snap(point: GeoPoint): Snap? {
            var best: Snap? = null
            segments.forEachIndexed { index, segment ->
                checkpoint()
                val fraction = projectFraction(point, segment.a, segment.b)
                val projected = GeoPoint(segment.a.lat + fraction * (segment.b.lat - segment.a.lat), segment.a.lon + fraction * (segment.b.lon - segment.a.lon))
                val distance = distanceMeters(point, projected)
                if (distance <= maxSnapM && (best == null || distance < best!!.distance)) {
                    best = Snap(index, fraction, projected, distance)
                }
            }
            return best
        }
        val startSnap = snap(from) ?: return emptyMap()
        val startSegment = segments[startSnap.segment]
        val distances = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(points.size) { -1 }
        val pending = PriorityQueue<Visit>(compareBy { it.distance })
        // A unique virtual source connects only to this source segment's two actual vertices.
        // Seeding their costs is equivalent, without introducing a coordinate-keyed junction.
        fun seed(id: Int, distance: Double) {
            distances[id] = distance
            pending.add(Visit(id, distance))
        }
        seed(startSegment.aId, startSegment.length * startSnap.fraction)
        seed(startSegment.bId, startSegment.length * (1.0 - startSnap.fraction))
        while (pending.isNotEmpty()) {
            checkpoint()
            val visit = pending.remove()
            if (visit.distance > distances[visit.id]) continue
            edges[visit.id].forEach { edge ->
                checkpoint()
                val candidate = visit.distance + edge.distance
                if (candidate < distances[edge.to]) {
                    distances[edge.to] = candidate
                    previous[edge.to] = visit.id
                    pending.add(Visit(edge.to, candidate))
                }
            }
        }
        val results = linkedMapOf<String, PathEstimate>()
        destinations.forEach { (id, to) ->
            checkCancelled()
            val endSnap = snap(to) ?: return@forEach
            val endSegment = segments[endSnap.segment]
            val throughA = distances[endSegment.aId] + endSegment.length * endSnap.fraction
            val throughB = distances[endSegment.bId] + endSegment.length * (1.0 - endSnap.fraction)
            val endpoint = if (throughA <= throughB) endSegment.aId else endSegment.bId
            var networkDistance = min(throughA, throughB)
            val direct = if (startSnap.segment == endSnap.segment) {
                endSegment.length * abs(startSnap.fraction - endSnap.fraction)
            } else Double.POSITIVE_INFINITY
            val middle = mutableListOf<GeoPoint>()
            if (direct <= networkDistance && direct.isFinite()) {
                networkDistance = direct
            } else {
                if (!networkDistance.isFinite()) return@forEach
                var cursor = endpoint
                while (cursor >= 0) {
                    checkpoint()
                    middle.add(points[cursor])
                    cursor = previous[cursor]
                }
                middle.reverse()
            }
            val geometry = mutableListOf<GeoPoint>()
            fun append(point: GeoPoint) { if (geometry.lastOrNull() != point) geometry.add(point) }
            append(from)
            append(startSnap.point)
            middle.forEach(::append)
            append(endSnap.point)
            append(to)
            val snapDistance = startSnap.distance + endSnap.distance
            val distance = networkDistance + snapDistance
            results[id] = PathEstimate(geometry, distance, distance / (4_000.0 / 60.0), snapDistance)
        }
        checkCancelled()
        return results
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
