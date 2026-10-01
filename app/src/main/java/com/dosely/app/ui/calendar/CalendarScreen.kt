package com.dosely.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.domain.Units
import com.dosely.app.translate.S
import com.dosely.app.ui.components.SectionCard
import com.dosely.app.ui.components.StatTile
import com.dosely.app.ui.theme.Coral
import com.dosely.app.ui.theme.MintStrong
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = koinViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(S("cal_title"), style = MaterialTheme.typography.headlineSmall)

        MonthHeader(ui, viewModel)

        SectionCard {
            WeekdayRow()
            Spacer(Modifier.height(6.dp))
            CalendarGrid(ui, viewModel)
            Spacer(Modifier.height(10.dp))
            Legend()
        }

        SelectedDayCard(ui)

        MonthSummary(ui)

        InsightsSection(ui)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MonthHeader(ui: CalendarUi, viewModel: CalendarViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewModel.prevMonth() }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = S("cal_prev_month"))
        }
        Text(
            ui.month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { viewModel.nextMonth() }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = S("cal_next_month"))
        }
    }
}

@Composable
private fun WeekdayRow() {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(Modifier.fillMaxWidth()) {
        days.forEach { d ->
            Text(
                d,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarGrid(ui: CalendarUi, viewModel: CalendarViewModel) {
    Column {
        ui.cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCellView(
                        cell = cell,
                        isSelected = ui.selected == cell.date,
                        onClick = { viewModel.selectDay(cell.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCellView(cell: DayCell, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary
        cell.hasInjection -> MaterialTheme.colorScheme.primaryContainer
        cell.hasSkip -> MaterialTheme.colorScheme.errorContainer
        cell.hasWeighIn -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        cell.hasInjection -> MaterialTheme.colorScheme.onPrimaryContainer
        cell.hasSkip -> MaterialTheme.colorScheme.onErrorContainer
        cell.hasWeighIn -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = cell.inMonth, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (cell.inMonth) fg else fg.copy(alpha = 0.35f),
        )
        if (cell.hasWeighIn && !cell.hasInjection) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(fg),
            )
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(MaterialTheme.colorScheme.primaryContainer, S("cal_legend_injection"))
        LegendDot(MaterialTheme.colorScheme.errorContainer, S("cal_legend_skipped"))
        LegendDot(MaterialTheme.colorScheme.secondaryContainer, S("cal_legend_weighin"))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.padding(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectedDayCard(ui: CalendarUi) {
    SectionCard {
        val date = ui.selected
        if (date == null) {
            Text(
                S("cal_day_none"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        val inj = ui.selectedInjection
        val weight = ui.selectedWeight
        if (inj == null && weight == null) {
            Text(
                S("cal_day_none"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inj != null && !inj.skipped) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("💉", style = MaterialTheme.typography.titleSmall)
                            Text(
                                S("cal_day_injection", "%.2f".format(inj.doseMg).trimEnd('0').trimEnd('.') + " " + S("doses_mg")),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (inj != null && inj.skipped) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("⚠️", style = MaterialTheme.typography.titleSmall)
                            Text(
                                S("cal_day_skipped"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                if (weight != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("⚖️", style = MaterialTheme.typography.titleSmall)
                            Text(
                                S("cal_day_weight", Units.format(weight.grams / 1000.0, ui.useImperial)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSummary(ui: CalendarUi) {
    SectionCard {
        Text(S("cal_month_change"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(S("cal_injections_month", ui.insights.monthInjections), "💉", Modifier.weight(1f))
            StatTile(S("cal_weighins_month", ui.insights.monthWeighIns), "⚖️", Modifier.weight(1f))
            StatTile(
                S("cal_month_change"),
                Units.formatChange(ui.insights.monthChangeKg, ui.useImperial),
                Modifier.weight(1f),
                accent = if ((ui.insights.monthChangeKg ?: 0.0) <= 0) MintStrong else Coral,
            )
        }
    }
}

@Composable
private fun InsightsSection(ui: CalendarUi) {
    SectionCard {
        Text(S("ins_title"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(S("ins_total_doses"), "${ui.insights.totalInjections}", Modifier.weight(1f))
            StatTile(S("ins_skipped"), "${ui.insights.skippedCount}", Modifier.weight(1f),
                accent = if (ui.insights.skippedCount > 0) Coral else MaterialTheme.colorScheme.primary)
            StatTile(S("ins_days_on"), "${ui.insights.daysOnTreatment}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(S("ins_longest_streak"), S("home_streak_days", ui.insights.longestStreak), Modifier.weight(1f))
            StatTile(
                S("ins_avg_weekly"),
                Units.formatChange(ui.insights.avgWeeklyChangeKg, ui.useImperial),
                Modifier.weight(1f),
                accent = if ((ui.insights.avgWeeklyChangeKg ?: 0.0) <= 0) MintStrong else Coral,
            )
        }
        Spacer(Modifier.height(10.dp))
        val proj = ui.insights.projectedGoalDate
        if (proj != null) {
            StatTile(
                S("ins_projected"),
                S("ins_projected_eta", proj.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                Modifier.fillMaxWidth(),
                accent = MintStrong,
            )
        } else {
            Text(S("ins_no_projection"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
