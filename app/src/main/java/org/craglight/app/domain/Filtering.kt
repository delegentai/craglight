package org.craglight.app.domain

import java.util.Locale

object Filtering {
    data class Filter(
        val query: String = "",
        val style: String? = null,
        val rock: String? = null,
        val region: String? = null,
        val gradeSystem: GradeSystem? = null,
        val minGrade: String? = null,
        val maxGrade: String? = null,
        val maxWalkInDistanceM: Double? = null,
        val maxWalkInTimeMin: Double? = null,
        val familyFriendlyOnly: Boolean = false,
        val includeUnknownGrades: Boolean = false,
    )

    enum class Sort { NAME, WALK_IN_DISTANCE, WALK_IN_TIME, DISTANCE_FROM_ORIGIN }

    fun matches(crag: Crag, filter: Filter): Boolean {
        val query = filter.query.trim().lowercase(Locale.ROOT)
        if (query.isNotEmpty()) {
            val searchable = listOf(crag.name, crag.region, crag.rock.orEmpty(), crag.description.orEmpty()) + crag.routes.map { it.name }
            if (searchable.none { it.lowercase(Locale.ROOT).contains(query) }) return false
        }
        if (!filter.region.isNullOrBlank() && !crag.region.equals(filter.region, true)) return false
        if (!filter.rock.isNullOrBlank() && crag.rock?.equals(filter.rock, true) != true) return false
        if (filter.familyFriendlyOnly && crag.familyFriendly != true) return false
        if (filter.maxWalkInDistanceM != null && !within(crag.walkInDistanceM, filter.maxWalkInDistanceM)) return false
        if (filter.maxWalkInTimeMin != null && !within(crag.walkInTimeMin, filter.maxWalkInTimeMin)) return false
        val allStyles = crag.styles + crag.routes.flatMap { it.styles }
        if (!filter.style.isNullOrBlank() && allStyles.none { it.equals(filter.style, true) }) return false
        val system = filter.gradeSystem ?: return filter.minGrade.isNullOrBlank() && filter.maxGrade.isNullOrBlank()
        val minValid = filter.minGrade.isNullOrBlank() || Grades.rank(system, filter.minGrade) != null
        val maxValid = filter.maxGrade.isNullOrBlank() || Grades.rank(system, filter.maxGrade) != null
        if (!minValid || !maxValid) return false
        if (!filter.minGrade.isNullOrBlank() && !filter.maxGrade.isNullOrBlank() && Grades.rank(system, filter.minGrade)!! > Grades.rank(system, filter.maxGrade)!!) return false
        val relevantRoutes = crag.routes.filter { route ->
            filter.style.isNullOrBlank() || route.styles.any { it.equals(filter.style, true) } || (route.styles.isEmpty() && crag.styles.size == 1 && crag.styles[0].equals(filter.style, true))
        }
        val grades = relevantRoutes.mapNotNull { it.grades[system.key] }
        if (grades.any { Grades.inRange(system, it, filter.minGrade, filter.maxGrade) }) return true
        if (crag.routes.isEmpty()) {
            val band = crag.gradeBands[system.key]
            if (band != null && Grades.bandsOverlap(system, band, filter.minGrade, filter.maxGrade)) return true
            if (band != null && (band.min?.let { Grades.rank(system, it) } != null || band.max?.let { Grades.rank(system, it) } != null)) return false
        }
        val knownGrades = grades.any { Grades.rank(system, it) != null }
        return filter.includeUnknownGrades && !knownGrades
    }

    fun apply(crags: List<Crag>, filter: Filter, sort: Sort = Sort.NAME, origin: GeoPoint? = null): List<Crag> {
        val matched = crags.filter { matches(it, filter) }
        val byName = compareBy<Crag> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
        return when (sort) {
            Sort.NAME -> matched.sortedWith(byName)
            Sort.WALK_IN_DISTANCE -> matched.sortedWith(compareBy<Crag> { knownOrLast(it.walkInDistanceM) }.then(byName))
            Sort.WALK_IN_TIME -> matched.sortedWith(compareBy<Crag> { knownOrLast(it.walkInTimeMin) }.then(byName))
            Sort.DISTANCE_FROM_ORIGIN -> if (origin == null) matched.sortedWith(byName) else matched.sortedWith(compareBy<Crag> { Geo.distanceMeters(origin, GeoPoint(it.lat, it.lon)) }.then(byName))
        }
    }

    private fun within(value: Double?, maximum: Double) = maximum.isFinite() && maximum >= 0 && value != null && value.isFinite() && value >= 0 && value <= maximum
    private fun knownOrLast(value: Double?) = value?.takeIf { it.isFinite() && it >= 0 } ?: Double.POSITIVE_INFINITY
}
