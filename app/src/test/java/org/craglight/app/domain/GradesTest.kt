package org.craglight.app.domain

import org.junit.Assert.*
import org.junit.Test

class GradesTest {
    @Test fun gradesOrderNumericallyWithinTheirSystem() {
        assertTrue(Grades.rank(GradeSystem.UK_TRAD, "E10")!! > Grades.rank(GradeSystem.UK_TRAD, "E9")!!)
        assertTrue(Grades.rank(GradeSystem.V_SCALE, "V10")!! > Grades.rank(GradeSystem.V_SCALE, "V9")!!)
        assertTrue(Grades.rank(GradeSystem.FRENCH, "6a+")!! > Grades.rank(GradeSystem.FRENCH, "6a")!!)
        assertTrue(Grades.rank(GradeSystem.FONT, "7C+")!! < Grades.rank(GradeSystem.FONT, "8A")!!)
    }

    @Test fun britishCombinedGradesRetainSeparateMeanings() {
        assertEquals(Grades.rank(GradeSystem.UK_TRAD, "HVS"), Grades.rank(GradeSystem.UK_TRAD, "HVS 5a"))
        assertEquals(Grades.rank(GradeSystem.UK_TECH, "5a"), Grades.rank(GradeSystem.UK_TECH, "HVS 5a"))
        assertEquals("M", Grades.canonical(GradeSystem.UK_TRAD, "Moderate"))
        assertNull(Grades.rank(GradeSystem.FRENCH, "HVS 5a"))
    }

    @Test fun unknownAndAmbiguousGradesAreNotInvented() {
        assertNull(Grades.rank(GradeSystem.UK_TRAD, "E3/E4"))
        assertNull(Grades.rank(GradeSystem.V_SCALE, "6A"))
        assertNull(Grades.rank(GradeSystem.FONT, "V4"))
        assertNull(Grades.rank(GradeSystem.FRENCH, "6a/b"))
        assertNull(Grades.rank(GradeSystem.FRENCH, "unknown"))
    }

    @Test fun rangeBoundariesAreInclusiveAndBadRangesFail() {
        assertTrue(Grades.inRange(GradeSystem.FRENCH, "6a", "6a", "6b"))
        assertTrue(Grades.inRange(GradeSystem.FRENCH, "6b", "6a", "6b"))
        assertFalse(Grades.inRange(GradeSystem.FRENCH, "6b+", "6a", "6b"))
        assertFalse(Grades.inRange(GradeSystem.FRENCH, "6a", "6b", "6a"))
        assertFalse(Grades.inRange(GradeSystem.FRENCH, "6a", "??", null))
        assertFalse(Grades.inRange(GradeSystem.FRENCH, "??"))
    }

    @Test fun bandsOnlyMatchWhenBothEndsAreKnownAndOrdered() {
        assertTrue(Grades.bandsOverlap(GradeSystem.UK_TRAD, GradeBand("VS", "E3"), "HVS", "E1"))
        assertFalse(Grades.bandsOverlap(GradeSystem.UK_TRAD, GradeBand("E3", "VS"), "HVS", "E1"))
        assertFalse(Grades.bandsOverlap(GradeSystem.UK_TRAD, GradeBand("VS", null), "HVS", "E1"))
    }

    @Test fun nativeVModifiersRetainTheirOrderingAndDoNotRoundToAWholeGrade() {
        val ordered = listOf("VB-", "VB", "V0-", "V0", "V0+", "V1-", "V1", "V3", "V3+", "V4", "V8", "V8+")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", Grades.rank(GradeSystem.V_SCALE, a)!! < Grades.rank(GradeSystem.V_SCALE, b)!!) }
        assertEquals("V0+", Grades.canonical(GradeSystem.V_SCALE, " v0+ "))
        assertFalse(Grades.inRange(GradeSystem.V_SCALE, "V0+", null, "V0"))
        assertFalse(Grades.inRange(GradeSystem.V_SCALE, "V0-", "V0", null))
        assertTrue(Grades.inRange(GradeSystem.V_SCALE, "V8+", "V8", "V9"))
        assertNull(Grades.rank(GradeSystem.V_SCALE, "V3++"))
    }

    @Test fun adjacentVSourceRangesIntersectEitherEndpointButNotNeighbouringGrades() {
        assertEquals("V4/V5", Grades.canonical(GradeSystem.V_SCALE, "v4 / v5"))
        assertEquals(Grades.rank(GradeSystem.V_SCALE, "V4"), Grades.rank(GradeSystem.V_SCALE, "V4/V5"))
        assertTrue(Grades.inRange(GradeSystem.V_SCALE, "V4/V5", "V4", "V4"))
        assertTrue(Grades.inRange(GradeSystem.V_SCALE, "V4/V5", "V5", "V5"))
        assertFalse(Grades.inRange(GradeSystem.V_SCALE, "V4/V5", "V6", "V7"))
        assertFalse(Grades.inRange(GradeSystem.V_SCALE, "V4/V5", "V2", "V3"))
        assertTrue(Grades.bandsOverlap(GradeSystem.V_SCALE, GradeBand("V4/V5", "V4/V5"), "V5", "V5"))
        assertFalse(Grades.bandsOverlap(GradeSystem.V_SCALE, GradeBand("V4/V5", "V4/V5"), "V6", null))
        listOf("V5/V4", "V4/V7", "V4/5", "V4/V5/V6", "V4/6A").forEach { assertNull(Grades.canonical(GradeSystem.V_SCALE, it)) }
    }

    @Test fun nativeFrenchLowGradesAndAdjacentPlusRangesStayInTheFrenchSystem() {
        assertTrue(Grades.inRange(GradeSystem.FRENCH, "3b", null, "3c"))
        assertTrue(Grades.rank(GradeSystem.FRENCH, "3c")!! < Grades.rank(GradeSystem.FRENCH, "4a")!!)
        assertTrue(Grades.inRange(GradeSystem.FRENCH, "6a/6a+", "6a+", "6a+"))
        assertTrue(Grades.inRange(GradeSystem.FRENCH, "6a/6a+", "6a", "6a"))
        assertFalse(Grades.inRange(GradeSystem.FRENCH, "6a/6a+", "6b", null))
        assertTrue(Grades.bandsOverlap(GradeSystem.FRENCH, GradeBand("6b/6b+", "6c/6c+"), "6c+", "6c+"))
        assertFalse(Grades.bandsOverlap(GradeSystem.FRENCH, GradeBand("6b/6b+", "6c/6c+"), "7a", null))
        listOf("6a/b", "6a/7a", "6a+/6a", "6a/V3").forEach { assertNull(Grades.canonical(GradeSystem.FRENCH, it)) }
        assertNull(Grades.canonical(GradeSystem.FONT, "6a/6a+"))
    }

    @Test fun bareTechnicalThreeRemainsAnUncertainLetterCategory() {
        assertEquals("3", Grades.canonical(GradeSystem.UK_TECH, "3"))
        assertTrue(Grades.inRange(GradeSystem.UK_TECH, "3", "3a", "3a"))
        assertTrue(Grades.inRange(GradeSystem.UK_TECH, "3", "3c", "3c"))
        assertFalse(Grades.inRange(GradeSystem.UK_TECH, "3", "4a", null))
        assertTrue(Grades.bandsOverlap(GradeSystem.UK_TECH, GradeBand("3", "3"), "3b", "3c"))
        assertNull(Grades.canonical(GradeSystem.UK_TECH, "5"))
    }
}
