package org.craglight.app.community

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private storage. Partially completed proposals are valid local drafts, without account or consent. */
class DraftStore(private val root: File) {
    init {
        root.mkdirs()
        listOf("drafts", "photos", "public-photos").forEach { File(root, it).mkdirs() }
    }
    fun photoFile(id: String): File { require(validId(id)); return File(root, "photos/$id.jpg") }
    fun publicPhotoFile(id: String): File {
        require(validId(id))
        return File(root, "public-photos/$id.jpg")
    }
    // Server-normalized media must never overwrite the author's local original bytes.
    fun fileForPhoto(photo: DraftPhoto): File =
        if (photo.originalWork) photoFile(photo.id) else publicPhotoFile(photo.id)
    private fun mediaKey(photo: DraftPhoto) = photo.id to photo.originalWork
    private fun draftFile(id: String): File { require(validId(id)); return File(root, "drafts/$id.json") }
    @Synchronized fun save(draft: CommunityDraft) {
        require(validId(draft.id))
        val file = draftFile(draft.id)
        val previousPhotos=if(file.exists()) runCatching {DraftJson.decode(JSONObject(file.readText())).photos}.getOrDefault(emptyList()) else emptyList()
        val temp = File(file.parentFile, "${draft.id}.tmp")
        temp.outputStream().use { it.write(DraftJson.encode(draft).toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // Only prune photos explicitly removed from this durable draft. A newly imported photo
        // or public preview not yet referenced by a saved draft must never be swept globally.
        val removed=previousPhotos.filter {old->draft.photos.none {mediaKey(it)==mediaKey(old)}}
        if(removed.isNotEmpty()) {
            val used=allReferencedPhotos() ?: return
            removed.filter {mediaKey(it) !in used}.forEach {fileForPhoto(it).delete()}
        }
    }
    @Synchronized fun load(): List<CommunityDraft> = File(root, "drafts").listFiles().orEmpty()
        .filter { it.extension == "json" && validId(it.nameWithoutExtension) }
        .mapNotNull { runCatching { DraftJson.decode(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }
    @Synchronized fun delete(draft: CommunityDraft) {
        require(validId(draft.id)); draftFile(draft.id).delete()
        // Photos can be shared by a copied draft; keep referenced assets.
        val used = allReferencedPhotos() ?: return
        draft.photos.filter { mediaKey(it) !in used }.forEach { fileForPhoto(it).delete() }
    }
    private fun allReferencedPhotos(): Set<Pair<String,Boolean>>? = runCatching {
        File(root,"drafts").listFiles().orEmpty().filter{it.extension=="json"}.flatMap {DraftJson.decode(JSONObject(it.readText())).photos}.map(::mediaKey).toSet()
    }.getOrNull() // A damaged unrelated record is a reason to retain media, never to delete it.
    companion object {
        fun validId(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    }
}

object DraftJson {
    fun encode(d: CommunityDraft): JSONObject = JSONObject().apply {
        put("schemaVersion", 1); put("id", d.id); put("existingCragId", d.existingCragId);put("basePublicationId",d.basePublicationId)
        put("cragName", d.cragName); put("latitude", d.latitude); put("longitude", d.longitude)
        put("parkingLatitude", d.parkingLatitude); put("parkingLongitude", d.parkingLongitude); put("parkingDescription", d.parkingDescription)
        put("parkingUnknownReason",d.parkingUnknownReason);put("access",d.access);put("accessCheckedAt",d.accessCheckedAt);put("kind",d.kind);put("caption",d.caption)
        put("approach", d.approach);put("descent",d.descent); put("credit", d.credit); put("originalWorkConsent", d.originalWorkConsent); put("consentedAt", d.consentedAt)
        put("createdAt", d.createdAt); put("updatedAt", d.updatedAt); put("submissionStatus", d.submissionStatus); put("submittedAt", d.submittedAt)
        put("serverMissing", JSONArray(d.serverMissing)); put("uploadError", d.uploadError)
        put("photos", JSONArray(d.photos.map { p -> JSONObject().put("id", p.id).put("fileName", p.fileName).put("width", p.width).put("height", p.height).put("sha256", p.sha256).put("originalWork",p.originalWork).put("sourceCredit",p.sourceCredit).put("sourceUrl",p.sourceUrl) }))
        put("routes", JSONArray(d.routes.map(::route)))
    }
    fun route(r: DraftRoute): JSONObject = JSONObject().put("id", r.id).put("name", r.name).put("gradeSystem", r.gradeSystem)
        .put("grade", r.grade).put("style", r.style).put("description", r.description).put("photoId", r.photoId)
        .put("lines", JSONArray(r.lines.map { line -> JSONArray(line.map { p -> JSONObject().put("x", p.x).put("y", p.y) }) }))
    private fun JSONObject.nullable(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    fun decode(j: JSONObject): CommunityDraft {
        require(j.getInt("schemaVersion") == 1 && DraftStore.validId(j.getString("id")))
        val photos=j.optJSONArray("photos")?.objects().orEmpty().map { p ->
            require(DraftStore.validId(p.getString("id")))
            DraftPhoto(p.getString("id"), "${p.getString("id")}.jpg", p.getInt("width"), p.getInt("height"), p.getString("sha256"),p.optBoolean("originalWork",true),p.nullable("sourceCredit"),p.nullable("sourceUrl"))
        }
        val routes=j.optJSONArray("routes")?.objects().orEmpty().map { r ->
            val lines=r.optJSONArray("lines") ?: JSONArray()
            DraftRoute(r.getString("id"), r.optString("name"), r.optString("gradeSystem", "ukTrad"), r.optString("grade"), r.optString("style", "trad"), r.optString("description"), r.nullable("photoId"),
                (0 until lines.length()).map { index -> lines.getJSONArray(index).objects().map { ImagePoint(it.getDouble("x"), it.getDouble("y")).also { point -> require(point.valid()) } } })
        }
        return CommunityDraft(id=j.getString("id"), existingCragId=j.nullable("existingCragId"),basePublicationId=j.nullable("basePublicationId"), cragName=j.optString("cragName"), latitude=j.optString("latitude"), longitude=j.optString("longitude"),
            parkingLatitude=j.optString("parkingLatitude"), parkingLongitude=j.optString("parkingLongitude"), parkingDescription=j.optString("parkingDescription"), approach=j.optString("approach"), credit=j.optString("credit"),
            descent=j.optString("descent"),
            parkingUnknownReason=j.optString("parkingUnknownReason"),access=j.optString("access"),accessCheckedAt=j.optString("accessCheckedAt"),kind=j.optString("kind","guide"),caption=j.optString("caption"),
            originalWorkConsent=j.optBoolean("originalWorkConsent"), consentedAt=j.nullable("consentedAt"), photos=photos, routes=routes,
            createdAt=j.getString("createdAt"), updatedAt=j.getString("updatedAt"), submissionStatus=j.nullable("submissionStatus"), submittedAt=j.nullable("submittedAt"),
            serverMissing=j.optJSONArray("serverMissing")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(), uploadError=j.nullable("uploadError"))
    }
}
