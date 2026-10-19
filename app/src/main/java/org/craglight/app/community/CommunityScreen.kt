@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.craglight.app.community

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.craglight.app.domain.Crag
import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

@Composable fun CommunityScreen(config: CommunityConfig=CommunityConfig(),initialCrag: Crag?=null,onClose: ()->Unit={},modifier: Modifier=Modifier) {
    val vm: CommunityViewModel=viewModel()
    val state by vm.state.collectAsState()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var tab by rememberSaveable {mutableStateOf("Drafts")}
    var seedOpened by rememberSaveable(initialCrag?.id) {mutableStateOf(false)}
    var signIn by remember {mutableStateOf(false)}
    var remove by remember {mutableStateOf<CommunityDraft?>(null)}
    var photoTarget by rememberSaveable {mutableStateOf<String?>(null)}
    var zipDraft by remember {mutableStateOf<CommunityDraft?>(null)}
    val exportZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->
        val snapshot=zipDraft;zipDraft=null
        if(uri!=null && snapshot!=null) scope.launch {
            runCatching {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri)?.use {DraftExport.writeZip(vm.store,snapshot,it)} ?: error("Cannot open the export destination.")}}
                .onSuccess {vm.showNotice("Draft archive exported with its photographs and attribution.")}
                .onFailure {vm.showError("Draft export could not be completed: ${it.message}")}
        }
    }
    val saveZip: (CommunityDraft)->Unit={snapshot->scope.launch {runCatching {vm.flush()}.onSuccess {zipDraft=snapshot;exportZip.launch("craglight-draft-${snapshot.id}.zip")}.onFailure {vm.showError("Save the draft before exporting: ${it.message}")}}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { vm.accountExport(it) }
                        ?: error("Could not open export destination.")
                }
            }.onSuccess {
                vm.showNotice("Account data exported. Photo download links require your active sign-in.")
            }.onFailure {
                vm.showError("Account export was not completed. The destination may contain an incomplete file. ${it.message}")
            }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri->
        val id=photoTarget;photoTarget=null;if(uri!=null && id!=null) vm.importPhoto(id,uri)
    }
    LaunchedEffect(config) {vm.configure(config)}
    LaunchedEffect(state.loading,initialCrag?.id) {if(!state.loading && initialCrag!=null && !seedOpened) {seedOpened=true;vm.newDraft(initialCrag)}}
    BackHandler(state.editing!=null) {if(!state.busy) vm.leaveEditor()}
    Column(modifier.fillMaxSize().testTag("community-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={if(state.editing!=null) vm.leaveEditor() else onClose()},enabled=!state.busy) {Text(if(state.editing!=null) "Drafts" else "Close")}
            Text(if(state.editing!=null) "Your contribution" else "Community",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            if(state.editing!=null) TextButton(onClick={scope.launch {runCatching {vm.flush()}.onSuccess {vm.leaveEditor()}.onFailure {vm.showError(it.message ?: "Could not save")}}},enabled=!state.busy,modifier=Modifier.testTag("community-save-draft")) {Text("Save & close")}
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {message-> Card(Modifier.fillMaxWidth().padding(12.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {Text(message,Modifier.padding(12.dp).testTag("community-error"))} }
        state.notice?.let {message-> Text(message,Modifier.padding(horizontal=16.dp,vertical=6.dp).testTag("community-notice"),style=MaterialTheme.typography.bodySmall) }
        val editing=state.editing
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            editing!=null -> ContributionEditor(editing,vm.store,state.busy,state.session!=null,config.configured,
                onChange=vm::change,onPhoto={photoTarget=editing.id;picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                onSubmit=vm::submit,onSignIn={signIn=true},onRefresh={vm.refreshDraft(editing)},onCopy={vm.copyDraft(editing)},onError=vm::showError,onExport={saveZip(editing)})
            else -> {
                Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Drafts","Community","Account").forEach {label-> FilterChip(tab==label,{tab=label;if(label=="Community" && config.configured && !state.publicLoaded) vm.loadPublic()},label={Text(label)}) }
                }
                when(tab) {
                    "Drafts" -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {Text("Start with a photo, a route or an approach. Save unfinished work and add the rest later.")}
                        item {Button(onClick={vm.newDraft()},modifier=Modifier.fillMaxWidth().testTag("community-new-draft")) {Text("New contribution")}}
                        if(state.drafts.isEmpty()) item {Text("Your drafts will appear here. No account is needed to save on this device.")}
                        items(state.drafts,key={it.id}) {draft-> Card(onClick={vm.open(draft)},modifier=Modifier.fillMaxWidth().testTag("community-draft-${draft.id}")) {
                            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(draft.cragName.ifBlank {"Untitled crag"},style=MaterialTheme.typography.titleMedium)
                                Text("${draft.photos.size} photos · ${draft.routes.size} routes · ${draft.submissionStatus?.replace('_',' ') ?: "Saved on this device"}")
                                Row {TextButton(onClick={vm.open(draft)}) {Text("Open draft")};TextButton(onClick={remove=draft}) {Text("Remove local draft")}}
                                TextButton(onClick={saveZip(draft)},enabled=!state.busy,modifier=Modifier.testTag("community-export-${draft.id}")) {Text("Export draft with photos")}
                            }
                        } }
                    }
                    "Community" -> CommunityBrowse(state,config,vm.store,onRefresh=vm::loadPublic,onReuse=vm::reusePublicPhoto,onReport=vm::report,onLoadMore=vm::loadMorePublic)
                    else -> CommunityAccount(state,config,onSignIn={signIn=true},onSignOut={context.activity()?.let(vm::signOut)},onUpdate=vm::updateProfile,onDelete=vm::deleteAccount,onRefresh=vm::refreshProfile,onExport={export.launch("craglight-community-account.json")})
                }
            }
        }
    }
    remove?.let {draft->AlertDialog(onDismissRequest={remove=null},title={Text("Remove this local draft?")},text={Text("The draft and photos used only by it will be removed from this device. This does not remove a server submission.")},confirmButton={TextButton(onClick={remove=null;vm.deleteDraft(draft)}) {Text("Remove local draft")}},dismissButton={TextButton(onClick={remove=null}) {Text("Keep draft")}})}
    if(signIn) SignInDialog(onDismiss={signIn=false},onSignIn={alias,photo->signIn=false;context.activity()?.let {vm.signIn(it,alias,photo)} ?: vm.showError("Open this screen in the app to sign in.")})
}

