package org.craglight.app.community

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun SignInDialog(onDismiss: ()->Unit,onSignIn: (String,Boolean)->Unit) {
    var alias by remember {mutableStateOf("")};var photo by remember {mutableStateOf(false)};var agree by remember {mutableStateOf(false)}
    val context=LocalContext.current
    AlertDialog(onDismissRequest=onDismiss,title={Text("Your community profile")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Use a nickname for public credit. Google verifies your account; your email and real name are not published or saved in your profile.")
        OutlinedTextField(alias,{alias=it.take(40)},label={Text("Public contributor name")},singleLine=true,modifier=Modifier.testTag("community-signin-alias"))
        Check("Use my Google profile photo (optional)",photo) {photo=it}
        Text("The service stores an account identifier, your chosen alias, optional photo and contributions. Photos, names and contributions are reviewed before publication. Export or delete your account from this screen.",style=MaterialTheme.typography.bodySmall)
        Check("I agree to share only work I can license under CC BY-SA 4.0, respect access restrictions and other people, and accept moderation. I have read the privacy notice.",agree,tag="community-signin-consent") {agree=it}
        TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/delegentai/craglight/blob/main/docs/PRIVACY.md")))}) {Text("Privacy notice")}
        TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://creativecommons.org/licenses/by-sa/4.0/")))}) {Text("CC BY-SA 4.0")}
    }},confirmButton={Button(onClick={onSignIn(alias.trim(),photo)},enabled=agree && alias.trim().length in 2..40) {Text("Sign in with Google")}},dismissButton={TextButton(onClick=onDismiss) {Text("Keep working offline")}})
}

@Composable internal fun CommunityAccount(state: CommunityState,config: CommunityConfig,onSignIn: ()->Unit,onSignOut: ()->Unit,
                                          onUpdate: (String,Boolean)->Unit,onDelete: ()->Unit,onRefresh: ()->Unit,onExport: ()->Unit) {
    val profile=state.session?.profile
    var alias by remember(profile?.id,profile?.alias) {mutableStateOf(profile?.alias.orEmpty())}
    var keepPhoto by remember(profile?.photoUrl) {mutableStateOf(profile?.photoUrl!=null)}
    var delete by remember {mutableStateOf(false)};var confirmation by remember {mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(profile==null) {
            item {Text("A simple profile gives credit to your accepted contributions. Saving drafts works without signing in.")}
            item {Button(onClick=onSignIn,enabled=config.configured && !state.busy,modifier=Modifier.testTag("community-sign-in")) {Text("Sign in with Google")}}
            if(!config.configured) item {Text("Online sharing is not available in this build. Your drafts stay on this device.")}
        } else {
            profile.photoUrl?.let {path->item {CommunityAvatar(path,config)}}
            item {Text(profile.alias,style=MaterialTheme.typography.headlineSmall);Text("${profile.acceptedContributions} accepted contributions",modifier=Modifier.testTag("community-accepted-credit"));Text("Only server-confirmed accepted contributions earn credit.",style=MaterialTheme.typography.bodySmall)}
            item {TextButton(onClick=onRefresh,enabled=!state.busy) {Text("Refresh profile and credit")}}
            item {OutlinedTextField(alias,{alias=it.take(40)},label={Text("Public contributor name")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            if(profile.photoUrl!=null) item {Check("Keep my optional profile photo",keepPhoto) {keepPhoto=it}}
            item {Button(onClick={onUpdate(alias,keepPhoto)},enabled=!state.busy && alias.trim().length in 2..40) {Text("Save profile changes")}}
            item {OutlinedButton(onClick=onExport,enabled=!state.busy) {Text("Export my account data")}}
            item {OutlinedButton(onClick=onSignOut,enabled=!state.busy) {Text("Sign out")}}
            item {TextButton(onClick={delete=true},enabled=!state.busy,modifier=Modifier.testTag("community-delete-account")) {Text("Delete my community account")}}
        }
    }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Delete your community account?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Your account, private uploads and drafts will be removed from the service, and sessions revoked. Your uploaded photos and guides depending on them will be withdrawn. Routes and guide text reused in another reviewed guide may remain with anonymised credit. Copies already downloaded by other people cannot be recalled. Local drafts on this device are separate.")
        OutlinedTextField(confirmation,{confirmation=it},label={Text("Type DELETE to confirm")},singleLine=true)
    }},confirmButton={TextButton(onClick={delete=false;onDelete()},enabled=confirmation=="DELETE") {Text("Delete account")}},dismissButton={TextButton(onClick={delete=false}) {Text("Cancel")}})
}

