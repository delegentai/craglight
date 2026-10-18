package org.craglight.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.craglight.app.AppState
import org.craglight.app.AppViewModel
import org.craglight.app.data.*
import org.craglight.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import org.json.JSONArray

@Composable internal fun CragDetail(crag: Crag, state: AppState, vm: AppViewModel, onTick: (TickRecord) -> Unit) {
    var tab by remember(crag.id) { mutableStateOf("Routes") }
    var tickRoute by remember { mutableStateOf<Route?>(null) }
    var tickOpen by remember { mutableStateOf(false) }
    var contribution by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(Instant.now()) }
    var chooseTime by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val approachMap = remember(state.mapJson, state.pathEstimates[crag.id]) {
        val map = JSONObject(state.mapJson)
        state.pathEstimates[crag.id]?.let { estimate ->
            val coordinates = JSONArray().also { points -> estimate.geometry.forEach { points.put(JSONArray().put(it.lon).put(it.lat)) } }
            map.getJSONArray("features").put(JSONObject().put("type", "Feature").put("properties", JSONObject().put("kind", "approach").put("name", "Estimated walk-in"))
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates)))
        }
        map.toString()
    }
    LaunchedEffect(crag.id) { vm.loadWeather(crag) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("crag-detail")) {
        item {
            Surface(color = Forest, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(crag.region.replace('-', ' ').uppercase(), style = MaterialTheme.typography.labelMedium, color = Sage)
                    Text(crag.name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(crag.styles.joinToString(" · ").ifBlank { "Style not recorded" } + (crag.rock?.let { " · $it" } ?: ""), color = Sage)
                    Text("${crag.routes.size} open route entries", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Routes", "Approach", "Conditions", "Details").forEach { title ->
                    FilterChip(selected = tab == title, onClick = { tab = title }, label = { Text(title, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        when (tab) {
            "Routes" -> {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Route list", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = { tickRoute = null; tickOpen = true }, modifier = Modifier.testTag("add-tick")) { Text("Add a tick") }
                } }
                if (crag.photos.isNotEmpty()) item { TopoViewer(crag) }
                if (crag.routes.isEmpty()) item { SectionCard("Room for local knowledge") { Text("No openly licensed route list is mapped here yet. You can still record your own ascent and propose original observations for review.") } }
                items(crag.routes, key = { it.id }) { route -> SectionCard(route.name) {
                    Text(gradeSummary(route), color = Clay)
                    if (route.styles.isNotEmpty()) Text(route.styles.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(route.description ?: "Description not recorded in the open source.")
                    if (route.geometry.size >= 2) { RouteDiagram(route); Text("Geographic plan from mapped route coordinates; not a face topo.", style = MaterialTheme.typography.bodySmall) }
                    Text("${route.provenance.source} ${route.provenance.sourceId} · ${route.provenance.license}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { tickRoute = route; tickOpen = true }) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(5.dp)); Text("Log this route") }
                } }
                if (crag.photos.isEmpty()) item { SectionCard("Topo coverage") { Text("No licensed photographic face topo is available for this entry. Mapped route geometry, where present, is shown above. Contributions can identify an openly licensed image and its route lines.") } }
            }
            "Approach" -> {
                item { SectionCard("Access & restrictions") {
                    Text("Access: ${crag.access ?: "Not recorded"}")
                    Text("Conditional access: ${crag.accessConditional ?: "Not recorded"}")
                    Text("Restrictions / nesting bans: ${crag.restriction ?: "Not recorded"}")
                    crag.restrictionDescription?.let { Text(it) }
                    crag.openingHours?.let { Text("Opening / access hours: $it") }
                    Text("No recorded ban is not permission to climb. Observe signs and current local guidance; seasonal restrictions may change.", style = MaterialTheme.typography.bodySmall)
                } }
                item { CragMap(listOf(crag), approachMap, Modifier.fillMaxWidth().height(280.dp), GeoPoint(crag.lat, crag.lon), packFiles = state.packFiles) }
                item { SectionCard("Walk-in") {
                    Text("Start: ${state.originLabel ?: "No starting point selected"}")
                    if (state.routing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Distance: ${distance(crag.walkInDistanceM)} · Time: ${crag.walkInTimeMin?.let { "${it.toInt()} min" } ?: "Not recorded"}")
                    state.pathEstimates[crag.id]?.let { estimate -> Text("Mapped-path estimate, including ${distance(estimate.snapDistanceM)} of endpoint connections. Terrain and path condition are not modelled.", style = MaterialTheme.typography.bodySmall) }
                    if (crag.walkInDistanceM == null) Text("Save this region and choose a starting point. If a permitted, connected mapped path cannot be found, the walk-in stays unknown.", style = MaterialTheme.typography.bodySmall)
                    Text("Family suitability: ${when(crag.familyFriendly) { true -> "Recorded as suitable"; false -> "Recorded as unsuitable"; null -> "Not recorded" }}")
                } }
                if (crag.parking.isEmpty()) item { Text("No nearby parking is mapped in this pack. Choose coordinates from Explore to set a starting point.") }
                items(crag.parking, key = { it.id }) { parking -> SectionCard(parking.name.ifBlank { "Mapped parking" }) {
                    Text("${parking.association.replace('-', ' ')} · Access: ${parking.access ?: "not recorded"}")
                    parking.accessConditional?.let { Text("Conditional parking access: $it") }
                    Text("${distance(Geo.distanceMeters(parking.lat, parking.lon, crag.lat, crag.lon))} straight-line from crag. Verify suitability and access.", style = MaterialTheme.typography.bodySmall)
                    val available = parking.access?.lowercase() !in listOf("private", "no", "agricultural", "forestry") && parking.accessConditional.isNullOrBlank()
                    Button(onClick = { vm.chooseOrigin(GeoPoint(parking.lat, parking.lon), parking.name.ifBlank { "Chosen parking" }) }, enabled = available) { Text("Calculate from this parking") }
                    if (!available) Text("This parking has restricted or conditional access; it is not offered as a starting point.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            "Conditions" -> {
                item { SectionCard("Sun on the face") {
                    val position = Sun.position(time, crag.lat, crag.lon)
                    val face = Sun.onFace(position, crag.aspectDegrees)
                    Text(when(face) { true -> "Potential direct sun"; false -> if (position.altitudeDeg <= 0) "Sun below the horizon" else "Face turned away from the sun"; null -> "Aspect not recorded" }, style = MaterialTheme.typography.titleLarge)
                    Text(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm z").format(time.atZone(London)))
                    Text("Aspect: ${crag.aspectDegrees?.let { "${it.toInt()}°" } ?: "unknown"} · Solar elevation: ${position.altitudeDeg.toInt()}°")
                    TextButton(onClick = { chooseTime = true }) { Text("Change date & time") }
                    Text("A vertical-face estimate. Terrain shadows, trees and overhangs are not included. Cloud cover below can reduce actual sunshine.", style = MaterialTheme.typography.bodySmall)
                } }
                item { SectionCard("Weather & drying") {
                    val record = state.weather[crag.id]
                    val weather = record?.let { Weather.at(it, time) }
                    if (weather != null) {
                        Text("${weather.temperature.toInt()}°C · ${weather.rain} mm rain · ${weather.wind.toInt()} km/h wind", style = MaterialTheme.typography.titleMedium)
                        Text("Humidity ${weather.humidity.toInt()}% · Cloud ${weather.cloud.toInt()}%")
                        Text(weather.drying, color = Clay)
                        if (weather.cloud >= 80 && Sun.onFace(time, crag.lat, crag.lon, crag.aspectDegrees) == true) Text("Face points toward the sun, but heavy cloud may block direct sunshine.")
                        Text("Previous 6 h rain: ${weather.previousRain?.let { "%.1f mm".format(it) } ?: "unknown"}")
                    } else Text(if (record == null) "No saved forecast. Connect once to save weather for offline viewing." else "Saved forecast does not cover the chosen time.")
                    record?.let { Text("Fetched ${DateTimeFormatter.ofPattern("d MMM HH:mm z").format(Instant.parse(it.fetchedAt).atZone(London))}. ${Duration.between(Instant.parse(it.fetchedAt), Instant.now()).toHours().coerceAtLeast(0)} h old.", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { vm.refreshWeather(crag) }, enabled = state.busy != "weather") { Text(if (state.busy == "weather") "Fetching forecast…" else "Refresh weather") }
                    Text("Sends this public crag's coordinates to Open-Meteo. Cached for 30 minutes. Drying estimates cannot detect seepage or tell you whether climbing will damage the rock.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openUrl(context, "https://open-meteo.com/") }) { Text("Weather data by Open-Meteo.com") }
                } }
            }
            else -> {
                item { SectionCard("About this crag") {
                    Text(crag.description ?: "No openly licensed description is recorded. Help build one from your own observations.")
                    if (crag.outdoorStatus == "unspecified") Text("Outdoor status is unconfirmed in the source. Check this location before visiting.", color = Clay)
                    Text("Coordinates: %.5f, %.5f".format(crag.lat, crag.lon))
                } }
                item { SectionCard("Record provenance") {
                    Text("Source: ${crag.provenance.source} · ${crag.provenance.sourceId}")
                    Text("Version: ${crag.provenance.version ?: "not supplied"} · Updated: ${crag.provenance.timestamp ?: "not supplied"}")
                    Text("Licence: ${crag.provenance.license}")
                    crag.osmUrl?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("View OpenStreetMap record") } }
                    crag.ukcUrl?.let { url -> TextButton(onClick = { openUrl(context, url) }) { Text("Open UKC crag page") } }
                } }
            }
        }
        item { OutlinedButton(onClick = { contribution = true }, modifier = Modifier.fillMaxWidth().testTag("improve-crag")) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Improve this crag") } }
    }
    if (tickOpen) TickDialog(crag, tickRoute, onDismiss = { tickOpen = false }) { onTick(it); tickOpen = false }
    if (contribution) ContributionDialog(crag, onDismiss = { contribution = false }) { vm.saveContribution(it); contribution = false }
    if (chooseTime) SunFilterDialog(time, false, onDismiss = { chooseTime = false }) { chosen, _ -> time = chosen; chooseTime = false }
}

@Composable private fun RouteDiagram(route: Route) {
    val points = route.geometry
    Canvas(Modifier.fillMaxWidth().height(160.dp).background(Sage, RoundedCornerShape(12.dp))) {
        val minX = points.minOf { it.lon }; val maxX = points.maxOf { it.lon }; val minY = points.minOf { it.lat }; val maxY = points.maxOf { it.lat }
        val width = (maxX - minX).coerceAtLeast(0.00001); val height = (maxY - minY).coerceAtLeast(0.00001)
        val path = Path()
        points.forEachIndexed { index, point -> val x = 20 + ((point.lon - minX) / width * (size.width - 40)).toFloat(); val y = size.height - 20 - ((point.lat - minY) / height * (size.height - 40)).toFloat(); if (index == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, Clay, style = Stroke(width = 5f))
    }
}
