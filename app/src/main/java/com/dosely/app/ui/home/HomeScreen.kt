package com.dosely.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.domain.Units
import com.dosely.app.translate.S
import com.dosely.app.ui.AppViewModel
import com.dosely.app.ui.components.HeroCard
import com.dosely.app.ui.components.SectionCard
import com.dosely.app.ui.components.StatTile
import com.dosely.app.ui.theme.Amber
import com.dosely.app.ui.theme.Coral
import com.dosely.app.ui.theme.Mint
import com.dosely.app.ui.theme.MintStrong
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeScreen(appViewModel: AppViewModel) {
    val homeVm: HomeViewModel = koinViewModel()
    val ui by homeVm.ui.collectAsStateWithLifecycle()
    var showWeightDialog by remember { mutableStateOf(false) }
    val imperial = ui.useImperial

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Greeting()
        Text(
            ui.medName.ifEmpty { "Dosely" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Next dose hero
        HeroCard(container = Brush.linearGradient(listOf(Mint, MintStrong))) {
            Text(
                S("home_next_dose"),
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF06281B),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                NextDoseLabel(ui),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF06281B),
            )
            if (ui.overdue) {
                Text(
                    S("home_overdue"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF7A1E12),
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { homeVm.logDoseNow() },
                enabled = !ui.takenToday,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF06281B),
                    contentColor = Color.White,
                ),
            ) {
                Text(S(if (ui.takenToday) "home_taken_today" else "home_take_now"))
            }
        }

        // Stock
        SectionCard {
            Text(S("home_stock_title"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    S("home_stock_pens", ui.pens),
                    StockStatusLabel(ui),
                    Modifier.weight(1f),
                    accent = if (ui.outOfStock) Coral else MaterialTheme.colorScheme.primary,
                )
                if (ui.runsOutOn != null) {
                    val runsOut: LocalDate = ui.runsOutOn ?: return@Row
                    StatTile(
                        StockStatusLabel(ui),
                        runsOut.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        Modifier.weight(1f),
                        accent = if (ui.lowStock) Amber else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                S(if (ui.outOfStock) "home_stock_out" else if (ui.lowStock) "home_stock_low" else "home_stock_ok"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.outOfStock || ui.lowStock) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Weight with goal progress
        SectionCard {
            Row {
                Text(S("home_weight_title"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showWeightDialog = true }) { Text(S("home_log_weight")) }
            }
            if (ui.currentWeightKg == null) {
                Text(
                    S("home_no_weight"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val progress = goalProgress(ui)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        S("home_weight_current"),
                        Units.format(ui.currentWeightKg, imperial),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        S("weight_total_change"),
                        Units.formatChange(ui.totalChangeKg, imperial),
                        Modifier.weight(1f),
                        accent = if ((ui.totalChangeKg ?: 0.0) <= 0) MintStrong else Coral,
                    )
                    if (ui.goalWeightKg != null) {
                        StatTile(
                            S("home_weight_goal"),
                            Units.format(ui.goalWeightKg, imperial),
                            Modifier.weight(1f),
                        )
                    }
                }
                if (progress != null) {
                    Spacer(Modifier.height(14.dp))
                    val animated by animateFloatAsState(progress, tween(700), label = "goal")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(animated)
                                .height(10.dp)
                                .background(
                                    Brush.horizontalGradient(listOf(MintStrong, Mint)),
                                ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (progress >= 1f) S("weight_goal_reached")
                        else S("weight_to_goal", Units.format(ui.toGoalKg, imperial)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Streak / adherence
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                S("home_streak"),
                S("home_streak_days", ui.streak),
                Modifier.weight(1f),
            )
            StatTile(
                S("home_adherence"),
                "${ui.adherencePct}%",
                Modifier.weight(1f),
                accent = MintStrong,
            )
        }

        SectionCard {
            Text(S("home_coach_hint"), style = MaterialTheme.typography.bodyLarge)
        }

        Text(
            S("home_disclaimer_short"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showWeightDialog) {
        LogWeightDialog(
            imperial = imperial,
            onDismiss = { showWeightDialog = false },
            onConfirm = { kg ->
                homeVm.logWeight((kg * 1000).toInt())
                showWeightDialog = false
            },
        )
    }
}

@Composable
private fun Greeting() {
    val h = LocalTime.now().hour
    Text(
        S(
            when {
                h < 12 -> "home_greeting_morning"
                h < 18 -> "home_greeting_afternoon"
                else -> "home_greeting_evening"
            },
        ),
        style = MaterialTheme.typography.headlineMedium,
    )
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
        title = { Text(S("home_log_weight")) },
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

@Composable
private fun NextDoseLabel(ui: HomeUi): String {
    val date = ui.nextDoseDate ?: return "—"
    val today = LocalDate.now()
    return when {
        ui.takenToday -> S("home_taken_today")
        date == today -> S("home_today")
        date == today.plusDays(1) -> S("home_tomorrow")
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

@Composable
private fun StockStatusLabel(ui: HomeUi): String = when {
    ui.outOfStock -> S("home_stock_out")
    ui.lowStock -> S("home_stock_low")
    else -> S("home_stock_ok")
}

private fun goalProgress(ui: HomeUi): Float? {
    val current = ui.currentWeightKg ?: return null
    val goal = ui.goalWeightKg ?: return null
    val start = ui.startWeightKg
    if (start <= 0 || start == goal) return null
    val progress = ((start - current) / (start - goal)).toFloat()
    return progress.coerceIn(0f, 1f)
}

