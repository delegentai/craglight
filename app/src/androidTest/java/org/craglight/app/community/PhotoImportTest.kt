package org.craglight.app.community

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoImportTest {
    @Test fun rotatedPhotoIsNormalizedGpsRemovedAndOriginalUnchanged() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.cacheDir,"photo-import-test-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(folder,"original.jpg")
            val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {drawColor(Color.RED);drawRect(40f,0f,80f,40f,Paint().apply{color=Color.BLUE})}
            source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(51.5,-2.6);setAttribute(ExifInterface.TAG_ARTIST,"Private photographer metadata");saveAttributes()
            }
            val originalHash=hash(source)
            val store=DraftStore(File(folder,"drafts"))
            val photo=PhotoImport.copyOriginal(context,Uri.fromFile(source),store)
            assertEquals(40,photo.width);assertEquals(80,photo.height)
            assertEquals(originalHash,hash(source))
            val normalized=store.photoFile(photo.id)
            assertEquals(photo.sha256,hash(normalized))
            val exif=ExifInterface(normalized)
            assertNull(exif.latLong);assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0) in listOf(0,1))
            val pixels=BitmapFactory.decodeFile(normalized.path)
            assertEquals(40,pixels.width);assertEquals(80,pixels.height)
            assertTrue(Color.red(pixels.getPixel(20,15))>200);assertTrue(Color.blue(pixels.getPixel(20,65))>200)
            pixels.recycle()
        } finally {folder.deleteRecursively()}
    }
    @Test fun mirroredPhotoPixelsAreNormalizedBeforeCoordinatesAreDrawn() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.cacheDir,"photo-mirror-test-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(folder,"original.jpg");val bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {drawColor(Color.RED);drawRect(40f,0f,80f,40f,Paint().apply{color=Color.BLUE})}
            source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
            ExifInterface(source).apply {setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_FLIP_HORIZONTAL.toString());saveAttributes()}
            val store=DraftStore(File(folder,"drafts"));val photo=PhotoImport.copyOriginal(context,Uri.fromFile(source),store)
            val pixels=BitmapFactory.decodeFile(store.photoFile(photo.id).path)
            assertTrue(Color.blue(pixels.getPixel(15,20))>200);assertTrue(Color.red(pixels.getPixel(65,20))>200)
            pixels.recycle()
        } finally {folder.deleteRecursively()}
    }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
}
