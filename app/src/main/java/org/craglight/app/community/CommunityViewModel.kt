package org.craglight.app.community

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.craglight.app.domain.Crag
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class CommunityState(
    val loading: Boolean=true,val drafts: List<CommunityDraft> = emptyList(),val editing: CommunityDraft?=null,
    val busy: Boolean=false,val error: String?=null,val notice: String?=null,val session: CommunitySession?=null,
    val guides: List<JSONObject> = emptyList(),val gallery: List<JSONObject> = emptyList(),val publicLoaded: Boolean=false,
    val guidesNextOffset: Int? = null, val galleryNextOffset: Int? = null,
)
class CommunityViewModel(application: Application): AndroidViewModel(application) {
    val store=DraftStore(File(application.filesDir,"community"))
    private val mutable=MutableStateFlow(CommunityState())
    val state=mutable.asStateFlow()
    private var config=CommunityConfig()
    private val ready=CompletableDeferred<Unit>()
    private data class Save(val draft: CommunityDraft,val done: CompletableDeferred<Unit>?=null)
    private val writes=Channel<Save>(Channel.UNLIMITED)
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { mutable.update { it.copy(drafts=store.load(),loading=false) } }
            catch(e: Exception) { mutable.update { it.copy(loading=false,error="Saved drafts could not be opened: ${e.message}") } }
            finally { ready.complete(Unit) }
            for(write in writes) {
                try { store.save(write.draft);write.done?.complete(Unit) }
                catch(e: Exception) { write.done?.completeExceptionally(e);mutable.update {it.copy(error="Could not save the draft on this device. ${e.message}")} }
            }
        }
    }
    fun configure(value: CommunityConfig) {config=value}
    private fun keep(draft: CommunityDraft) {
        mutable.update { s -> s.copy(drafts=(s.drafts.filterNot {it.id==draft.id}+draft).sortedByDescending {it.updatedAt},editing=if(s.editing?.id==draft.id) draft else s.editing) }
        writes.trySend(Save(draft))
    }
    fun newDraft(crag: Crag?=null) {viewModelScope.launch {
        ready.await()
        val d=CommunityDraft(existingCragId=crag?.id,cragName=crag?.name.orEmpty(),latitude=crag?.lat?.toString().orEmpty(),longitude=crag?.lon?.toString().orEmpty(),credit=mutable.value.session?.profile?.alias.orEmpty())
        mutable.update {it.copy(editing=d,error=null,notice=null)};keep(d)
    }}
    fun open(draft: CommunityDraft) {mutable.update {it.copy(editing=draft,error=null,notice=null)}}
    fun change(transform: (CommunityDraft)->CommunityDraft) {
        val d=mutable.value.editing ?: return
        if(d.isSubmitted || mutable.value.busy) return
        keep(transform(d).copy(updatedAt=Instant.now().toString(),uploadError=null))
    }
    suspend fun flush() {
        val d=mutable.value.editing ?: return
        val done=CompletableDeferred<Unit>();writes.send(Save(d,done));done.await()
    }
    fun leaveEditor() {viewModelScope.launch {try {flush();mutable.update {it.copy(editing=null,error=null,notice=null)}}catch(_: Exception){}}}
    fun copyDraft(draft: CommunityDraft) {
        val now=Instant.now().toString()
        val copy=draft.copy(id=UUID.randomUUID().toString(),createdAt=now,updatedAt=now,submissionStatus=null,submittedAt=null,serverMissing=emptyList(),uploadError=null,originalWorkConsent=false,consentedAt=null)
        mutable.update {it.copy(editing=copy)};keep(copy)
    }
    fun deleteDraft(draft: CommunityDraft) {runTask {
        // Drain every previously queued save before deleting, so a late autosave cannot resurrect it.
        val done=CompletableDeferred<Unit>();writes.send(Save(draft,done));done.await()
        withContext(Dispatchers.IO) {store.delete(draft)}
        mutable.update {it.copy(drafts=it.drafts.filterNot {d->d.id==draft.id},editing=it.editing?.takeUnless {d->d.id==draft.id},notice="Local draft removed. Server submissions are managed separately.")}
    }}
    fun importPhoto(draftId: String,uri: Uri) {runTask {
        val photo=PhotoImport.copyOriginal(getApplication(),uri,store)
        val current=mutable.value.drafts.firstOrNull {it.id==draftId}
        if(current==null || current.isSubmitted) {withContext(Dispatchers.IO){store.photoFile(photo.id).delete()};return@runTask}
        keep(current.copy(photos=current.photos+photo,updatedAt=Instant.now().toString()))
        mutable.update {it.copy(notice="Photo saved on this device. Hidden location metadata has been removed.")}
    }}
    fun signIn(activity: Activity,alias: String,usePhoto: Boolean) {runTask {
        val session=CommunityApi(config).signIn(activity,alias,usePhoto)
        mutable.update {it.copy(session=session,notice="Signed in. New contributions are reviewed before publication.")}
        val draft=mutable.value.editing
        if(draft!=null && !draft.isSubmitted && draft.credit.isBlank()) keep(draft.copy(credit=alias))
    }}
    fun signOut(activity: Activity) {runTask {
        val session=mutable.value.session ?: return@runTask
        try {CommunityApi(config).logout(activity,session)} finally {mutable.update {it.copy(session=null)}}
        mutable.update {it.copy(notice="Signed out. Your local drafts are still here.")}
    }}
    fun updateProfile(alias: String,usePhoto: Boolean) {runTask {
        val session=mutable.value.session ?: error("Sign in first.")
        val profile=CommunityApi(config).updateProfile(session,alias,usePhoto)
        mutable.update {it.copy(session=session.copy(profile=profile),notice="Profile change received. Public names and photos may need review.")}
    }}
    fun refreshProfile() {runTask {val session=mutable.value.session ?: return@runTask;val profile=CommunityApi(config).ownProfile(session);mutable.update {it.copy(session=session.copy(profile=profile))}}}
    fun deleteAccount() {runTask {
        val session=mutable.value.session ?: error("Sign in first.")
        CommunityApi(config).deleteAccount(session)
        runCatching {androidx.credentials.CredentialManager.create(getApplication()).clearCredentialState(androidx.credentials.ClearCredentialStateRequest())}
        mutable.update {it.copy(session=null,notice="Account deleted. Your photos and guides depending on them were withdrawn. Routes and text reused in another reviewed guide may remain with anonymised credit. Downloaded copies cannot be recalled. Local drafts remain on this device.")}
    }}
    suspend fun accountExport(destination: java.io.OutputStream) {
        val session=mutable.value.session ?: error("Sign in first.")
        CommunityApi(config).exportAccount(session, destination)
    }
    fun submit() {runTask {
        val d=mutable.value.editing ?: return@runTask
        val session=mutable.value.session ?: error("Sign in before sending. Your draft is saved.")
        require(d.submissionIssues().isEmpty()) {d.submissionIssues().first()}
        flush()
        try {
            val response=CommunityApi(config).submit(session,d,store)
            val status=response.getString("status")
            require(status in listOf("pending","approved","accepted","rejected","changes_requested")) {"The service did not confirm a submission status. Your draft is saved."}
            val missing=response.optJSONArray("missing")?.let {a->(0 until a.length()).map(a::getString)}.orEmpty()
            keep(d.copy(submissionStatus=status,submittedAt=Instant.now().toString(),serverMissing=missing,uploadError=null))
            mutable.update {it.copy(notice=if(status=="pending") "Received for review. This is not published yet." else "Server status: ${status.replace('_',' ')}")}
        } catch(e: Exception) {keep(d.copy(uploadError="The send could not be confirmed. Refresh status or retry; the same draft ID prevents duplicate submissions."));throw e}
    }}
    fun refreshDraft(draft: CommunityDraft) {runTask {
        val session=mutable.value.session ?: error("Sign in first.")
        val response=CommunityApi(config).status(session,draft.id)
        val value=response.optJSONObject("draft") ?: response
        val status=response.optString("status").ifBlank {value.optString("status")}
        require(status.isNotBlank()) {"The service returned no draft status."}
        val missing=response.optJSONArray("missing") ?: value.optJSONArray("missing")
        keep(draft.copy(submissionStatus=status,submittedAt=if(status=="draft") null else draft.submittedAt ?: Instant.now().toString(),serverMissing=missing?.let{a->(0 until a.length()).map(a::getString)}.orEmpty(),uploadError=null))
    }}
    fun loadPublic() = loadPublicPage(reset = true)
    fun loadMorePublic() = loadPublicPage(reset = false)

    private fun loadPublicPage(reset: Boolean) { runTask {
        val before = mutable.value
        val api = CommunityApi(config)
        val guides = if (reset || before.guidesNextOffset != null)
            api.publicGuides(if (reset) 0 else before.guidesNextOffset!!) else null
        val gallery = if (reset || before.galleryNextOffset != null)
            api.publicGallery(if (reset) 0 else before.galleryNextOffset!!) else null
        fun rows(page: JSONObject?, key: String): List<JSONObject> {
            val array = page?.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map(array::getJSONObject)
        }
        fun next(page: JSONObject?): Int? = page?.takeUnless { it.isNull("nextOffset") }
            ?.optInt("nextOffset", -1)?.takeIf { it >= 0 }
        mutable.update { state -> state.copy(
            guides = ((if (reset) emptyList() else state.guides) + rows(guides, "crags"))
                .distinctBy { it.getString("id") },
            gallery = ((if (reset) emptyList() else state.gallery) + rows(gallery, "gallery"))
                .distinctBy { it.getString("id") },
            guidesNextOffset = next(guides),
            galleryNextOffset = next(gallery),
            publicLoaded = true,
        ) }
    } }
    fun reusePublicPhoto(item: JSONObject,photo: JSONObject) {runTask {
        val crag=item.optJSONObject("crag") ?: item
        val id=photo.getString("id")
        val attributions=item.optJSONArray("photoAttributions")
        val credit=attributions?.let {a->(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull {it.optString("photoId")==id}}
        val asset=DraftPhoto(id,"$id.jpg",photo.getInt("width"),photo.getInt("height"),photo.getString("sha256"),false,
            "Photo: ${credit?.optString("alias") ?: item.optJSONObject("contributor")?.optString("alias") ?: "Community contributor"} · CC BY-SA 4.0",credit?.optString("sourceUrl")?.takeIf{it.isNotBlank()}?.let{java.net.URI(config.baseUrl).resolve(it).toString()} ?: config.baseUrl.trimEnd('/').removeSuffix("/v1")+"/v1/photos/$id")
        CommunityApi(config).downloadPublicPhoto(asset,store)
        val draft=CommunityDraft(existingCragId=crag.optString("existingId").takeIf {it.isNotBlank() && it!="null"} ?: item.optString("id").takeIf {it.isNotBlank()},cragName=crag.optString("name"),
            basePublicationId=item.optString("id").takeIf {item.optString("kind")=="guide" && DraftStore.validId(it)},
            latitude=if(crag.has("lat") && !crag.isNull("lat")) crag.getDouble("lat").toString() else "",longitude=if(crag.has("lon") && !crag.isNull("lon")) crag.getDouble("lon").toString() else "",
            photos=listOf(asset),routes=listOf(DraftRoute(photoId=id)),credit=mutable.value.session?.profile?.alias.orEmpty())
        mutable.update {it.copy(editing=draft)};keep(draft)
    }}
    fun report(targetType: String,targetId: String,reason: String,details: String) {runTask {
        val session=mutable.value.session
        CommunityApi(config).report(session,targetType,targetId,reason,details)
        mutable.update {it.copy(notice="Report received for review.")}
    }}
    fun clearNotice() {mutable.update {it.copy(error=null,notice=null)}}
    fun showError(message: String) {mutable.update {it.copy(error=message)}}
    fun showNotice(message: String) {mutable.update {it.copy(notice=message,error=null)}}
    private fun runTask(block: suspend ()->Unit) {
        if(mutable.value.busy) return
        mutable.update {it.copy(busy=true,error=null,notice=null)}
        viewModelScope.launch {
            try {block()}
            catch(e: CancellationException) {throw e}
            catch(e: Exception) {mutable.update {it.copy(error=e.message ?: "That action could not be completed. Your drafts are still saved.",session=if(e is CommunityApiException && e.statusCode==401) null else it.session)}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
}