@Composable fun ContributionEditor(draft: CommunityDraft,store: DraftStore,busy: Boolean,signedIn: Boolean,configured: Boolean,
    onChange: ((CommunityDraft)->CommunityDraft)->Unit,onPhoto: ()->Unit,onSubmit: ()->Unit,onSignIn: ()->Unit,onRefresh: ()->Unit,onCopy: ()->Unit,onError: (String)->Unit,onExport: ()->Unit={}) {
    var photoId by remember(draft.id) {mutableStateOf(draft.photos.firstOrNull()?.id)}
    var routeId by remember(draft.id) {mutableStateOf(draft.routes.firstOrNull()?.id)}
    val photo=draft.photos.firstOrNull {it.id==photoId} ?: draft.photos.firstOrNull()
    val route=draft.routes.firstOrNull {it.id==routeId} ?: draft.routes.firstOrNull {it.photoId==photo?.id} ?: draft.routes.firstOrNull()
    val enabled=!busy && !draft.isSubmitted
    val context=LocalContext.current
    fun updateRoute(transform: (DraftRoute)->DraftRoute) {route?.let {r->onChange {d->d.copy(routes=d.routes.map {if(it.id==r.id) transform(it) else it})}}}
    LazyColumn(Modifier.fillMaxSize().testTag("community-editor"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text(if(draft.isSubmitted) "Received by the community service. Public changes require moderation." else "Draft saved on this device. Missing details can be added later.",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedButton(onClick=onExport,enabled=!busy,modifier=Modifier.testTag("community-export-draft")) {Text("Export draft with photos")}}
        if(draft.basePublicationId!=null) item {Text("Adding to a published guide. Its existing routes, directions and original author credits will be retained during review.",style=MaterialTheme.typography.bodySmall)}
        if(draft.isSubmitted) item {
            Text("Status: ${draft.submissionStatus?.replace('_',' ')}",style=MaterialTheme.typography.titleMedium)
            if(draft.serverMissing.isNotEmpty()) Text("Still needed for a complete guide: ${draft.serverMissing.joinToString(", ")}")
            Row {TextButton(onClick=onRefresh,enabled=signedIn && !busy) {Text("Refresh status")};TextButton(onClick=onCopy,enabled=!busy) {Text("Copy to a new draft")}}
        }
        item {Field("Crag name",draft.cragName,{v->onChange{it.copy(cragName=v)}},150,enabled,"community-crag-name")}
        item {Selector("Contribution",draft.kind,listOf("guide","gallery"),enabled) {v->onChange{it.copy(kind=v)}}}
        if(draft.kind=="gallery") item {Field("Photo caption",draft.caption,{v->onChange{it.copy(caption=v)}},2000,enabled,"community-caption",3)}
        item {Text("Your photographs",style=MaterialTheme.typography.titleMedium);Text("Add photographs you took yourself. Published community photos retain their original credit.",style=MaterialTheme.typography.bodySmall)}
        item {Button(onClick=onPhoto,enabled=enabled && draft.photos.size<12,modifier=Modifier.testTag("community-add-photo")) {Text("Choose my original photo")}}
        if(draft.photos.size>1) item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick={val i=draft.photos.indexOf(photo);photoId=draft.photos[(i-1).coerceAtLeast(0)].id},enabled=draft.photos.indexOf(photo)>0) {Text("Previous")}
            Text("Photo ${draft.photos.indexOf(photo)+1} / ${draft.photos.size}",Modifier.align(Alignment.CenterVertically))
            TextButton(onClick={val i=draft.photos.indexOf(photo);photoId=draft.photos[(i+1).coerceAtMost(draft.photos.lastIndex)].id},enabled=draft.photos.indexOf(photo)<draft.photos.lastIndex) {Text("Next")}
        }}
        photo?.let {p->item {
            if(!p.originalWork) Text(p.sourceCredit ?: "Approved community photo · CC BY-SA 4.0",style=MaterialTheme.typography.bodySmall)
            RouteDrawingCanvas(p,store.fileForPhoto(p),draft.routes,route?.takeIf {enabled && (it.photoId==null || it.photoId==p.id)}?.id,
                onStroke={line->if(route!=null && enabled) {if(route.lines.size>=12) onError("A route can have up to 12 separate strokes. Undo or clear a stroke first.") else updateRoute {it.copy(photoId=p.id,lines=it.lines+listOf(boundedStroke(line)))}}},
                onUndo={updateRoute {it.copy(lines=it.lines.dropLast(1))}},onReset={updateRoute {it.copy(lines=emptyList())}})
            TextButton(onClick={onChange {d->d.copy(photos=d.photos.filterNot {it.id==p.id},routes=d.routes.map {if(it.photoId==p.id) it.copy(photoId=null,lines=emptyList()) else it})}},enabled=enabled) {Text("Remove photo and its drawn lines")}
        }}
        item {Text("Routes",style=MaterialTheme.typography.titleMedium)}
        if(draft.routes.isNotEmpty()) item {Selector("Selected route",route?.id ?: "",draft.routes.map{it.id},enabled,label={id->draft.routes.first{it.id==id}.name.ifBlank {"Unnamed route ${draft.routes.indexOfFirst{it.id==id}+1}"}}) {id->routeId=id;draft.routes.first{it.id==id}.photoId?.let{photoId=it}}}
        item {OutlinedButton(onClick={val added=DraftRoute(photoId=photo?.id);onChange{it.copy(routes=it.routes+added)};routeId=added.id},enabled=enabled && draft.routes.size<100,modifier=Modifier.testTag("community-add-route")) {Text("Add route")}}
        route?.let {r->
            item {Field("Route name",r.name,{v->updateRoute{it.copy(name=v)}},150,enabled,"community-route-name")}
            item {Selector("Style",r.style,listOf("trad","sport","boulder","mixed"),enabled) {v->updateRoute{it.copy(style=v)}}}
            item {Selector("Grade system",r.gradeSystem,listOf("ukTrad","ukTech","french","font","v"),enabled) {v->updateRoute{it.copy(gradeSystem=v)}}}
            item {Field("Grade",r.grade,{v->updateRoute{it.copy(grade=v)}},24,enabled,"community-route-grade")}
            item {Field("Your route description",r.description,{v->updateRoute{it.copy(description=v)}},4000,enabled,"community-route-description",3)}
            item {TextButton(onClick={onChange{d->d.copy(routes=d.routes.filterNot{it.id==r.id})};routeId=null},enabled=enabled) {Text("Remove route")}}
        }
        item {Text("Location and walk-in",style=MaterialTheme.typography.titleMedium);Text("Use decimal UK coordinates. Leave unknown details blank; do not guess.",style=MaterialTheme.typography.bodySmall)}
        item {Coordinates("Crag",draft.latitude,draft.longitude,enabled,{v->onChange{it.copy(latitude=v)}},{v->onChange{it.copy(longitude=v)}})}
        item {Coordinates("Parking",draft.parkingLatitude,draft.parkingLongitude,enabled,{v->onChange{it.copy(parkingLatitude=v)}},{v->onChange{it.copy(parkingLongitude=v)}})}
        item {Field("Parking notes",draft.parkingDescription,{v->onChange{it.copy(parkingDescription=v)}},2000,enabled,"community-parking-description",2)}
        item {Field("If parking is unknown or unavailable, explain",draft.parkingUnknownReason,{v->onChange{it.copy(parkingUnknownReason=v)}},1000,enabled,"community-parking-unknown",2)}
        item {Field("Your approach description",draft.approach,{v->onChange{it.copy(approach=v)}},6000,enabled,"community-approach",4)}
        item {Field("Your descent / return description",draft.descent,{v->onChange{it.copy(descent=v)}},6000,enabled,"community-descent",3)}
        item {Field("Access, restrictions and conditions observed",draft.access,{v->onChange{it.copy(access=v)}},4000,enabled,"community-access",3)}
        item {Field("Observed on · yyyy-MM-dd",draft.accessCheckedAt,{v->onChange{it.copy(accessCheckedAt=v)}},10,enabled,"community-access-date")}
        item {Field("Public contributor name",draft.credit,{v->onChange{it.copy(credit=v)}},40,enabled,"community-credit")}
        item {Check("I created the text, route lines and newly uploaded photographs. Any reused community photo keeps its original credit. I release my contribution under CC BY-SA 4.0 and have not copied a guidebook or another person's work.",draft.originalWorkConsent,enabled,"community-consent") {value->onChange{it.copy(originalWorkConsent=value,consentedAt=if(value) Instant.now().toString() else null)}}
            TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://creativecommons.org/licenses/by-sa/4.0/")))}) {Text("Read the reuse licence")}
        }
        item {Text("A partial contribution can be reviewed, but a published guide needs a photo topo, route details, approach, descent, parking information and current access notes.",style=MaterialTheme.typography.bodySmall)}
        draft.uploadError?.let {message->item {Text(message,color=MaterialTheme.colorScheme.error);TextButton(onClick=onRefresh,enabled=signedIn && !busy) {Text("Check server status")}}}
        if(!configured) item {Text("Sharing is not available in this build. Keep editing and saving on this device.",modifier=Modifier.testTag("community-offline-only"))}
        else if(!signedIn) item {Button(onClick=onSignIn,enabled=!busy) {Text("Sign in with Google to send")}}
        if(configured && signedIn && !draft.isSubmitted) {
            val issues=draft.submissionIssues()
            if(issues.isNotEmpty()) item {Text(issues.joinToString("\n"),style=MaterialTheme.typography.bodySmall)}
            item {Button(onClick=onSubmit,enabled=enabled && issues.isEmpty(),modifier=Modifier.fillMaxWidth().testTag("community-submit")) {Text("Send for review")}}
        }
        item {Spacer(Modifier.height(24.dp))}
    }
}

