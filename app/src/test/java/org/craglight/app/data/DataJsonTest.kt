package org.craglight.app.data

import org.craglight.app.domain.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DataJsonTest {
    private fun crag(extra: String) = JSONObject("""{"id":"osm:node:1","name":"Fixture boulder","lat":53.0,"lon":-2.0,"region":"england-north",$extra}""")

    @Test fun repeatedRelationMembersAreOneRouteAndOutdoorUncertaintySurvives() {
        val item = DataJson.crag(crag(""""outdoorStatus":"unspecified","routes":[{"id":"r1","name":"Repeated route"},{"id":"r1","name":"Repeated route"},{"id":"r2","name":"Other route"}]"""))
        assertEquals(listOf("r1", "r2"), item.routes.map { it.id })
        assertEquals("unspecified", item.outdoorStatus)
    }

    @Test fun rawRelationAccessRestrictionsReachTheRoutingGate() {
        val item = DataJson.crag(crag(""""approaches":[{"id":"osm:way:2","geometry":{"type":"LineString","coordinates":[[-2,53],[-2,53.001]]},"rawTags":{"access":"private","foot":"no","access:conditional":"no @ (Mar-Jul)","foot:conditional":"yes @ (sunrise-sunset)"}}]"""))
        val path = item.approaches.single()
        assertEquals("private", path.access)
        assertEquals("no", path.foot)
        assertEquals("no @ (Mar-Jul)", path.accessConditional)
        assertEquals("yes @ (sunrise-sunset)", path.footConditional)
        assertFalse(Geo.usableForEstimate(path))
        assertNull(Geo.shortestPath(GeoPoint(53.0, -2.0), GeoPoint(53.001, -2.0), item.approaches))
    }

    @Test fun flatAndTagsBasedApproachesAreBothSupported() {
        val item = DataJson.crag(crag(""""approaches":[{"id":"one","access":"private","tags":{"access":"yes"}},{"id":"two","tags":{"foot":"no"}},{"id":"three","accessConditional":"no @ (summer)"}]"""))
        assertEquals("private", item.approaches[0].access)
        assertEquals("no", item.approaches[1].foot)
        assertEquals("no @ (summer)", item.approaches[2].accessConditional)
        assertTrue(item.approaches.all { !Geo.usableForEstimate(it) })
    }

    @Test fun exactBoulderGradePassesItsOwnSystemFilterWithoutInventingARoute() {
        val item = DataJson.crag(crag(""""kind":"boulder","styles":["boulder"],"grades":{"font":"6A","french":null},"gradeRanges":{"font":{"min":null,"max":null}},"routes":[]"""))
        assertEquals(GradeBand("6A", "6A"), item.gradeBands["font"])
        assertTrue(item.routes.isEmpty())
        assertTrue(Filtering.matches(item, Filtering.Filter(style = "boulder", gradeSystem = GradeSystem.FONT, minGrade = "5", maxGrade = "6B")))
        assertFalse(Filtering.matches(item, Filtering.Filter(gradeSystem = GradeSystem.FRENCH, minGrade = "5a", maxGrade = "6b")))
    }

    @Test fun completeSourceRangeIsPreservedAndPartialRangeCanUseAnExactGrade() {
        val complete = DataJson.crag(crag(""""grades":{"french":"6a"},"gradeRanges":{"french":{"min":"5a","max":"7b"}}"""))
        val partial = DataJson.crag(crag(""""grades":{"french":"6a"},"gradeRanges":{"french":{"min":"5a","max":null}}"""))
        assertEquals(GradeBand("5a", "7b"), complete.gradeBands["french"])
        assertEquals(GradeBand("6a", "6a"), partial.gradeBands["french"])
    }

    @Test fun missingGradesAccessAndFamilySuitabilityRemainUnknown() {
        val item = DataJson.crag(crag(""""grades":{},"approaches":[{"id":"one"}]"""))
        assertTrue(item.gradeBands.isEmpty())
        assertNull(item.familyFriendly)
        assertNull(item.access)
        assertNull(item.approaches.single().access)
        assertFalse(Filtering.matches(item, Filtering.Filter(familyFriendlyOnly = true)))
    }

    @Test fun nestingDatesAndOpeningHoursAreRetainedVerbatim() {
        val item = DataJson.crag(crag(""""restriction":"seasonal","restrictionDescription":"No climbing 1 March to 31 July: nesting birds","openingHours":"sunrise-sunset""""))
        assertEquals("seasonal", item.restriction)
        assertEquals("No climbing 1 March to 31 July: nesting birds", item.restrictionDescription)
        assertEquals("sunrise-sunset", item.openingHours)
        val unknown = DataJson.crag(crag(""""routes":[]"""))
        assertNull(unknown.restrictionDescription)
        assertNull(unknown.openingHours)
    }

    @Test fun parkingRestrictionsSurviveBothFlatAndRawTagFormats() {
        val item = DataJson.crag(crag(""""parking":[{"id":"one","lat":53,"lon":-2,"accessConditional":"no @ (22:00-06:00)"},{"id":"two","lat":53,"lon":-2,"rawTags":{"access":"private","access:conditional":"no @ (Mo-Fr)"}},{"id":"three","lat":53,"lon":-2}]"""))
        assertEquals("no @ (22:00-06:00)", item.parking[0].accessConditional)
        assertEquals("private", item.parking[1].access)
        assertEquals("no @ (Mo-Fr)", item.parking[1].accessConditional)
        assertNull(item.parking[2].access)
        assertNull(item.parking[2].accessConditional)
        assertEquals("nearby-unverified", item.parking[2].association)
    }

    private fun photo() = JSONObject("""{"ref":"File:Fixture photo.jpg","assetPath":"photos/fixture.jpg","url":"https://upload.wikimedia.org/fixture.jpg","sourceUrl":"https://commons.wikimedia.org/wiki/File:Fixture_photo.jpg","author":"Fixture photographer","license":"CC-BY-SA-4.0","licenseUrl":"https://creativecommons.org/licenses/by-sa/4.0/","width":1200,"height":800,"changes":"Thumbnail resized by Wikimedia","sha256":"${"0".repeat(64)}","bytes":5000}""")

    @Test fun topoRetainsLicensedPhotoMetadataAndExactSourceCoordinates() {
        val value = crag(""""routes":[{"id":"route-1","name":"Mapped route","topoImageRef":"File:Fixture_photo.jpg","topoPath":[{"x":0.682,"y":0.823,"dottedAfter":true},{"x":0.642,"y":0.453,"type":"B"},{"x":0.557,"y":0.177,"type":"A"}]}]""")
        value.put("photos", JSONArray().put(photo()))
        val parsed = DataJson.crag(value)
        assertEquals("Fixture photographer", parsed.photos.single().author)
        assertEquals("CC-BY-SA-4.0", parsed.photos.single().license)
        assertEquals(5000L, parsed.photos.single().bytes)
        assertEquals("Thumbnail resized by Wikimedia", parsed.photos.single().changes)
        val route = parsed.routes.single()
        assertEquals(TopoPoint(0.682, 0.823, null, true), route.topoPath.first())
        assertEquals("B", route.topoPath[1].type)
        assertEquals(0.177, route.topoPath.last().y, 0.0)
        assertEquals(commonsReferenceKey(parsed.photos.single().ref), commonsReferenceKey(route.topoImageRef!!))
    }

    @Test fun invalidTopoVertexRejectsTheWholeLineInsteadOfBridgingTheGap() {
        for (middle in listOf("{\"x\":2,\"y\":0.5}", "{\"x\":0.5,\"y\":-0.1}", "null", "{\"x\":0.5}", "{\"x\":0.5,\"y\":0.5,\"type\":\"X\"}", "{\"x\":0.5,\"y\":0.5,\"dottedAfter\":\"yes\"}")) {
            val path = JSONArray("[{\"x\":0,\"y\":1},$middle,{\"x\":1,\"y\":0}]")
            assertTrue(DataJson.topoPath(path).isEmpty())
        }
        assertTrue(DataJson.topoPath(JSONArray("[{\"x\":0,\"y\":1}]")).isEmpty())
    }

    @Test fun topoPhotoNeedsSafeLocalAssetAndAttribution() {
        for ((key, value) in listOf("assetPath" to "photos/../private/key.jpg", "assetPath" to "/photos/a.jpg", "assetPath" to "https://example.org/a.jpg", "author" to "", "licenseUrl" to "javascript:alert(1)", "width" to 0)) {
            val input = crag(""""routes":[]""").put("photos", JSONArray().put(photo().put(key, value)))
            assertTrue(DataJson.crag(input).photos.isEmpty())
        }
    }

    @Test fun photoAliasDoesNotDuplicateTheSameCommonsFile() {
        val input = crag(""""routes":[]""").put("photos", JSONArray().put(photo())).put("photo", photo().put("ref", "File:Fixture_photo.jpg"))
        assertEquals(1, DataJson.crag(input).photos.size)
    }

    @Test fun verifiedCreativeCommonsLegacyLicenceLinksUseHttps() {
        val input = crag(""""routes":[]""").put("photos", JSONArray().put(photo().put("license", "CC0-1.0").put("licenseUrl", "http://creativecommons.org/publicdomain/zero/1.0/deed.en")))
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/deed.en", DataJson.crag(input).photos.single().licenseUrl)
        input.put("photos", JSONArray().put(photo().put("licenseUrl", "http://creativecommons.org.attacker.example/licence")))
        assertTrue(DataJson.crag(input).photos.isEmpty())
    }

    @Test fun routeWithoutTopoDoesNotReceiveFabricatedCoordinates() {
        val route = DataJson.route(JSONObject("""{"id":"r1","name":"No topo","geometry":{"type":"LineString","coordinates":[[-2,53],[-2,53.001]]}}"""))
        assertEquals(2, route.geometry.size)
        assertTrue(route.topoPath.isEmpty())
        assertNull(route.topoImageRef)
    }

    @Test fun sourceNodeIdsSurviveRelationAndMapParsing() {
        val item = DataJson.crag(crag(""""approaches":[{"id":"one","nodeIds":[100,101]},{"id":"bad","nodeIds":[100,0]}]"""))
        assertEquals(listOf(100L, 101L), item.approaches.first().nodeIds)
        assertTrue(item.approaches[1].nodeIds.isEmpty())
        val paths = DataJson.paths("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"kind":"path","id":"way-1","nodeIds":[101,102]},"geometry":{"type":"LineString","coordinates":[[-2,53],[-2,53.001]]}}]}""")
        assertEquals(listOf(101L, 102L), paths.single().nodeIds)
    }
}
