package com.dosely.app.ui.journal

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.data.db.*
import com.dosely.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun JournalScreen() {
    val context = LocalContext.current
    val dao = remember { DoselyDb.get(context).journalDao() }
    val entries by remember { dao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<JournalEntity?>(null) }
    var delete by remember { mutableStateOf<JournalEntity?>(null) }
    var filter by remember { mutableStateOf("All") }
    var error by remember { mutableStateOf("") }
    val today = LocalDate.now().toEpochDay()
    val dayEntries = entries.filter { it.epochDay == today }
    val shown = entries.filter {
        when (filter) { "Symptoms" -> it.symptom.isNotBlank(); "Photos" -> it.photoUri.isNotBlank(); else -> true }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Text("Your daily journal", style = MaterialTheme.typography.headlineMedium)
            Text("Small details. A clearer picture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Water today", "${dayEntries.sumOf { it.waterMl }} mL", Modifier.weight(1f))
                StatTile("Protein", "${dayEntries.sumOf { it.proteinGrams }} g", Modifier.weight(1f))
            }
        }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Today's nutrition", style = MaterialTheme.typography.titleMedium)
                    Text("${dayEntries.sumOf { it.calories }} kcal", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                Text("Record how you feel alongside food, water, and progress photos.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { editing = JournalEntity(epochDay = today, loggedAtMillis = System.currentTimeMillis()) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add check-in")
                }
            }
        }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Symptoms", "Photos").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
        } }
        if (shown.isEmpty()) item { EmptyState(Icons.Outlined.FavoriteBorder, "Make room for how you feel", "Your check-ins will appear here. Add your first entry whenever you're ready.") }
        items(shown, key = { it.id }) { entry ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LocalDate.ofEpochDay(entry.epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { editing = entry }) { Icon(Icons.Outlined.Edit, "Edit check-in") }
                }
                if (entry.symptom.isNotBlank()) Text("${entry.symptom} · ${listOf("None", "Mild", "Moderate", "Severe")[entry.severity.coerceIn(0, 3)]}", color = MaterialTheme.colorScheme.primary)
                val metrics = buildList {
                    if (entry.waterMl > 0) add("${entry.waterMl} mL water")
                    if (entry.calories > 0) add("${entry.calories} kcal")
                    if (entry.proteinGrams > 0) add("${entry.proteinGrams} g protein")
                    if (entry.appetite > 0) add("Appetite ${entry.appetite}/5")
                    if (entry.foodNoise > 0) add("Food noise ${entry.foodNoise}/5")
                }
                if (metrics.isNotEmpty()) Text(metrics.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (entry.notes.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(entry.notes) }
                if (entry.photoUri.isNotBlank()) { Spacer(Modifier.height(12.dp)); ProgressPhoto(entry.photoUri) }
                TextButton(onClick = { delete = entry }) { Text("Delete entry") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    editing?.let { entry -> JournalDialog(entry, onDismiss = { editing = null }, onSave = { updated ->
        scope.launch {
            runCatching { dao.upsert(updated) }.onSuccess { editing = null; error = "" }.onFailure { error = "Could not save your check-in. Please retry." }
        }
    }) }
    delete?.let { entry -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this check-in?") },
        text = { Text("This removes the journal entry. Your original photo is kept in your gallery.") },
        confirmButton = { TextButton(onClick = { scope.launch { dao.delete(entry.id); delete = null } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
}

@Composable
private fun JournalDialog(entry: JournalEntity, onDismiss: () -> Unit, onSave: (JournalEntity) -> Unit) {
    val context = LocalContext.current
    var date by remember(entry.id) { mutableStateOf(LocalDate.ofEpochDay(entry.epochDay).toString()) }
    var symptom by remember(entry.id) { mutableStateOf(entry.symptom) }
    var severity by remember(entry.id) { mutableFloatStateOf(entry.severity.toFloat()) }
    var water by remember(entry.id) { mutableStateOf(entry.waterMl.takeIf { it > 0 }?.toString().orEmpty()) }
    var calories by remember(entry.id) { mutableStateOf(entry.calories.takeIf { it > 0 }?.toString().orEmpty()) }
    var protein by remember(entry.id) { mutableStateOf(entry.proteinGrams.takeIf { it > 0 }?.toString().orEmpty()) }
    var appetite by remember(entry.id) { mutableFloatStateOf(entry.appetite.toFloat()) }
    var noise by remember(entry.id) { mutableFloatStateOf(entry.foodNoise.toFloat()) }
    var notes by remember(entry.id) { mutableStateOf(entry.notes) }
    var photo by remember(entry.id) { mutableStateOf(entry.photoUri) }
    var photoError by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); photo = uri.toString() }
            .onFailure { photoError = "This photo could not be attached. Try another image." }
    }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    fun validNumber(text: String, max: Int) = text.isEmpty() || (text.toIntOrNull()?.let { it in 0..max } == true)
    val valid = parsedDate != null && !parsedDate.isAfter(LocalDate.now()) && parsedDate.year >= 2000 &&
        validNumber(water, 20_000) && validNumber(calories, 20_000) && validNumber(protein, 1000) &&
        (symptom.isNotBlank() || water.toIntOrNull()?.let { it > 0 } == true || calories.toIntOrNull()?.let { it > 0 } == true ||
            protein.toIntOrNull()?.let { it > 0 } == true || appetite > 0 || noise > 0 || notes.isNotBlank() || photo.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Daily check-in") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(date, { date = it }, label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(symptom, { symptom = it.take(80) }, label = { Text("Symptom, e.g. nausea or fatigue") })
            if (symptom.isNotBlank()) {
                Text("Severity: " + listOf("None", "Mild", "Moderate", "Severe")[severity.toInt()])
                Slider(severity, { severity = it }, valueRange = 0f..3f, steps = 2)
            }
            JournalNumber("Water (mL)", water) { water = it }
            JournalNumber("Calories (kcal)", calories) { calories = it }
            JournalNumber("Protein (g)", protein) { protein = it }
            Text("Appetite: ${appetite.toInt()}/5 · 0 = not tracked")
            Slider(appetite, { appetite = it }, valueRange = 0f..5f, steps = 4)
            Text("Food noise: ${noise.toInt()}/5 · 0 = not tracked")
            Slider(noise, { noise = it }, valueRange = 0f..5f, steps = 4)
            OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Notes") })
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(if (photo.isBlank()) "Add progress photo" else "Change progress photo") }
            if (photo.isNotBlank()) TextButton(onClick = { photo = "" }) { Text("Remove attached photo") }
            if (photoError.isNotEmpty()) Text(photoError, color = MaterialTheme.colorScheme.error)
            if (!valid) Text("Add a check-in detail and use a valid date and non-negative amounts.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(enabled = valid, onClick = { onSave(entry.copy(epochDay = parsedDate!!.toEpochDay(),
        symptom = symptom.trim(), severity = if (symptom.isBlank()) 0 else severity.toInt(), waterMl = water.toIntOrNull() ?: 0,
        calories = calories.toIntOrNull() ?: 0, proteinGrams = protein.toIntOrNull() ?: 0, appetite = appetite.toInt(), foodNoise = noise.toInt(), notes = notes.trim(), photoUri = photo)) }) { Text("Save check-in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun JournalNumber(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, { change(it.filter(Char::isDigit).take(5)) }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ProgressPhoto(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            val parsed = Uri.parse(uri)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            context.contentResolver.openInputStream(parsed)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), "Progress photo", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop) }
        ?: Text("Photo unavailable. Reattach it using Edit.", style = MaterialTheme.typography.bodySmall)
}