@Composable internal fun CommunityBrowse(state: CommunityState,config: CommunityConfig,store: DraftStore,onRefresh: ()->Unit,
                                         onReuse: (JSONObject,JSONObject)->Unit,onReport: (String,String,String,String)->Unit,
                                         onLoadMore: ()->Unit) {
    var report by remember {mutableStateOf<Pair<String,String>?>(null)}
    var reason by remember {mutableStateOf("safety")};var details by remember {mutableStateOf("")}
    var expanded by remember {mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("Published by the community",style=MaterialTheme.typography.titleLarge);Text("Guides and gallery photographs are reviewed separately. A gallery photo alone is not a complete guide.",style=MaterialTheme.typography.bodySmall)}
        if(!config.configured) item {Text("Online community browsing is not available in this build. You can work on your own drafts offline.")}
        else item {OutlinedButton(onClick=onRefresh,enabled=!state.busy) {Text("Refresh published contributions")}}
        if(state.publicLoaded && state.guides.isEmpty() && state.gallery.isEmpty()) item {Text("The service returned no published contributions yet.")}
        items(state.guides+state.gallery,key={it.getString("id")}) {item->
            val id=item.getString("id");val crag=item.optJSONObject("crag") ?: JSONObject()
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(crag.optString("name","Community crag"),style=MaterialTheme.typography.titleMedium)
                Text(if(item.optString("kind")=="gallery") "Gallery photograph" else "Reviewed guide",style=MaterialTheme.typography.labelLarge)
                val contributor=item.optJSONObject("contributor")
                contributor?.let {credit->ContributorBadge(credit,config)}
                if(item.optString("caption").isNotBlank()) Text(item.getString("caption"))
                TextButton(onClick={expanded=if(expanded==id) null else id}) {Text(if(expanded==id) "Hide details" else "View photos and details")}
                if(expanded==id) {
                    val photos=item.optJSONArray("photos")
                    for(index in 0 until (photos?.length() ?: 0)) {
                        val photo=photos!!.getJSONObject(index)
                        PublicPhoto(photo,item,config,store)
                        val attribution=item.optJSONArray("photoAttributions")?.let {a->(0 until a.length()).map {a.getJSONObject(it)}.firstOrNull {it.optString("photoId")==photo.optString("id")}}
                        Text("Photo: ${attribution?.optString("alias") ?: "Community contributor"} · CC BY-SA 4.0",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={onReuse(item,photo)},enabled=!state.busy) {Text("Add my routes to this photo")}
                    }
                    val routes=item.optJSONArray("routes")
                    for(index in 0 until (routes?.length() ?: 0)) {
                        val route=routes!!.getJSONObject(index)
                        Text("${route.optString("name")} · ${route.optString("grade")} · ${route.optString("style")}",style=MaterialTheme.typography.titleSmall)
                        val routeCredit=item.optJSONArray("routeAttributions")?.let {a->(0 until a.length()).map {a.getJSONObject(it)}.firstOrNull {it.optString("routeId")==route.optString("id")}}
                        routeCredit?.let {credit->Text("Route contribution: ${credit.optString("alias","Community contributor")} · CC BY-SA 4.0",style=MaterialTheme.typography.bodySmall)}
                        if(route.optString("description").isNotBlank()) Text(route.getString("description"))
                    }
                    if(item.optString("approach").isNotBlank()) {Text("Approach",style=MaterialTheme.typography.titleSmall);Text(item.getString("approach"))}
                    if(item.optString("descent").isNotBlank()) {Text("Descent / return",style=MaterialTheme.typography.titleSmall);Text(item.getString("descent"))}
                    item.optJSONObject("parking")?.let {parking->Text("Parking",style=MaterialTheme.typography.titleSmall);if(!parking.isNull("lat") && !parking.isNull("lon")) Text("${parking.optDouble("lat")}, ${parking.optDouble("lon")}");if(parking.optString("description").isNotBlank()) Text(parking.getString("description"))}
                    if(item.optString("parkingUnknownReason").isNotBlank()) {Text("Parking information",style=MaterialTheme.typography.titleSmall);Text(item.getString("parkingUnknownReason"))}
                    if(item.optString("access").isNotBlank()) {Text("Access and restrictions",style=MaterialTheme.typography.titleSmall);Text(item.getString("access"))}
                    if(!item.isNull("accessCheckedAt") && item.optString("accessCheckedAt").isNotBlank()) Text("Observed on ${item.getString("accessCheckedAt")}",style=MaterialTheme.typography.bodySmall)
                    SectionCredits(item, config)
                    Text(if(item.optString("kind")=="gallery") "Scope: a moderated photograph and caption. This is not a complete climbing guide." else "Scope: this contributor's reviewed routes and directions. Conditions and access may change; the guide is not a safety guarantee.",style=MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = {
                        val type = if (item.optString("kind") == "gallery") "gallery" else "crag"
                        report = type to id
                    }) { Text("Report a concern") }
                    if(contributor!=null) TextButton(onClick={report="profile" to contributor.getString("id")}) {Text("Report profile")}
                }
            }}
        }
        if (state.guidesNextOffset != null || state.galleryNextOffset != null) {
            item {
                OutlinedButton(onClick = onLoadMore, enabled = !state.busy) {
                    Text("Load more contributions")
                }
            }
        }
    }
    report?.let {target-> AlertDialog(onDismissRequest={report=null},title={Text("Report a concern")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Selector("Reason",reason,listOf("safety","access","copyright","privacy","harassment","illegal","spam","other")) {reason=it}
        OutlinedTextField(details,{details=it.take(3000)},label={Text("What should reviewers check?")},minLines=3)
        Text("Your report is sent privately to moderators; it is not a public comment.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={report=null;onReport(target.first,target.second,reason,details)},enabled=details.isNotBlank()) {Text("Send report")}},dismissButton={TextButton(onClick={report=null}) {Text("Cancel")}})}
}

@Composable private fun SectionCredits(publication: JSONObject, config: CommunityConfig) {
    val credits = publication.optJSONObject("sectionAttributions") ?: return
    val context = LocalContext.current
    val fields = linkedMapOf(
        "crag" to "Crag location", "approach" to "Approach", "descent" to "Descent / return",
        "parking" to "Parking", "parkingUnknownReason" to "Parking information",
        "access" to "Access notes", "accessCheckedAt" to "Observation date", "caption" to "Caption",
    )
    Text("Text and location credits", style = MaterialTheme.typography.titleSmall)
    fields.forEach { (field, label) ->
        val rows = credits.optJSONArray(field)
        val sources = (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it) }
        if (sources.isNotEmpty()) {
            Text(
                "$label: ${sources.map { it.optString("alias", "Community contributor") }.distinct().joinToString(", ")} · CC BY-SA 4.0",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    val sourceUrls = fields.keys.flatMap { field ->
        val rows = credits.optJSONArray(field)
        (0 until (rows?.length() ?: 0)).mapNotNull { index ->
            val source = rows!!.getJSONObject(index).optString("sourceUrl")
            runCatching {
                require(source.isNotBlank())
                val base = java.net.URI(config.baseUrl)
                base.resolve(source).takeIf { it.scheme == "https" && it.host == base.host }
            }.getOrNull()
        }
    }.distinct()
    sourceUrls.forEachIndexed { index, source ->
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.toString())))
        }) { Text("Source contribution ${index + 1}") }
    }
}

