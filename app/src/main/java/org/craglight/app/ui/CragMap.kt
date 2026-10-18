package org.craglight.app.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.craglight.app.domain.Crag
import org.craglight.app.domain.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

private const val BASE_SOURCE = "offline-geography"
private const val CRAG_SOURCE = "filtered-crags"
private const val CRAG_LAYER = "crag-pins"
private const val CLUSTER_LAYER = "crag-clusters"
private const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"

/** An entirely local map: no remote styles, tiles, glyphs, sprites, or API key. */
@Composable
fun CragMap(
    crags: List<Crag>,
    mapGeoJson: String,
    modifier: Modifier = Modifier,
    focus: GeoPoint? = null,
    onCragClick: (String) -> Unit = {},
    packFiles: List<File> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onClick = rememberUpdatedState(onCragClick)
    val controller = remember { OfflineMapController() }
    val preparedGeography by produceState<PreparedGeography?>(null, mapGeoJson) {
        value = withContext(Dispatchers.Default) { prepareGeography(mapGeoJson.ifBlank { EMPTY_GEOJSON }) }
    }
    val tileTransport by produceState<Result<TileServer>?>(null, packFiles) {
        var opened: TileServer? = null
        try {
            value = if (packFiles.isEmpty()) null else withContext(Dispatchers.IO) {
                runCatching { TileServer(packFiles, File(context.filesDir, "packs")).also { opened = it } }
            }
            awaitCancellation()
        } finally {
            opened?.close()
        }
    }
    val tileServer = tileTransport?.getOrNull()
    val points = remember(crags) {
        FeatureCollection.fromFeatures(crags.filter {
            it.lat.isFinite() && it.lon.isFinite() && it.lat in -85.0..85.0 && it.lon in -180.0..180.0
        }.map { crag ->
            Feature.fromGeometry(Point.fromLngLat(crag.lon, crag.lat)).apply {
                addStringProperty("crag_id", crag.id)
                addStringProperty("name", crag.name)
            }
        })
    }
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        // All map resources are local. Radio connectivity must not pause the
        // loopback HTTP transport used to read installed MBTiles.
        MapLibre.setConnected(true)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .foregroundLoadColor(AndroidColor.rgb(174, 199, 203))).apply { onCreate(null) }
    }

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        fun synchronize() {
            val shouldStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (shouldStart && !started) { mapView.onStart(); started = true }
            if (shouldResume && !resumed) { mapView.onResume(); resumed = true }
            if (!shouldResume && resumed) { mapView.onPause(); resumed = false }
            if (!shouldStart && started) { mapView.onStop(); started = false }
        }
        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        val memoryCallback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }
        }
        lifecycle.addObserver(observer)
        context.applicationContext.registerComponentCallbacks(memoryCallback)
        synchronize()
        onDispose {
            controller.disposed = true
            lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallback)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
            controller.map = null
        }
    }

    Box(modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        if (!controller.disposed) {
                            controller.map = map
                            map.uiSettings.apply {
                                isLogoEnabled = false
                                isAttributionEnabled = false // Full source attribution is displayed below.
                                isCompassEnabled = true
                                isRotateGesturesEnabled = true
                                isTiltGesturesEnabled = false
                            }
                            map.setMinZoomPreference(3.5)
                            map.setMaxZoomPreference(19.0)
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(54.25, -3.0), 5.0))
                            map.setStyle(Style.Builder().fromJson("""
                                {"version":8,"name":"CragLight offline","sources":{},"layers":[
                                {"id":"sea","type":"background","paint":{"background-color":"#AEC7CB"}}]}
                            """.trimIndent())) { style ->
                                if (!controller.disposed) {
                                    controller.install(style)
                                    controller.refresh()
                                    if (focus == null) controller.showAll()
                                }
                            }
                            map.addOnMapClickListener { coordinate ->
                                val pixel = map.projection.toScreenLocation(coordinate)
                                val hitBox = RectF(pixel.x - 22, pixel.y - 22, pixel.x + 22, pixel.y + 22)
                                val cluster = map.queryRenderedFeatures(hitBox, CLUSTER_LAYER).firstOrNull()
                                if (cluster != null) {
                                    val center = cluster.geometry() as? Point
                                    val zoom = map.style?.getSourceAs<GeoJsonSource>(CRAG_SOURCE)
                                        ?.getClusterExpansionZoom(cluster)?.toDouble()
                                    if (center != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                        LatLng(center.latitude(), center.longitude()), zoom ?: (map.cameraPosition.zoom + 2)
                                    ))
                                    true
                                } else {
                                    val feature = map.queryRenderedFeatures(hitBox, CRAG_LAYER).firstOrNull()
                                    if (feature?.hasProperty("crag_id") == true) {
                                        onClick.value(feature.getStringProperty("crag_id"))
                                        true
                                    } else false
                                }
                            }
                        }
                    }
                }
            },
            update = {
                controller.points = points
                controller.geography = preparedGeography
                controller.tileServer = tileServer
                controller.focus = focus
                controller.refresh()
            },
            modifier = Modifier.matchParentSize().semantics {
                contentDescription = "Offline climbing map. Pinch to zoom. Tap grouped pins to expand, or a crag to open it."
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            FilledTonalIconButton(onClick = { controller.map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                modifier = Modifier.semantics { contentDescription = "Zoom map in" }) { Text("+", fontSize = 24.sp) }
            FilledTonalIconButton(onClick = { controller.map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                modifier = Modifier.semantics { contentDescription = "Zoom map out" }) { Text("−", fontSize = 24.sp) }
            FilledTonalIconButton(onClick = { controller.showAll() },
                modifier = Modifier.semantics { contentDescription = "Show all filtered crags" }) { Text("⌂", fontSize = 23.sp) }
        }
        Text("© OpenStreetMap contributors · OS OpenData",
            fontSize = 9.sp, color = Color(0xFF263B35),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color(0xE8F6F4E9), RoundedCornerShape(3.dp)).padding(4.dp))
        if (tileTransport?.isFailure == true) Text("Saved map tiles could not be opened. Re-save the region in Offline.",
            color = Color(0xFF8D302A), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.White).padding(8.dp))
    }
}

