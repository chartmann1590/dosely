package com.dosely.app.ui.doses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.data.db.*
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.domain.Medications
import com.dosely.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun DosePlanCard() {
    val context = LocalContext.current
    val dao = remember { DoselyDb.get(context).journalDao() }
    val plans by remember { dao.observePlans() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<DosePlanEntity?>(null) }
    var status by remember { mutableStateOf("") }
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your treatment plan", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { edit = null; show = true }) { Text("Plan") }
        }
        Text("Record upcoming doses agreed with your clinician.", style = MaterialTheme.typography.bodySmall)
        plans.take(3).forEach { plan ->
            TextButton(onClick = { edit = plan; show = true }, modifier = Modifier.fillMaxWidth()) {
                Text("${LocalDate.ofEpochDay(plan.epochDay)} · ${Medications.byId(plan.medId).brand} · ${plan.doseMg} mg")
            }
        }
        if (plans.size > 3) Text("+ ${plans.size - 3} more planned doses", style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
    if (show) {
        var date by remember(edit) { mutableStateOf(edit?.let { LocalDate.ofEpochDay(it.epochDay).toString() } ?: LocalDate.now().toString()) }
        var dose by remember(edit) { mutableStateOf(edit?.doseMg?.toString().orEmpty()) }
        var notes by remember(edit) { mutableStateOf(edit?.notes.orEmpty()) }
        val day = runCatching { LocalDate.parse(date) }.getOrNull()
        val mg = dose.replace(',', '.').toDoubleOrNull()
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Plan a dose") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Planning does not record a shot as taken or change medication-level estimates.")
                OutlinedTextField(date, { date = it }, label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(dose, { dose = it }, label = { Text("Prescribed dose (mg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes from your treatment plan") })
                if (edit != null) TextButton(onClick = { scope.launch { dao.deletePlan(edit!!.id); show = false } }) { Text("Remove plan") }
                if (plans.size > 3) plans.drop(3).forEach { plan ->
                    TextButton(onClick = { edit = plan }) { Text("Edit ${LocalDate.ofEpochDay(plan.epochDay)} · ${plan.doseMg} mg") }
                }
            }
        }, confirmButton = {
            Button(enabled = day != null && day.year >= 2000 && mg != null && mg.isFinite() && mg > 0 && mg <= 100, onClick = {
                scope.launch {
                    runCatching { dao.savePlan(DosePlanEntity(id = edit?.id ?: java.util.UUID.randomUUID().toString(),
                        epochDay = day!!.toEpochDay(), medId = edit?.medId ?: SettingsRepository(context).current().medId, doseMg = mg!!, notes = notes)) }
                        .onSuccess { show = false; status = "Plan saved. Log the shot separately when taken." }
                        .onFailure { status = "Could not save your plan. Please retry." }
                }
            }) { Text("Save plan") }
        }, dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
    }
}
