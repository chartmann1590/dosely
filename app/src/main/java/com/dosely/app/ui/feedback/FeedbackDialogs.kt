package com.dosely.app.ui.feedback

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.data.feedback.BugReport
import com.dosely.app.data.feedback.ImageHelper
import com.dosely.app.translate.S
import com.dosely.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Report a Problem dialog
// ---------------------------------------------------------------------------

@Composable
fun ReportProblemDialog(
    state: FeedbackViewModel.ReportUiState,
    onSubmit: (title: String, description: String, includeDiagnostics: Boolean, name: String?, email: String?, attachmentUri: Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attachmentUri = uri
    }

    val submitState = state.submitState
    val submitting = submitState is FeedbackViewModel.SubmitState.Submitting ||
        submitState is FeedbackViewModel.SubmitState.UploadingAttachment

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(S("feedback_report_title")) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!state.workerConfigured) {
                    Text(
                        S("feedback_not_configured"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Privacy warning: prominent, always visible in the report dialog.
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            S("feedback_privacy_warning_1"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Text(S("feedback_privacy_warning_2"), style = MaterialTheme.typography.bodySmall)
                        Text(S("feedback_privacy_warning_3"), style = MaterialTheme.typography.bodySmall)
                        Text(S("feedback_privacy_warning_4"), style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(S("feedback_field_title")) },
                    singleLine = true,
                    enabled = !submitting && state.workerConfigured,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(S("feedback_field_description")) },
                    minLines = 4,
                    enabled = !submitting && state.workerConfigured,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeDiagnostics,
                        onCheckedChange = { includeDiagnostics = it },
                        enabled = !submitting,
                    )
                    Text(S("feedback_include_diagnostics"), style = MaterialTheme.typography.bodyMedium)
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(S("feedback_field_name")) },
                    singleLine = true,
                    enabled = !submitting && state.workerConfigured,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(S("feedback_field_email")) },
                    singleLine = true,
                    enabled = !submitting && state.workerConfigured,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Attachment row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !submitting && state.workerConfigured,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(S("feedback_attach_screenshot"))
                    }
                    if (attachmentUri != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            S("feedback_screenshot_attached"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        IconButton(onClick = { attachmentUri = null }, enabled = !submitting) {
                            Icon(Icons.Filled.Close, contentDescription = S("feedback_remove_attachment"))
                        }
                        // Small preview thumbnail
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = rememberBitmapFromUri(context, attachmentUri!!) ?: return@Box,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (submitState is FeedbackViewModel.SubmitState.UploadingAttachment) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                when (submitState) {
                    is FeedbackViewModel.SubmitState.Failure -> Text(
                        submitState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is FeedbackViewModel.SubmitState.Success -> Text(
                        S("feedback_submitted") + " (#${submitState.issue.number})",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (submitState is FeedbackViewModel.SubmitState.Success) {
                TextButton(onClick = onDismiss) { Text(S("settings_done")) }
            } else {
                Button(
                    onClick = { onSubmit(title, description, includeDiagnostics, name.ifBlank { null }, email.ifBlank { null }, attachmentUri) },
                    enabled = !submitting && state.workerConfigured &&
                        title.isNotBlank() && description.isNotBlank(),
                ) {
                    Text(S("feedback_submit"))
                }
            }
        },
        dismissButton = {
            if (submitState !is FeedbackViewModel.SubmitState.Success) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text(S("common_cancel")) }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Issue details dialog (full-height)
// ---------------------------------------------------------------------------

@Composable
fun IssueDetailsDialog(
    report: BugReport,
    state: FeedbackViewModel.DetailsUiState,
    onRefresh: () -> Unit,
    onReply: (text: String, attachmentUri: Uri?, onCompleted: () -> Unit) -> Unit,
    onReplySucceeded: () -> Unit,
    onDismiss: () -> Unit,
) {
    var replyText by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    // The draft is cleared synchronously from onCompleted (fired by the
    // ViewModel when the reply actually posted, before it refreshes the
    // issue). Observing replyState here instead would be racy: back-to-back
    // StateFlow writes conflate, so Success can be replaced before this
    // composable ever sees it, leaving the posted text in the field.
    // Failures never call onCompleted, so drafts survive for retry.

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attachmentUri = uri
    }

    LaunchedEffect(report.number) { onRefresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#${report.number} — ${report.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
            ) {
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            S("feedback_load_error"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRefresh) { Text(S("feedback_retry")) }
                    }
                    else -> {
                        val issue = state.issue
                        if (issue != null) {
                            IssueMetaRow(issue, report)
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                        }

                        LazyColumn(
                            Modifier
                                .weight(1f, fill = false)
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                if (issue?.body != null) {
                                    Text(
                                        issue.body!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(state.comments, key = { it.id }) { comment ->
                                CommentRow(comment)
                            }
                            if (state.comments.isEmpty()) {
                                item { Text(S("feedback_no_comments"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))

                        // Reply section
                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            label = { Text(S("feedback_reply_hint")) },
                            minLines = 2,
                            enabled = state.replyState !is FeedbackViewModel.ReplyState.Submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                enabled = state.replyState !is FeedbackViewModel.ReplyState.Submitting,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(S("feedback_attach_screenshot"))
                            }
                            if (attachmentUri != null) {
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { attachmentUri = null }) {
                                    Icon(Icons.Filled.Close, contentDescription = S("feedback_remove_attachment"))
                                }
                                Text(S("feedback_screenshot_attached"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onRefresh) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(S("feedback_refresh"))
                            }
                        }
                        when (val rs = state.replyState) {
                            is FeedbackViewModel.ReplyState.Failure -> Text(
                                rs.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            is FeedbackViewModel.ReplyState.Success -> Text(
                                rs.message,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // onCompleted runs synchronously on success: clear the
                    // draft (and reset reply state) BEFORE the ViewModel
                    // refreshes, closing the duplicate-post window.
                    onReply(replyText, attachmentUri) {
                        replyText = ""
                        attachmentUri = null
                        onReplySucceeded()
                    }
                },
                enabled = replyText.isNotBlank() && state.replyState !is FeedbackViewModel.ReplyState.Submitting && state.error == null,
            ) {
                if (state.replyState is FeedbackViewModel.ReplyState.Submitting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(S("feedback_send_reply"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S("common_close")) }
        },
    )
}

@Composable
private fun IssueMetaRow(issue: com.dosely.app.data.feedback.FeedbackIssue, report: BugReport) {
    val open = issue.state == "open"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(50), color = if (open) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }) {
            Text(
                if (open) "OPEN" else "CLOSED",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(issue.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { }, enabled = false) { }
    }
    // GitHub link
    Text(
        issue.htmlUrl,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CommentRow(comment: com.dosely.app.data.feedback.FeedbackComment) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                comment.user.login,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(comment.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(comment.body, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Loads a sampled thumbnail for the attachment preview off the main thread.
 * Never decodes the full-size bitmap, so large photos can't freeze the UI or
 * exhaust memory; returns null while loading or on failure.
 */
@Composable
private fun rememberBitmapFromUri(context: android.content.Context, uri: Uri): ImageBitmap? {
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { ImageHelper.loadThumbnail(context, uri) }.getOrNull()
        }?.asImageBitmap()
    }
    return bitmap
}

/** Renders a list of locally stored reports as rows inside a SectionCard. */
@Composable
fun BugReportList(
    reports: List<BugReport>,
    onOpenReport: (BugReport) -> Unit,
) {
    if (reports.isEmpty()) return
    Column {
        reports.forEach { report ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenReport(report) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(report.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "#${report.number} · " + DateTimeFormatter.ofPattern("MMM d, yyyy")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(report.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val open = report.status == "open"
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (open) "OPEN" else "CLOSED",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (open) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
