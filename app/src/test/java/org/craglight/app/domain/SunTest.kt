package org.craglight.app.domain

import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class SunTest {
    @Test fun londonNoonTracksEquinoxAndSolstices() {
        val equinox = Sun.position(Instant.parse("2024-03-20T12:00:00Z"), 51.5, 0.0)
        val summer = Sun.position(Instant.parse("2024-06-21T12:00:00Z"), 51.5, 0.0)
        val winter = Sun.position(Instant.parse("2024-12-21T12:00:00Z"), 51.5, 0.0)
        assertEquals(38.5, equinox.altitudeDeg, 1.0)
        assertEquals(61.94, summer.altitudeDeg, 1.0)
        assertEquals(15.06, winter.altitudeDeg, 1.0)
        assertEquals(180.0, summer.azimuthDeg, 3.0)
    }

    @Test fun solarAzimuthMovesFromEastToWest() {
        val morning = Sun.position(Instant.parse("2024-06-21T08:00:00Z"), 53.0, -2.0)
        val evening = Sun.position(Instant.parse("2024-06-21T16:00:00Z"), 53.0, -2.0)
        assertTrue(morning.azimuthDeg in 45.0..150.0)
        assertTrue(evening.azimuthDeg in 210.0..315.0)
        assertEquals(true, Sun.onFace(morning, 90.0))
        assertEquals(false, Sun.onFace(morning, 270.0))
        assertEquals(true, Sun.onFace(evening, 270.0))
    }

    @Test fun unknownAspectIsNotReportedAsShadeOrSun() {
        val noon = SolarPosition(45.0, 180.0)
        assertNull(Sun.onFace(noon, null))
        assertNull(Sun.onFace(noon, Double.NaN))
        assertEquals(true, Sun.onFace(noon, 180.0))
        assertEquals(false, Sun.onFace(noon, 0.0))
        assertEquals(false, Sun.onFace(noon, 90.0))
        assertEquals(false, Sun.onFace(SolarPosition(-10.0, 180.0), 180.0))
    }

    @Test fun instantHandlingDoesNotShiftBritishSummerTime() {
        val utc = Instant.parse("2024-06-21T12:00:00Z")
        val bst = OffsetDateTime.parse("2024-06-21T13:00:00+01:00").toInstant()
        assertEquals(Sun.position(utc, 54.0, -3.0), Sun.position(bst, 54.0, -3.0))
    }

    @Test fun polarAndYearBoundaryResultsStayFinite() {
        listOf("2024-02-29T23:59:59Z", "2025-01-01T00:00:00Z").forEach { time ->
            listOf(-90.0, 90.0).forEach { latitude ->
                val position = Sun.position(Instant.parse(time), latitude, 180.0)
                assertTrue(position.altitudeDeg.isFinite())
                assertTrue(position.azimuthDeg in 0.0..360.0)
            }
        }
    }
}
