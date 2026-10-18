package org.craglight.app.data

import org.craglight.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
internal fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
object DataJson {
    /** Reject the whole overlay if any vertex is malformed; never bridge across a missing point. */
    fun topoPath(value: JSONArray?): List<TopoPoint> {
        if (value == null || value.length() !in 2..1000) return emptyList()
        val points = mutableListOf<TopoPoint>()
        for (index in 0 until value.length()) {
            val point = value.optJSONObject(index) ?: return emptyList()
            val x = point.number("x") ?: return emptyList()
            val y = point.number("y") ?: return emptyList()
            if (x !in 0.0..1.0 || y !in 0.0..1.0) return emptyList()
            val type = point.text("type")
            if (type != null && type !in setOf("B", "A", "P", "S", "U")) return emptyList()
            if (!point.isNull("dottedAfter") && point.opt("dottedAfter") !is Boolean) return emptyList()
            points.add(TopoPoint(x, y, type, point.optBoolean("dottedAfter", false)))
        }
        return points
    }

    private fun photo(o: JSONObject): TopoPhoto? {
        val ref = o.text("ref") ?: return null
        val path = o.text("assetPath") ?: return null
        if (!path.startsWith("photos/") || path.split('/').any { it == "." || it == ".." } || !path.matches(Regex("[A-Za-z0-9_./-]+"))) return null
        val source = o.text("sourceUrl") ?: return null
        val rawLicenseUrl = o.text("licenseUrl") ?: return null
        val licenseUrl = when {
            rawLicenseUrl.startsWith("http://creativecommons.org/") -> "https://" + rawLicenseUrl.removePrefix("http://")
            rawLicenseUrl.startsWith("http://www.creativecommons.org/") -> "https://" + rawLicenseUrl.removePrefix("http://")
            else -> rawLicenseUrl
        }
        val url = o.text("url") ?: return null
        if (listOf(source, licenseUrl, url).any { address -> runCatching { java.net.URI(address).let { it.scheme != "https" || it.host.isNullOrBlank() || it.userInfo != null } }.getOrDefault(true) }) return null
        val author = o.text("author") ?: return null
        val license = o.text("license") ?: return null
        val width = o.optInt("width")
        val height = o.optInt("height")
        if (width !in 1..100_000 || height !in 1..100_000) return null
        return TopoPhoto(ref, path, url, source, author, license, licenseUrl, width, height, o.text("changes") ?: "No source-image edits recorded", o.text("sha256"), o.number("bytes")?.toLong())
    }

    private fun nodeIds(array: JSONArray?): List<Long> {
        if (array == null) return emptyList()
        val result = (0 until array.length()).map { array.optLong(it, -1) }
        return result.takeIf { it.all { id -> id > 0 } } ?: emptyList()
    }

