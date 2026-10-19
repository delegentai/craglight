package org.craglight.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.JsonReader
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.craglight.app.BuildConfig
import org.craglight.app.domain.Crag
import org.craglight.app.domain.Approach
import org.craglight.app.domain.GeoPoint
import org.craglight.app.domain.GuideQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

data class RegionPack(val id: String, val name: String, val cragCount: Int, val routeCount: Int, val file: String, val sha256: String, val bytes: Long,
    val tilesFile: String, val tilesSha256: String, val tilesBytes: Long)
class AppRepository(private val context: Context) {
    private val database = Room.databaseBuilder(context, LocalDatabase::class.java, "craglight-local.db").build()
    val dao = database.dao()
    private val packDir = File(context.filesDir, "packs").apply { mkdirs() }
    var regions: List<RegionPack> = emptyList(); private set
    var crags: List<Crag> = emptyList(); private set
    var sourceDate: String = ""; private set
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val manifest = JSONObject(context.assets.open("packs/manifest.json").bufferedReader().use { it.readText() })
        sourceDate = manifest.optString("generatedAt")
        regions = manifest.getJSONArray("regions").objects().map { o ->
            val f = o.getJSONObject("files"); val hash = o.getJSONObject("sha256"); val bytes = o.getJSONObject("bytes")
            RegionPack(o.getString("id"), o.getString("name"), o.optInt("cragCount"), o.optInt("routeCount"), f.getString("json"), hash.getString("json"), bytes.optLong("json"), f.getString("mbtiles"), hash.getString("mbtiles"), bytes.optLong("mbtiles"))
        }
        val catalogueText = context.assets.open("packs/all-crags.json").bufferedReader().use { it.readText() }
        val rows = JSONObject(catalogueText).getJSONArray("crags").objects().filter { GuideQuality.isPublishable(DataJson.crag(it)) }
        dao.replaceCatalogue(rows.map { CragRecord(it.getString("id"), it.toString()) })
        crags = dao.crags().map { DataJson.crag(JSONObject(it.json)) }.sortedBy { it.name.lowercase() }
        // Old installed packs must not put withdrawn/sparse catalogue pins back on the map.
        regions.forEach { region ->
            val json = File(packDir, "${region.id}.json")
            val tiles = File(packDir, "${region.id}.mbtiles")
            if ((json.exists() && !matchesHash(json, region.sha256)) || (tiles.exists() && !matchesHash(tiles, region.tilesSha256))) {
                json.delete(); tiles.delete()
            }
        }
    }
    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun matchesHash(file: File, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }.getOrDefault(false)
    fun installed(): Set<String> = regions.filter { File(packDir, "${it.id}.json").exists() && File(packDir, "${it.id}.mbtiles").exists() }.map { it.id }.toSet()
    fun tileFiles(): List<File> = installed().map { File(packDir, "$it.mbtiles") }
    suspend fun install(region: RegionPack): String = withContext(Dispatchers.IO) {
        var bytes: ByteArray
        var origin: String
        try {
            check(hasNetwork()) { "No network; use the bundled pack" }
            bytes = download(BuildConfig.PACK_BASE_URL + region.file, 100L * 1024 * 1024)
            origin = "Downloaded and verified"
        } catch (networkError: Exception) {
            val fallback = runCatching { context.assets.open("packs/${region.file}").use { it.readBytes() } }.getOrNull()
                ?: throw IllegalStateException("Download unavailable. Connect and retry, or import the regional JSON file.", networkError)
            bytes = fallback; origin = "Saved bundled pack for offline use"
        }
        prepareTiles(region)
        installBytes(region, bytes)
        commitTiles(region)
        "$origin: ${region.name}"
    }
    suspend fun importPack(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.size <= 100 * 1024 * 1024) { "Pack exceeds 100 MB" }
        val id = identify(bytes)
        val region = regions.find { it.id == id } ?: error("Unknown region; update CragLight to use this pack")
        prepareTiles(region)
        installBytes(region, bytes)
        commitTiles(region)
        "Imported and verified ${region.name}"
    }
    private fun installBytes(region: RegionPack, bytes: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash.equals(region.sha256, ignoreCase = true)) { "Pack checksum does not match this app's catalogue. No data was installed." }
        require(identify(bytes) == region.id) { "Unsupported pack" }
        val temporary = File(packDir, "${region.id}.tmp")
        temporary.writeBytes(bytes)
        val destination = File(packDir, "${region.id}.json")
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun identify(bytes: ByteArray): String {
        var schema = 0; var id = ""
        JsonReader(bytes.inputStream().reader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "schemaVersion" -> schema = reader.nextInt()
                "region" -> { reader.beginObject(); while (reader.hasNext()) if (reader.nextName() == "id") id = reader.nextString() else reader.skipValue(); reader.endObject() }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        require(schema == 1 && id.isNotBlank()) { "Unsupported pack format" }
        return id
    }
    private fun prepareTiles(region: RegionPack) {
        val temporary = File(packDir, "${region.id}.mbtiles.tmp")
        try {
            check(hasNetwork()) { "No network; use the bundled map" }
            downloadFile(BuildConfig.PACK_BASE_URL + region.tilesFile, temporary, 200L * 1024 * 1024)
        } catch (networkError: Exception) {
            runCatching { context.assets.open("packs/${region.tilesFile}").use { input -> temporary.outputStream().use(input::copyTo) } }
                .getOrElse { throw IllegalStateException("Map tiles unavailable. Download the complete pack while connected.", networkError) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        temporary.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(region.tilesSha256, true)) { "Map checksum mismatch; installation cancelled" }
    }
    private fun commitTiles(region: RegionPack) {
        java.nio.file.Files.move(File(packDir, "${region.id}.mbtiles.tmp").toPath(), File(packDir, "${region.id}.mbtiles").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    suspend fun removePack(id: String) = withContext(Dispatchers.IO) {
        require(regions.any { it.id == id })
        File(packDir, "$id.json").takeIf { it.exists() }?.let { check(it.delete()) { "Unable to remove pack" } }
        File(packDir, "$id.mbtiles").takeIf { it.exists() }?.let { check(it.delete()) { "Unable to remove map tiles" } }
    }
    suspend fun mapJson(): String = withContext(Dispatchers.IO) {
        val features = JSONArray()
        runCatching { context.assets.open("packs/basemap.geojson").bufferedReader().use { it.readText() } }.getOrNull()?.let {
            JSONObject(it).optJSONArray("features")?.objects()?.forEach(features::put)
        }
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }
    suspend fun pathsNear(point: GeoPoint): List<Approach> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<Approach>()
        for (id in installed()) JsonReader(File(packDir, "$id.json").reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "map") { reader.skipValue(); continue }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "features") { reader.skipValue(); continue }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val feature = readJsonValue(reader) as? JSONObject ?: continue
                        if (feature.optJSONObject("properties")?.optString("kind") != "path") continue
                        val geometry = DataJson.geometry(feature.opt("geometry"))
                        if (geometry.none { kotlin.math.abs(it.lat - point.lat) <= .18 && kotlin.math.abs(it.lon - point.lon) <= .35 }) continue
                        val wrapped = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(feature)).toString()
                        paths += DataJson.paths(wrapped)
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
            reader.endObject()
        }
        paths
    }
    private fun readJsonValue(reader: JsonReader): Any? = when (reader.peek()) {
        android.util.JsonToken.BEGIN_OBJECT -> JSONObject().also { o -> reader.beginObject(); while (reader.hasNext()) o.put(reader.nextName(), readJsonValue(reader)); reader.endObject() }
        android.util.JsonToken.BEGIN_ARRAY -> JSONArray().also { a -> reader.beginArray(); while (reader.hasNext()) a.put(readJsonValue(reader)); reader.endArray() }
        android.util.JsonToken.STRING -> reader.nextString()
        android.util.JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
        android.util.JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
        else -> error("Unexpected map JSON")
    }
    suspend fun weather(id: String): WeatherRecord? = withContext(Dispatchers.IO) { dao.weather(id) }
    suspend fun fetchWeather(crag: Crag): WeatherRecord = withContext(Dispatchers.IO) {
        val cached = dao.weather(crag.id)
        if (cached != null && Instant.now().epochSecond - Instant.parse(cached.fetchedAt).epochSecond < 1800) return@withContext cached
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${crag.lat}&longitude=${crag.lon}" +
            "&hourly=temperature_2m,relative_humidity_2m,precipitation,cloud_cover,wind_speed_10m&past_days=1&forecast_days=3&timezone=GMT&timeformat=unixtime"
        val data = download(url, 2 * 1024 * 1024).toString(Charsets.UTF_8)
        val json = JSONObject(data)
        require(json.has("hourly")) { json.optString("reason", "Forecast unavailable") }
        WeatherRecord(crag.id, Instant.now().toString(), data).also { dao.saveWeather(it) }
    }
    private fun download(url: String, limit: Long): ByteArray {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 12000; connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "CragLight/1.0 (https://github.com/delegentai/craglight)")
        try {
            check(connection.responseCode in 200..299) { "Server returned ${connection.responseCode}" }
            require(connection.contentLengthLong <= limit) { "Response too large" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size().toLong() + n <= limit) { "Response too large" }; output.write(buffer, 0, n) }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    private fun downloadFile(url: String, destination: File, limit: Long) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 12000; connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "CragLight/1.0 (https://github.com/delegentai/craglight)")
        try {
            check(connection.responseCode in 200..299) { "Server returned ${connection.responseCode}" }
            require(connection.contentLengthLong <= limit)
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= limit) { "Map file too large" }; output.write(buffer, 0, n) }
            } }
        } finally { connection.disconnect() }
    }
}
