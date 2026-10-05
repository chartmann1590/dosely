package com.dosely.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.dosely.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.sync(this)
        setContent {
            val wearColors = Colors(
                primary = Color(0xFF4ED8B0),
                primaryVariant = Color(0xFF289A7B),
                secondary = Color(0xFFFF8A7A),
                secondaryVariant = Color(0xFFD66052),
                background = Color(0xFF05110F),
                surface = Color(0xFF0F2622),
                onPrimary = Color(0xFF051B17),
                onSecondary = Color(0xFF280703),
                onBackground = Color(0xFFE8F5F2),
                onSurface = Color(0xFFE0F0EC),
                onSurfaceVariant = Color(0xFF90B5AC),
            )
            MaterialTheme(colors = wearColors) {
                WatchHome(WatchStore.get(this), onManualSync = { WatchStore.sync(this) }) { event, done ->
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) { runCatching { WatchStore.get(this@WatchActivity).save(event) }.isSuccess }
                        if (saved) WatchStore.sync(this@WatchActivity)
                        done(saved)
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        WatchStore.sync(this)
    }
}

@Composable
private fun WatchHome(
    store: WatchStore,
    onManualSync: () -> Unit,
    save: (WatchEvent, (Boolean) -> Unit) -> Unit,
) {
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val pending by store.pending.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf("home") }

    val defaultDose = snapshot.lastDoseMg ?: snapshot.doses.firstOrNull() ?: 0.25
    var dose by remember(snapshot.lastDoseMg, snapshot.doses) { mutableDoubleStateOf(defaultDose) }

    // Calculate next suggested site from rotation
    val allSites = WatchContract.sites
    val lastSiteIndex = allSites.indexOf(snapshot.lastSite)
    val defaultSiteIndex = if (lastSiteIndex >= 0) (lastSiteIndex + 1) % allSites.size else 0
    var siteIndex by remember(snapshot.lastSite) { mutableIntStateOf(defaultSiteIndex) }

    var weightGrams by remember(snapshot.weightGrams) {
        mutableIntStateOf(snapshot.weightGrams.takeIf { it > 0 } ?: 80_000)
    }
    var statusMessage by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val listState = rememberScalingLazyListState()

    fun submit(event: WatchEvent, successMsg: String = "Saved on watch!") {
        saving = true
        save(event) { ok ->
            saving = false
            if (ok) {
                statusMessage = successMsg
                screen = "success"
            } else {
                statusMessage = "Could not save. Please retry."
            }
        }
    }

    LaunchedEffect(screen) {
        if (screen == "success") {
            delay(1600)
            screen = "home"
        }
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 34.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                screen == "success" -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colors.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✓", style = MaterialTheme.typography.title1, color = MaterialTheme.colors.onPrimary)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(statusMessage, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Text("Syncing with phone…", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
                        }
                    }
                }

                !snapshot.onboarded -> {
                    item { Text("Dosely", style = MaterialTheme.typography.title2, color = MaterialTheme.colors.primary) }
                    item {
                        Text(
                            "Open Dosely on your phone to set up your routine, then open watch to sync.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    item {
                        CompactChip(
                            onClick = onManualSync,
                            label = { Text("Try Sync Now") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        CompactChip(
                            onClick = { store.useDemoSnapshot() },
                            label = { Text("Quick Start") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                screen == "home" -> {
                    // Title & Sync pill
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Dosely", style = MaterialTheme.typography.title3, color = MaterialTheme.colors.primary)
                            Text(
                                if (pending > 0) "⏳ $pending pending" else "● Synced",
                                style = MaterialTheme.typography.caption2,
                                color = if (pending > 0) MaterialTheme.colors.secondary else MaterialTheme.colors.primary,
                            )
                        }
                    }

                    // Next Shot Hero Card
                    item {
                        Card(
                            onClick = { screen = "shot" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                Text(
                                    snapshot.medName.ifBlank { "Next Shot" },
                                    style = MaterialTheme.typography.caption1,
                                    color = MaterialTheme.colors.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    snapshot.nextDose.ifBlank { "Schedule ready" },
                                    style = MaterialTheme.typography.body1,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Next: ${allSites[defaultSiteIndex]}",
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Primary Action: Log Shot
                    item {
                        Chip(
                            onClick = { screen = "shot" },
                            label = { Text("Log Shot (${dose} mg)", fontWeight = FontWeight.Bold) },
                            secondaryLabel = { Text(allSites[siteIndex]) },
                            icon = { Text("💉") },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Quick Water Log (1-Tap!)
                    item {
                        Chip(
                            onClick = {
                                submit(
                                    WatchEvent(
                                        kind = "water",
                                        atMillis = System.currentTimeMillis(),
                                        epochDay = LocalDate.now().toEpochDay(),
                                        waterMl = 250,
                                    ),
                                    successMsg = "+250 mL logged!",
                                )
                            },
                            enabled = !saving,
                            label = { Text("Log Water +250 mL") },
                            secondaryLabel = { Text("Quick tap hydration") },
                            icon = { Text("💧") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Log Weight
                    item {
                        Chip(
                            onClick = { screen = "weight" },
                            label = { Text("Log Weight") },
                            secondaryLabel = {
                                Text(
                                    if (snapshot.imperial) "%.1f lb".format(weightGrams / 453.59237)
                                    else "%.1f kg".format(weightGrams / 1000.0),
                                )
                            },
                            icon = { Text("⚖️") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Force Sync / Status
                    item {
                        CompactChip(
                            onClick = onManualSync,
                            label = { Text("Sync with Phone") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                screen == "shot" -> {
                    item {
                        Text("Record Dose", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                    }
                    item {
                        Text(
                            "%.2f mg".format(dose),
                            style = MaterialTheme.typography.display3,
                            color = MaterialTheme.colors.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    val doses = snapshot.doses
                                    if (doses.isNotEmpty()) {
                                        val idx = doses.indexOf(dose)
                                        dose = doses.getOrElse((idx - 1).coerceAtLeast(0)) { dose }
                                    } else {
                                        dose = (dose - 0.25).coerceAtLeast(0.25)
                                    }
                                },
                            ) { Text("−", fontSize = 18.sp) }
                            Spacer(Modifier.width(16.dp))
                            Button(
                                onClick = {
                                    val doses = snapshot.doses
                                    if (doses.isNotEmpty()) {
                                        val idx = doses.indexOf(dose)
                                        dose = doses.getOrElse((idx + 1).coerceAtMost(doses.lastIndex)) { dose }
                                    } else {
                                        dose = (dose + 0.25).coerceAtMost(30.0)
                                    }
                                },
                            ) { Text("+", fontSize = 18.sp) }
                        }
                    }

                    item {
                        Chip(
                            onClick = { siteIndex = (siteIndex + 1) % allSites.size },
                            label = { Text(allSites[siteIndex]) },
                            secondaryLabel = { Text("Tap to rotate site") },
                            icon = { Text("🎯") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item {
                        Chip(
                            onClick = {
                                submit(
                                    WatchEvent(
                                        kind = "dose",
                                        atMillis = System.currentTimeMillis(),
                                        epochDay = LocalDate.now().toEpochDay(),
                                        medId = snapshot.medId,
                                        doseMg = dose,
                                        site = allSites[siteIndex],
                                    ),
                                    successMsg = "Shot Confirmed! 💉",
                                )
                            },
                            enabled = !saving,
                            label = { Text("Confirm Shot", fontWeight = FontWeight.Bold) },
                            secondaryLabel = { Text("Today · ${allSites[siteIndex]}") },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item {
                        CompactChip(
                            onClick = { screen = "home" },
                            label = { Text("Cancel") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                screen == "weight" -> {
                    item {
                        Text("Log Weight", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                    }
                    item {
                        Text(
                            if (snapshot.imperial) "%.1f lb".format(weightGrams / 453.59237)
                            else "%.1f kg".format(weightGrams / 1000.0),
                            style = MaterialTheme.typography.display3,
                            color = MaterialTheme.colors.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    val step = if (snapshot.imperial) 226 else 100 // 0.5 lb or 0.1 kg
                                    weightGrams = (weightGrams - step).coerceAtLeast(20_000)
                                },
                            ) { Text("−", fontSize = 18.sp) }
                            Spacer(Modifier.width(16.dp))
                            Button(
                                onClick = {
                                    val step = if (snapshot.imperial) 226 else 100
                                    weightGrams = (weightGrams + step).coerceAtMost(500_000)
                                },
                            ) { Text("+", fontSize = 18.sp) }
                        }
                    }

                    item {
                        Chip(
                            onClick = {
                                submit(
                                    WatchEvent(
                                        kind = "weight",
                                        atMillis = System.currentTimeMillis(),
                                        epochDay = LocalDate.now().toEpochDay(),
                                        grams = weightGrams,
                                    ),
                                    successMsg = "Weight Saved!",
                                )
                            },
                            enabled = !saving,
                            label = { Text("Save Weight", fontWeight = FontWeight.Bold) },
                            secondaryLabel = { Text("Today's check-in") },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    item {
                        CompactChip(
                            onClick = { screen = "home" },
                            label = { Text("Cancel") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