    private fun provenance(value: JSONObject?): Provenance = value?.let {
        Provenance(it.optString("source", "osm"), it.optString("sourceId", ""), it.text("version"), it.text("timestamp"), it.optString("license", "ODbL-1.0"))
    } ?: Provenance()
    fun geometry(value: Any?): List<GeoPoint> {
        val coordinates = when (value) {
            is JSONObject -> value.optJSONArray("coordinates") ?: return emptyList()
            is JSONArray -> value
            else -> return emptyList()
        }
        return (0 until coordinates.length()).mapNotNull { index ->
            coordinates.optJSONArray(index)?.let { p ->
                if (p.length() >= 2 && p.optDouble(0).isFinite() && p.optDouble(1).isFinite()) GeoPoint(p.getDouble(1), p.getDouble(0)) else null
            }
        }
    }
    fun route(o: JSONObject): Route {
        val grades = o.optJSONObject("grades") ?: JSONObject()
        return Route(id = o.getString("id"), name = o.optString("name", "Unnamed route"),
            grades = grades.keys().asSequence().mapNotNull { key -> grades.text(key)?.let { key to it } }.toMap(),
            styles = o.optJSONArray("styles")?.strings().orEmpty(), description = o.text("description"),
            geometry = geometry(o.opt("geometry")), provenance = provenance(o.optJSONObject("provenance")),
            topoImageRef = o.text("topoImageRef"), topoPath = topoPath(o.optJSONArray("topoPath")))
    }
    fun crag(o: JSONObject): Crag {
        val ranges = o.optJSONObject("gradeRanges") ?: o.optJSONObject("gradeBands") ?: JSONObject()
        val exactGrades = o.optJSONObject("grades") ?: JSONObject()
        val gradeKeys = (ranges.keys().asSequence().toList() + exactGrades.keys().asSequence().toList()).distinct()
        val bands = gradeKeys.mapNotNull { key ->
            val range = ranges.optJSONObject(key)?.let { GradeBand(it.text("min"), it.text("max")) }
            val exact = exactGrades.text(key)
            val band = when {
                range?.min != null && range.max != null -> range
                exact != null -> GradeBand(exact, exact)
                else -> range
            }
            band?.let { key to it }
        }.toMap()
        return Crag(
            id = o.getString("id"), name = o.optString("name", "Unnamed climbing feature"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
            region = o.optString("region"), kind = o.optString("kind", "crag"), styles = o.optJSONArray("styles")?.strings().orEmpty(),
            rock = o.text("rock"), aspectDegrees = o.number("aspectDegrees"), description = o.text("description"), access = o.text("access"),
            accessConditional = o.text("accessConditional"), restriction = o.text("restriction"),
            restrictionDescription = o.text("restrictionDescription"), openingHours = o.text("openingHours"),
            outdoorStatus = o.text("outdoorStatus"),
            photos = (o.optJSONArray("photos")?.objects().orEmpty() + listOfNotNull(o.optJSONObject("photo"))).mapNotNull(::photo).distinctBy { commonsReferenceKey(it.ref) },
            familyFriendly = if (o.isNull("familyFriendly")) null else o.optBoolean("familyFriendly"),
            walkInDistanceM = o.number("walkInDistanceM"), walkInTimeMin = o.number("walkInTimeMin"),
            osmUrl = o.text("osmUrl"), ukcUrl = o.text("ukcUrl"), provenance = provenance(o.optJSONObject("provenance")),
            routes = o.optJSONArray("routes")?.objects()?.map(::route).orEmpty().distinctBy { it.id }, gradeBands = bands,
            parking = o.optJSONArray("parking")?.objects()?.map { p ->
                val tags = p.optJSONObject("rawTags") ?: p.optJSONObject("tags") ?: JSONObject()
                Parking(id = p.optString("id"), name = p.optString("name", "Mapped parking"), lat = p.optDouble("lat"), lon = p.optDouble("lon"),
                    access = p.text("access") ?: tags.text("access"), association = p.optString("association", "nearby-unverified"),
                    accessConditional = p.text("accessConditional") ?: tags.text("access:conditional"))
            }.orEmpty(),
            approaches = o.optJSONArray("approaches")?.objects()?.map { p ->
                val tags = p.optJSONObject("rawTags") ?: p.optJSONObject("tags") ?: JSONObject()
                Approach(id = p.optString("id"), geometry = geometry(p.opt("geometry")), distanceM = p.number("distanceM"), timeMin = p.number("timeMin"),
                    access = p.text("access") ?: tags.text("access"), foot = p.text("foot") ?: tags.text("foot"),
                    accessConditional = p.text("accessConditional") ?: tags.text("access:conditional"),
                    footConditional = p.text("footConditional") ?: tags.text("foot:conditional"), nodeIds = nodeIds(p.optJSONArray("nodeIds")))
            }.orEmpty()
        )
    }
    fun crags(json: String): List<Crag> = JSONObject(json).optJSONArray("crags")?.objects()?.map(::crag).orEmpty()
    fun paths(mapJson: String): List<Approach> = runCatching {
        JSONObject(mapJson).getJSONArray("features").objects().filter { it.optJSONObject("properties")?.optString("kind") == "path" }.map { feature ->
            val p = feature.optJSONObject("properties") ?: JSONObject()
            val tags = p.optJSONObject("tags") ?: p
            Approach(id = p.optString("id"), geometry = geometry(feature.opt("geometry")), access = tags.text("access"), foot = tags.text("foot"),
                accessConditional = tags.text("access:conditional"), footConditional = tags.text("foot:conditional"), nodeIds = nodeIds(p.optJSONArray("nodeIds")))
        }
    }.getOrDefault(emptyList())
}
