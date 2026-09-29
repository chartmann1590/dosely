package com.dosely.app.ui.weight

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.MonitorWeight
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
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.domain.Units
import com.dosely.app.translate.S
import com.dosely.app.ui.components.EmptyState
import com.dosely.app.ui.components.StatTile
import com.dosely.app.ui.theme.Coral
import com.dosely.app.ui.theme.MintStrong
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun WeightScreen(viewModel: WeightViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showLogDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(S("weight_title"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                S("home_weight_current"),
                ui.currentKg?.let { Units.format(it, ui.useImperial) } ?: "—",
                Modifier.weight(1f),
            )
            StatTile(
                S("weight_total_change"),
                Units.formatChange(ui.changeKg, ui.useImperial),
                Modifier.weight(1f),
                accent = if ((ui.changeKg ?: 0.0) <= 0) MintStrong else Coral,
            )
            StatTile(
                S("home_weight_goal"),
                ui.goalKg?.let { Units.format(it, ui.useImperial) } ?: "—",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                S("weight_week_avg"),
                ui.weekAvgKg?.let { Units.format(it, ui.useImperial) } ?: "—",
                Modifier.weight(1f),
            )
            StatTile(
                S("ins_avg_weekly"),
                if (ui.entries.size >= 2 && ui.changeKg != null) {
                    val change = ui.changeKg
                    val span = (ui.entries.last().epochDay - ui.entries.first().epochDay).coerceAtLeast(1)
                    if (change != null) Units.formatChange(change * 7.0 / span, ui.useImperial) else "—"
                } else "—",
                Modifier.weight(1f),
                accent = MintStrong,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (ui.toGoalKg != null) {
            val toGoal = ui.toGoalKg ?: 0.0
            Text(
                if (toGoal <= 0) S("weight_goal_reached")
                else S("weight_to_goal", Units.format(toGoal, ui.useImperial)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (toGoal <= 0) MintStrong else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { showLogDialog = true }) { Text(S("weight_log_new")) }
        Spacer(Modifier.height(16.dp))

        if (ui.entries.isEmpty()) {
            EmptyState(Icons.Outlined.MonitorWeight, S("weight_title"), S("weight_empty"))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.entries.reversed(), key = { it.id }) { entry ->
                    WeightRow(
                        entry = entry,
                        startKg = if (ui.startKg > 0) ui.startKg else (ui.entries.firstOrNull()?.grams ?: 0) / 1000.0,
                        imperial = ui.useImperial,
                        onDelete = { viewModel.delete(entry.id) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showLogDialog) {
        LogWeightDialog(
            imperial = ui.useImperial,
            onDismiss = { showLogDialog = false },
            onConfirm = { kg ->
                viewModel.logWeight(kg)
                showLogDialog = false
            },
        )
    }
}

@Composable
private fun WeightRow(entry: WeightEntryEntity, startKg: Double, imperial: Boolean, onDelete: () -> Unit) {
    val kg = entry.grams / 1000.0
    val date = LocalDate.ofEpochDay(entry.epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val change = kg - startKg
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(date, style = MaterialTheme.typography.titleSmall)
                Text(
                    Units.format(kg, imperial),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (change <= 0) MintStrong else Coral,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Units.formatChange(change, imperial),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete, contentDescription = S("common_delete"),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun LogWeightDialog(
    imperial: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S("weight_log_new")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(S("onb_weight_kg") + " (" + Units.label(imperial) + ")") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = {
                text.replace(',', '.').toDoubleOrNull()?.let { onConfirm(Units.toKg(it, imperial)) }
            }) { Text(S("common_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S("doses_cancel")) } },
    )
}
