package org.craglight.app.community

import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Self-contained local backup, available without an account or network. */
object DraftExport {
    fun writeZip(store: DraftStore,draft: CommunityDraft,output: OutputStream) {
        val photos=draft.photos.map {photo->
            val file=store.fileForPhoto(photo)
            require(file.isFile) {"A draft photo is missing. Reopen the draft and choose the photograph again."}
            val hash=file.inputStream().use {input->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
            require(hash==photo.sha256) {"A draft photograph changed. Reopen it before exporting."}
            photo to file
        }
        ZipOutputStream(output).use {zip->
            fun text(name: String,content: String) {zip.putNextEntry(ZipEntry(name));zip.write(content.toByteArray(Charsets.UTF_8));zip.closeEntry()}
            text("draft.json",DraftJson.encode(draft).toString(2))
            text("README.txt","CragLight offline contribution draft\n\nThis archive contains unfinished contributor work, not a reviewed climbing guide. It has not been published by exporting it. Image-relative lines use the normalized photo pixels, top-left (0,0) to bottom-right (1,1). Photos are in photos/. No account token or email is included.\n\nDraft: ${draft.id}\nCrag: ${draft.cragName}\nLocal consent recorded: ${draft.originalWorkConsent}\n")
            text("ATTRIBUTION.txt",buildString {
                append("CragLight contribution draft\nContributor: ${draft.credit.ifBlank{"Not recorded"}}\n")
                append(if(draft.originalWorkConsent) "Own authored contribution offered under CC BY-SA 4.0.\n" else "Draft author has not yet agreed to a publication licence. Do not redistribute their unfinished work.\n")
                append("Licence: https://creativecommons.org/licenses/by-sa/4.0/\n\n")
                draft.photos.forEach {p->append("Photo ${p.id}\nSHA256: ${p.sha256}\n");if(p.originalWork) append("Contributor's original photograph. Pixels normalized; metadata removed.\n") else append("${p.sourceCredit ?: "Original community contributor"}\nSource: ${p.sourceUrl.orEmpty()}\nOriginal photo reused unchanged; any added route annotations are separate authored contributions.\n");append('\n')}
            })
            photos.forEach {(photo,file)->zip.putNextEntry(ZipEntry("photos/${photo.id}.jpg"));file.inputStream().use {it.copyTo(zip)};zip.closeEntry()}
            zip.finish()
        }
    }
}
