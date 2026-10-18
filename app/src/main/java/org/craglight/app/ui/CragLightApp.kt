@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.craglight.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.craglight.app.AppState
import org.craglight.app.AppViewModel
import org.craglight.app.BuildConfig
import org.craglight.app.data.*
import org.craglight.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

internal val London: ZoneId = ZoneId.of("Europe/London")
internal fun openUrl(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in listOf("https", "geo")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
internal fun distance(value: Double?): String = value?.let { if (it < 1000) "${it.toInt()} m" else "%.1f km".format(it / 1000) } ?: "Not recorded"
internal fun gradeSummary(route: Route): String = route.grades.entries.joinToString(" · ") { (key, value) -> "${GradeSystem.fromKey(key)?.label ?: key} $value" }.ifBlank { "Grade not recorded" }

@Composable fun CragLightApp(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val ticks by vm.ticks.collectAsState()
    val contributions by vm.contributions.collectAsState()
    var page by rememberSaveable { mutableStateOf("Explore") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.crags.find { it.id == selectedId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exportContent by remember { mutableStateOf("") }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportContent.toByteArray()) } ?: error("Cannot open export") }.onSuccess { vm.message("CSV exported") }.onFailure { vm.message("Export failed: ${it.message}") } }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportContent.toByteArray()) } ?: error("Cannot open export") }.onSuccess { vm.message("Review queue exported") }.onFailure { vm.message("Export failed: ${it.message}") } }
    }
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; require(out.size() + count <= 100 * 1024 * 1024) { "File is too large" }; out.write(chunk, 0, count) }
                out.toByteArray()
            } ?: error("Cannot read file") } }.onSuccess { vm.import(it) }.onFailure { vm.message(it.message) }
        }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    BackHandler(selected != null) { selectedId = null }
    Scaffold(
        containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { if (selected != null) Text(selected.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Landscape, null, tint = Forest, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(8.dp))
                    Text("CragLight", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                    Spacer(Modifier.weight(1f)); Text("UK · OPEN GUIDE", style = MaterialTheme.typography.labelSmall, color = Forest)
                } },
                navigationIcon = { if (selected != null) IconButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to explore") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream))
        },
        bottomBar = { if (selected == null) NavigationBar(containerColor = Cream) {
            listOf("Explore" to Icons.Default.Explore, "Logbook" to Icons.Default.Book, "Offline" to Icons.Default.DownloadForOffline, "About" to Icons.Default.Info).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label, onClick = { page = label }, icon = { Icon(icon, label) }, label = { Text(label) })
            }
        } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Opening the UK catalogue…") }
                state.error != null -> Column(Modifier.padding(24.dp)) { Text(state.error!!); Button(onClick = { vm.initialize() }) { Text("Retry") } }
                selected != null -> CragDetail(selected, state, vm) { tick -> vm.saveTick(tick) }
                page == "Explore" -> Explore(state, vm, onSelect = { selectedId = it })
                page == "Logbook" -> Logbook(ticks, onSave = vm::saveTick, onDelete = vm::deleteTick, onExport = { exportContent = vm.csv(ticks); exportCsv.launch("craglight-logbook.csv") })
                page == "Offline" -> Offline(state, vm, onImport = { importPack.launch(arrayOf("application/json", "application/octet-stream")) })
                else -> About(state, contributions, onExport = { exportContent = vm.contributionJson(contributions); exportJson.launch("craglight-review-queue.json") }, onDelete = vm::deleteContribution)
            }
        }
    }
}

