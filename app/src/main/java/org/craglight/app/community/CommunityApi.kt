package org.craglight.app.community

import android.app.Activity
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class CommunityApiException(val statusCode: Int, message: String): Exception(message)

/** Real HTTP only. A missing deployment is an unavailable service, never a simulated successful upload. */
class CommunityApi(private val config: CommunityConfig) {
    private fun url(path: String): java.net.URL {
        require(config.configured) { "Community uploads are not configured in this build. Your drafts stay on this device." }
        val uri=URI(config.baseUrl)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "Community service must use a valid HTTPS address." }
        val base=config.baseUrl.trimEnd('/').removeSuffix("/v1")
        return URI("$base/v1$path").toURL()
    }
    private suspend fun request(method: String, path: String, token: String?=null, json: JSONObject?=null, photo: File?=null, photoId: String?=null): JSONObject = withContext(Dispatchers.IO) {
        val connection=url(path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method; connection.connectTimeout=15_000; connection.readTimeout=60_000; connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept","application/json")
            token?.let { connection.setRequestProperty("Authorization","Bearer $it") }
            if(photo!=null) {
                require(photo.length() in 1..8L*1024*1024) { "The photo must be at most 8 MB." }
                connection.doOutput=true; connection.setRequestProperty("Content-Type","image/jpeg"); connection.setRequestProperty("X-Photo-Id",photoId)
                connection.setFixedLengthStreamingMode(photo.length());connection.outputStream.use { out -> photo.inputStream().use { it.copyTo(out) } }
            } else if(json!=null) {
                val bytes=json.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code=connection.responseCode
            val stream=if(code in 200..299) connection.inputStream else connection.errorStream
            val body=stream?.use { input -> val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val n=input.read(buffer);if(n<0)break;require(output.size()+n<=4*1024*1024) {"Community response was too large."};output.write(buffer,0,n)}
                output.toString("UTF-8") }.orEmpty()
            if(code !in 200..299) throw CommunityApiException(code,when(code) {
                401 -> "Your sign-in expired. Sign in again; your draft is saved."
                403 -> "This account cannot perform that action. Your draft is saved."
                409 -> "This contribution already exists on the server. Refresh its status before trying again."
                413 -> "The upload is too large. Your draft is saved."
                422,400 -> "The service could not accept these details. Check the contribution and try again."
                429 -> "Too many requests. Please try later; your draft is saved."
                else -> "The community service could not complete the request (HTTP $code). Your draft is saved."
            })
            if(body.isBlank()) JSONObject() else if(body.trimStart().startsWith("[")) JSONObject().put("items",JSONArray(body)) else JSONObject(body)
        } finally { connection.disconnect() }
    }
    suspend fun signIn(activity: Activity, alias: String, usePhoto: Boolean): CommunitySession {
        require(alias.trim().length in 2..40) {"Choose a public name of 2–40 characters."}
        val challenge=request("GET","/auth/challenge")
        val option=GetSignInWithGoogleOption.Builder(config.googleWebClientId).setNonce(challenge.getString("nonce")).build()
        val result=try {
            CredentialManager.create(activity).getCredential(MutableContextWrapper(activity),GetCredentialRequest.Builder().addCredentialOption(option).build())
        } catch(_: NoCredentialException) {
            throw IllegalStateException("No Google account is ready for sign-in on this device. Check that a Google account and Google Play services are available, then try again. Your draft is saved.")
        } catch(_: GetCredentialCancellationException) {
            throw IllegalStateException("Sign-in cancelled. Your draft is still saved on this device.")
        }
        val credential=result.credential
        require(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {"Google sign-in did not return a supported credential."}
        val google=GoogleIdTokenCredential.createFrom(credential.data)
        val response=request("POST","/auth/google",json=JSONObject().put("idToken",google.idToken).put("challengeId",challenge.getString("challengeId"))
            .put("publicAlias",alias.trim()).put("useGooglePhoto",usePhoto).put("termsAccepted",true).put("licenceAccepted",true))
        return CommunitySession(response.getString("token"),response.getString("expiresAt"),profile(response.getJSONObject("profile")))
    }
    suspend fun logout(activity: Activity,session: CommunitySession) {
        try { request("POST","/auth/logout",session.token,JSONObject()) }
        finally { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
    }
    suspend fun updateProfile(session: CommunitySession,alias: String,usePhoto: Boolean): CommunityProfile {
        require(alias.trim().length in 2..40)
        val response=request("PATCH","/me",session.token,JSONObject().put("publicAlias",alias.trim()).put("removePhoto",!usePhoto))
        return profile(response.optJSONObject("profile") ?: response)
    }
    suspend fun exportAccount(session: CommunitySession, destination: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val connection = url("/me/export").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            val status = connection.responseCode
            if (status != 200) throw CommunityApiException(status, "Account export failed (HTTP $status). Sign in again if your session has expired.")
            // Account exports can contain many drafts. Stream to the chosen document rather
            // than parsing a potentially large account archive into memory.
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 100L * 1024 * 1024) { "Account export exceeded the 100 MB download limit." }
                    destination.write(buffer, 0, count)
                }
            }
            destination.flush()
        } finally { connection.disconnect() }
    }
    suspend fun deleteAccount(session: CommunitySession) {request("DELETE","/me",session.token,JSONObject().put("confirmation","DELETE"))}
    suspend fun report(session: CommunitySession?,targetType: String,targetId: String,reason: String,details: String) {
        request("POST","/reports",session?.token,JSONObject().put("targetType",targetType).put("targetId",targetId).put("reason",reason).put("details",details))
    }
    suspend fun status(session: CommunitySession,id: String): JSONObject {require(DraftStore.validId(id));return request("GET","/drafts/$id",session.token)}
    suspend fun publicGuides(offset: Int = 0): JSONObject {
        require(offset >= 0)
        return request("GET", "/crags?limit=3&offset=$offset")
    }
    suspend fun publicGallery(offset: Int = 0): JSONObject {
        require(offset >= 0)
        return request("GET", "/gallery?limit=3&offset=$offset")
    }
    suspend fun publicProfile(id: String): CommunityProfile {require(DraftStore.validId(id));return profile(request("GET","/profiles/$id"))}
    suspend fun ownProfile(session: CommunitySession): CommunityProfile = profile(request("GET","/me",session.token))
    suspend fun avatarBytes(servicePath: String): ByteArray = withContext(Dispatchers.IO) {
        require(servicePath.matches(Regex("/v1/photos/[0-9a-fA-F-]{36}"))) {"Unsupported profile photo location."}
        val connection=url(servicePath.removePrefix("/v1")).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=10_000;connection.readTimeout=20_000;connection.instanceFollowRedirects=false
            require(connection.responseCode==200) {"Profile photo unavailable."}
            connection.inputStream.use {input->val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=8*1024*1024);bytes.write(buffer,0,n)};bytes.toByteArray()}
        } finally {connection.disconnect()}
    }
    suspend fun downloadPublicPhoto(photo: DraftPhoto,store: DraftStore): File = withContext(Dispatchers.IO) {
        require(DraftStore.validId(photo.id) && !photo.originalWork)
        val destination=store.publicPhotoFile(photo.id)
        fun sha(file: File)=file.inputStream().use {input-> val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
        if(destination.exists() && sha(destination)==photo.sha256) return@withContext destination
        val connection=url("/photos/${photo.id}").openConnection() as HttpURLConnection
        val temp=File(destination.parentFile,"${photo.id}.download")
        try {
            connection.connectTimeout=15_000;connection.readTimeout=45_000;connection.instanceFollowRedirects=false
            require(connection.responseCode==200) {"This photo is no longer publicly available."}
            connection.inputStream.use {input->temp.outputStream().use {out->val buffer=ByteArray(8192);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=8L*1024*1024);out.write(buffer,0,n)}}}
            require(sha(temp)==photo.sha256) {"The public photo changed. Refresh the community gallery."}
            java.nio.file.Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            destination
        } finally {connection.disconnect();temp.delete()}
    }
    suspend fun submit(session: CommunitySession,draft: CommunityDraft,store: DraftStore): JSONObject {
        require(draft.submissionIssues().isEmpty()) {draft.submissionIssues().first()}
        // Check the complete encoded request before sending any photo bytes.
        requireUploadSize(wireDraft(draft, draft.photos.map(::photoMetadata)))
        // On a timeout after POST /submit, a retry first reconciles the durable server state.
        try {
            val old=status(session,draft.id)
            val value=old.optJSONObject("draft") ?: old
            if(value.optString("status") in listOf("pending","approved","accepted","rejected","changes_requested")) return value
        } catch(e: CommunityApiException) {if(e.statusCode!=404) throw e}
        val uploaded=draft.photos.map { p -> if(p.originalWork) request("POST","/photos",session.token,photo=store.photoFile(p.id),photoId=p.id).put("originalWork",true)
            else JSONObject().put("id",p.id).put("width",p.width).put("height",p.height).put("sha256",p.sha256).put("originalWork",false) }
        val payload = wireDraft(draft, uploaded)
        requireUploadSize(payload)
        request("PUT","/drafts/${draft.id}",session.token,payload)
        return request("POST","/drafts/${draft.id}/submit",session.token,JSONObject())
    }
    companion object {
        private const val MAX_DRAFT_BYTES = 512 * 1024
        private fun photoMetadata(photo: DraftPhoto): JSONObject = JSONObject()
            .put("id", photo.id).put("width", photo.width).put("height", photo.height)
            .put("sha256", photo.sha256).put("originalWork", photo.originalWork)

        fun requireUploadSize(payload: JSONObject) {
            require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES) {
                "This draft has too many drawn points or too much text to send at once (512 KB limit). " +
                    "Your draft is saved. Copy it and split the routes into smaller contributions, " +
                    "or simplify some lines before trying again."
            }
        }
        private fun profile(j: JSONObject)=CommunityProfile(j.getString("id"),j.getString("alias"),j.optString("photoUrl").takeUnless {it.isBlank() || it=="null"},j.optInt("acceptedContributions"))
        fun wireDraft(d: CommunityDraft,uploaded: List<JSONObject>): JSONObject = JSONObject().apply {
            put("id",d.id);put("crag",JSONObject().put("name",d.cragName.trim()).put("existingId",d.existingCragId).put("lat",d.latitude.toDoubleOrNull()).put("lon",d.longitude.toDoubleOrNull()))
            put("basePublicationId",d.basePublicationId)
            put("parking",if(d.parkingLatitude.isBlank() && d.parkingLongitude.isBlank() && d.parkingDescription.isBlank()) JSONObject.NULL else JSONObject().put("lat",d.parkingLatitude.toDoubleOrNull()).put("lon",d.parkingLongitude.toDoubleOrNull()).put("description",d.parkingDescription.trim()))
            put("kind",d.kind);put("caption",d.caption.trim());put("parkingUnknownReason",d.parkingUnknownReason.trim());put("access",d.access.trim());put("accessCheckedAt",d.accessCheckedAt.takeIf {it.isNotBlank()})
            put("approach",d.approach.trim());put("descent",d.descent.trim());put("contributorAlias",d.credit.trim());put("licence",COMMUNITY_LICENCE);put("consent",d.originalWorkConsent)
            put("photos",JSONArray(uploaded.map { p -> JSONObject().put("id",p.getString("id")).put("width",p.getInt("width")).put("height",p.getInt("height")).put("sha256",p.getString("sha256")).put("originalWork",p.optBoolean("originalWork",true)) }))
            put("routes",JSONArray(d.routes.map(DraftJson::route)))
        }
    }
}
