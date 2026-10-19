package org.craglight.app.domain

import org.craglight.app.data.DataJson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GuideQualityTest {
    private fun seed(): List<Crag> = DataJson.crags(File("src/main/assets/packs/all-crags.json").readText())
    @Test fun everyBundledGuidePassesAndHasRealPhotoBytes() {
        val crags = seed()
        assertEquals(10, crags.size)
        crags.forEach { crag ->
            assertTrue(crag.name, GuideQuality.isPublishable(crag))
            crag.photos.forEach { photo ->
                val bytes = File("src/main/assets/${photo.assetPath}").readBytes()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(photo.sha256, hash)
                assertTrue(bytes.size > 1000)
            }
        }
    }
    @Test fun oldOrIncompleteGuidesNeverEnterPublicCatalogue() {
        val good = seed().first()
        listOf(
            good.copy(publicationStatus = null, qualityVersion = null),
            good.copy(publicationStatus = "pending"), good.copy(qualityVersion = 2),
            good.copy(photos = emptyList()), good.copy(approachDescription = ""),
            good.copy(parkingDescription = null), good.copy(descentDescription = null),
            good.copy(accessNotes = null), good.copy(coverageScope = null),
            good.copy(routes = emptyList()), good.copy(lat = Double.NaN),
            good.copy(routes = good.routes.map { it.copy(description = null) }),
            good.copy(routes = good.routes.map { it.copy(topoImageRef = "unrelated") }),
            good.copy(photos = good.photos.map { it.copy(kind = null) }),
            good.copy(photos = good.photos.map { it.copy(license = "All rights reserved") }),
            good.copy(photos = good.photos.map { it.copy(license = "CC-BY-ND-4.0") }),
            good.copy(photos = good.photos.map { it.copy(license = "CC-BY-NC-SA-4.0") }),
        ).forEach { assertFalse(GuideQuality.isPublishable(it)) }
    }
}
