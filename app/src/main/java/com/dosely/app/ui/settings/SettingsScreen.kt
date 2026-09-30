package com.dosely.app.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.ai.DownloadState
import com.dosely.app.ai.GemmaModelCatalog
import com.dosely.app.data.feedback.BugReport
import com.dosely.app.translate.AppLanguages
import com.dosely.app.translate.S
import com.dosely.app.ui.components.Chip
import com.dosely.app.ui.components.SectionCard
import com.dosely.app.ui.feedback.BugReportList
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel(), onOpenLegal: (privacy: Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val uiState by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedReport by remember { mutableStateOf<BugReport?>(null) }

    val feedbackViewModel: com.dosely.app.ui.feedback.FeedbackViewModel = koinViewModel()
    val feedbackUiState by feedbackViewModel.reportState.collectAsStateWithLifecycle()
    val feedbackDetailsState by feedbackViewModel.detailsState.collectAsStateWithLifecycle()
    val bugReports by feedbackViewModel.bugReports.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(S("settings_title"), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
        }
        LazyColumn(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard {
                    SettingsRow(
                        title = S("settings_language"),
                        subtitle = S("settings_language_sub"),
                        onClick = { showLanguagePicker = true },
                        value = languageLabel(settings?.appLanguageTag ?: ""),
                    )
                }
            }

            item {
                SectionCard {
                    Text(S("settings_medication"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.dosely.app.domain.Medications.all.take(3).forEach { med ->
                            Chip(
                                text = med.brand,
                                selected = settings?.medId == med.id,
                                onClick = { viewModel.setMed(med.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.dosely.app.domain.Medications.all.drop(3).forEach { med ->
                            Chip(
                                text = med.brand,
                                selected = settings?.medId == med.id,
                                onClick = { viewModel.setMed(med.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(S("settings_interval"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 28).forEach { d ->
                            Chip(
                                text = "$d " + S("onb_dose_days"),
                                selected = settings?.intervalDays == d,
                                onClick = { viewModel.setInterval(d) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(S("settings_rem_time"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("07:00" to (7 to 0), "09:00" to (9 to 0), "19:00" to (19 to 0), "21:00" to (21 to 0)).forEach { (label, hm) ->
                            Chip(
                                text = label,
                                selected = settings?.reminderHour == hm.first && settings?.reminderMinute == hm.second,
                                onClick = { viewModel.setReminderTime(hm.first, hm.second) },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(S("settings_stock"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(S("settings_stock_pens"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 2, 3, 4, 5).forEach { n ->
                            Chip(
                                text = "$n",
                                selected = settings?.pensOnHand == n,
                                onClick = { viewModel.setPens(n) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(S("settings_stock_threshold"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { n ->
                            Chip(
                                text = "$n",
                                selected = settings?.lowStockThreshold == n,
                                onClick = { viewModel.setLowStockThreshold(n) },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(S("settings_reminders"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = S("settings_reminders_enable"),
                        checked = settings?.remindersEnabled ?: true,
                        onChecked = { viewModel.setRemindersEnabled(it) },
                    )
                    SwitchRow(
                        title = S("settings_refill_enable"),
                        checked = settings?.refillRemindersEnabled ?: true,
                        onChecked = { viewModel.setRefillEnabled(it) },
                    )
                    SwitchRow(
                        title = S("settings_weekly_enable"),
                        checked = settings?.weeklyWeighInEnabled ?: true,
                        onChecked = { viewModel.setWeeklyEnabled(it) },
                    )
                    if (settings?.remindersEnabled == true && !NotificationsEnabled(context)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            S("settings_notifications_blocked"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { OpenAppSettings(context) }) {
                            Text(S("settings_open_system_settings"))
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(S("settings_ai_model"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S("settings_ai_sub"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    when (val dl = uiState.aiState) {
                        is DownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { dl.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                S("onb_ai_downloading", GemmaModelCatalog.humanSize(dl.downloadedBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        DownloadState.Done -> {
                            Text(
                                S("settings_ai_ready", GemmaModelCatalog.default.displayName),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(onClick = { viewModel.deleteModel() }) {
                                Text(S("settings_ai_delete"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> {
                            if (uiState.aiOnDeviceBytes > 0) {
                                Text(
                                    S("settings_ai_size_on_device", GemmaModelCatalog.humanSize(uiState.aiOnDeviceBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = { viewModel.startModelDownload() }) {
                                Text(S("settings_ai_download", GemmaModelCatalog.humanSize(GemmaModelCatalog.default.sizeBytes)))
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Text(S("settings_units"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        S("settings_units_sub"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            text = S("settings_units_metric"),
                            selected = settings?.useImperial == false,
                            onClick = { viewModel.setImperial(false) },
                        )
                        Chip(
                            text = S("settings_units_imperial"),
                            selected = settings?.useImperial == true,
                            onClick = { viewModel.setImperial(true) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(S("settings_theme"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            text = S("settings_theme_system"),
                            selected = settings?.themeMode == "system",
                            onClick = { viewModel.setTheme("system") },
                        )
                        Chip(
                            text = S("settings_theme_light"),
            selected = settings?.themeMode == "light",
                            onClick = { viewModel.setTheme("light") },
                        )
                        Chip(
                            text = S("settings_theme_dark"),
                            selected = settings?.themeMode == "dark",
                            onClick = { viewModel.setTheme("dark") },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(S("settings_add_widget"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        S("settings_add_widget_sub"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.requestPinWidget(context) }) {
                        Text(S("settings_add_widget"))
                    }
                }
            }

            item {
                SectionCard {
                    Text(S("settings_about"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S("settings_privacy"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showDisclaimer = true }) {
                        Text(S("settings_disclaimer"))
                    }
                    if (uiState.privacyOptionsRequired) {
                        TextButton(onClick = { viewModel.showPrivacyOptions(context) }) {
                            Text(S("settings_privacy_options"))
                        }
                        Text(
                            S("settings_privacy_options_sub"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpenLegal(false) }) { Text(S("settings_tos")) }
                    TextButton(onClick = { onOpenLegal(true) }) { Text(S("settings_privacy_policy")) }
                }
            }

            // Support & Feedback: GitHub-backed bug reports via Cloudflare Worker
            item {
                SectionCard {
                    Text(S("feedback_support_title"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S("feedback_support_sub"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { showReportDialog = true }) {
                        Text(S("feedback_report_button"))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (bugReports.isNotEmpty()) {
                        Text(S("feedback_reports_heading"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        BugReportList(reports = bugReports, onOpenReport = { report ->
                            selectedReport = report
                            // The row click owns the initial load; the dialog
                            // itself never self-loads on composition, so the
                            // details can only ever be fetched once per open.
                            feedbackViewModel.refreshIssueDetails(report.number)
                        })
                    }
                }
            }

            // Cross-promotion: other Hartmann Studios apps, discovered dynamically
            // from the backend. Renders nothing when offline/empty/error.
            item {
                com.hartmann.crosspromo.ui.HartmannPromoRow(placement = "settings")
            }

            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    if (showReportDialog) {
        com.dosely.app.ui.feedback.ReportProblemDialog(
            state = feedbackUiState,
            onSubmit = { title, description, includeDiagnostics, name, email, attachmentUri ->
                feedbackViewModel.submitReport(
                    appContext = context.applicationContext,
                    title = title,
                    description = description,
                    includeDiagnostics = includeDiagnostics,
                    name = name,
                    email = email,
                    attachmentUri = attachmentUri,
                    onSuccess = { },
                )
            },
            onDismiss = {
                showReportDialog = false
                feedbackViewModel.resetSubmitState()
            },
        )
    }

    selectedReport?.let { report ->
        com.dosely.app.ui.feedback.IssueDetailsDialog(
            report = report,
            state = feedbackDetailsState,
            onRefresh = { feedbackViewModel.refreshIssueDetails(report.number) },
            onReply = { text, attachmentUri, onCompleted ->
                feedbackViewModel.submitReply(context.applicationContext, report.number, text, attachmentUri, onCompleted)
            },
            onReplySucceeded = { feedbackViewModel.resetReplyState() },
            onDismiss = {
                selectedReport = null
                feedbackViewModel.resetReplyState()
            },
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentTag = settings?.appLanguageTag ?: "",
            busyTag = uiState.langBusyTag,
            error = uiState.langError,
            downloaded = uiState.downloadedPacks,
            onSelect = { viewModel.selectLanguage(it) },
            onDelete = { viewModel.removeLanguagePack(it) },
            onDismiss = { showLanguagePicker = false },
        )
    }
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(S("settings_disclaimer")) },
            text = { Text(S("settings_disclaimer_body")) },
            confirmButton = {
                TextButton(onClick = { showDisclaimer = false }) { Text(S("settings_done")) }
            },
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun languageLabel(tag: String): String {
    if (tag.isEmpty()) return "System"
    return AppLanguages.byTag(tag)?.englishName ?: tag
}

@Composable
private fun NotificationsEnabled(context: android.content.Context): Boolean {
    return com.dosely.app.reminder.Notifications.canNotify(context)
}

private fun OpenAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(intent)
}

@Composable
private fun LanguagePickerDialog(
    currentTag: String,
    busyTag: String?,
    error: String?,
    downloaded: List<String>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S("settings_language")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(S("settings_lang_search")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (error != null) {
                    Text(
                        S("common_error") + ": " + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        AppLanguages.all.filter {
                            query.isEmpty() || it.englishName.contains(query, ignoreCase = true) ||
                                it.tag.contains(query, ignoreCase = true)
                        },
                        key = { it.tag },
                    ) { lang ->
                        val selected = currentTag == lang.tag
                        val busy = busyTag == lang.tag
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                )
                                .clickable(enabled = busyTag == null) { onSelect(lang.tag) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(lang.englishName, style = MaterialTheme.typography.bodyLarge)
                                if (downloaded.contains(lang.tag) && !selected) {
                                    Text(
                                        S("settings_lang_delete_pack"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (busy) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(lang.tag) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = S("settings_lang_delete_pack"),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(S("settings_done")) }
        },
    )
}