@Composable private fun PublicPhoto(json: JSONObject,publication: JSONObject,config: CommunityConfig,store: DraftStore) {
    val id=json.getString("id")
    var error by remember(id) {mutableStateOf<String?>(null)}
    val photo=remember(id) {DraftPhoto(id,"$id.jpg",json.getInt("width"),json.getInt("height"),json.getString("sha256"),false)}
    val file by produceState<java.io.File?>(null,id) {
        try {value=CommunityApi(config).downloadPublicPhoto(photo,store)} catch(e: Exception) {error=e.message ?: "Photo unavailable."}
    }
    val routes=remember(publication,id) {
        val array=publication.optJSONArray("routes")
        (0 until (array?.length() ?: 0)).mapNotNull {index->runCatching {val r=array!!.getJSONObject(index);if(r.optString("photoId")!=id)return@runCatching null
            val lines=r.optJSONArray("lines") ?: org.json.JSONArray()
            DraftRoute(r.getString("id"),r.optString("name"),r.optString("gradeSystem"),r.optString("grade"),r.optString("style"),r.optString("description"),id,
                (0 until lines.length()).map {i->val points=lines.getJSONArray(i);(0 until points.length()).map {j->points.getJSONObject(j).let {ImagePoint(it.getDouble("x"),it.getDouble("y")).also{p->require(p.valid())}}}})
        }.getOrNull()}
    }
    var selected by remember(id) {mutableStateOf(routes.firstOrNull()?.id)}
    val local=file
    if(local!=null) {
        RouteDrawingCanvas(photo,local,routes,selected,{}, {}, {},readOnly=true)
        routes.forEachIndexed {index,route->TextButton(onClick={selected=route.id}) {Text("${index+1}. ${route.name} · ${route.grade}${if(route.id==selected) " · selected" else ""}")}}
        if(routes.isEmpty()) Text("No route lines are attached to this photograph.",style=MaterialTheme.typography.bodySmall)
    } else Text(error ?: "Opening photograph…",style=MaterialTheme.typography.bodySmall)
}

