package org.craglight.app.domain

import org.junit.Assert.*
import org.junit.Test

class FilteringTest {
    private val base = Crag("c1", "Test crag", 53.0, -2.0, "Peak", styles = listOf("sport"), rock = "limestone")

    @Test fun activeKnownOnlyFiltersExcludeMissingFacts() {
        assertFalse(Filtering.matches(base, Filtering.Filter(familyFriendlyOnly = true)))
        assertFalse(Filtering.matches(base, Filtering.Filter(maxWalkInDistanceM = 500.0)))
        assertFalse(Filtering.matches(base, Filtering.Filter(maxWalkInTimeMin = 20.0)))
        assertFalse(Filtering.matches(base.copy(rock = null), Filtering.Filter(rock = "limestone")))
        assertTrue(Filtering.matches(base.copy(familyFriendly = true, walkInDistanceM = 500.0), Filtering.Filter(familyFriendlyOnly = true, maxWalkInDistanceM = 500.0)))
    }

    @Test fun routeGradeAndStyleMustReferToTheSameRoute() {
        val crag = base.copy(styles = listOf("trad", "sport"), routes = listOf(
            Route("r1", "Sport route", grades = mapOf("french" to "7a"), styles = listOf("sport")),
            Route("r2", "Trad route", grades = mapOf("ukTrad" to "VS"), styles = listOf("trad")),
        ))
        assertTrue(Filtering.matches(crag, Filtering.Filter(style = "sport", gradeSystem = GradeSystem.FRENCH, minGrade = "6c", maxGrade = "7a")))
        assertFalse(Filtering.matches(crag, Filtering.Filter(style = "sport", gradeSystem = GradeSystem.UK_TRAD, minGrade = "VS", maxGrade = "E1")))
    }

    @Test fun unknownGradesNeedAnExplicitOptIn() {
        val filter = Filtering.Filter(gradeSystem = GradeSystem.FRENCH, minGrade = "6a", maxGrade = "6b")
        assertFalse(Filtering.matches(base, filter))
        assertTrue(Filtering.matches(base, filter.copy(includeUnknownGrades = true)))
        val tooHard = base.copy(routes = listOf(Route("r1", "Hard route", grades = mapOf("french" to "8a"))))
        assertFalse(Filtering.matches(tooHard, filter.copy(includeUnknownGrades = true)))
        assertFalse(Filtering.matches(base, filter.copy(minGrade = "bad", includeUnknownGrades = true)))
        assertFalse(Filtering.matches(base, filter.copy(minGrade = "7a", maxGrade = "6a", includeUnknownGrades = true)))
    }

    @Test fun aggregateGradeBandsAreUsedOnlyWithoutRouteRecords() {
        val crag = base.copy(gradeBands = mapOf("french" to GradeBand("5a", "7a")))
        val filter = Filtering.Filter(gradeSystem = GradeSystem.FRENCH, minGrade = "6a", maxGrade = "6b")
        assertTrue(Filtering.matches(crag, filter))
        assertFalse(Filtering.matches(crag.copy(routes = listOf(Route("r1", "Known", grades = mapOf("french" to "8a")))), filter))
    }

    @Test fun sortingPlacesUnknownWalkInLast() {
        val unknown = base.copy(id = "unknown", name = "A unknown")
        val far = base.copy(id = "far", name = "B far", walkInDistanceM = 800.0, walkInTimeMin = 20.0)
        val near = base.copy(id = "near", name = "C near", walkInDistanceM = 100.0, walkInTimeMin = 3.0)
        assertEquals(listOf("near", "far", "unknown"), Filtering.apply(listOf(unknown, far, near), Filtering.Filter(), Filtering.Sort.WALK_IN_DISTANCE).map { it.id })
        assertEquals(listOf("near", "far", "unknown"), Filtering.apply(listOf(unknown, far, near), Filtering.Filter(), Filtering.Sort.WALK_IN_TIME).map { it.id })
    }

    @Test fun searchFindsRouteNamesAndRegionWithoutCaseSensitivity() {
        val crag = base.copy(routes = listOf(Route("r1", "The Example")))
        assertTrue(Filtering.matches(crag, Filtering.Filter(query = "example")))
        assertTrue(Filtering.matches(crag, Filtering.Filter(query = "PEAK", rock = "LIMESTONE")))
        assertFalse(Filtering.matches(crag, Filtering.Filter(region = "Wales")))
    }
}
