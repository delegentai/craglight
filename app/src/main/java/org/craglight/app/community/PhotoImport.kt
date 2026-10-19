package org.craglight.app.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object PhotoImport {
    /** Normalizes the pixels before any route is drawn. Fresh JPEG encoding drops EXIF, GPS and other metadata. */
    suspend fun copyOriginal(context: Context, uri: Uri, store: DraftStore): DraftPhoto = withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString()
        val source=File(context.cacheDir, "community-$id.input")
        val target=store.photoFile(id)
        var decoded: Bitmap?=null; var rotated: Bitmap?=null; var clean: Bitmap?=null
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { output ->
                val buffer=ByteArray(8192); var total=0L
                while (true) { val count=input.read(buffer); if (count<0) break; total+=count; require(total<=40L*1024*1024) { "Choose a photograph smaller than 40 MB." }; output.write(buffer,0,count) }
            } } ?: error("This photo cannot be opened. Choose it again.")
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(source.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=120_000_000L) { "Choose a supported photograph up to 120 megapixels." }
            val orientation=runCatching { ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            var sample=1
            while (bounds.outWidth/sample>2400 || bounds.outHeight/sample>2400) sample*=2
            decoded=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("This image format could not be decoded.")
            val matrix=orientationMatrix(orientation)
            rotated=Bitmap.createBitmap(decoded!!,0,0,decoded!!.width,decoded!!.height,matrix,true)
            clean=Bitmap.createBitmap(rotated!!.width,rotated!!.height,Bitmap.Config.ARGB_8888)
            Canvas(clean!!).apply { drawColor(Color.WHITE); drawBitmap(rotated!!,0f,0f,null) }
            target.outputStream().use { require(clean!!.compress(Bitmap.CompressFormat.JPEG,90,it)) { "Could not save the photograph." }; it.fd.sync() }
            require(target.length()<=8L*1024*1024) { "The normalized photo is too large to upload." }
            val sha=target.inputStream().use { input -> val hash=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(8192); while (true) { val n=input.read(buffer); if(n<0)break; hash.update(buffer,0,n) }; hash.digest().joinToString("") { "%02x".format(it) } }
            DraftPhoto(id,target.name,clean!!.width,clean!!.height,sha)
        } catch (failure: Exception) { target.delete(); throw failure }
        finally { source.delete(); listOfNotNull(clean,rotated,decoded).distinct().forEach { if(!it.isRecycled) it.recycle() } }
    }
    internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
