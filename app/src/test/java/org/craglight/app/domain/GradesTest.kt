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
}