private class OfflineMapController {
    var map: MapLibreMap? = null
    var disposed = false
    var points: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
    var geography: PreparedGeography? = null
    var tileServer: TileServer? = null
    var focus: GeoPoint? = null
    private var lastPoints: FeatureCollection? = null
    private var lastGeography: PreparedGeography? = null
    private var lastFocus: GeoPoint? = null
    private var ready = false
    private var lastTileServer: TileServer? = null
    private val vectorLayers = mutableListOf<String>()
    private val vectorSources = mutableListOf<String>()

    fun install(style: Style) {
        style.addSource(GeoJsonSource(BASE_SOURCE, EMPTY_GEOJSON))
        style.addSource(GeoJsonSource(CRAG_SOURCE, points,
            GeoJsonOptions().withCluster(true).withClusterRadius(45).withClusterMaxZoom(12)))
        fun kind(value: String) = eq(get("kind"), literal(value))
        style.addLayer(FillLayer("land", BASE_SOURCE).withFilter(kind("land"))
            .withProperties(fillColor("#E8E6D9"), fillOutlineColor("#839F94")))
        style.addLayer(LineLayer("paths-casing", BASE_SOURCE).withFilter(kind("path"))
            .withProperties(lineColor("#FAF8EE"), lineWidth(4.5f)))
        style.addLayer(LineLayer("paths", BASE_SOURCE).withFilter(kind("path"))
            .withProperties(lineColor("#7A8970"), lineWidth(2f), lineDasharray(arrayOf(2f, 1.5f))))
        style.addLayer(LineLayer("route-lines", BASE_SOURCE).withFilter(kind("route"))
            .withProperties(lineColor("#BA573D"), lineWidth(3.5f), lineOpacity(0.9f)))
        style.addLayer(LineLayer("chosen-approach", BASE_SOURCE).withFilter(kind("approach"))
            .withProperties(lineColor("#2878A0"), lineWidth(5f), lineDasharray(arrayOf(2f, 1f))))
        style.addImage("parking-symbol", labelBitmap("P", 28f, AndroidColor.WHITE, AndroidColor.rgb(68, 104, 120)))
        style.addLayer(SymbolLayer("parking", BASE_SOURCE).withFilter(kind("parking"))
            .withProperties(iconImage("parking-symbol"), iconSize(0.65f), iconAllowOverlap(false)))
        style.getLayer("parking")?.minZoom = 10f
        style.addLayer(CircleLayer("places", BASE_SOURCE).withFilter(kind("place"))
            .withProperties(circleColor("#8D9282"), circleRadius(2.0f)))
        style.addLayer(SymbolLayer("place-labels", BASE_SOURCE).withFilter(kind("place"))
            .withProperties(iconImage(get("local_label")), iconSize(0.65f),
                iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 7f)), iconAllowOverlap(false)))
        style.addLayer(CircleLayer(CLUSTER_LAYER, CRAG_SOURCE).withFilter(has("point_count"))
            .withProperties(circleColor("#294D40"), circleRadius(18f), circleStrokeColor("#FAF8EE"), circleStrokeWidth(2.5f)))
        // Rasterized device-font labels are packaged in memory, so offline rendering never needs a glyph service.
        for (count in 2..99) style.addImage("count-$count", labelBitmap(count.toString(), 46f, AndroidColor.WHITE))
        style.addImage("count-99+", labelBitmap("99+", 42f, AndroidColor.WHITE))
        style.addLayer(SymbolLayer("cluster-counts", CRAG_SOURCE).withFilter(has("point_count"))
            .withProperties(iconImage(switchCase(gt(get("point_count"), literal(99)), literal("count-99+"),
                concat(literal("count-"), toString(get("point_count"))))),
                iconSize(0.9f), iconAllowOverlap(true), iconIgnorePlacement(true)))
        style.addLayer(CircleLayer(CRAG_LAYER, CRAG_SOURCE).withFilter(not(has("point_count")))
            .withProperties(circleColor("#CE6D42"), circleRadius(7.5f), circleStrokeColor("#FFFCF2"), circleStrokeWidth(2.5f)))
        ready = true
    }

    fun refresh() {
        if (!ready || disposed) return
        val activeMap = map ?: return
        val style = activeMap.style ?: return
        if (tileServer !== lastTileServer) {
            vectorLayers.forEach { style.removeLayer(it) }
            vectorSources.forEach { style.removeSource(it) }
            vectorLayers.clear()
            vectorSources.clear()
            tileServer?.tiles?.forEach { pack ->
                val tiles = TileSet("2.2.0", pack.url).apply {
                    minZoom = pack.minZoom.toFloat()
                    maxZoom = pack.maxZoom.toFloat()
                }
                style.addSource(VectorSource(pack.id, tiles))
                vectorSources += pack.id
                fun kind(value: String) = eq(get("kind"), literal(value))
                fun add(layer: org.maplibre.android.style.layers.Layer) {
                    style.addLayerBelow(layer, CLUSTER_LAYER)
                    vectorLayers += layer.id
                }
                add(LineLayer("${pack.id}-paths-casing", pack.id).withSourceLayer("craglight").withFilter(kind("path"))
                    .withProperties(lineColor("#FAF8EE"), lineWidth(4.5f)))
                add(LineLayer("${pack.id}-paths", pack.id).withSourceLayer("craglight").withFilter(kind("path"))
                    .withProperties(lineColor("#7A8970"), lineWidth(2f), lineDasharray(arrayOf(2f, 1.5f))))
                add(LineLayer("${pack.id}-cliffs", pack.id).withSourceLayer("craglight").withFilter(kind("cliff"))
                    .withProperties(lineColor("#83715E"), lineWidth(2.5f)))
                add(LineLayer("${pack.id}-routes", pack.id).withSourceLayer("craglight").withFilter(kind("route"))
                    .withProperties(lineColor("#BA573D"), lineWidth(3.5f)))
                add(SymbolLayer("${pack.id}-parking", pack.id).withSourceLayer("craglight").withFilter(kind("parking"))
                    .withProperties(iconImage("parking-symbol"), iconSize(0.65f), iconAllowOverlap(false)))
                add(CircleLayer("${pack.id}-places", pack.id).withSourceLayer("craglight").withFilter(kind("place"))
                    .withProperties(circleColor("#8D9282"), circleRadius(2f)))
            }
            lastTileServer = tileServer
        }
        val prepared = geography
        if (prepared != null && prepared !== lastGeography) {
            prepared.labels.forEach { (id, bitmap) -> style.addImage(id, bitmap) }
            style.getSourceAs<GeoJsonSource>(BASE_SOURCE)?.setGeoJson(prepared.collection)
            lastGeography = prepared
        }
        if (points !== lastPoints) {
            style.getSourceAs<GeoJsonSource>(CRAG_SOURCE)?.setGeoJson(points)
            lastPoints = points
        }
        val target = focus
        if (target != null && target != lastFocus && target.lat.isFinite() && target.lon.isFinite()) {
            activeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat, target.lon), 13.5))
        }
        lastFocus = target
    }

    fun showAll() {
        val activeMap = map ?: return
        val coordinates = points.features().orEmpty().mapNotNull { it.geometry() as? Point }
        if (coordinates.size == 1) {
            activeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinates[0].latitude(), coordinates[0].longitude()), 12.0))
        } else if (coordinates.isNotEmpty()) {
            val bounds = LatLngBounds.Builder().includes(coordinates.map { LatLng(it.latitude(), it.longitude()) }).build()
            activeMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }
}