@Composable private fun Explore(state: AppState, vm: AppViewModel, onSelect: (String) -> Unit) {
    var filter by remember { mutableStateOf(Filtering.Filter()) }
    var sort by remember { mutableStateOf(Filtering.Sort.NAME) }
    var showFilters by remember { mutableStateOf(false) }
    var map by rememberSaveable { mutableStateOf(false) }
    var onlySun by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(Instant.now()) }
    var timeDialog by remember { mutableStateOf(false) }
    var locationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locate = {
        requestLocation(context, { point -> vm.chooseOrigin(point, "Your location") }, { vm.message(it) })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else vm.message("Location permission declined. You can choose parking or enter coordinates.")
    }
    val result = remember(state.crags, filter, sort, state.origin, onlySun, time) {
        Filtering.apply(state.crags, filter, sort, state.origin).filter { !onlySun || Sun.onFace(time, it.lat, it.lon, it.aspectDegrees) == true }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (!map) {
                Text("A good day\nstarts outside.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text("${state.crags.size} mapped climbing places. Yours to explore.", style = MaterialTheme.typography.bodyMedium, color = Forest.copy(alpha = .8f))
                Spacer(Modifier.height(14.dp))
            }
            OutlinedTextField(value = filter.query, onValueChange = { filter = filter.copy(query = it) }, placeholder = { Text("Search crags, routes or regions") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("search"), shape = RoundedCornerShape(16.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(map, { map = !map }, label = { Text(if (map) "List" else "Map") }, leadingIcon = { Icon(if (map) Icons.AutoMirrored.Filled.List else Icons.Default.Map, null, Modifier.size(18.dp)) })
                Spacer(Modifier.width(8.dp)); FilterChip(showFilters || filter != Filtering.Filter(query = filter.query), { showFilters = true }, label = { Text("Filters") }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) })
                Spacer(Modifier.weight(1f)); IconButton(onClick = { locationDialog = true }, modifier = Modifier.testTag("location-open")) { Icon(Icons.Default.MyLocation, "Choose starting location") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${result.size} places", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { timeDialog = true }) { Icon(Icons.Default.WbSunny, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (onlySun) "Sun filter on" else "Plan for sunlight") }
            }
            state.originLabel?.let { origin -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From $origin", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).testTag("location-status"))
                TextButton(onClick = { vm.chooseOrigin(null) }) { Text("Clear") }
            } }
            if (state.routing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking mapped walking connections…", style = MaterialTheme.typography.bodySmall) }
        }
        if (map) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CragMap(result, state.mapJson, Modifier.fillMaxSize(), state.origin, onSelect, packFiles = state.packFiles)
                Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = Cream.copy(alpha = .95f), shape = RoundedCornerShape(12.dp)) {
                    Text(if (state.installed.isEmpty()) "Save a region in Offline for paths and parking" else "Tap a crag pin · paths from saved packs", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (result.isEmpty()) {
            EmptyState("No matching places", "Try a wider filter. Missing grades, walk-ins and family suitability do not count as confirmed matches.")
        } else LazyColumn(Modifier.weight(1f).testTag("crag-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(result, key = { it.id }) { crag -> CragCard(crag, state, onSelect) }
            item { Text("Open data has gaps. Check access on the ground and help improve your crag.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp)) }
        }
    }
    if (showFilters) FilterDialog(filter, sort, state, onDismiss = { showFilters = false }, onApply = { f, s -> filter = f; sort = s; showFilters = false })
    if (timeDialog) SunFilterDialog(time, onlySun, onDismiss = { timeDialog = false }) { t, active -> time = t; onlySun = active; timeDialog = false }
    if (locationDialog) LocationDialog(onDismiss = { locationDialog = false }, onCurrent = {
        locationDialog = false
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }, onPoint = { point -> locationDialog = false; vm.chooseOrigin(point, "Chosen coordinates") })
}

@Composable private fun CragCard(crag: Crag, state: AppState, onSelect: (String) -> Unit) {
    Card(onClick = { onSelect(crag.id) }, modifier = Modifier.fillMaxWidth().testTag("crag-${crag.id}"), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Sage, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Terrain, null, tint = Forest) } }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(crag.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(crag.region.replace('-', ' ') + (crag.rock?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(5.dp))
                Text(crag.styles.joinToString(" · ").ifBlank { "Style not recorded" }, color = Clay, style = MaterialTheme.typography.labelMedium)
                if (crag.walkInDistanceM != null) Text("${distance(crag.walkInDistanceM)} · ${crag.walkInTimeMin?.toInt() ?: "?"} min walk-in" + if (state.origin != null) " estimate" else "", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) { Text("${crag.routes.size}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Forest); Text("routes", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable private fun Offline(state: AppState, vm: AppViewModel, onImport: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Take the guide\nwith you.", style = MaterialTheme.typography.headlineLarge); Spacer(Modifier.height(8.dp)); Text("Save a region for maps, paths and parking without signal. Your logbook and sun calculations always work offline.") }
        item { OutlinedButton(onClick = onImport, enabled = state.busy == null) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import downloaded pack") } }
        items(state.regions, key = { it.id }) { region ->
            val installed = region.id in state.installed
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(region.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (installed) Icon(Icons.Default.CheckCircle, "Available offline", tint = Forest) }
                Text("${region.cragCount} places · ${region.routeCount} routes · %.1f MB".format((region.bytes + region.tilesBytes) / 1_000_000.0), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (state.busy == region.id) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Row {
                    Button(onClick = { vm.install(region) }, enabled = state.busy == null, modifier = Modifier.testTag("pack-${region.id}")) { Text(if (installed) "Verify / update" else "Save offline") }
                    if (installed) TextButton(onClick = { vm.remove(region) }, enabled = state.busy == null) { Text("Remove") }
                }
                Text(if (installed) "Saved on this device" else "Download verified data, with a bundled copy as an offline fallback.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item { Text("Snapshot: ${state.sourceDate.take(10)}. Weather needs a connection to refresh; saved forecasts keep their timestamp. Maps are open-data schematics, not a complete navigation map.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Logbook(ticks: List<TickRecord>, onSave: (TickRecord) -> Unit, onDelete: (String) -> Unit, onExport: () -> Unit) {
    var editing by remember { mutableStateOf<TickRecord?>(null) }
    var deleting by remember { mutableStateOf<TickRecord?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Days worth\nremembering.", style = MaterialTheme.typography.headlineLarge); Spacer(Modifier.height(8.dp)); Text("${ticks.size} ascents · stored only on this device") }
        item { Button(onClick = onExport, enabled = ticks.isNotEmpty()) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export CSV") } }
        if (ticks.isEmpty()) item { EmptyState("Your first ascent belongs here", "Open a crag and add a tick. You can record a route even when its open-data entry is missing.") }
        items(ticks, key = { it.id }) { tick -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tick.routeName, style = MaterialTheme.typography.titleMedium); Text(tick.cragName, style = MaterialTheme.typography.bodyMedium)
            Text("${tick.style} · ${tick.date} · ${tick.grade.ifBlank { "Ungraded" }}", color = Clay, style = MaterialTheme.typography.labelMedium)
            if (tick.notes.isNotBlank()) Text(tick.notes, Modifier.padding(top = 8.dp))
            Row { TextButton(onClick = { editing = tick }) { Text("Edit") }; TextButton(onClick = { deleting = tick }) { Text("Delete") } }
        } } }
    }
    editing?.let { record -> TickDialog(null, null, record, onDismiss = { editing = null }) { onSave(it); editing = null } }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this tick?") }, text = { Text(record.routeName) }, confirmButton = { TextButton(onClick = { onDelete(record.id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
}

@Composable private fun About(state: AppState, contributions: List<ContributionRecord>, onExport: () -> Unit, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    var showLicences by remember { mutableStateOf(false) }
    if (showLicences) LicenceDialog { showLicences = false }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Open rock.\nShared knowledge.", style = MaterialTheme.typography.headlineLarge); Text("CragLight ${BuildConfig.VERSION_NAME} · Free, UK only. No ads, accounts or analytics.", Modifier.padding(top = 8.dp)) }
        item { SectionCard("What this guide knows") {
            Text("${state.crags.size} mapped climbing places and ${state.crags.flatMap { it.routes }.distinctBy { it.id }.size} unique routes. Coverage varies greatly. A missing restriction does not mean access is permitted. A nearby car park is not a verified approach.")
            Text("Map geometry is not a face topo. Sun estimates use aspect and solar position, without terrain shadows or overhangs. Drying is a weather heuristic, not a statement that rock is safe or dry.", Modifier.padding(top = 8.dp))
        } }
        item { SectionCard("Sources & attribution") {
            Text("© OpenStreetMap contributors · ODbL 1.0. Extracted and normalised by CragLight. Source IDs, versions and dates are retained with every record.")
            TextButton(onClick = { openUrl(context, "https://www.openstreetmap.org/copyright") }) { Text("OpenStreetMap licence") }
            Text("OS Open Names place labels: Open Government Licence v3. Contains OS data © Crown Copyright and database rights 2026. Contains Royal Mail data © Royal Mail copyright and database right 2026. Contains National Statistics data © Crown copyright and database right 2026. Land outlines use Natural Earth (public domain), clipped to OpenStreetMap country boundaries.")
            TextButton(onClick = { openUrl(context, "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/") }) { Text("OS OpenData licence") }
            Text("Weather data by Open-Meteo.com · CC BY 4.0. Forecasts are cached; derived drying estimates are CragLight's own calculations.")
            TextButton(onClick = { openUrl(context, "https://open-meteo.com/en/licence") }) { Text("Weather licence") }
            Text("MapLibre Native · BSD 2-Clause. AndroidX / Compose / Room · Apache 2.0. Kotlin · Apache 2.0.")
            TextButton(onClick = { showLicences = true }) { Text("Offline licences & photo credits") }
            TextButton(onClick = { openUrl(context, "https://github.com/delegentai/craglight/blob/main/SOURCES.md") }) { Text("Full sources & reusable data") }
        } }
        item { SectionCard("Privacy") {
            Text("Ticks, notes and queued edits stay in this app's private storage. Android cloud backup is disabled. Export a backup before uninstalling; uninstalling deletes local data.")
            Text("Location is optional and used on-device to sort or estimate walks. It is not sent to our servers. Weather requests send the selected public crag coordinates and your IP address to Open-Meteo; pack downloads contact GitHub. Nothing refreshes in the background.", Modifier.padding(top = 8.dp))
            Text("Sharing a review proposal opens GitHub in your browser. You choose whether to submit; your GitHub profile and submitted text may be public. Do not include personal information. Exports go only to the destination you choose.", Modifier.padding(top = 8.dp))
            TextButton(onClick = { openUrl(context, "https://github.com/delegentai/craglight/blob/main/docs/PRIVACY.md") }) { Text("Privacy policy & contact") }
        } }
        item { SectionCard("Your review queue (${contributions.size})") {
            Text("Saved proposals have not been sent. Open a review form or export the queue. A maintainer must check the source and licence before anything enters a public pack.")
            OutlinedButton(onClick = onExport, enabled = contributions.isNotEmpty()) { Text("Export review queue") }
        } }
        items(contributions, key = { it.id }) { proposal -> Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp)) {
            Text(proposal.cragName, style = MaterialTheme.typography.titleMedium); Text(proposal.category + " · queued on device", color = Clay)
            Text(proposal.proposal); Text(proposal.license, style = MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick = {
                val body = "Crag: ${proposal.cragName}\nOSM ID: ${proposal.cragId}\nCategory: ${proposal.category}\n\n${proposal.proposal}\n\nSource: ${proposal.sourceUrl}\nLicence: ${proposal.license}\nProposal ID: ${proposal.id}\n\nI confirm I own this contribution or have permission under the stated open licence. Please moderate before publication."
                openUrl(context, BuildConfig.CONTRIBUTION_URL + "?title=" + Uri.encode("Crag correction: ${proposal.cragName}") + "&body=" + Uri.encode(body))
            }) { Text("Open review form") }; TextButton(onClick = { onDelete(proposal.id) }) { Text("Delete local") } }
        } } }
    }
}

@Composable internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable internal fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.Terrain, null, Modifier.size(40.dp), tint = Forest); Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text(body, style = MaterialTheme.typography.bodyMedium)
} }

@Suppress("MissingPermission") private fun requestLocation(context: Context, onPoint: (GeoPoint) -> Unit, onError: (String) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { onError("Location permission is needed, or choose coordinates manually."); return }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
        (it != LocationManager.GPS_PROVIDER || fineGranted) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
    }
    if (providers.isEmpty()) { onError("Location is switched off. Choose parking or enter coordinates."); return }
    val handler = Handler(Looper.getMainLooper())
    var completed = false
    lateinit var listener: LocationListener
    listener = object : LocationListener { override fun onLocationChanged(location: Location) { if (!completed) { completed = true; manager.removeUpdates(listener); onPoint(GeoPoint(location.latitude, location.longitude)) } } }
    val requested = providers.count { provider -> runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.isSuccess }
    if (requested == 0) { completed = true; manager.removeUpdates(listener); onError("Unable to get location. You can choose parking instead."); return }
    handler.postDelayed({ if (!completed) { completed = true; manager.removeUpdates(listener); onError("No location fix yet. Choose a mapped car park or enter coordinates.") } }, 15000)
}
