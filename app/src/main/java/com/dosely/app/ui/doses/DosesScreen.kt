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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DosesScreen(viewModel: DosesViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showLogDialog by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InjectionEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(S("doses_title"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            ui.medName + " · " + S("onb_dose_every") + " " + ui.intervalDays + " " + S("onb_dose_days"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

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

        if (ui.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Medication,
                title = S("doses_title"),
                body = S("doses_empty"),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.entries, key = { it.id }) { entry ->
                    DoseRow(
                        entry = entry,
                        onDelete = { pendingDelete = entry },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showLogDialog) {
        LogInjectionDialog(
            suggestedMg = ui.suggestedDoseMg,
            onDismiss = { showLogDialog = false },
            onConfirm = { site, mg, notes ->
                viewModel.logInjection(site, mg, notes)
                showLogDialog = false
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
private fun DoseRow(entry: InjectionEntity, onDelete: () -> Unit) {
    val date = LocalDate.ofEpochDay(entry.epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(0.85f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleMedium)
                if (entry.skipped) {
                    Text(
                        S("doses_skip"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (entry.skipped) {
                    S("doses_scheduled")
                } else {
                    "%.1f".format(entry.doseMg) + " " + S("doses_mg") + " · " + S(siteKey(entry.site))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.notes, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = S("common_delete"),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LogInjectionDialog(
    suggestedMg: Double,
    onDismiss: () -> Unit,
    onConfirm: (site: String, mg: Double, notes: String) -> Unit,
) {
    var site by remember { mutableStateOf("Abdomen") }
    var mgText by remember { mutableStateOf("%.1f".format(suggestedMg)) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S("doses_log_new")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(S("doses_site"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Abdomen" to "doses_site_abdomen",
                        "Thigh" to "doses_site_thigh",
                        "Upper arm" to "doses_site_arm",
                    ).forEach { (value, key) ->
                        Chip(
                            text = S(key),
                            selected = site == value,
                            onClick = { site = value },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = mgText,
                    onValueChange = { v -> mgText = v.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(S("doses_mg")) },
                    singleLine = true,
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
            Button(onClick = {
                val mg = mgText.replace(',', '.').toDoubleOrNull() ?: suggestedMg
                onConfirm(site, mg, notes.trim())
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
