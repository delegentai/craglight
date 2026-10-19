package org.craglight.app.community

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class CommunityDraftTest {
    @Test fun `letterbox touches are rejected and normalized endpoints are exact`() {
        val view=ImageViewport(400.0,400.0,800,400)
        assertNull(view.toImage(200.0,50.0))
        assertEquals(ImagePoint(0.0,0.0),view.toImage(0.0,100.0))
        assertEquals(ImagePoint(1.0,1.0),view.toImage(400.0,300.0))
        assertEquals(ImagePoint(.5,.5),view.toImage(200.0,200.0))
    }
    @Test fun `zoom and pan invert the exact image transform without shifting route lines`() {
        val view=ImageViewport(1080.0,600.0,1600,2400,3.0,180.0,-200.0)
        for(point in listOf(ImagePoint(0.0,0.0),ImagePoint(.37,.81),ImagePoint(1.0,1.0))) {
            val screen=view.toScreen(point);val restored=view.toImage(screen.x,screen.y)!!
            assertEquals(point.x,restored.x,1e-10);assertEquals(point.y,restored.y,1e-10)
        }
        assertEquals(0.0,ImageViewport(400.0,400.0,800,400,1.0,300.0,300.0).clamped().panX,0.0)
    }
    @Test fun `draft survives restart with incomplete coordinates strokes and reused source credit`() {
        val root=Files.createTempDirectory("craglight-draft-test").toFile()
        try {
            val photoId=UUID.randomUUID().toString()
            val photo=DraftPhoto(photoId,"$photoId.jpg",640,400,"a".repeat(64),false,"Original Author","https://example.org/source")
            val route=DraftRoute(name="Original new line",photoId=photo.id,lines=listOf(listOf(ImagePoint(.1,.9),ImagePoint(.2,.3))))
            val draft=CommunityDraft(cragName="Partial draft",latitude="50.",longitude="",photos=listOf(photo),routes=listOf(route),approach="My own directions",descent="My own return",access="Observed open",accessCheckedAt="2026-10-19")
            DraftStore(root).save(draft)
            val loaded=DraftStore(root).load().single()
            assertEquals(draft,loaded)
            assertFalse(loaded.originalWorkConsent)
            assertTrue(loaded.submissionIssues().any {it.contains("coordinate")})
        } finally {root.deleteRecursively()}
    }
    @Test fun `partial photo only submission is valid with crag identity credit and consent`() {
        val photoId=UUID.randomUUID().toString()
        val draft=CommunityDraft(cragName="Original crag",photos=listOf(DraftPhoto(photoId,"$photoId.jpg",200,100,"a".repeat(64))),credit="Rock walker",originalWorkConsent=true,consentedAt=Instant.now().toString())
        assertTrue(draft.submissionIssues().isEmpty())
        assertFalse(draft.copy(originalWorkConsent=false).submissionIssues().isEmpty())
        assertFalse(draft.copy(latitude="NaN",longitude="-3").submissionIssues().isEmpty())
        assertFalse(draft.copy(latitude="42",longitude="-3").submissionIssues().isEmpty())
    }
    @Test fun `wire schema preserves existing photo reuse and excludes local secrets and consent timestamp`() {
        val id=UUID.randomUUID().toString()
        val draft=CommunityDraft(basePublicationId=UUID.randomUUID().toString(),cragName="Crag",credit="Alias",descent="Return",originalWorkConsent=true,consentedAt="2026-10-19T00:00:00Z")
        val wire=CommunityApi.wireDraft(draft,listOf(JSONObject().put("id",id).put("width",100).put("height",100).put("sha256","f".repeat(64)).put("originalWork",false)))
        assertFalse(wire.getJSONArray("photos").getJSONObject(0).getBoolean("originalWork"))
        assertEquals(draft.basePublicationId,wire.getString("basePublicationId"))
        assertEquals("Return",wire.getString("descent"))
        assertFalse(wire.has("consentedAt"));assertFalse(wire.toString().contains("fileName"));assertFalse(wire.toString().contains("token"))
    }
    @Test fun `path traversal identifiers cannot escape private store`() {
        val root=Files.createTempDirectory("craglight-path-test").toFile()
        try {val store=DraftStore(root);assertThrows(IllegalArgumentException::class.java) {store.photoFile("../../outside")};assertThrows(IllegalArgumentException::class.java) {store.save(CommunityDraft(id="../outside"))}}
        finally {root.deleteRecursively()}
    }
    @Test fun `offline zip contains draft photo and attribution without account credentials`() {
        val root=Files.createTempDirectory("craglight-export-test").toFile()
        try {
            val store=DraftStore(root);val id=UUID.randomUUID().toString();val bytes="test-original-image-bytes".toByteArray()
            store.photoFile(id).writeBytes(bytes)
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val draft=CommunityDraft(cragName="Export fixture",credit="Original author",photos=listOf(DraftPhoto(id,"$id.jpg",80,40,sha)))
            val output=java.io.ByteArrayOutputStream();DraftExport.writeZip(store,draft,output)
            val entries=mutableMapOf<String,ByteArray>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use {zip->while(true){val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes()}}
            assertEquals(setOf("draft.json","README.txt","ATTRIBUTION.txt","photos/$id.jpg"),entries.keys)
            assertArrayEquals(bytes,entries["photos/$id.jpg"])
            assertTrue(entries.getValue("ATTRIBUTION.txt").toString(Charsets.UTF_8).contains("not yet agreed"))
            assertEquals(draft,DraftJson.decode(JSONObject(entries.getValue("draft.json").toString(Charsets.UTF_8))))
        } finally {root.deleteRecursively()}
    }
    @Test fun `removing a photo prunes only explicitly removed media after last saved reference`() {
        val root=Files.createTempDirectory("craglight-prune-test").toFile()
        try {
            val store=DraftStore(root);val photoId=UUID.randomUUID().toString();val unsavedId=UUID.randomUUID().toString()
            store.photoFile(photoId).writeText("photo");store.photoFile(unsavedId).writeText("import-in-progress")
            val photo=DraftPhoto(photoId,"$photoId.jpg",1,1,"a".repeat(64));val first=CommunityDraft(photos=listOf(photo));val second=CommunityDraft(photos=listOf(photo))
            store.save(first);store.save(second);store.save(first.copy(photos=emptyList()))
            assertTrue(store.photoFile(photoId).exists())
            store.save(second.copy(photos=emptyList()))
            assertFalse(store.photoFile(photoId).exists());assertTrue(store.photoFile(unsavedId).exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun `public normalized photo never overwrites original and export selects correct bytes`() {
        val root = Files.createTempDirectory("craglight-media-isolation").toFile()
        try {
            val store = DraftStore(root)
            val id = UUID.randomUUID().toString()
            val originalBytes = "author-original-jpeg".toByteArray()
            val publicBytes = "server-normalized-jpeg".toByteArray()
            fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            val original = DraftPhoto(id, "$id.jpg", 80, 40, sha(originalBytes))
            val approved = original.copy(sha256 = sha(publicBytes), originalWork = false,
                sourceCredit = "Original photographer", sourceUrl = "https://example.org/photo")
            store.photoFile(id).writeBytes(originalBytes)
            store.publicPhotoFile(id).writeBytes(publicBytes)
            val originalDraft = CommunityDraft(photos = listOf(original))
            val reusedDraft = CommunityDraft(photos = listOf(approved))
            store.save(originalDraft)
            store.save(reusedDraft)
            assertNotEquals(store.fileForPhoto(original), store.fileForPhoto(approved))
            assertArrayEquals(originalBytes, store.fileForPhoto(original).readBytes())
            val output = java.io.ByteArrayOutputStream()
            DraftExport.writeZip(store, reusedDraft, output)
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "photos/$id.jpg") assertArrayEquals(publicBytes, zip.readBytes())
                }
            }
            store.delete(reusedDraft)
            assertFalse(store.publicPhotoFile(id).exists())
            assertArrayEquals(originalBytes, store.photoFile(id).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun `oversized route geometry is rejected before upload with actionable help`() {
        val points = (0 until 200).map { index -> ImagePoint(index / 199.0, 1.0 - index / 199.0) }
        val routes = (0 until 5).map {
            DraftRoute(name = "Own route $it", lines = List(12) { points })
        }
        val draft = CommunityDraft(cragName = "Own crag", routes = routes)
        val payload = CommunityApi.wireDraft(draft, emptyList())
        assertTrue(payload.toString().toByteArray().size > 512 * 1024)
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommunityApi.requireUploadSize(payload)
        }
        assertTrue(error.message!!.contains("split the routes"))
        CommunityApi.requireUploadSize(CommunityApi.wireDraft(draft.copy(routes = routes.take(1)), emptyList()))
    }
}
