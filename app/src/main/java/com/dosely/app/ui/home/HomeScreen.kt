package com.dosely.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.domain.MedicationLevels
import com.dosely.app.domain.Units
import com.dosely.sync.WatchContract
import com.dosely.app.ui.AppViewModel
import com.dosely.app.ui.components.*
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun HomeScreen(
    appViewModel: AppViewModel,
    onNavigateToCoach: (() -> Unit)? = null,
    onNavigateToDoses: (() -> Unit)? = null,
    onNavigateToJournal: () -> Unit = {},
    onNavigateToWeight: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
) {
    val vm: HomeViewModel = koinViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) }
    }
    val level = MedicationLevels.estimate(ui.medId, ui.injections, now)
    val last = ui.injections.firstOrNull { !it.skipped && it.medId == ui.medId }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showQuickShotDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Top Greeting Header
        HomeTopHeader(onNavigateToCalendar)

        if (snackbarMessage.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(snackbarMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    TextButton(onClick = { snackbarMessage = "" }) { Text("OK") }
                }
            }
        }

        // Shotsy-Style Hero Card: Shot Countdown & Routine Hub
        ShotsyHeroCard(
            ui = ui,
            onLogShot = {
                if (ui.takenToday) onNavigateToDoses?.invoke()
                else showQuickShotDialog = true
            },
        )

        // Estimated Medication Level Card (Signature Shotsy feature)
        MedicationLevelCard(
            ui = ui,
            level = level,
            hasInjections = last != null,
            now = now,
        )

        // Weight Journey & Goal Progress Card
        WeightProgressCard(
            ui = ui,
            onViewAndLog = onNavigateToWeight,
            onLogWeight = { showWeightDialog = true },
        )

        // Daily GLP-1 Care & Nutrition (Water, Protein, Appetite)
        DailyVitalsCard(
            waterMl = ui.waterMlToday,
            proteinG = ui.proteinGramsToday,
            onAddWater = { ml ->
                vm.addWater(ml)
                snackbarMessage = "+$ml mL logged! Stay hydrated."
            },
            onAddProtein = { g ->
                vm.addProtein(g)
                snackbarMessage = "+$g g protein added!"
            },
            onOpenJournal = onNavigateToJournal,
        )

        // Injection Site Rotation Card (Shotsy signature body rotation)
        SiteRotationCard(
            lastSite = ui.lastSite,
            nextSite = ui.nextSuggestedSite,
            onSelectSite = { site ->
                vm.logDoseNow(site = site)
                snackbarMessage = "Dose logged at $site!"
            },
        )

        // Quick Symptom Check-in Row
        QuickSymptomCard(
            recentSymptoms = ui.recentSymptoms,
            onLogSymptom = { sym ->
                vm.logQuickSymptom(sym)
                snackbarMessage = "$sym logged in journal."
            },
            onOpenJournal = onNavigateToJournal,
        )

        // On-Device AI Health Coach Shortcut
        AiCoachCard(onOpenCoach = onNavigateToCoach)

        Spacer(Modifier.height(12.dp))
    }

    if (showWeightDialog) {
        QuickWeightDialog(
            imperial = ui.useImperial,
            currentWeightKg = ui.currentWeightKg,
            onDismiss = { showWeightDialog = false },
            onSave = { kg ->
                vm.logWeight((kg * 1000).toInt())
                showWeightDialog = false
                snackbarMessage = "Weight saved!"
            },
        )
    }

    if (showQuickShotDialog) {
        QuickShotDialog(
            medName = ui.medName,
            doseMg = ui.currentDoseMg ?: 0.25,
            suggestedSite = ui.nextSuggestedSite,
            onDismiss = { showQuickShotDialog = false },
            onConfirm = { site, dose ->
                vm.logDoseNow(site = site, doseMg = dose)
                showQuickShotDialog = false
                snackbarMessage = "Shot logged at $site! 🎉"
            },
        )
    }
}

@Composable
private fun HomeTopHeader(onNavigateToCalendar: () -> Unit) {
    val h = LocalTime.now().hour
    val greeting = when {
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(greeting, style = MaterialTheme.typography.headlineMedium)
        }
        FilledTonalIconButton(onClick = onNavigateToCalendar) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Open Calendar")
        }
    }
}

