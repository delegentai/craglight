package org.craglight.app.community

import java.time.Instant
import java.util.UUID
import kotlin.math.min

const val COMMUNITY_LICENCE = "CC-BY-SA-4.0"

data class ImagePoint(val x: Double, val y: Double) {
    fun valid() = x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}
data class DraftPhoto(val id: String, val fileName: String, val width: Int, val height: Int, val sha256: String,
                      val originalWork: Boolean=true, val sourceCredit: String?=null, val sourceUrl: String?=null)
data class DraftRoute(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val gradeSystem: String = "ukTrad",
    val grade: String = "", val style: String = "trad", val description: String = "",
    val photoId: String? = null, val lines: List<List<ImagePoint>> = emptyList(),
)
data class CommunityDraft(
    val id: String = UUID.randomUUID().toString(), val existingCragId: String? = null, val basePublicationId: String? = null,
    val cragName: String = "", val latitude: String = "", val longitude: String = "",
    val parkingLatitude: String = "", val parkingLongitude: String = "", val parkingDescription: String = "",
    val parkingUnknownReason: String = "", val access: String = "", val accessCheckedAt: String = "",
    val kind: String = "guide", val caption: String = "",
    val approach: String = "", val descent: String = "", val credit: String = "", val originalWorkConsent: Boolean = false,
    val consentedAt: String? = null, val photos: List<DraftPhoto> = emptyList(), val routes: List<DraftRoute> = emptyList(),
    val createdAt: String = Instant.now().toString(), val updatedAt: String = Instant.now().toString(),
    val submissionStatus: String? = null, val submittedAt: String? = null,
    val serverMissing: List<String> = emptyList(), val uploadError: String? = null,
) {
    val hasContent: Boolean get() = photos.isNotEmpty() || routes.any { it.name.isNotBlank() || it.description.isNotBlank() } || approach.isNotBlank() || parkingDescription.isNotBlank()
    val isSubmitted: Boolean get() = submittedAt != null
    fun submissionIssues(): List<String> = buildList {
        if (cragName.isBlank() && existingCragId.isNullOrBlank()) add("Name the crag so reviewers can place your contribution.")
        if (!hasContent) add("Add a photo, route or approach observation.")
        if (credit.trim().length !in 2..40) add("Choose a public contributor name of 2–40 characters.")
        if (!originalWorkConsent || consentedAt == null) add("Confirm original work and the CC BY-SA 4.0 licence.")
        if (!coordinatePairValid(latitude, longitude)) add("Enter a valid crag coordinate pair, or leave both blank.")
        if (!coordinatePairValid(parkingLatitude, parkingLongitude)) add("Enter a valid parking coordinate pair, or leave both blank.")
        if (accessCheckedAt.isNotBlank() && runCatching { java.time.LocalDate.parse(accessCheckedAt).isAfter(java.time.LocalDate.now()) }.getOrDefault(true)) add("Use an observation date of today or earlier (yyyy-MM-dd).")
        if (routes.any { route -> route.lines.isNotEmpty() && (photos.none { it.id == route.photoId } || route.lines.any { it.size < 2 || it.any { p -> !p.valid() } }) }) add("A route line needs its original photo and at least two points.")
    }
}

fun coordinatePairValid(latitude: String, longitude: String): Boolean {
    if (latitude.isBlank() && longitude.isBlank()) return true
    val lat = latitude.toDoubleOrNull() ?: return false
    val lon = longitude.toDoubleOrNull() ?: return false
    return lat.isFinite() && lon.isFinite() && lat in 49.0..61.0 && lon in -9.0..2.2
}

/** The same transform is used to draw and to invert touches. Letterbox space is never a photo coordinate. */
data class ImageViewport(val width: Double, val height: Double, val imageWidth: Int, val imageHeight: Int,
                         val zoom: Double = 1.0, val panX: Double = 0.0, val panY: Double = 0.0) {
    private val scale get() = min(width / imageWidth.coerceAtLeast(1), height / imageHeight.coerceAtLeast(1)) * zoom
    val drawnWidth get() = imageWidth * scale
    val drawnHeight get() = imageHeight * scale
    val left get() = (width - drawnWidth) / 2 + panX
    val top get() = (height - drawnHeight) / 2 + panY
    fun toImage(x: Double, y: Double): ImagePoint? {
        if (drawnWidth <= 0 || drawnHeight <= 0) return null
        return ImagePoint((x - left) / drawnWidth, (y - top) / drawnHeight).takeIf { it.valid() }
    }
    fun toScreen(point: ImagePoint) = ImagePoint(left + point.x * drawnWidth, top + point.y * drawnHeight)
    fun clamped(): ImageViewport {
        val dx = ((drawnWidth - width) / 2).coerceAtLeast(0.0)
        val dy = ((drawnHeight - height) / 2).coerceAtLeast(0.0)
        return copy(panX = panX.coerceIn(-dx, dx), panY = panY.coerceIn(-dy, dy))
    }
}

data class CommunityConfig(val baseUrl: String = "", val googleWebClientId: String = "") {
    val configured: Boolean get() = baseUrl.isNotBlank() && googleWebClientId.isNotBlank()
}
data class CommunityProfile(val id: String, val alias: String, val photoUrl: String? = null, val acceptedContributions: Int = 0)
data class CommunitySession(val token: String, val expiresAt: String, val profile: CommunityProfile)
