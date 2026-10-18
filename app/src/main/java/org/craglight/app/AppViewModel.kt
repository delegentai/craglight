package org.craglight.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.craglight.app.data.*
import org.craglight.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AppState(
    val loading: Boolean = true, val error: String? = null, val crags: List<Crag> = emptyList(),
    val regions: List<RegionPack> = emptyList(), val installed: Set<String> = emptySet(),
    val mapJson: String = "{\"type\":\"FeatureCollection\",\"features\":[]}", val sourceDate: String = "",
    val busy: String? = null, val message: String? = null, val origin: GeoPoint? = null, val originLabel: String? = null,
    val pathEstimates: Map<String, PathEstimate> = emptyMap(), val weather: Map<String, WeatherRecord> = emptyMap(), val packFiles: List<java.io.File> = emptyList(), val routing: Boolean = false
)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repo = AppRepository(application)
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()
    val ticks = repo.dao.ticks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val contributions = repo.dao.contributions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var routeJob: Job? = null
    init { initialize() }
    fun initialize() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            repo.initialize()
            _state.update { it.copy(loading = false, crags = repo.crags, regions = repo.regions, installed = repo.installed(), mapJson = repo.mapJson(), sourceDate = repo.sourceDate, packFiles = repo.tileFiles()) }
        }.onFailure { e -> _state.update { it.copy(loading = false, error = "Unable to open the catalogue: ${e.message}") } }
    }
    fun message(text: String?) { _state.update { it.copy(message = text) } }
    fun install(region: RegionPack) = task(region.id) { val result = repo.install(region); reloadPacks(); result }
    fun import(bytes: ByteArray) = task("import") { val result = repo.importPack(bytes); reloadPacks(); result }
    fun remove(region: RegionPack) = task(region.id) { repo.removePack(region.id); reloadPacks(); "Removed ${region.name}; your ticks are kept" }
    private suspend fun reloadPacks() {
        val map = repo.mapJson()
        _state.update { it.copy(installed = repo.installed(), mapJson = map, packFiles = repo.tileFiles()) }
        _state.value.origin?.let { chooseOrigin(it, _state.value.originLabel ?: "Chosen location") }
    }
    private fun task(label: String, operation: suspend () -> String) = viewModelScope.launch {
        _state.update { it.copy(busy = label) }
        runCatching { operation() }.onSuccess { message(it) }.onFailure { message(it.message ?: "Please try again") }
        _state.update { it.copy(busy = null) }
    }
    fun saveTick(tick: TickRecord) = viewModelScope.launch { repo.dao.saveTick(tick); message("Saved to your logbook") }
    fun deleteTick(id: String) = viewModelScope.launch { repo.dao.deleteTick(id); message("Tick deleted") }
    fun saveContribution(item: ContributionRecord) = viewModelScope.launch { repo.dao.saveContribution(item); message("Queued on this device. Open the review form or export it to send for moderation.") }
    fun deleteContribution(id: String) = viewModelScope.launch { repo.dao.deleteContribution(id) }
    fun loadWeather(crag: Crag) = viewModelScope.launch {
        repo.weather(crag.id)?.let { data -> _state.update { it.copy(weather = it.weather + (crag.id to data)) } }
    }
    fun refreshWeather(crag: Crag) = task("weather") {
        val data = repo.fetchWeather(crag)
        _state.update { it.copy(weather = it.weather + (crag.id to data)) }
        "Forecast saved; available offline. Refreshes are limited to once per 30 minutes."
    }
    fun chooseOrigin(point: GeoPoint?, label: String? = null) {
        routeJob?.cancel()
        _state.update { it.copy(origin = point, originLabel = label, pathEstimates = emptyMap(), crags = repo.crags, routing = point != null) }
        if (point == null) return
        routeJob = viewModelScope.launch {
            val paths = repo.pathsNear(point)
            val estimates = withContext(Dispatchers.Default) {
                repo.crags.filter { Geo.distanceMeters(point, GeoPoint(it.lat, it.lon)) <= 15000 }
                    .mapNotNull { crag -> ensureActive(); Geo.shortestPath(point, GeoPoint(crag.lat, crag.lon), paths + crag.approaches)?.let { crag.id to it } }.toMap()
            }
            val adapted = repo.crags.map { crag -> crag.copy(walkInDistanceM = estimates[crag.id]?.distanceM, walkInTimeMin = estimates[crag.id]?.timeMin) }
            _state.update { it.copy(crags = adapted, pathEstimates = estimates, routing = false) }
        }
    }
    fun csv(rows: List<TickRecord>): String = buildString {
        append(Csv.row(listOf("id", "crag", "route", "grade", "style", "date", "notes"))).append("\r\n")
        rows.forEach { append(Csv.row(listOf(it.id, it.cragName, it.routeName, it.grade, it.style, it.date, it.notes))).append("\r\n") }
    }
    fun contributionJson(rows: List<ContributionRecord>): String = JSONObject().put("schemaVersion", 1).put("exportedAt", Instant.now().toString()).put("contributions", JSONArray().also { array ->
        rows.forEach { c -> array.put(JSONObject().put("id", c.id).put("cragId", c.cragId).put("cragName", c.cragName).put("category", c.category).put("proposal", c.proposal).put("sourceUrl", c.sourceUrl).put("license", c.license).put("createdAt", c.createdAt).put("status", c.status)) }
    }).toString(2)
}
