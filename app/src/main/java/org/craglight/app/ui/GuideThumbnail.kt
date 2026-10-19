package org.craglight.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.craglight.app.domain.Crag

@Composable internal fun GuideThumbnail(crag: Crag, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path = crag.photos.firstOrNull()?.assetPath
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (path == null) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
        }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Sage)) {
        bitmap?.let { Image(it, "${crag.name} photo topo", Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}