internal fun boundedStroke(line: List<ImagePoint>): List<ImagePoint> = if(line.size<=200) line else (0 until 200).map {line[(it*(line.size-1)/199.0).roundToInt()]}
@Composable private fun Field(label: String,value: String,onValue: (String)->Unit,limit: Int,enabled: Boolean,tag: String,lines: Int=1) {
    OutlinedTextField(value,{onValue(it.take(limit))},label={Text(label)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag),minLines=lines,singleLine=lines==1)
}
@Composable private fun Coordinates(label: String,lat: String,lon: String,enabled: Boolean,onLat: (String)->Unit,onLon: (String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(lat,{onLat(it.take(20))},label={Text("$label latitude")},enabled=enabled,singleLine=true,modifier=Modifier.weight(1f),isError=!coordinatePairValid(lat,lon))
        OutlinedTextField(lon,{onLon(it.take(20))},label={Text("$label longitude")},enabled=enabled,singleLine=true,modifier=Modifier.weight(1f),isError=!coordinatePairValid(lat,lon))
    }
}
@Composable internal fun Selector(title: String,value: String,options: List<String>,enabled: Boolean=true,label: (String)->String={it},onSelect: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) {Text("$title: ${label(value)}")}
        DropdownMenu(expanded,{expanded=false}) {options.forEach {option->DropdownMenuItem(text={Text(label(option))},onClick={expanded=false;onSelect(option)})}}
    }
}
@Composable internal fun Check(label: String,value: Boolean,enabled: Boolean=true,tag: String="",onChange: (Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(value,onChange,enabled=enabled,modifier=Modifier.testTag(tag));Text(label,style=MaterialTheme.typography.bodyMedium)}
}
private fun Context.activity(): Activity? = when(this) {is Activity->this;is ContextWrapper->baseContext.activity();else->null}
