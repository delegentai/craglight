package org.craglight.app.data

import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

data class Conditions(val time: Instant, val temperature: Double, val humidity: Double, val rain: Double, val cloud: Double, val wind: Double, val previousRain: Double?, val drying: String)
object Weather {
    fun at(record: WeatherRecord, instant: Instant): Conditions? = runCatching {
        val hourly = JSONObject(record.json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val i = (0 until times.length()).minByOrNull { abs(times.getLong(it) - instant.epochSecond) } ?: return null
        if (abs(times.getLong(i) - instant.epochSecond) > 3600) return null
        fun value(name: String): Double = hourly.getJSONArray(name).optDouble(i).also { require(it.isFinite()) }
        val temperature = value("temperature_2m"); val humidity = value("relative_humidity_2m")
        val rain = value("precipitation"); val cloud = value("cloud_cover"); val wind = value("wind_speed_10m")
        val prior = if (i >= 6) ((i - 6) until i).map { hourly.getJSONArray("precipitation").optDouble(it) }.takeIf { it.all(Double::isFinite) }?.sum() else null
        val drying = when {
            rain > 0.1 -> "Rain forecast; drying unlikely"
            prior == null -> "Recent rain history unavailable"
            prior > 2 -> "Recent rain; rock may remain wet"
            humidity >= 85 || temperature <= 3 -> "Slow drying conditions"
            wind >= 10 && humidity < 75 -> "Breeze may support drying"
            else -> "Some drying possible"
        }
        Conditions(Instant.ofEpochSecond(times.getLong(i)), temperature, humidity, rain, cloud, wind, prior, drying)
    }.getOrNull()
}
