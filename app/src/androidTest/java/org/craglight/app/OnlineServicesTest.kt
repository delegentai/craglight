package org.craglight.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.craglight.app.data.Weather
import org.junit.Assert.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Explicit opt-in real public-service checks; no user accounts or submissions. */
@RunWith(AndroidJUnit4::class)
class OnlineServicesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun publishedPackWeatherCacheAndOfflineGps() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowNetwork") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_COARSE_LOCATION")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_FINE_LOCATION")
        compose.waitUntil(90_000) { compose.onAllNodesWithTag("search").fetchSemanticsNodes().isNotEmpty() }
        lateinit var model: AppViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val crag = model.state.value.crags.first { it.region == "wales" && it.photos.isNotEmpty() }
        val region = model.repo.regions.first { it.id == "northern-ireland" }

        // This status is returned only when the JSON came from the public HTTPS release.
        val installed = runBlocking { model.repo.install(region) }
        assertTrue("Expected a genuine remote pack download: $installed", installed.startsWith("Downloaded and verified"))
        val packFile = File(context.filesDir, "packs/${region.id}.json")
        val tilesFile = File(context.filesDir, "packs/${region.id}.mbtiles")
        fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(region.sha256, hash(packFile))
        assertEquals(region.tilesSha256, hash(tilesFile))

        compose.onNodeWithTag("search").performTextReplacement(crag.name)
        compose.onNodeWithTag("crag-list").performScrollToNode(hasTestTag("crag-${crag.id}"))
        compose.onNodeWithTag("crag-${crag.id}").performClick()
        compose.onNodeWithText("Conditions", substring = false).performClick()
        compose.onNodeWithTag("crag-detail").performScrollToNode(hasText("Refresh weather"))
        compose.onNodeWithText("Refresh weather", substring = false).performClick()
        compose.waitUntil(60_000) { model.state.value.weather.containsKey(crag.id) }
        compose.waitUntil(10_000) { model.state.value.busy == null }
        val weather = checkNotNull(model.state.value.weather[crag.id])
        assertNotNull("Live weather covers the current time", Weather.at(weather, Instant.now()))
        screenshot("10-live-weather")

        val oldWifi = shell("settings get global wifi_on").trim()
        val oldData = shell("settings get global mobile_data").trim()
        try {
            shell("svc wifi disable"); shell("svc data disable")
            assertEquals("0", shell("settings get global wifi_on").trim())
            assertEquals("0", shell("settings get global mobile_data").trim())
            compose.activityRule.scenario.recreate()
            compose.waitUntil(90_000) { compose.onAllNodesWithTag("crag-detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("crag-detail").performScrollToIndex(0)
            compose.onNodeWithText("Conditions", substring = false).performClick()
            compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppViewModel::class.java] }
            compose.waitUntil(15_000) { model.state.value.weather.containsKey(crag.id) }
            assertEquals(weather.fetchedAt, model.state.value.weather[crag.id]?.fetchedAt)
            compose.onNodeWithTag("crag-detail").performScrollToNode(hasText("Refresh weather"))
            compose.onNodeWithText("Refresh weather", substring = false).performClick()
            compose.waitUntil(10_000) { model.state.value.busy == null }
            assertEquals("Offline refresh returns saved forecast within 30 minute cache window", weather.fetchedAt, model.state.value.weather[crag.id]?.fetchedAt)
            screenshot("11-weather-cached-offline")

            // Host script grants this app location permission and feeds the dedicated AVD GPS.
            compose.onNodeWithContentDescription("Back to explore").performClick()
            compose.onNodeWithTag("location-open").performClick()
            compose.onNodeWithTag("location-current").performClick()
            compose.waitUntil(40_000) { model.state.value.origin != null }
            val origin = checkNotNull(model.state.value.origin)
            assertEquals(53.066, origin.lat, .002)
            assertEquals(-4.075, origin.lon, .002)
            compose.onNodeWithTag("location-status").assertIsDisplayed()
            screenshot("12-offline-gps")
        } finally {
            if (oldWifi != "0") shell("svc wifi enable")
            if (oldData != "0") shell("svc data enable")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))).apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @After fun captureFinalState() { runCatching { screenshot("99-online-final-state") } }
}