private val avatarCache=android.util.LruCache<String,ImageBitmap>(12)
@Composable private fun CommunityAvatar(path: String,config: CommunityConfig) {
    val key=config.baseUrl+path
    val bitmap by produceState(avatarCache.get(key),key) {
        if(value==null) runCatching {withContext(Dispatchers.IO) {
            val bytes=CommunityApi(config).avatarBytes(path)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=12_000_000)
            var sample=1;while(bounds.outWidth/sample>192 || bounds.outHeight/sample>192) sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?.asImageBitmap()
        }}.getOrNull()?.let {value=it;avatarCache.put(key,it)}
    }
    bitmap?.let {Image(it,"Optional contributor profile photograph",Modifier.size(64.dp).clip(CircleShape),contentScale=ContentScale.Crop)}
}
@Composable private fun ContributorBadge(json: JSONObject,config: CommunityConfig) {
    val id=json.optString("id")
    val profile by produceState<CommunityProfile?>(null,id) {value=runCatching {CommunityApi(config).publicProfile(id)}.getOrNull()}
    profile?.photoUrl?.let {CommunityAvatar(it,config)}
    Text("Shared by ${profile?.alias ?: json.optString("alias","Community contributor")} · CC BY-SA 4.0",style=MaterialTheme.typography.bodySmall)
    profile?.let {Text("${it.acceptedContributions} accepted contributions",style=MaterialTheme.typography.bodySmall)}
}