private data class PreparedGeography(val collection: FeatureCollection, val labels: Map<String, Bitmap>)

private fun prepareGeography(json: String): PreparedGeography {
    val collection = runCatching { FeatureCollection.fromJson(json) }
        .getOrElse { FeatureCollection.fromFeatures(emptyList()) }
    val labels = mutableMapOf<String, Bitmap>()
    collection.features()?.asSequence()?.filter {
        it.hasProperty("kind") && it.getStringProperty("kind") == "place" && it.hasProperty("name")
    }?.take(300)?.forEachIndexed { index, feature ->
        val name = feature.getStringProperty("name")?.take(45).orEmpty()
        if (name.isNotBlank()) {
            val id = "place-label-$index"
            feature.addStringProperty("local_label", id)
            labels[id] = labelBitmap(name, 23f, AndroidColor.rgb(75, 83, 67), AndroidColor.argb(210, 248, 246, 235))
        }
    }
    return PreparedGeography(collection, labels)
}

private fun labelBitmap(text: String, textSize: Float, foreground: Int, background: Int = AndroidColor.TRANSPARENT): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        this.textSize = textSize
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val width = (paint.measureText(text) + 16).toInt().coerceAtLeast(38)
    val height = (textSize + 16).toInt()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        if (background != AndroidColor.TRANSPARENT) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        }
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, (width - paint.measureText(text)) / 2f, baseline, paint)
    }
}
