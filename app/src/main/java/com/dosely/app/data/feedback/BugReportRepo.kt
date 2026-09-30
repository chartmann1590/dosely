package com.dosely.app.data.feedback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.feedbackDataStore by preferencesDataStore(name = "feedback_bug_reports")

/**
 * Local tracking of submitted feedback reports, backed by DataStore
 * Preferences. The list is stored as JSON under the `bug_reports_list` key.
 */
class BugReportRepo(private val context: Context) {

    companion object {
        private val KEY = stringPreferencesKey("bug_reports_list")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val bugReports: Flow<List<BugReport>> = context.feedbackDataStore.data.map { prefs ->
        prefs[KEY]?.let { decode(it) } ?: emptyList()
    }

    suspend fun getBugReportsList(): List<BugReport> = bugReports.first()

    /** Inserts (by issue number) or updates a report; never duplicates entries. */
    suspend fun saveBugReport(report: BugReport) {
        val current = getBugReportsList().filterNot { it.number == report.number }
        updateBugReports(listOf(report) + current)
    }

    suspend fun updateBugReports(reports: List<BugReport>) {
        val sorted = reports.distinctBy { it.number }.sortedByDescending { it.createdAt }
        context.feedbackDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(sorted)
        }
    }

    private fun decode(raw: String): List<BugReport> = try {
        json.decodeFromString<List<BugReport>>(raw)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}
