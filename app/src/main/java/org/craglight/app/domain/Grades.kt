package org.craglight.app.domain

import java.util.Locale

enum class GradeSystem(val key: String, val label: String) {
    UK_TRAD("ukTrad", "UK trad"),
    UK_TECH("ukTech", "UK technical"),
    FRENCH("french", "French sport"),
    V_SCALE("v", "V boulder"),
    FONT("font", "Font boulder");

    companion object {
        fun fromKey(key: String): GradeSystem? = entries.firstOrNull { it.key.equals(key, true) }
    }
}

/** Ordering within a named system only. No numerical cross-system conversion is made. */
object Grades {
    private val trad = listOf("M", "D", "HD", "VD", "HVD", "S", "HS", "VS", "HVS") + (1..12).map { "E$it" }
    private val technical = (3..7).flatMap { number -> ('a'..'c').map { "$number$it" } }
    private val french = listOf("1", "2", "3", "3+") + (3..9).flatMap { number ->
        ('a'..'c').flatMap { letter -> listOf("$number$letter", "$number$letter+") }
    }
    private val font = listOf("1", "2", "3", "4", "4+", "5", "5+") + (6..8).flatMap { number ->
        ('A'..'C').flatMap { letter -> listOf("$number$letter", "$number$letter+") }
    } + "9A"
    private val v = listOf("VB") + (0..17).map { "V$it" }

    fun values(system: GradeSystem): List<String> = when (system) {
        GradeSystem.UK_TRAD -> trad
        GradeSystem.UK_TECH -> technical
        GradeSystem.FRENCH -> french
        GradeSystem.V_SCALE -> v
        GradeSystem.FONT -> font
    }

    private data class Interval(val text: String, val low: Int, val high: Int = low)

    private fun normalise(system: GradeSystem, raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        val grade = when (system) {
            GradeSystem.UK_TRAD -> {
                val upper = clean.uppercase(Locale.ROOT)
                val aliases = mapOf("MOD" to "M", "MODERATE" to "M", "DIFF" to "D", "DIFFICULT" to "D", "VERY DIFFICULT" to "VD", "SEVERE" to "S", "HARD SEVERE" to "HS", "VERY SEVERE" to "VS", "HARD VERY SEVERE" to "HVS")
                aliases[upper] ?: if (upper.matches(Regex("(?:M|D|HD|VD|HVD|S|HS|VS|HVS|E\\d+) [3-7][ABC]"))) upper.substringBefore(' ') else upper
            }
            GradeSystem.UK_TECH -> {
                val lower = clean.lowercase(Locale.ROOT)
                if (lower.matches(Regex("(?:m|d|hd|vd|hvd|s|hs|vs|hvs|e\\d+) [3-7][abc]"))) lower.substringAfter(' ') else lower
            }
            GradeSystem.FRENCH -> clean.lowercase(Locale.ROOT)
            GradeSystem.FONT, GradeSystem.V_SCALE -> clean.uppercase(Locale.ROOT)
        }
        return grade
    }

    private fun atomic(system: GradeSystem, raw: String): Interval? {
        val grade = normalise(system, raw)
        if (system == GradeSystem.V_SCALE) {
            val match = Regex("(VB|V(?:[0-9]|1[0-7]))([+-]?)").matchEntire(grade) ?: return null
            val index = v.indexOf(match.groupValues[1])
            val modifier = when (match.groupValues[2]) { "-" -> -1; "+" -> 1; else -> 0 }
            return Interval(grade, index * 4 + modifier)
        }
        // A source's bare technical "3" lacks a letter. Keep that uncertainty rather than inventing 3a.
        if (system == GradeSystem.UK_TECH && grade == "3") {
            return Interval(grade, technical.indexOf("3a") * 4, technical.indexOf("3c") * 4)
        }
        val index = values(system).indexOf(grade)
        if (index < 0) return null
        return Interval(grade, index * 4)
    }

    private fun interval(system: GradeSystem, raw: String): Interval? {
        if ('/' !in raw) return atomic(system, raw)
        // Only the unambiguous adjacent, fully written variants found in the open source are supported.
        if (system != GradeSystem.V_SCALE && system != GradeSystem.FRENCH) return null
        val parts = raw.split('/')
        if (parts.size != 2) return null
        val left = atomic(system, parts[0]) ?: return null
        val right = atomic(system, parts[1]) ?: return null
        if (left.low >= right.high) return null
        val adjacent = if (system == GradeSystem.V_SCALE) {
            val leftBase = v.indexOf(left.text.trimEnd('+', '-'))
            val rightBase = v.indexOf(right.text.trimEnd('+', '-'))
            rightBase - leftBase in 0..1
        } else french.indexOf(right.text) - french.indexOf(left.text) == 1
        if (!adjacent) return null
        return Interval("${left.text}/${right.text}", left.low, right.high)
    }

    fun canonical(system: GradeSystem, raw: String): String? = interval(system, raw)?.text

    /** Ordinal lower edge, not a conversion score. Intervals retain both edges for filter comparisons. */
    fun rank(system: GradeSystem, grade: String): Int? = interval(system, grade)?.low

    private fun limits(system: GradeSystem, min: String?, max: String?): IntRange? {
        val lower = if (min.isNullOrBlank()) Int.MIN_VALUE else interval(system, min)?.low ?: return null
        val upper = if (max.isNullOrBlank()) Int.MAX_VALUE else interval(system, max)?.high ?: return null
        return (lower..upper).takeIf { lower <= upper }
    }

    /** Invalid input or unknown grades never silently match an active range. */
    fun inRange(system: GradeSystem, grade: String, min: String? = null, max: String? = null): Boolean {
        val value = interval(system, grade) ?: return false
        val bounds = limits(system, min, max) ?: return false
        // A source interval is discoverable at either endpoint; the displayed original grade remains a range.
        return value.low <= bounds.last && value.high >= bounds.first
    }

    fun bandsOverlap(system: GradeSystem, band: GradeBand, min: String?, max: String?): Boolean {
        val bandLow = band.min?.let { interval(system, it)?.low } ?: return false
        val bandHigh = band.max?.let { interval(system, it)?.high } ?: return false
        val bounds = limits(system, min, max) ?: return false
        return bandLow <= bandHigh && bandLow <= bounds.last && bandHigh >= bounds.first
    }
}
