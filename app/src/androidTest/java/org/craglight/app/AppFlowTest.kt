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
    fun completeGuidesMapTopoApproachAndPrivateLogbookWorkOffline() {
        awaitTag("search")
        val catalogue = org.craglight.app.data.DataJson.crags(context.assets.open("packs/all-crags.json").bufferedReader().use { it.readText() })
        assertEquals(10, catalogue.size)
        assertTrue(catalogue.all(org.craglight.app.domain.GuideQuality::isPublishable))
        compose.onNodeWithContentDescription(mapDescription).assertIsDisplayed()
        val native = nativeMap()
        compose.waitUntil(30_000) {
            var drawn = 0
            compose.runOnIdle { drawn = native.second.queryRenderedFeatures(RectF(0f,0f,native.first.width.toFloat(),native.first.height.toFloat()), "crag-pins", "crag-clusters").size }
            drawn > 0
        }
        var oldZoom = 0.0
        compose.runOnIdle { oldZoom = native.second.cameraPosition.zoom }
        compose.onNodeWithContentDescription(mapDescription).performTouchInput { pinch(Offset(centerX-25,centerY),Offset(centerX+25,centerY),Offset(centerX-100,centerY),Offset(centerX+100,centerY),500) }
        compose.waitUntil(10_000) { var zoom = 0.0; compose.runOnIdle { zoom = native.second.cameraPosition.zoom }; zoom > oldZoom }
        screenshot("v11-01-interactive-map")
        compose.onNodeWithText("List", substring=false).performClick()
        val crag = catalogue.first { it.name == "Bosigran" }
        compose.onNodeWithTag("search").performTextReplacement(crag.name)
        compose.onNodeWithTag("crag-${crag.id}").performClick()
        awaitTag("crag-detail")
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("topo-photo"))
        compose.onNodeWithTag("topo-photo").assertIsDisplayed()
        compose.onNodeWithTag("topo-photo").performTouchInput { doubleClick(center) }
        compose.onAllNodesWithText("The bundled photograph could not be opened.").assertCountEquals(0)
        screenshot("v11-02-real-topo")
        compose.onNodeWithTag("crag-detail").performScrollToIndex(1)
        compose.onNodeWithText("Approach",substring=false).performClick()
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("written-approach"))
        compose.onNodeWithTag("written-approach").assertTextEquals(crag.approachDescription!!)
        screenshot("v11-03-written-approach")
        compose.onNodeWithTag("crag-detail").performScrollToIndex(1)
        compose.onNodeWithText("Routes",substring=false).performClick()
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasTestTag("add-tick"))
        compose.onNodeWithTag("add-tick").performClick()
        awaitTag("tick-route-name")
        compose.onNodeWithTag("tick-route-name").performTextReplacement("Curated guide test ascent")
        compose.onNodeWithTag("tick-save").performClick()
        compose.onNodeWithContentDescription("Back to explore").performClick()
        compose.onNodeWithText("Offline",substring=false).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("pack-${crag.region}"))
        // The logbook snackbar can briefly cover the lower pack button.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved to your logbook").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("pack-${crag.region}").assertIsEnabled().performClick()
        screenshot("v11-04-pack-save-started")
        compose.waitUntil(60_000) { File(context.filesDir,"packs/${crag.region}.json").exists() && File(context.filesDir,"packs/${crag.region}.mbtiles").exists() }
        screenshot("v11-04-offline-pack")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(90_000) { compose.onAllNodesWithText("Logbook",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Logbook",substring=false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Curated guide test ascent").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Curated guide test ascent").onFirst().assertIsDisplayed()
    }

    @Test
    fun fingerDrawingIsSavedAndPartialDraftStaysPrivate() {
        awaitTag("search")
        val store = org.craglight.app.community.DraftStore(File(context.filesDir,"community"))
        val photoId = java.util.UUID.randomUUID().toString()
        val photoFile = store.photoFile(photoId)
        // Test fixture only: never submitted, no ownership or licence declaration made.
        val crag = org.craglight.app.data.DataJson.crags(context.assets.open("packs/all-crags.json").bufferedReader().use { it.readText() }).first()
        context.assets.open(crag.photos.first().assetPath).use { input -> photoFile.outputStream().use { input.copyTo(it) } }
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeFile(photoFile.path,bounds)
        val draft=org.craglight.app.community.CommunityDraft(cragName="Private editor test",photos=listOf(org.craglight.app.community.DraftPhoto(photoId,"$photoId.jpg",bounds.outWidth,bounds.outHeight,crag.photos.first().sha256!!)),routes=listOf(org.craglight.app.community.DraftRoute(name="Draft line",photoId=photoId)))
        store.save(draft)
        compose.onNodeWithText("Community",substring=false).performClick()
        awaitTag("community-draft-${draft.id}")
        compose.onNodeWithText("Open draft",substring=false).performClick()
        awaitTag("community-editor")
        compose.onNodeWithTag("community-editor").performScrollToNode(hasTestTag("community-route-canvas"))
        compose.onNodeWithTag("community-route-canvas").performTouchInput { swipe(Offset(centerX,centerY+40),Offset(centerX+20,centerY-40),700) }
        compose.waitUntil(10_000) { store.load().first { it.id==draft.id }.routes.first().lines.isNotEmpty() }
        val saved=store.load().first { it.id==draft.id }
        assertTrue(saved.routes.first().lines.flatten().all { it.valid() })
        assertFalse(saved.originalWorkConsent)
        assertNull(saved.submittedAt)
        screenshot("v11-05-finger-drawn-route")
        compose.onNodeWithTag("community-editor").performScrollToNode(hasTestTag("community-undo-line"))
        compose.onNodeWithTag("community-undo-line").performClick()
        compose.waitUntil(10_000) { store.load().first { it.id==draft.id }.routes.first().lines.isEmpty() }
        compose.onNodeWithTag("community-editor").performScrollToNode(hasTestTag("community-route-canvas"))
        compose.onNodeWithTag("community-route-canvas").performTouchInput { swipe(Offset(centerX,centerY+30),Offset(centerX,centerY-30),500) }
        compose.waitUntil(10_000) { store.load().first { it.id==draft.id }.routes.first().lines.isNotEmpty() }
        compose.activityRule.scenario.recreate()
        assertTrue(store.load().first { it.id==draft.id }.routes.first().lines.isNotEmpty())
        store.delete(store.load().first { it.id==draft.id })
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