@Composable
private fun ShotsyHeroCard(
    ui: HomeUi,
    onLogShot: () -> Unit,
) {
    val today = LocalDate.now()
    val nextDate = ui.nextDoseDate
    val daysUntil = ui.daysUntilNextDose

    val statusTitle = when {
        ui.takenToday -> "Shot Logged Today! ✨"
        ui.overdue -> "Shot Overdue ⚠️"
        nextDate == today -> "It's Shot Day! 💉"
        daysUntil != null && daysUntil > 0 -> "Shot Day in $daysUntil Days ⏳"
        else -> "Your GLP-1 Routine"
    }

    val subtitle = when {
        ui.takenToday -> "You're all set for this interval."
        ui.overdue -> "Scheduled for ${nextDate?.format(DateTimeFormatter.ofPattern("MMM d"))}. Tap to record your injection."
        nextDate == today -> "Prescribed dose ready. Don't forget to rotate sites."
        nextDate != null -> "Next scheduled · ${nextDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}"
        else -> "Set your injection routine in settings."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0D3D35),
                        Color(0xFF145248),
                        Color(0xFF1A6A5D),
                    ),
                ),
            ),
        ) {
            Column(Modifier.padding(22.dp)) {
                // Med pill & stock chip
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF287D70).copy(alpha = 0.5f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Medication, null, tint = Color(0xFFD2F5EC), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                (ui.medName.ifBlank { "GLP-1" }) + (ui.currentDoseMg?.let { " · ${it} mg" } ?: ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE5FAF4),
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (ui.lowStock || ui.outOfStock) Color(0x66FF8A7A) else Color(0x33FFFFFF),
                    ) {
                        Text(
                            "${ui.pens} pens left",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(statusTitle, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD3EBE4))

                Spacer(Modifier.height(14.dp))
                // Recommended site chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF10463D).copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4ED8B0).copy(alpha = 0.4f)),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🎯 Recommended Site:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9DE0D1))
                        Spacer(Modifier.width(6.dp))
                        Text(ui.nextSuggestedSite, style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }

                Spacer(Modifier.height(16.dp))
                // Weekly day pill timeline (M T W T F S S)
                WeeklyDayTracker(today = today, nextDoseDate = nextDate, takenToday = ui.takenToday)

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onLogShot,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD6F5EB),
                        contentColor = Color(0xFF0D3D35),
                    ),
                ) {
                    Icon(if (ui.takenToday) Icons.Outlined.History else Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ui.takenToday) "View Shot History" else "Log Shot (${ui.currentDoseMg ?: 0.25} mg)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyDayTracker(
    today: LocalDate,
    nextDoseDate: LocalDate?,
    takenToday: Boolean,
) {
    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        (0..6).forEach { offset ->
            val day = startOfWeek.plusDays(offset.toLong())
            val isToday = day == today
            val isShotDay = day == nextDoseDate || (isToday && takenToday)
            val dayInitial = day.dayOfWeek.name.take(1)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    dayInitial,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) Color(0xFF4ED8B0) else Color(0xFFB5DDD2),
                )
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isToday && takenToday -> Color(0xFF4ED8B0)
                                isToday -> Color.White.copy(alpha = 0.25f)
                                isShotDay -> Color(0xFF4ED8B0).copy(alpha = 0.6f)
                                day.isBefore(today) -> Color.White.copy(alpha = 0.1f)
                                else -> Color.White.copy(alpha = 0.05f)
                            },
                        )
                        .then(
                            if (isToday) Modifier.border(1.5.dp, Color(0xFF4ED8B0), CircleShape)
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isShotDay) {
                        Text("💉", style = MaterialTheme.typography.labelSmall)
                    } else if (day.isBefore(today)) {
                        Text("✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9DDDD2))
                    } else {
                        Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationLevelCard(
    ui: HomeUi,
    level: Double?,
    hasInjections: Boolean,
    now: Long,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Estimated in Body", style = MaterialTheme.typography.titleMedium)
                Text("Pharmacokinetic active level", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    if (level != null && level > 0) "Active" else "Awaiting shot",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (hasInjections && level != null) "%.2f mg".format(level) else "Awaiting First Shot",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        if (hasInjections && level != null) {
            Spacer(Modifier.height(8.dp))
            TrendChart(
                values = MedicationLevels.series(ui.medId, ui.injections, now),
                description = "Medication level over past and next 7 days",
                fromZero = true,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("7 days ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("● Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("In 7 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Record your prescribed shot to visualize your estimated medication decay curve over the 7-day cycle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Educational elimination-only estimate based on published half-life. Not a direct blood test. Do not use to alter medical treatment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeightProgressCard(
    ui: HomeUi,
    onViewAndLog: () -> Unit,
    onLogWeight: () -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Weight Journey", style = MaterialTheme.typography.titleMedium)
                Text("Progress towards your goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onViewAndLog) { Text("View & Log") }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(
                label = "Current",
                value = Units.format(ui.currentWeightKg, ui.useImperial),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Total Change",
                value = Units.formatChange(ui.totalChangeKg, ui.useImperial),
                modifier = Modifier.weight(1f),
                accent = if ((ui.totalChangeKg ?: 0.0) <= 0) Color(0xFF1B8268) else MaterialTheme.colorScheme.error,
            )
            StatTile(
                label = "Goal",
                value = Units.format(ui.goalWeightKg, ui.useImperial),
                modifier = Modifier.weight(1f),
            )
        }

        // Goal Progress Bar
        val start = ui.startWeightKg
        val current = ui.currentWeightKg
        val goal = ui.goalWeightKg
        if (start > 0 && current != null && goal != null && start != goal) {
            val progress = ((start - current) / (start - goal)).toFloat().coerceIn(0f, 1f)
            val pct = (progress * 100).toInt()
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Goal progress", style = MaterialTheme.typography.labelMedium)
                Text("$pct% reached", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        if (ui.weights.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            TrendChart(
                values = ui.weights.takeLast(30).map { it.grams / 1000.0 },
                description = "Recent weight measurements",
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onLogWeight,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Outlined.Scale, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Log Today's Weight")
        }
    }
}

@Composable
private fun DailyVitalsCard(
    waterMl: Int,
    proteinG: Int,
    onAddWater: (Int) -> Unit,
    onAddProtein: (Int) -> Unit,
    onOpenJournal: () -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Daily GLP-1 Care", style = MaterialTheme.typography.titleMedium)
                Text("Hydration & lean protein priorities", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenJournal) {
                Icon(Icons.Outlined.ArrowForward, "Open Daily Journal")
            }
        }

        Spacer(Modifier.height(14.dp))
        // Water row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE1F5FE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.LocalDrink, null, tint = Color(0xFF0288D1), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Water", style = MaterialTheme.typography.labelMedium)
                    Text("$waterMl mL / 2,500 mL", style = MaterialTheme.typography.titleMedium, color = Color(0xFF0288D1))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = { onAddWater(250) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("+250 mL", style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { onAddWater(500) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("+500 mL", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // Protein row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Restaurant, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Protein", style = MaterialTheme.typography.labelMedium)
                    Text("$proteinG g / 100 g", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = { onAddProtein(20) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("+20 g", style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { onAddProtein(30) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("+30 g", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SiteRotationCard(
    lastSite: String,
    nextSite: String,
    onSelectSite: (String) -> Unit,
) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Injection Site Rotation", style = MaterialTheme.typography.titleMedium)
                Text("Prevents tissue fatigue & soreness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.RotateRight, null, tint = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        val sites = com.dosely.sync.WatchContract.sites
        sites.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { site ->
                    val isNext = site == nextSite
                    val isLast = site == lastSite
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isNext -> MaterialTheme.colorScheme.primaryContainer
                            isLast -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isNext) MaterialTheme.colorScheme.primary else Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    site,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (isNext) {
                                    Text("🎯 Next", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                } else if (isLast) {
                                    Text("Last", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSymptomCard(
    recentSymptoms: List<String>,
    onLogSymptom: (String) -> Unit,
    onOpenJournal: () -> Unit,
) {
    val commonSymptoms = listOf("Nausea", "Fatigue", "Headache", "Acid Reflux", "Constipation")
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("How are you feeling?", style = MaterialTheme.typography.titleMedium)
                Text("One-tap symptom & side effect logging", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenJournal) { Text("Journal") }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            commonSymptoms.take(3).forEach { sym ->
                FilterChip(
                    selected = recentSymptoms.contains(sym),
                    onClick = { onLogSymptom(sym) },
                    label = { Text(sym, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            commonSymptoms.drop(3).forEach { sym ->
                FilterChip(
                    selected = recentSymptoms.contains(sym),
                    onClick = { onLogSymptom(sym) },
                    label = { Text(sym, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AiCoachCard(onOpenCoach: (() -> Unit)?) {
    OutlinedCard(
        onClick = { onOpenCoach?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Private On-Device AI Coach", style = MaterialTheme.typography.titleMedium)
                Text("Powered by offline Gemma · Ask about foods, tips & hydration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
private fun QuickWeightDialog(
    imperial: Boolean,
    currentWeightKg: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            currentWeightKg?.let { Units.formatValue(it, imperial) } ?: "",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Weight") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' } },
                    label = { Text("Weight in " + Units.label(imperial)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                text.replace(',', '.').toDoubleOrNull()?.let { onSave(Units.toKg(it, imperial)) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QuickShotDialog(
    medName: String,
    doseMg: Double,
    suggestedSite: String,
    onDismiss: () -> Unit,
    onConfirm: (site: String, dose: Double) -> Unit,
) {
    var selectedSite by remember { mutableStateOf(suggestedSite) }
    var dose by remember { mutableDoubleStateOf(doseMg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Shot · $medName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Confirm your dose & injection site for today:", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dose:", style = MaterialTheme.typography.titleMedium)
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$dose mg", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text("Injection Site:", style = MaterialTheme.typography.labelMedium)
                com.dosely.sync.WatchContract.sites.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { site ->
                            FilterChip(
                                selected = selectedSite == site,
                                onClick = { selectedSite = site },
                                label = { Text(site, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedSite, dose) }) { Text("Confirm Shot") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
