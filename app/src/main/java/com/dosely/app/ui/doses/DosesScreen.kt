package com.dosely.app.ui.doses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.translate.S
import com.dosely.app.ui.components.EmptyState
import com.dosely.app.ui.components.Chip
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.KeyboardType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DosesScreen(viewModel: DosesViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showLogDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InjectionEntity?>(null) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InjectionEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
        Column {
        Spacer(Modifier.height(12.dp))
        Text(S("doses_title"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            ui.medName + " · " + S("onb_dose_every") + " " + ui.intervalDays + " " + S("onb_dose_days"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        DosePlanCard()
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showLogDialog = true }, modifier = Modifier.weight(2f)) {
                Text(S("doses_log_new"))
            }
            if (ui.canSkip) {
                TextButton(onClick = { showSkipDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(S("doses_skip"))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        }
        }
        if (ui.entries.isEmpty()) {
            item {
            EmptyState(
                icon = Icons.Outlined.Medication,
                title = S("doses_title"),
                body = S("doses_empty"),
            )
            }
        } else {
                items(ui.entries, key = { it.id }) { entry ->
                    DoseRow(
                        entry = entry,
                        onDelete = { pendingDelete = entry },
                        onEdit = { editing = entry; showLogDialog = true },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showLogDialog) {
        LogInjectionDialog(
            suggestedMg = ui.suggestedDoseMg,
            availableDoses = ui.availableDoses,
            existing = editing,
            lastSite = ui.entries.firstOrNull { !it.skipped }?.site,
            onDismiss = { showLogDialog = false; editing = null },
            onConfirm = { site, mg, notes, date, time ->
                viewModel.logInjection(site, mg, notes, date, time, editing)
                showLogDialog = false
                editing = null
            },
        )
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text(S("doses_missed_title")) },
            text = { Text(S("doses_missed_body")) },
            confirmButton = {
                Button(onClick = {
                    viewModel.skipToday()
                    showSkipDialog = false
                }) { Text(S("doses_skip")) }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) { Text(S("doses_cancel")) }
            },
        )
    }

    if (pendingDelete != null) {
        val toDelete = pendingDelete ?: return
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(S("doses_delete")) },
            text = { Text(S("doses_stock_deducted")) },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(toDelete)
                    pendingDelete = null
                }) { Text(S("common_delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(S("doses_cancel")) }
            },
        )
    }
}

@Composable
private fun DoseRow(entry: InjectionEntity, onDelete: () -> Unit, onEdit: () -> Unit) {
    val date = LocalDate.ofEpochDay(entry.epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(date, style = MaterialTheme.typography.titleMedium)
                    if (entry.skipped) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                S("doses_skip"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (entry.skipped) {
                    Text(
                        S("doses_scheduled"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "%.2f".format(entry.doseMg).trimEnd('0').trimEnd('.') + " " + S("doses_mg"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                entry.site,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (entry.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!entry.skipped) TextButton(onClick = onEdit) { Text("Edit") }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = S("common_delete"),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun LogInjectionDialog(
    suggestedMg: Double,
    availableDoses: List<Double>,
    existing: InjectionEntity?,
    lastSite: String?,
    onDismiss: () -> Unit,
    onConfirm: (site: String, mg: Double, notes: String, date: LocalDate, time: java.time.LocalTime) -> Unit,
) {
    val sites = com.dosely.sync.WatchContract.sites
    var site by remember { mutableStateOf(existing?.site ?: sites[(sites.indexOf(lastSite) + 1) % sites.size]) }
    var mgText by remember { mutableStateOf((existing?.doseMg ?: suggestedMg).toString()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var dateText by remember { mutableStateOf(existing?.let { LocalDate.ofEpochDay(it.epochDay).toString() } ?: LocalDate.now().toString()) }
    var timeText by remember { mutableStateOf((existing?.let { java.time.Instant.ofEpochMilli(it.takenAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime() } ?: java.time.LocalTime.now()).format(DateTimeFormatter.ofPattern("HH:mm"))) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val time = runCatching { java.time.LocalTime.parse(timeText) }.getOrNull()
    val mg = mgText.replace(',', '.').toDoubleOrNull()
    val valid = date != null && date.year >= 2000 && !date.isAfter(LocalDate.now()) && time != null &&
        !date.atTime(time).isAfter(java.time.LocalDateTime.now()) && mg != null && mg.isFinite() && mg > 0 && mg <= 100

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Record a shot" else "Edit shot") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Enter the dose you actually took. Follow your prescribed treatment plan.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(dateText, { dateText = it }, label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(timeText, { timeText = it }, label = { Text("Time · HH:mm") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                if (lastSite != null) Text("Last site: $lastSite", style = MaterialTheme.typography.bodySmall)
                Text(S("doses_site"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                com.dosely.app.ui.doses.SiteSelector(site) { site = it }
                if (availableDoses.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(S("doses_mg"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableDoses.forEach { d ->
                            val label = "%.2f".format(d).trimEnd('0').trimEnd('.')
                            val isSelected = mgText.replace(',', '.').toDoubleOrNull() == d
                            Chip(
                                text = "$label " + S("doses_mg"),
                                selected = isSelected,
                                onClick = { mgText = label },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = mgText,
                    onValueChange = { v -> mgText = v.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text(S("doses_mg")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(S("doses_notes")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onConfirm(site, mg!!, notes.trim(), date!!, time!!)
            }) { Text(S("doses_save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S("doses_cancel")) }
        },
    )
}

private fun siteKey(site: String): String = when (site) {
    "Abdomen" -> "doses_site_abdomen"
    "Thigh" -> "doses_site_thigh"
    "Upper arm" -> "doses_site_arm"
    else -> "doses_site_other"
}
