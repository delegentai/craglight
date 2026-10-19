package org.craglight.app.domain

/** Publication is an explicit editorial decision as well as a completeness check. */
object GuideQuality {
    private val photoLicences = setOf("CC BY 2.0", "CC BY 3.0", "CC BY 4.0", "CC BY SA 2.0", "CC BY SA 3.0", "CC BY SA 4.0")
    fun isPublishable(crag: Crag): Boolean =
        crag.publicationStatus == "published" && crag.qualityVersion == 1 &&
        crag.name.isNotBlank() && crag.lat in 49.0..61.5 && crag.lon in -9.0..2.5 &&
        !crag.coverageScope.isNullOrBlank() && !crag.approachDescription.isNullOrBlank() &&
        !crag.parkingDescription.isNullOrBlank() && !crag.descentDescription.isNullOrBlank() &&
        !crag.accessNotes.isNullOrBlank() && !crag.sourceUrl.isNullOrBlank() &&
        !crag.contentCheckedAt.isNullOrBlank() && crag.photos.isNotEmpty() &&
        crag.photos.all { it.author.isNotBlank() && it.license.replace('-', ' ') in photoLicences && it.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true } &&
        crag.routes.isNotEmpty() && crag.routes.all { route ->
            route.name.isNotBlank() && route.grades.isNotEmpty() && !route.description.isNullOrBlank() &&
            crag.photos.any { photo ->
                route.topoImageRef == photo.ref && (photo.kind == "annotated-topo" || route.topoPath.size >= 2)
            }
        }
}
