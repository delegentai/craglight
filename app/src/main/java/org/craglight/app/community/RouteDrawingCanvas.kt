package org.craglight.app.community

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable fun RouteDrawingCanvas(photo: DraftPhoto, file: File, routes: List<DraftRoute>, selectedRouteId: String?,
                                   onStroke: (List<ImagePoint>) -> Unit, onUndo: () -> Unit, onReset: () -> Unit,
                                   modifier: Modifier=Modifier,readOnly: Boolean=false) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,photo.id) {
        value=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    var viewport by remember(photo.id) { mutableStateOf(ImageViewport(0.0,0.0,photo.width,photo.height)) }
    var drawing by remember(photo.id,readOnly) { mutableStateOf(!readOnly) }
    var pending by remember(photo.id,selectedRouteId) { mutableStateOf<List<ImagePoint>>(emptyList()) }
    var resetConfirm by remember { mutableStateOf(false) }
    val currentStroke by rememberUpdatedState(onStroke)
    val selected=routes.firstOrNull { it.id==selectedRouteId }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(if(readOnly) "Photo topo${selected?.let { " · ${it.name}" }.orEmpty()}" else if(selected==null) "Photo preview · add a route to draw its line" else "Draw: ${selected.name.ifBlank { "Unnamed route" }}",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!readOnly) FilterChip(drawing,{drawing=true},label={Text("Draw")},enabled=selected!=null,modifier=Modifier.testTag("community-draw-mode"))
            FilterChip(!drawing,{drawing=false},label={Text("Pan & zoom")},modifier=Modifier.testTag("community-pan-mode"))
            TextButton(onClick={viewport=viewport.copy(zoom=1.0,panX=0.0,panY=0.0)}) { Text("Fit photo") }
        }
        Canvas(Modifier.fillMaxWidth().height(340.dp).background(Color(0xFF18231D)).clipToBounds()
            .onSizeChanged { viewport=viewport.copy(width=it.width.toDouble(),height=it.height.toDouble()).clamped() }
            .testTag("community-route-canvas")
            .semantics { contentDescription="Original climbing photo. ${selected?.lines?.size ?: 0} strokes on selected route. Use Draw to trace a route; Pan and zoom to inspect." }
            .pointerInput(photo.id,drawing,selectedRouteId,readOnly) {
                if (!readOnly && drawing && selectedRouteId!=null) detectDragGestures(
                    onDragStart={ point -> pending=viewport.toImage(point.x.toDouble(),point.y.toDouble())?.let { listOf(it) }.orEmpty() },
                    onDragCancel={pending=emptyList()},
                    onDragEnd={if(pending.size>=2) currentStroke(pending); pending=emptyList()},
                ) { change,_ ->
                    change.consume()
                    val point=viewport.toImage(change.position.x.toDouble(),change.position.y.toDouble())
                    if(point!=null && pending.isNotEmpty() && pending.size<1500) {
                        val last=pending.last()
                        if(hypot((point.x-last.x)*photo.width,(point.y-last.y)*photo.height)>=2.0) pending=pending+point
                    }
                    // Leaving the photograph ends a stroke; re-entry never creates a line across blank space.
                    if(point==null && pending.isNotEmpty()) { if(pending.size>=2) currentStroke(pending); pending=emptyList() }
                } else detectTransformGestures { centroid,pan,factor,_ ->
                    val previous=viewport
                    val newZoom=(previous.zoom*factor).coerceIn(1.0,6.0)
                    val ratio=newZoom/previous.zoom
                    val cx=centroid.x-previous.width/2; val cy=centroid.y-previous.height/2
                    viewport=previous.copy(zoom=newZoom,panX=(previous.panX-cx)*ratio+cx+pan.x,panY=(previous.panY-cy)*ratio+cy+pan.y).clamped()
                }
            }) {
            val current=image ?: return@Canvas
            drawImage(current,dstOffset=IntOffset(viewport.left.roundToInt(),viewport.top.roundToInt()),dstSize=IntSize(viewport.drawnWidth.roundToInt().coerceAtLeast(1),viewport.drawnHeight.roundToInt().coerceAtLeast(1)))
            clipRect(viewport.left.toFloat(),viewport.top.toFloat(),(viewport.left+viewport.drawnWidth).toFloat(),(viewport.top+viewport.drawnHeight).toFloat()) {
                fun stroke(line: List<ImagePoint>,active: Boolean) {
                    line.zipWithNext().forEach { (a,b) ->
                        val p=viewport.toScreen(a); val q=viewport.toScreen(b)
                        val from=Offset(p.x.toFloat(),p.y.toFloat());val to=Offset(q.x.toFloat(),q.y.toFloat())
                        drawLine(Color.Black,from,to,6.dp.toPx(),StrokeCap.Round)
                        drawLine(if(active) Color(0xFFFFCE55) else Color.White.copy(alpha=.6f),from,to,3.dp.toPx(),StrokeCap.Round)
                    }
                }
                routes.filter { it.photoId==photo.id }.sortedBy { it.id==selectedRouteId }.forEach { route -> route.lines.forEach { stroke(it,route.id==selectedRouteId) } }
                stroke(pending,true)
            }
        }
        if(image==null) Text("Opening photo…",style=MaterialTheme.typography.bodySmall)
        Text(if(readOnly) "Pinch to zoom and drag to inspect the published route lines." else if(drawing) "Trace with one finger. Switch to Pan & zoom for closer work. Lines stay aligned with the photo." else "Pinch to zoom, drag to move. Switch to Draw before tracing.",style=MaterialTheme.typography.bodySmall)
        if(!readOnly) Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onUndo,enabled=selected?.lines?.isNotEmpty()==true,modifier=Modifier.testTag("community-undo-line")) { Text("Undo stroke") }
            TextButton(onClick={resetConfirm=true},enabled=selected?.lines?.isNotEmpty()==true) { Text("Clear route line") }
            Text("${selected?.lines?.size ?: 0} strokes",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(resetConfirm) AlertDialog(onDismissRequest={resetConfirm=false},title={Text("Clear this route's line?")},text={Text("The photograph and route details will stay in your draft.")},confirmButton={TextButton(onClick={onReset();resetConfirm=false}) {Text("Clear line")}},dismissButton={TextButton(onClick={resetConfirm=false}) {Text("Keep line")}})
}
