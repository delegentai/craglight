package org.craglight.app.domain

data class GeoPoint(val lat: Double, val lon: Double)

data class Provenance(
    val source: String = "unknown",
    val sourceId: String = "",
    val version: String? = null,
    val timestamp: String? = null,
    val license: String = "unknown",
)

data class GradeBand(val min: String? = null, val max: String? = null)

/** Image-relative OSM coordinates, with (0,0) at the top left. */
data class TopoPoint(val x: Double, val y: Double, val type: String? = null, val dottedAfter: Boolean = false)

data class TopoPhoto(
    val ref: String,
    val assetPath: String,
    val url: String,
    val sourceUrl: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val width: Int,
    val height: Int,
    val changes: String,
    val sha256: String? = null,
    val bytes: Long? = null,
    val kind: String? = null,
)

fun commonsReferenceKey(ref: String): String = ref.trim().replace('_', ' ')

data class Route(
    val id: String,
    val name: String,
    val grades: Map<String, String> = emptyMap(),
    val styles: List<String> = emptyList(),
    val description: String? = null,
    val geometry: List<GeoPoint> = emptyList(),
    val provenance: Provenance = Provenance(),
    val topoImageRef: String? = null,
    val topoPath: List<TopoPoint> = emptyList(),
    val lengthM: Double? = null,
    val pitches: Int? = null,
    val sourceUrl: String? = null,
)

data class Parking(
    val id: String,
    val name: String = "Mapped parking",
    val lat: Double,
    val lon: Double,
    val access: String? = null,
    val association: String = "nearby-unverified",
    val accessConditional: String? = null,
)

data class Approach(
    val id: String,
    val geometry: List<GeoPoint> = emptyList(),
    val distanceM: Double? = null,
    val timeMin: Double? = null,
    val access: String? = null,
    val foot: String? = null,
    val accessConditional: String? = null,
    val footConditional: String? = null,
    val nodeIds: List<Long> = emptyList(),
)

data class Crag(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val region: String,
    val kind: String = "crag",
    val styles: List<String> = emptyList(),
    val rock: String? = null,
    val aspectDegrees: Double? = null,
    val description: String? = null,
    val access: String? = null,
    val accessConditional: String? = null,
    val restriction: String? = null,
    val familyFriendly: Boolean? = null,
    val walkInDistanceM: Double? = null,
    val walkInTimeMin: Double? = null,
    val osmUrl: String? = null,
    val ukcUrl: String? = null,
    val provenance: Provenance = Provenance(),
    val routes: List<Route> = emptyList(),
    val parking: List<Parking> = emptyList(),
    val approaches: List<Approach> = emptyList(),
    val gradeBands: Map<String, GradeBand> = emptyMap(),
    val restrictionDescription: String? = null,
    val openingHours: String? = null,
    val photos: List<TopoPhoto> = emptyList(),
    val outdoorStatus: String? = null,
    val publicationStatus: String? = null,
    val qualityVersion: Int? = null,
    val coverageScope: String? = null,
    val approachDescription: String? = null,
    val descentDescription: String? = null,
    val parkingDescription: String? = null,
    val approachSourceUrl: String? = null,
    val sourceUrl: String? = null,
    val contentCheckedAt: String? = null,
    val accessNotes: String? = null,
)
