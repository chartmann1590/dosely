package com.dosely.app.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.ui.components.SectionCard
import com.dosely.app.ui.components.TrendChart
import java.time.LocalDate

@Composable
fun JourneyInsights() {
    val context = LocalContext.current
    val db = remember { DoselyDb.get(context) }
    val journal by remember { db.journalDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val shots by remember { db.injectionDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    SectionCard {
        Text("Patterns in your routine", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val since = LocalDate.now().minusDays(29).toEpochDay()
        val recent = journal.filter { it.epochDay >= since }
        val symptoms = recent.filter { it.symptom.isNotBlank() }.groupingBy { it.symptom.lowercase().trim() }.eachCount()
        if (symptoms.isEmpty()) Text("Your symptom patterns will appear as you add check-ins.", style = MaterialTheme.typography.bodyMedium)
        symptoms.entries.sortedByDescending { it.value }.take(5).forEach { (name, count) ->
            Text("$name · $count check-ins in 30 days", style = MaterialTheme.typography.bodyMedium)
        }
        if (recent.any { it.waterMl > 0 }) {
            Spacer(Modifier.height(10.dp))
            Text("Water · last 30 days", style = MaterialTheme.typography.titleSmall)
            TrendChart((0L..29L).map { offset -> recent.filter { it.epochDay == since + offset }.sumOf { it.waterMl }.toDouble() },
                "Daily logged water in milliliters over the last 30 days", fromZero = true)
        }
        val siteCounts = shots.filter { !it.skipped }.groupingBy { it.site }.eachCount()
        if (siteCounts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Injection site history", style = MaterialTheme.typography.titleSmall)
            siteCounts.entries.sortedByDescending { it.value }.forEach { (site, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(site, style = MaterialTheme.typography.bodyMedium)
                    Text("$count shots", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        val doses = shots.filter { !it.skipped }.groupBy { it.medId to it.doseMg }
        if (doses.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Dose history", style = MaterialTheme.typography.titleSmall)
            doses.forEach { (dose, records) -> Text("${dose.first} · ${dose.second} mg · ${records.size} shots", style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Logged patterns do not show that a medication or injection site caused a symptom.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
