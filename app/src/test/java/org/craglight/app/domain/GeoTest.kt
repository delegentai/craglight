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
}
