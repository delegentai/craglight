package org.craglight.app.ui

import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.craglight.app.domain.*
import kotlin.math.*

/** Licensed bundled photo with source-authored OSM overlays. No network request is made. */
@Composable internal fun TopoViewer(crag: Crag, modifier: Modifier = Modifier, onRouteSelected: (Route) -> Unit = {}) {
    if (crag.photos.isEmpty()) return
    var photoIndex by remember(crag.id) { mutableIntStateOf(0) }
    val photo = crag.photos[photoIndex.coerceIn(crag.photos.indices)]
    val routes = remember(crag.routes, photo.ref) {
        crag.routes.filter { it.topoImageRef?.let(::commonsReferenceKey) == commonsReferenceKey(photo.ref) && it.topoPath.size >= 2 }
    }
    var selectedId by remember(crag.id, photo.ref) { mutableStateOf(routes.firstOrNull()?.id) }
    var zoom by remember(photo.ref) { mutableFloatStateOf(1f) }
    var offset by remember(photo.ref) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val hitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    val imageState by produceState(PhotoLoad(loading = true), photo.assetPath) {
        value = PhotoLoad(loading = true, assetPath = photo.assetPath)
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(photo.assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
                var sample = 1
                while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.assets.open(photo.assetPath).use { BitmapFactory.decodeStream(it, null, options) } ?: error("Image could not be decoded")
                PhotoLoad(bitmap = bitmap.asImageBitmap(), assetPath = photo.assetPath)
            }.getOrElse { PhotoLoad(error = "The bundled photograph could not be opened.", assetPath = photo.assetPath) }
        }
    }
    val active = routes.firstOrNull { it.id == selectedId }
    fun select(route: Route) { selectedId = route.id; onRouteSelected(route) }
    Column(modifier.fillMaxWidth().testTag("topo-viewer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Photo topo", style = MaterialTheme.typography.titleLarge)
        if (crag.photos.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { photoIndex-- }, enabled = photoIndex > 0) { Text("Previous photo") }
            Text("${photoIndex + 1} / ${crag.photos.size}", Modifier.weight(1f))
            TextButton(onClick = { photoIndex++ }, enabled = photoIndex < crag.photos.lastIndex) { Text("Next photo") }
        }
        Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C231E))
            .onSizeChanged { viewport = it }
            .testTag("topo-photo")
            .semantics { contentDescription = "Climbing photograph with ${routes.size} mapped route overlays. Select routes using the list below. Pinch to zoom." }
            .pointerInput(photo.ref) {
                detectTransformGestures { centroid, pan, factor, _ ->
                    val newZoom = (zoom * factor).coerceIn(1f, 6f)
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val proposed = (offset - (centroid - centre)) * (newZoom / zoom) + (centroid - centre) + pan
                    zoom = newZoom
                    offset = clampOffset(proposed, viewport, zoom)
                }
            }
            .pointerInput(photo.ref, routes) {
                detectTapGestures(onDoubleTap = {
                    zoom = if (zoom > 1f) 1f else 2.5f
                    offset = Offset.Zero
                }, onTap = { tap ->
                    val bitmap = imageState.takeIf { it.assetPath == photo.assetPath }?.bitmap
                    if (bitmap != null && viewport.width > 0 && viewport.height > 0) {
                        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                        val original = (tap - centre - offset) / zoom + centre
                        val fit = fitPhoto(viewport.width.toFloat(), viewport.height.toFloat(), bitmap.width, bitmap.height)
                        val nearest = routes.map { route -> route to route.topoPath.zipWithNext().minOf { (a, b) ->
                            pointSegmentDistance(original, fit.point(a), fit.point(b))
                        } }.minByOrNull { it.second }
                        if (nearest != null && nearest.second <= hitRadiusPx / zoom) select(nearest.first)
                    }
                })
            }) {
            val bitmap = imageState.takeIf { it.assetPath == photo.assetPath }?.bitmap
            if (bitmap != null) Canvas(Modifier.matchParentSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y)) {
                val fit = fitPhoto(size.width, size.height, bitmap.width, bitmap.height)
                drawImage(bitmap, dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()), dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()))
                val ordered = routes.sortedBy { if (it.id == selectedId) 1 else 0 }
                ordered.forEach { route ->
                    val selected = route.id == selectedId
                    val color = if (selected) Color(0xFFFFCA4A) else Color.White.copy(alpha = 0.68f)
                    route.topoPath.zipWithNext().forEach { (a, b) ->
                        val effect = if (a.dottedAfter) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / zoom, 4.dp.toPx() / zoom)) else null
                        drawLine(Color.Black.copy(alpha = 0.8f), fit.point(a), fit.point(b), strokeWidth = (if (selected) 6.dp else 4.dp).toPx() / zoom, cap = StrokeCap.Round, pathEffect = effect)
                        drawLine(color, fit.point(a), fit.point(b), strokeWidth = (if (selected) 3.5.dp else 2.dp).toPx() / zoom, cap = StrokeCap.Round, pathEffect = effect)
                    }
                    val start = fit.point(route.topoPath.first())
                    drawCircle(Color.Black.copy(alpha = 0.85f), 11.dp.toPx() / zoom, start)
                    val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        this.color = if (selected) android.graphics.Color.rgb(255, 202, 74) else android.graphics.Color.WHITE
                        textAlign = AndroidPaint.Align.CENTER
                        textSize = 12.dp.toPx() / zoom
                        isFakeBoldText = true
                    }
                    drawIntoCanvas { it.nativeCanvas.drawText((routes.indexOf(route) + 1).toString(), start.x, start.y - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint) }
                    if (selected) route.topoPath.filter { it.type != null }.forEach { point ->
                        val position = fit.point(point)
                        drawCircle(color, 4.dp.toPx() / zoom, position)
                        labelPaint.textSize = 10.dp.toPx() / zoom
                        labelPaint.textAlign = AndroidPaint.Align.LEFT
                        drawIntoCanvas { it.nativeCanvas.drawText(point.type!!, position.x + 6.dp.toPx() / zoom, position.y, labelPaint) }
                    }
                }
            }
            if (imageState.loading || imageState.assetPath != photo.assetPath) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            imageState.error?.let { Text(it, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pinch to zoom · drag to pan · tap a route", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Reset view") }
        }
        active?.let { route -> Text("Selected: ${route.name} · ${gradeSummary(route)}", color = Clay, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("topo-selected-route")) }
        if (routes.isEmpty()) Text("No source-mapped route lines are available for this photograph.", style = MaterialTheme.typography.bodySmall)
        routes.forEachIndexed { index, route ->
            TextButton(onClick = { select(route) }, modifier = Modifier.fillMaxWidth().testTag("topo-route-${route.id}"), colors = ButtonDefaults.textButtonColors(contentColor = if (route.id == selectedId) Clay else Forest)) {
                Text("${index + 1}. ${route.name} · ${gradeSummary(route)}", Modifier.weight(1f))
                if (route.id == selectedId) Text("Selected", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Mapped symbols: B bolt, A anchor, P piton, S sling, U unfinished. Dashed segments follow the source annotation. Lines and fixed equipment may be incomplete or out of date.", style = MaterialTheme.typography.bodySmall)
        Text("Photo: ${photo.author} · ${photo.license}", style = MaterialTheme.typography.bodySmall)
        Text("${photo.changes}. Route overlays are drawn from OpenStreetMap contributors (ODbL 1.0). Photo-and-overlay display retains the photograph's stated licence.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { openUrl(context, photo.sourceUrl) }) { Text("Photo source & author") }
            TextButton(onClick = { openUrl(context, photo.licenseUrl) }) { Text("Photo licence") }
        }
    }
}

private data class PhotoLoad(val bitmap: ImageBitmap? = null, val loading: Boolean = false, val error: String? = null, val assetPath: String = "")
private data class PhotoFit(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(point: TopoPoint): Offset = Offset(left + point.x.toFloat() * width, top + point.y.toFloat() * height)
}
private fun fitPhoto(width: Float, height: Float, photoWidth: Int, photoHeight: Int): PhotoFit {
    val ratio = min(width / photoWidth, height / photoHeight)
    val drawnWidth = photoWidth * ratio
    val drawnHeight = photoHeight * ratio
    return PhotoFit((width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight)
}
private fun clampOffset(offset: Offset, viewport: IntSize, zoom: Float): Offset {
    val x = viewport.width * (zoom - 1) / 2f
    val y = viewport.height * (zoom - 1) / 2f
    return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}
private fun pointSegmentDistance(point: Offset, a: Offset, b: Offset): Float {
    val vector = b - a
    val lengthSquared = vector.x * vector.x + vector.y * vector.y
    val t = if (lengthSquared == 0f) 0f else (((point.x - a.x) * vector.x + (point.y - a.y) * vector.y) / lengthSquared).coerceIn(0f, 1f)
    return (point - (a + vector * t)).getDistance()
}
