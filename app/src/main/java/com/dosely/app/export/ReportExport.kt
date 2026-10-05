package com.dosely.app.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dosely.app.data.db.DoselyDb
import com.dosely.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

object ReportExport {
    suspend fun write(context: Context, uri: Uri, pdf: Boolean) = withContext(Dispatchers.IO) {
        val db = DoselyDb.get(context)
        val shots = db.injectionDao().allAsc()
        val weights = db.weightDao().observeAllAsc().first()
        val journal = db.journalDao().observeAll().first()
        val rows = buildList<List<String>> {
            add(listOf("Type", "Date", "Medication / symptom", "Dose mg / severity", "Weight kg", "Site", "Water mL", "Calories", "Protein g", "Appetite /5", "Food noise /5", "Notes"))
            shots.forEach { add(listOf(if (it.skipped) "Skipped" else "Shot", LocalDate.ofEpochDay(it.epochDay).toString(), it.medId, it.doseMg.toString(), "", it.site, "", "", "", "", "", it.notes)) }
            weights.forEach { add(listOf("Weight", LocalDate.ofEpochDay(it.epochDay).toString(), "", "", (it.grams / 1000.0).toString(), "", "", "", "", "", "", "")) }
            journal.forEach { add(listOf("Journal", LocalDate.ofEpochDay(it.epochDay).toString(), it.symptom, it.severity.toString(), "", "", it.waterMl.toString(), it.calories.toString(), it.proteinGrams.toString(), it.appetite.toString(), it.foodNoise.toString(), it.notes)) }
        }
        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Could not open destination" }
        output.use { stream ->
            if (!pdf) stream.writer(Charsets.UTF_8).use { writer ->
                rows.forEach { row -> writer.appendLine(row.joinToString(",") { csvCell(it) }) }
            } else {
                val document = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(25, 47, 42) }
                var pageNumber = 1
                var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                var y = 44f
                fun line(text: String) {
                    if (y > 788f) {
                        document.finishPage(page)
                        pageNumber++
                        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                        y = 44f
                    }
                    page.canvas.drawText(text, 36f, y, paint)
                    y += 16f
                }
                try {
                    paint.textSize = 24f; line("Dosely · Your journey"); paint.textSize = 10f
                    y += 16f
                    line("Exported ${LocalDate.now()} · ${shots.size} shot records · ${weights.size} weigh-ins")
                    line("Personal tracking record. Not medical advice. Photos are not included.")
                    y += 16f
                    rows.drop(1).forEach { row ->
                        line("${row[0]} · ${row[1]}")
                        val details = when (row[0]) {
                            "Shot", "Skipped" -> "${row[2]} · ${row[3]} mg · ${row[5]} · ${row[11]}"
                            "Weight" -> "${row[4]} kg"
                            else -> "${row[2]} (severity ${row[3]}/3) · water ${row[6]} mL · ${row[7]} kcal · protein ${row[8]} g · appetite ${row[9]}/5 · food noise ${row[10]}/5 · ${row[11]}"
                        }
                        var remaining = details.replace("\n", " ")
                        while (remaining.isNotEmpty()) {
                            val count = paint.breakText(remaining, true, 523f, null).coerceAtLeast(1)
                            line(remaining.take(count))
                            remaining = remaining.drop(count)
                        }
                        y += 8f
                    }
                    document.finishPage(page)
                    document.writeTo(stream)
                } finally { document.close() }
            }
        }
    }

    // Neutralize spreadsheet formulas in user notes, then escape RFC 4180 cells.
    fun csvCell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}

@Composable
fun ExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun export(uri: Uri?, pdf: Boolean) {
        if (uri == null) return
        busy = true
        scope.launch {
            status = runCatching { ReportExport.write(context, uri, pdf) }.fold({ "Report saved." }, { "Could not save the report. Try another location." })
            busy = false
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { export(it, true) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    SectionCard {
        Text("Ready for your next appointment", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Export shots, weight, and check-ins to keep or share with your care team.", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(enabled = !busy, onClick = { pdf.launch("Dosely-report-${LocalDate.now()}.pdf") }) { Text("Save PDF") }
            TextButton(enabled = !busy, onClick = { csv.launch("Dosely-history-${LocalDate.now()}.csv") }) { Text("Export CSV") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
