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
    private val french = listOf("1", "2", "3", "3+") + (4..9).flatMap { number ->
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

    fun canonical(system: GradeSystem, raw: String): String? {
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
        return grade.takeIf { it in values(system) }
    }

    fun rank(system: GradeSystem, grade: String): Int? = canonical(system, grade)?.let { values(system).indexOf(it) }

    /** Invalid input or unknown grades never silently match an active range. */
    fun inRange(system: GradeSystem, grade: String, min: String? = null, max: String? = null): Boolean {
        val value = rank(system, grade) ?: return false
        val lower = if (min.isNullOrBlank()) Int.MIN_VALUE else rank(system, min) ?: return false
        val upper = if (max.isNullOrBlank()) Int.MAX_VALUE else rank(system, max) ?: return false
        return lower <= upper && value in lower..upper
    }

    fun bandsOverlap(system: GradeSystem, band: GradeBand, min: String?, max: String?): Boolean {
        val bandLow = band.min?.let { rank(system, it) } ?: return false
        val bandHigh = band.max?.let { rank(system, it) } ?: return false
        val lower = if (min.isNullOrBlank()) Int.MIN_VALUE else rank(system, min) ?: return false
        val upper = if (max.isNullOrBlank()) Int.MAX_VALUE else rank(system, max) ?: return false
        return bandLow <= bandHigh && lower <= upper && bandLow <= upper && bandHigh >= lower
    }
}
