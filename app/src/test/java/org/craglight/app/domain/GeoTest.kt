package org.craglight.app.domain

import org.junit.Assert.*
import org.junit.Test

class GeoTest {
    private val a = GeoPoint(53.0, -2.0)
    private val b = GeoPoint(53.001, -2.0)
    private val c = GeoPoint(53.001, -1.998)

    @Test fun greatCircleDistanceHasKnownMagnitudeAndSymmetry() {
        assertEquals(111_195.08, Geo.distanceMeters(0.0, 0.0, 0.0, 1.0), 0.1)
        assertEquals(0.0, Geo.distanceMeters(a, a), 0.0)
        assertEquals(Geo.distanceMeters(a, c), Geo.distanceMeters(c, a), 1e-8)
    }

    @Test fun connectedMappedPathsFollowTheDetour() {
        val estimate = Geo.shortestPath(a, c, listOf(Approach("one", listOf(a, b)), Approach("two", listOf(b, c))))!!
        assertEquals(Geo.distanceMeters(a, b) + Geo.distanceMeters(b, c), estimate.distanceM, 0.001)
        assertTrue(estimate.distanceM > Geo.distanceMeters(a, c))
        assertEquals(listOf(a, b, c), estimate.geometry)
        assertTrue(estimate.estimated)
        assertEquals(estimate.distanceM / (4_000.0 / 60.0), estimate.timeMin, 1e-8)
    }

    @Test fun endpointsSnapToTheMiddleOfTheSameSegment() {
        val start = GeoPoint(53.0004, -2.0)
        val end = GeoPoint(53.0006, -2.0)
        val estimate = Geo.shortestPath(start, end, listOf(Approach("one", listOf(a, b))))!!
        assertEquals(Geo.distanceMeters(start, end), estimate.distanceM, 0.01)
        assertEquals(0.0, estimate.snapDistanceM, 0.01)
    }

    @Test fun forbiddenAndConditionalPathsNeverSupplyAnApproach() {
        val line = listOf(a, b)
        listOf(
            Approach("private", line, access = "private"),
            Approach("foot-no", line, foot = "no"),
            Approach("conditional", line, accessConditional = "no @ (Mar-Jul)"),
            Approach("foot-conditional", line, footConditional = "yes @ (sunrise-sunset)"),
        ).forEach { assertNull(Geo.shortestPath(a, b, listOf(it))) }
    }

    @Test fun disconnectedPathsAndRemoteEndpointsReturnUnknown() {
        val farA = GeoPoint(53.02, -2.0)
        val farB = GeoPoint(53.021, -2.0)
        val paths = listOf(Approach("one", listOf(a, b)), Approach("two", listOf(farA, farB)))
        assertNull(Geo.shortestPath(a, farB, paths))
        assertNull(Geo.shortestPath(GeoPoint(53.0, -2.1), b, paths))
    }

    @Test fun visualCrossingsWithoutMappedJunctionsDoNotConnect() {
        val west = GeoPoint(53.0005, -2.002)
        val east = GeoPoint(53.0005, -1.998)
        val paths = listOf(Approach("vertical", listOf(a, b)), Approach("horizontal", listOf(west, east)))
        assertNull(Geo.shortestPath(a, east, paths, 20.0))
    }

    @Test fun nearbyUnverifiedSnapIsMeasuredSeparately() {
        val start = GeoPoint(53.0, -2.0002)
        val estimate = Geo.shortestPath(start, b, listOf(Approach("one", listOf(a, b))))!!
        assertEquals(Geo.distanceMeters(start, a), estimate.snapDistanceM, 0.01)
        assertTrue(estimate.snapDistanceM > 10.0)
    }

    @Test fun collocatedVerticesWithDifferentOsmIdsDoNotJoinBridges() {
        val north = GeoPoint(53.002, -2.0)
        val east = GeoPoint(53.001, -1.996)
        val paths = listOf(
            Approach("lower", listOf(a, b, north), nodeIds = listOf(1, 2, 3)),
            Approach("bridge", listOf(b, east), nodeIds = listOf(8, 9))
        )
        assertNull(Geo.shortestPath(a, east, paths, 5.0))
        val connected = paths[1].copy(nodeIds = listOf(2, 9))
        assertNotNull(Geo.shortestPath(a, east, listOf(paths[0], connected), 5.0))
    }

