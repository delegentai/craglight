package org.craglight.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point
import java.io.File
import kotlin.math.atan
import kotlin.math.sinh
import org.maplibre.android.camera.CameraUpdateFactory

/** Runs against the bundled real catalogue. Run on the isolated AVD with wifi/data disabled. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val mapDescription = "Offline climbing map. Pinch to zoom. Tap grouped pins to expand, or a crag to open it."

    @Test
    fun offlineMapPackTickAndModerationSurviveRecreation() {
        awaitTag("search")
        val routeName = "CragLight instrumentation test ascent"
        val proposal = "Instrumentation test proposal. Do not publish: local moderation queue persistence check."
        lateinit var model: AppViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        runBlocking {
            model.repo.dao.ticks().first().filter { it.routeName == routeName }.forEach { model.repo.dao.deleteTick(it.id) }
            model.repo.dao.contributions().first().filter { it.proposal == proposal }.forEach { model.repo.dao.deleteContribution(it.id) }
        }
        val catalogue = JSONObject(context.assets.open("packs/all-crags.json").bufferedReader().use { it.readText() })
        val crags = catalogue.getJSONArray("crags")
        assertTrue("Real bundled catalogue must contain climbing places", crags.length() > 0)
        val candidates = (0 until crags.length()).map(crags::getJSONObject)
        val selected = candidates.firstOrNull { it.optString("region") == "wales" && it.optJSONArray("photos")?.length()?.let { count -> count > 0 } == true }
            ?: candidates.firstOrNull { it.optJSONArray("routes")?.length()?.let { count -> count > 0 } == true }
            ?: crags.getJSONObject(0)
        val cragId = selected.getString("id")
        val cragName = selected.getString("name")
        screenshot("01-explore")

        // Exercise the real native offline renderer and its cluster click handling.
        compose.onNodeWithText("Map", substring = false).performClick()
        compose.onNodeWithContentDescription(mapDescription).assertIsDisplayed()
        val native = nativeMap()
        var visibleCount = 0
        var landCount = 0
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                visibleCount = native.second.queryRenderedFeatures(
                    RectF(0f, 0f, native.first.width.toFloat(), native.first.height.toFloat()),
                    "crag-pins", "crag-clusters"
                ).size
                landCount = native.second.queryRenderedFeatures(
                    RectF(0f, 0f, native.first.width.toFloat(), native.first.height.toFloat()), "land"
                ).size
            }
            visibleCount > 0 && landCount > 0
        }
        compose.runOnIdle {
            val clusters = native.second.queryRenderedFeatures(RectF(0f, 0f, native.first.width.toFloat(), native.first.height.toFloat()), "crag-clusters")
            outputFile("map-clusters.json").writeText(org.maplibre.geojson.FeatureCollection.fromFeatures(clusters).toJson())
            outputFile("map-style.json").writeText(native.second.style!!.json)
            native.second.style!!.getImage("count-4")!!.let { bitmap ->
                outputFile("map-count-sprite.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        compose.waitUntil(15_000) {
            var labelled = 0
            compose.runOnIdle { labelled = native.second.queryRenderedFeatures(RectF(0f, 0f, native.first.width.toFloat(), native.first.height.toFloat()), "cluster-counts").size }
            labelled >= 3
        }
        screenshot("02-offline-uk-map")
        // Geometry queries alone can pass when a device GPU fails to draw its
        // sprite atlas. Verify actual white numeral pixels inside cluster fills.
        val clusterCenters = mutableListOf<android.graphics.PointF>()
        val mapLocation = IntArray(2)
        compose.runOnIdle {
            native.first.getLocationOnScreen(mapLocation)
            native.second.queryRenderedFeatures(RectF(50f, 100f, native.first.width - 100f, native.first.height - 60f), "crag-clusters")
                .mapNotNull { it.geometry() as? Point }.forEach { point ->
                    clusterCenters += native.second.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                }
        }
        val clusterScreen = instrumentation.uiAutomation.takeScreenshot()
        try {
            val drawnCounts = clusterCenters.count { center ->
                var white = 0
                for (dx in -18..18) for (dy in -18..18) {
                    val x = center.x.toInt() + mapLocation[0] + dx
                    val y = center.y.toInt() + mapLocation[1] + dy
                    if (x in 0 until clusterScreen.width && y in 0 until clusterScreen.height) {
                        val pixel = clusterScreen.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 245 && android.graphics.Color.green(pixel) > 245 && android.graphics.Color.blue(pixel) > 245) white++
                    }
                }
                white >= 8
            }
            assertTrue("At least three cluster numerals must actually draw pixels", drawnCounts >= 3)
        } finally { clusterScreen.recycle() }
        var clusterPixel: Offset? = null
        var originalZoom = 0.0
        compose.runOnIdle {
            originalZoom = native.second.cameraPosition.zoom
            val cluster = native.second.queryRenderedFeatures(
                RectF(45f, 45f, native.first.width - 70f, native.first.height - 45f), "crag-clusters"
            ).firstOrNull()
            (cluster?.geometry() as? Point)?.let { point ->
                val pixel = native.second.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                clusterPixel = Offset(pixel.x, pixel.y)
            }
        }
        assertNotNull("UK catalogue should form clustered crag markers", clusterPixel)
        compose.onNodeWithContentDescription(mapDescription).performTouchInput { click(clusterPixel!!) }
        compose.waitUntil(10_000) {
            var expanded = false
            compose.runOnIdle { expanded = native.second.cameraPosition.zoom > originalZoom + .1 }
            expanded
        }
        screenshot("03-expanded-cluster")
        compose.onNodeWithText("List", substring = false).performClick()

        // Search and open a source-backed crag, then add a private tick.
        compose.onNodeWithTag("search").performTextReplacement(cragName)
        compose.onNodeWithTag("crag-list").performScrollToNode(hasTestTag("crag-$cragId"))
        compose.onNodeWithTag("crag-$cragId").performClick()
        awaitTag("crag-detail")
        screenshot("04-crag-detail")
        if (selected.optJSONArray("photos")?.length()?.let { it > 0 } == true) {
            compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("topo-viewer"))
            compose.onNodeWithTag("topo-photo").assertIsDisplayed()
            screenshot("05-licensed-photo-topo")
            compose.onAllNodesWithText("The bundled photograph could not be opened.").assertCountEquals(0)
            val routes = selected.getJSONArray("routes")
            val overlay = (0 until routes.length()).map(routes::getJSONObject)
                .lastOrNull { it.optJSONArray("topoPath")?.length()?.let { count -> count >= 2 } == true }
            if (overlay != null) {
                compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("topo-route-${overlay.getString("id")}"))
                compose.onNodeWithTag("topo-route-${overlay.getString("id")}").performClick()
                compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("topo-selected-route"))
                compose.onNodeWithTag("topo-selected-route").assertTextContains(overlay.getString("name"), substring = true)
            }
        }
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("add-tick"))
        compose.onNodeWithTag("add-tick").performClick()
        awaitTag("tick-route-name")
        val note = "Private test note, with a comma and \"quotes\"."
        compose.onNodeWithTag("tick-route-name").performTextReplacement(routeName)
        compose.onNodeWithTag("tick-notes").performScrollTo().performTextReplacement(note)
        compose.onNodeWithTag("tick-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tick-save").fetchSemanticsNodes().isEmpty() }
        // The confirmation snackbar temporarily covers the bottom of the lazy list.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved to your logbook").fetchSemanticsNodes().isEmpty() }

        // A proposal is queued locally and never automatically submitted to an external service.
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("improve-crag"))
        compose.onNodeWithTag("improve-crag").assertIsDisplayed().performClick()
        awaitTag("contribution-proposal")
        compose.onNodeWithTag("contribution-proposal").performTextReplacement(proposal)
        compose.onNodeWithTag("contribution-license-consent").performScrollTo().performClick()
        compose.onNodeWithTag("contribution-save").performClick()
        compose.onNodeWithContentDescription("Back to explore").performClick()
        compose.onNodeWithText("Logbook", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(routeName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(routeName).assertIsDisplayed()
        compose.onNodeWithText("Export CSV").assertIsEnabled()
        compose.waitUntil(12_000) { model.state.value.message == null }
        screenshot("06-local-logbook")

        // Validate export quoting with the persisted Room record and the same exporter used by the UI.
        val persisted = runBlocking { model.repo.dao.ticks().first() }
        val tick = persisted.single { it.routeName == routeName }
        assertEquals(note, tick.notes)
        assertTrue(model.csv(listOf(tick)).contains("\"Private test note, with a comma and \"\"quotes\"\".\""))
        val proposals = runBlocking { model.repo.dao.contributions().first() }
        assertTrue(proposals.any { it.proposal == proposal && it.status == "queued_on_device" })

        // Save a verified bundled region with the network disabled, then recreate the Activity.
        compose.onNodeWithText("Offline", substring = false).performClick()
        val manifest = JSONObject(context.assets.open("packs/manifest.json").bufferedReader().use { it.readText() })
        val regions = manifest.getJSONArray("regions")
        val region = (0 until regions.length()).map(regions::getJSONObject)
            .firstOrNull { it.getString("id") == selected.getString("region") } ?: regions.getJSONObject(0)
        val regionId = region.getString("id")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("pack-$regionId"))
        val installedFile = File(context.filesDir, "packs/$regionId.json")
        val installedTiles = File(context.filesDir, "packs/$regionId.mbtiles")
        val previousModified = installedFile.lastModified()
        compose.onNodeWithTag("pack-$regionId").performClick()
        compose.waitUntil(60_000) { installedFile.exists() && installedFile.length() > 0L && installedFile.lastModified() > previousModified && installedTiles.exists() && installedTiles.length() > 0 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved on this device").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(12_000) { model.state.value.message == null }
        screenshot("07-region-saved-offline")
        compose.onNodeWithText("Explore", substring = false).performClick()
        compose.onNodeWithText("Map", substring = false).performClick()
        val regional = nativeMap()
        val tileCenter = SQLiteDatabase.openDatabase(installedTiles.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT zoom_level,tile_column,tile_row FROM tiles WHERE zoom_level=14 ORDER BY length(tile_data) DESC LIMIT 1", null).use { cursor ->
                assertTrue("Regional MBTiles contains detailed tiles", cursor.moveToFirst())
                val z = cursor.getInt(0)
                val n = (1 shl z).toDouble()
                val x = cursor.getInt(1)
                val y = (1 shl z) - 1 - cursor.getInt(2)
                LatLng(Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (y + .5) / n)))), (x + .5) / n * 360.0 - 180.0)
            }
        }
        compose.runOnIdle { regional.second.moveCamera(CameraUpdateFactory.newLatLngZoom(tileCenter, 14.0)) }
        compose.waitUntil(30_000) {
            var rendered = 0
            compose.runOnIdle {
                val layers = regional.second.style?.layers?.map { it.id }?.filter {
                    it.startsWith("regional-") && (it.endsWith("-paths") || it.endsWith("-routes") || it.endsWith("-parking") || it.endsWith("-cliffs"))
                }.orEmpty()
                if (layers.isNotEmpty()) rendered = regional.second.queryRenderedFeatures(
                    RectF(0f, 0f, regional.first.width.toFloat(), regional.first.height.toFloat()), *layers.toTypedArray()
                ).size
            }
            rendered > 0
        }
        screenshot("08-offline-vector-paths")
        // A real connected OSM path from Clean Wall's nearby parking proves the
        // streaming regional path reader and source-node routing work together.
        compose.onNodeWithText("List", substring = false).performClick()
        compose.onNodeWithTag("search").performTextReplacement("Clean Wall")
        compose.onNodeWithTag("crag-list").performScrollToNode(hasTestTag("crag-osm:relation:21389059"))
        compose.onNodeWithTag("crag-osm:relation:21389059").performClick()
        compose.onNodeWithText("Approach", substring = false).performClick()
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasText("Calculate from this parking"))
        compose.onAllNodesWithText("Calculate from this parking").onFirst().performClick()
        compose.waitUntil(45_000) { model.state.value.pathEstimates.containsKey("osm:relation:21389059") }
        val approach = checkNotNull(model.state.value.pathEstimates["osm:relation:21389059"])
        assertEquals(836.55, approach.distanceM, 3.0)
        assertTrue(approach.geometry.size >= 2)
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasText("Walk-in"))
        screenshot("09-mapped-walk-in")
        compose.onNodeWithContentDescription("Back to explore").performClick()
        compose.onNodeWithText("Offline", substring = false).performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(90_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Take the guide\nwith you.").assertIsDisplayed()
        assertTrue("Downloaded regional data persists across Activity recreation", installedFile.exists())
        compose.onNodeWithText("Logbook", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(routeName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(routeName).assertIsDisplayed()
        compose.onNodeWithText("About", substring = false).performClick()
        val queueTitle = "Your review queue (${proposals.size})"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(queueTitle))
        compose.onNodeWithText(queueTitle).assertIsDisplayed()
        screenshot("10-moderation-queue")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Offline licences & photo credits"))
        compose.onNodeWithText("Offline licences & photo credits").performClick()
        awaitTag("licence-list")
        compose.onNodeWithTag("licence-list").performScrollToNode(hasText("Apache License 2.0"))
        compose.onNodeWithText("Apache License 2.0").performClick()
        awaitTag("licence-text")
        compose.onNodeWithTag("licence-text").performScrollToIndex(2)
        compose.onAllNodesWithText("This bundled notice could not be opened.").assertCountEquals(0)
        screenshot("11-offline-licence")
        compose.onNodeWithText("Close", substring = false).performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(90_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun nativeMap(): Pair<MapView, MapLibreMap> {
        var view: MapView? = null
        var map: MapLibreMap? = null
        fun find(node: View): MapView? = when (node) {
            is MapView -> node
            is ViewGroup -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChildAt(it)) }
            else -> null
        }
        compose.runOnIdle {
            view = find(compose.activity.window.decorView)
            checkNotNull(view).getMapAsync { map = it }
        }
        compose.waitUntil(15_000) { map != null }
        return checkNotNull(view) to checkNotNull(map)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(1_000) // Native MapLibre renders independently of Compose's idle clock.
        capture(name)
    }

    @After fun captureFinalState() { runCatching { capture("99-final-state") } }

    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            outputFile("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun outputFile(name: String): File {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.getExternalFilesDir(null), "evidence")
        directory.mkdirs()
        return File(directory, name)
    }

    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}
