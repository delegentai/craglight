@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package org.craglight.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.craglight.app.AppState
import org.craglight.app.data.*
import org.craglight.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable internal fun Choice(label: String, selected: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected") }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable internal fun FilterDialog(initial: Filtering.Filter, initialSort: Filtering.Sort, state: AppState, onDismiss: () -> Unit, onApply: (Filtering.Filter, Filtering.Sort) -> Unit) {
    var filter by remember { mutableStateOf(initial) }; var sort by remember { mutableStateOf(initialSort) }
    val sortNames = mapOf(Filtering.Sort.NAME to "Name", Filtering.Sort.WALK_IN_DISTANCE to "Walk-in distance", Filtering.Sort.WALK_IN_TIME to "Walk-in time", Filtering.Sort.DISTANCE_FROM_ORIGIN to "Near starting point (straight-line)")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find your kind of climbing") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Region", filter.region ?: "All", listOf("All") + state.crags.map { it.region }.distinct().sorted()) { filter = filter.copy(region = it.takeUnless { it == "All" }) }
            Choice("Style", filter.style ?: "All", listOf("All", "trad", "sport", "boulder", "ice", "mixed")) { filter = filter.copy(style = it.takeUnless { it == "All" }) }
            Choice("Rock", filter.rock ?: "All", listOf("All") + state.crags.mapNotNull { it.rock }.distinct().sorted()) { filter = filter.copy(rock = it.takeUnless { it == "All" }) }
            Choice("Grades", filter.gradeSystem?.label ?: "Any", listOf("Any") + GradeSystem.entries.map { it.label }) { selected -> filter = filter.copy(gradeSystem = GradeSystem.entries.find { it.label == selected }, minGrade = null, maxGrade = null) }
            filter.gradeSystem?.let { system ->
                Choice("From", filter.minGrade ?: "Any", listOf("Any") + Grades.values(system)) { filter = filter.copy(minGrade = it.takeUnless { it == "Any" }) }
                Choice("To", filter.maxGrade ?: "Any", listOf("Any") + Grades.values(system)) { filter = filter.copy(maxGrade = it.takeUnless { it == "Any" }) }
                CheckRow("Include unrecorded grades", filter.includeUnknownGrades) { filter = filter.copy(includeUnknownGrades = it) }
            }
            Choice("Walk-in", filter.maxWalkInTimeMin?.let { "Up to ${it.toInt()} min" } ?: "Any", listOf("Any", "Up to 10 min", "Up to 20 min", "Up to 30 min", "Up to 60 min")) { filter = filter.copy(maxWalkInTimeMin = Regex("[0-9]+").find(it)?.value?.toDouble()) }
            Choice("Distance", filter.maxWalkInDistanceM?.let { "Up to ${it.toInt()} m" } ?: "Any", listOf("Any", "Up to 500 m", "Up to 1000 m", "Up to 3000 m", "Up to 5000 m")) { filter = filter.copy(maxWalkInDistanceM = Regex("[0-9]+").find(it)?.value?.toDouble()) }
            CheckRow("Family-friendly recorded", filter.familyFriendlyOnly) { filter = filter.copy(familyFriendlyOnly = it) }
            Choice("Sort", sortNames.getValue(sort), sortNames.values.toList()) { name -> sort = sortNames.entries.first { it.value == name }.key }
            Text("Unknown attributes are excluded by active filters. Choose a starting location or parking to calculate walk-ins from saved paths. Disconnected paths stay unknown.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onApply(filter, sort) }) { Text("Apply filters") } }, dismissButton = { TextButton(onClick = { onApply(Filtering.Filter(query = initial.query), Filtering.Sort.NAME) }) { Text("Reset") } })
}

@Composable internal fun CheckRow(label: String, checked: Boolean, tag: String? = null, onCheck: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onCheck, if (tag != null) Modifier.testTag(tag) else Modifier); Text(label, style = MaterialTheme.typography.bodyMedium) }
}

