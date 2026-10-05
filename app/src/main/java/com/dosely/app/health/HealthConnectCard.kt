package com.dosely.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.ui.components.SectionCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { com.dosely.app.ui.theme.DoselyTheme {
            com.dosely.app.ui.legal.LegalScreen(isPrivacy = true, onBack = { finish() })
        } }
    }
}

@Composable
fun HealthConnectCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = remember { setOf(HealthPermission.getReadPermission(WeightRecord::class)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    suspend fun import() {
        busy = true
        try {
            val client = HealthConnectClient.getOrCreate(context)
            if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                status = "Weight access was not granted. You can keep logging manually."
                return
            }
            val dao = DoselyDb.get(context).weightDao()
            val existing = dao.observeAllAsc().first().associateBy { it.epochDay }.toMutableMap()
            var token: String? = null
            var count = 0
            val end = Instant.now()
            do {
                val response = client.readRecords(ReadRecordsRequest(WeightRecord::class,
                    TimeRangeFilter.between(end.minus(30, ChronoUnit.DAYS), end), pageToken = token))
                response.records.sortedBy { it.time }.forEach { record ->
                    val grams = (record.weight.inKilograms * 1000).toInt()
                    val day = record.time.atZone(record.zoneOffset ?: ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    val previous = existing[day]
                    if (grams in 20_000..500_000 && (previous == null || previous.loggedAtMillis < record.time.toEpochMilli())) {
                        val entry = WeightEntryEntity(epochDay = day, grams = grams, loggedAtMillis = record.time.toEpochMilli())
                        dao.upsert(entry)
                        existing[day] = entry
                        count++
                    }
                }
                token = response.pageToken
            } while (token != null)
            status = "Imported $count weigh-ins from the last 30 days."
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = "Could not import weight. Check Health Connect access and try again."
        } finally { busy = false }
    }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(permissions)) scope.launch { import() }
        else status = "Weight access wasn't granted. Manual logging is always available."
    }
    SectionCard {
        Text("Bring your weigh-ins together", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Import weight from Health Connect. Only newer measurements for each day are added. Health data is never used for advertising.", style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !busy, onClick = {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                status = "Install or update Health Connect on this phone to import your weight."
            } else scope.launch {
                runCatching {
                    val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
                    if (granted.containsAll(permissions)) import() else request.launch(permissions)
                }.onFailure { status = "Health Connect could not be opened." }
            }
        }) { Text(if (busy) "Importing…" else "Import from Health Connect") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