    @Test fun batchDestinationsPreserveDetoursDisconnectedPathsAndAccessGates() {
        val farA = GeoPoint(53.02, -2.0)
        val farB = GeoPoint(53.021, -2.0)
        val restricted = GeoPoint(53.003, -1.996)
        val conditional = GeoPoint(53.004, -1.998)
        val paths = listOf(
            Approach("lower", listOf(a, b)), Approach("turn", listOf(b, c)),
            Approach("disconnected", listOf(farA, farB)),
            Approach("private", listOf(c, restricted), access = "private"),
            Approach("conditional", listOf(c, conditional), footConditional = "yes @ (sunrise-sunset)"),
        )
        val batch = Geo.shortestPaths(a, mapOf("start" to a, "corner" to b, "crag" to c, "island" to farB,
            "private" to restricted, "conditional" to conditional), paths, 5.0)
        assertEquals(setOf("start", "corner", "crag"), batch.keys)
        assertEquals(0.0, batch.getValue("start").distanceM, .001)
        assertEquals(Geo.distanceMeters(a, b), batch.getValue("corner").distanceM, .001)
        assertEquals(Geo.distanceMeters(a, b) + Geo.distanceMeters(b, c), batch.getValue("crag").distanceM, .001)
        assertEquals(listOf(a, b, c), batch.getValue("crag").geometry)
    }

    @Test fun severalDestinationSnapsDoNotJoinCoincidentBridgeNodes() {
        val north = GeoPoint(53.002, -2.0)
        val east = GeoPoint(53.001, -1.996)
        val paths = listOf(
            Approach("lower", listOf(a, b, north), nodeIds = listOf(1, 2, 3)),
            Approach("bridge", listOf(b, east), nodeIds = listOf(8, 9)),
        )
        val targets = linkedMapOf("crossing" to b, "above" to north, "bridge-end" to east)
        val batch = Geo.shortestPaths(a, targets, paths, 5.0)
        assertEquals(setOf("crossing", "above"), batch.keys)
        assertEquals(batch, Geo.shortestPaths(a, targets.entries.reversed().associate { it.toPair() }, paths, 5.0))
        val connected = paths[1].copy(nodeIds = listOf(2, 9))
        val shared = Geo.shortestPaths(a, targets, listOf(paths[0], connected), 5.0)
        assertEquals(listOf(a, b, east), shared.getValue("bridge-end").geometry)
    }

    @Test fun batchEndpointsOnBothSidesOfStartUseTheSameSegmentShortcut() {
        val start = GeoPoint(53.0005, -2.0)
        val destinations = mapOf("south" to GeoPoint(53.0002, -2.0), "north" to GeoPoint(53.0008, -2.0), "same" to start)
        val batch = Geo.shortestPaths(start, destinations, listOf(Approach("one", listOf(a, b))), 5.0)
        destinations.forEach { (id, target) ->
            assertEquals(Geo.distanceMeters(start, target), batch.getValue(id).distanceM, .001)
            assertEquals(listOf(start, target).distinct(), batch.getValue(id).geometry)
        }
    }

    @Test fun batchSnapLimitAndMeasuredEndpointConnectionsRemainExplicit() {
        val start = GeoPoint(53.0, -2.0002)
        val nearby = GeoPoint(53.001, -1.9998)
        val remote = GeoPoint(53.001, -1.995)
        val batch = Geo.shortestPaths(start, mapOf("near" to nearby, "far" to remote), listOf(Approach("one", listOf(a, b))), 20.0)
        assertEquals(setOf("near"), batch.keys)
        val result = batch.getValue("near")
        val snapped = Geo.distanceMeters(start, a) + Geo.distanceMeters(b, nearby)
        assertEquals(snapped, result.snapDistanceM, .01)
        assertEquals(snapped + Geo.distanceMeters(a, b), result.distanceM, .01)
        assertEquals(listOf(start, a, b, nearby), result.geometry)
        assertTrue(result.estimated)
    }

    @Test fun cancellingAChangedOriginInterruptsCpuWork() {
        val geometry = (0..2000).map { GeoPoint(53.0 + it * .000001, -2.0) }
        var checks = 0
        try {
            Geo.shortestPaths(geometry.first(), mapOf("end" to geometry.last()), listOf(Approach("long", geometry))) {
                if (++checks == 3) throw java.util.concurrent.CancellationException("Origin changed")
            }
            fail("Cancellation must escape without returning stale estimates")
        } catch (expected: java.util.concurrent.CancellationException) {
            assertEquals("Origin changed", expected.message)
            assertEquals(3, checks)
        }
    }
}
