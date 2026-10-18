package org.craglight.app.domain

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

data class SolarPosition(val altitudeDeg: Double, val azimuthDeg: Double)

/** NOAA fractional-year approximation. UTC input avoids local-zone/DST ambiguity.
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF
 * Geometric solar centre only: no terrain, tree, cloud, overhang or refraction model.
 */
object Sun {
    fun position(instant: Instant, lat: Double, lon: Double): SolarPosition {
        require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
        val utc = instant.atOffset(ZoneOffset.UTC)
        val hour = utc.hour + utc.minute / 60.0 + (utc.second + utc.nano / 1e9) / 3600.0
        val days = if (utc.toLocalDate().isLeapYear) 366.0 else 365.0
        val gamma = 2.0 * PI / days * (utc.dayOfYear - 1 + (hour - 12.0) / 24.0)
        val equationMinutes = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val solarMinutes = positiveModulo(hour * 60.0 + equationMinutes + 4.0 * lon, 1440.0)
        val hourAngle = Math.toRadians(solarMinutes / 4.0 - 180.0)
        val latitude = Math.toRadians(lat)
        val altitude = asin((sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0))
        val azimuth = atan2(sin(hourAngle), cos(hourAngle) * sin(latitude) - tan(declination) * cos(latitude))
        return SolarPosition(Math.toDegrees(altitude), positiveModulo(Math.toDegrees(azimuth) + 180.0, 360.0))
    }

    /** Potential direct sun on a vertical face; null means the aspect is unknown. */
    fun onFace(position: SolarPosition, aspectDeg: Double?): Boolean? {
        if (aspectDeg == null || !aspectDeg.isFinite()) return null
        if (position.altitudeDeg <= 0.0) return false
        val difference = Math.toRadians(positiveModulo(position.azimuthDeg - aspectDeg + 180.0, 360.0) - 180.0)
        return cos(Math.toRadians(position.altitudeDeg)) * cos(difference) > 1e-9
    }

    fun onFace(instant: Instant, lat: Double, lon: Double, aspectDeg: Double?): Boolean? = onFace(position(instant, lat, lon), aspectDeg)

    private fun positiveModulo(value: Double, divisor: Double): Double = ((value % divisor) + divisor) % divisor
}