@Composable internal fun SunFilterDialog(initial: Instant, active: Boolean, onDismiss: () -> Unit, onApply: (Instant, Boolean) -> Unit) {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    var value by remember { mutableStateOf(format.format(initial.atZone(London))) }
    var only by remember { mutableStateOf(active) }
    val parsed = runCatching { LocalDateTime.parse(value, format).let { require(London.rules.getValidOffsets(it).isNotEmpty()); it.atZone(London).toInstant() } }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Plan for sunlight") }, text = { Column {
        OutlinedTextField(value, { value = it }, label = { Text("UK date & time") }, supportingText = { Text("yyyy-MM-dd HH:mm · Europe/London") }, isError = parsed == null, singleLine = true)
        TextButton(onClick = { value = format.format(Instant.now().atZone(London)) }) { Text("Use now") }
        CheckRow("Only faces with potential direct sun", only) { only = it }
        Text("Uses recorded aspect. Unknown aspects are excluded. Terrain shadows, overhangs and clouds can block actual sunshine.", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { parsed?.let { onApply(it, only) } }, enabled = parsed != null) { Text("Use time") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable internal fun LocationDialog(onDismiss: () -> Unit, onCurrent: () -> Unit, onPoint: (GeoPoint) -> Unit) {
    var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    val point = lat.toDoubleOrNull()?.let { y -> lon.toDoubleOrNull()?.let { x -> if (y.isFinite() && x.isFinite() && y in -90.0..90.0 && x in -180.0..180.0) GeoPoint(y, x) else null } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Starting location") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Location stays on your device. Choose a mapped car park from a crag, use your location, or enter coordinates.")
        Button(onClick = onCurrent, modifier = Modifier.testTag("location-current")) { Text("Use my location") }
        OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true)
        OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true)
    } }, confirmButton = { TextButton(onClick = { point?.let(onPoint) }, enabled = point != null) { Text("Use coordinates") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable internal fun TickDialog(crag: Crag?, route: Route?, existing: TickRecord? = null, onDismiss: () -> Unit, onSave: (TickRecord) -> Unit) {
    var name by remember { mutableStateOf(existing?.routeName ?: route?.name ?: "") }
    var grade by remember { mutableStateOf(existing?.grade ?: route?.let(::gradeSummary)?.takeUnless { it == "Grade not recorded" } ?: "") }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now(London).toString()) }
    var style by remember { mutableStateOf(existing?.style ?: "Onsight") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    val validDate = runCatching { LocalDate.parse(date) }.getOrNull()?.let { !it.isAfter(LocalDate.now(London)) } == true
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add a tick" else "Edit tick") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(existing?.cragName ?: crag?.name ?: "")
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("Route name") }, modifier = Modifier.testTag("tick-route-name"), singleLine = true)
            OutlinedTextField(grade, { grade = it.take(80) }, label = { Text("Grade (optional)") }, singleLine = true)
            Choice("Style", style, listOf("Onsight", "Flash", "Redpoint", "Ground-up", "Top rope", "Second", "Solo", "Repeat", "Attempt")) { style = it }
            OutlinedTextField(date, { date = it }, label = { Text("Date · yyyy-MM-dd") }, isError = !validDate, singleLine = true)
            OutlinedTextField(notes, { notes = it.take(3000) }, label = { Text("Notes") }, modifier = Modifier.testTag("tick-notes"), minLines = 2)
            Text("Personal logbook only. Free-text route names and notes are never added to public packs.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(modifier = Modifier.testTag("tick-save"), enabled = name.isNotBlank() && validDate, onClick = {
        onSave(TickRecord(existing?.id ?: UUID.randomUUID().toString(), existing?.cragId ?: crag!!.id, existing?.cragName ?: crag!!.name,
            existing?.routeId ?: route?.id ?: "personal-${UUID.randomUUID()}", name.trim(), grade.trim(), style, date, notes))
    }) { Text("Save tick") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable internal fun ContributionDialog(crag: Crag, onDismiss: () -> Unit, onSave: (ContributionRecord) -> Unit) {
    var category by remember { mutableStateOf("Route or grade") }; var proposal by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }; var consent by remember { mutableStateOf(false) }
    val validSource = source.isBlank() || runCatching { java.net.URI(source).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Improve this crag") }, text = {
        Column(Modifier.heightIn(max = 530.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(crag.name)
            Choice("Topic", category, listOf("Route or grade", "Access / restriction", "Parking / approach", "Aspect / rock", "Topo / image licence", "Other")) { category = it }
            OutlinedTextField(proposal, { proposal = it.take(2000) }, label = { Text("Your original observations") }, minLines = 3, modifier = Modifier.testTag("contribution-proposal"))
            OutlinedTextField(source, { source = it.take(1000) }, label = { Text("Evidence URL (optional, HTTPS)") }, isError = !validSource)
            CheckRow("I wrote this from my own observations and release it under CC0 1.0. It contains no copied guidebook text or personal information.", consent, "contribution-license-consent") { consent = it }
            Text("Saved to a local review queue. You then choose to send it through the review form or export it. It will not change public crag data before moderation.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(modifier = Modifier.testTag("contribution-save"), enabled = consent && proposal.isNotBlank() && validSource, onClick = {
        onSave(ContributionRecord(UUID.randomUUID().toString(), crag.id, crag.name, category, proposal.trim(), source.trim(), "CC0-1.0", Instant.now().toString()))
    }) { Text("Queue for review") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
