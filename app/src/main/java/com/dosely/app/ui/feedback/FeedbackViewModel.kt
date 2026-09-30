package com.dosely.app.ui.feedback

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.feedback.BugReport
import com.dosely.app.data.feedback.BugReportRepo
import com.dosely.app.data.feedback.CreateIssueWithAssetRequest
import com.dosely.app.data.feedback.DiagnosticsHelper
import com.dosely.app.data.feedback.FeedbackComment
import com.dosely.app.data.feedback.FeedbackIssue
import com.dosely.app.data.feedback.FeedbackWorkerApi
import com.dosely.app.data.feedback.ImageHelper
import com.dosely.app.data.feedback.ImageAttachmentException
import com.dosely.app.data.feedback.PostCommentWithAssetRequest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Support & Feedback feature.
 *
 * Submission flow:
 *   1. Validate form
 *   2. If screenshot: upload via worker /api/assets -> worker uploads to GitHub feedback-assets/
 *   3. Build Markdown issue body
 *   4. POST /api/issues (worker creates a REAL GitHub issue)
 *   5. Persist the report locally (DataStore)
 */
class FeedbackViewModel(
    private val api: FeedbackWorkerApi,
    private val bugReportRepo: BugReportRepo,
) : ViewModel() {

    companion object {
        private val ATTACHMENT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    // ------------------------------------------------------------------ state

    data class ReportUiState(
        val submitState: SubmitState = SubmitState.Idle,
        val reports: List<BugReport> = emptyList(),
        val workerConfigured: Boolean = FeedbackWorkerApi.isConfigured(),
    )

    sealed class SubmitState {
        object Idle : SubmitState()
        data class UploadingAttachment(val progressLabel: String = "Uploading screenshot…") : SubmitState()
        object Submitting : SubmitState()
        data class Success(val issue: FeedbackIssue) : SubmitState()
        data class Failure(val message: String) : SubmitState()
    }

    data class DetailsUiState(
        val loading: Boolean = true,
        val issue: FeedbackIssue? = null,
        val comments: List<FeedbackComment> = emptyList(),
        val error: String? = null,
        val replyState: ReplyState = ReplyState.Idle,
    )

    sealed class ReplyState {
        object Idle : ReplyState()
        object Submitting : ReplyState()
        data class Success(val message: String) : ReplyState()
        data class Failure(val message: String) : ReplyState()
    }

    private val _reportState = MutableStateFlow(ReportUiState())
    val reportState: StateFlow<ReportUiState> = _reportState

    private val _detailsState = MutableStateFlow(DetailsUiState())
    val detailsState: StateFlow<DetailsUiState> = _detailsState

    /** Most recent issue-details load; cancelled when a newer one starts. */
    private var detailsLoadJob: Job? = null

    val bugReports: StateFlow<List<BugReport>> = bugReportRepo.bugReports
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            bugReports.collect { reports ->
                _reportState.value = _reportState.value.copy(reports = reports)
            }
        }
    }

    // ------------------------------------------------------- report submission

    /**
     * Submits a bug report. Returns the created issue on success so the UI can
     * link to it; sets [SubmitState] for the dialog to observe.
     */
    fun submitReport(
        appContext: Context,
        title: String,
        description: String,
        includeDiagnostics: Boolean,
        name: String?,
        email: String?,
        attachmentUri: Uri?,
        onSuccess: (FeedbackIssue) -> Unit,
    ) {
        if (!FeedbackWorkerApi.isConfigured()) {
            _reportState.value = _reportState.value.copy(
                submitState = SubmitState.Failure("Feedback service is not configured for this build."),
            )
            return
        }
        if (title.isBlank() || description.isBlank()) {
            _reportState.value = _reportState.value.copy(
                submitState = SubmitState.Failure("Please fill in a title and description."),
            )
            return
        }
        if (_reportState.value.submitState is SubmitState.Submitting ||
            _reportState.value.submitState is SubmitState.UploadingAttachment
        ) {
            return // already in-flight; prevent double submission
        }

        _reportState.value = _reportState.value.copy(submitState = SubmitState.UploadingAttachment())
        viewModelScope.launch {
            try {
                // Read/encode the image locally only; the upload happens
                // worker-side as part of the issue-creation operation, so a
                // failed submission can never orphan an uploaded screenshot.
                val attachmentBase64 = attachmentUri?.let { uri ->
                    withContext(Dispatchers.IO) { ImageHelper.uriToBase64(appContext, uri) }
                }

                val body = buildString {
                    appendLine("## Description")
                    appendLine()
                    appendLine(description.trim())
                    appendLine()
                    appendLine("## Contact Info")
                    appendLine()
                    appendLine("- Name: ${name?.trim()?.takeIf { it.isNotEmpty() } ?: "Not provided"}")
                    appendLine("- Email: ${email?.trim()?.takeIf { it.isNotEmpty() } ?: "Not provided"}")
                    if (includeDiagnostics) {
                        appendLine()
                        append(DiagnosticsHelper.collect(appContext))
                    }
                }

                _reportState.value = _reportState.value.copy(submitState = SubmitState.Submitting)
                val issue = api.createIssueWithAsset(
                    CreateIssueWithAssetRequest(
                        title = "[Feedback] ${title.trim()}",
                        body = body,
                        attachmentFileName = attachmentBase64?.let { generateAttachmentFileName("issue") },
                        attachmentContentBase64 = attachmentBase64,
                    ),
                )

                bugReportRepo.saveBugReport(
                    BugReport(
                        number = issue.number,
                        title = issue.title,
                        status = issue.state,
                        createdAt = issue.createdAt,
                        htmlUrl = issue.htmlUrl,
                    ),
                )
                _reportState.value = _reportState.value.copy(submitState = SubmitState.Success(issue))
                onSuccess(issue)
            } catch (e: ImageAttachmentException) {
                _reportState.value = _reportState.value.copy(
                    submitState = SubmitState.Failure(e.message ?: "Could not read the selected image."),
                )
            } catch (e: FeedbackWorkerApi.ApiException) {
                _reportState.value = _reportState.value.copy(
                    submitState = SubmitState.Failure(e.message ?: "Submission failed."),
                )
            } catch (e: FeedbackWorkerApi.NetworkException) {
                _reportState.value = _reportState.value.copy(
                    submitState = SubmitState.Failure("Could not reach the feedback service. Check your connection."),
                )
            } catch (e: Exception) {
                _reportState.value = _reportState.value.copy(
                    submitState = SubmitState.Failure("Submission failed: ${e.message ?: "unknown error"}"),
                )
            }
        }
    }

    fun resetSubmitState() {
        _reportState.value = _reportState.value.copy(submitState = SubmitState.Idle)
    }

    private fun generateAttachmentFileName(prefix: String): String =
        "$prefix-${LocalDateTime.now().format(ATTACHMENT_TIMESTAMP)}-${UUID.randomUUID().toString().take(6)}.png"

    // ------------------------------------------------------- details + replies

    /**
     * Loads (or refreshes) the issue + comments.
     *
     * Guarantees (each one fixed a real review finding):
     *  - A reply in [ReplyState.Submitting] is never reset by a refresh, so
     *    the in-flight guard in [submitReply] cannot be bypassed into a
     *    duplicate post while an upload or POST is still running.
     *  - Only the most recent load publishes: a slower response for an
     *    earlier issue can never overwrite the state of the issue the user is
     *    actually viewing (previous loads are cancelled via [detailsLoadJob]).
     *  - Callers that must not lose a pending reply outcome (see
     *    [submitReply]) clear the draft via their success callback BEFORE
     *    refreshing, because back-to-back writes to a StateFlow conflate and
     *    the UI would never observe the intermediate Success state.
     */
    fun loadIssueDetails(number: Int) {
        val sameIssue = _detailsState.value.issue?.number == number
        _detailsState.value = if (sameIssue) {
            // Same issue: keep everything (including an in-flight reply state)
            // and just show the spinner while comments refresh.
            _detailsState.value.copy(loading = true)
        } else {
            // Switching issues: full reset (reply state must not leak across).
            DetailsUiState(loading = true)
        }
        detailsLoadJob?.cancel()
        detailsLoadJob = viewModelScope.launch {
            try {
                val issue = api.getIssue(number)
                val comments = api.getComments(number)
                // Stale-response guard: a newer load (this one was cancelled
                // when it started) must never publish — otherwise issue A's
                // body/comments could render under issue B's title while its
                // reply action still posts to B.
                coroutineContext.ensureActive()
                // Sync local cached status with GitHub state.
                bugReportRepo.saveBugReport(
                    BugReport(
                        number = issue.number,
                        title = issue.title,
                        status = issue.state,
                        createdAt = issue.createdAt,
                        htmlUrl = issue.htmlUrl,
                    ),
                )
                _detailsState.value = _detailsState.value.copy(
                    loading = false,
                    issue = issue,
                    comments = comments,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: FeedbackWorkerApi.ApiException) {
                _detailsState.value = _detailsState.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load the issue.",
                )
            } catch (e: FeedbackWorkerApi.NetworkException) {
                _detailsState.value = _detailsState.value.copy(
                    loading = false,
                    error = "Could not reach the feedback service.",
                )
            } catch (e: Exception) {
                _detailsState.value = _detailsState.value.copy(
                    loading = false,
                    error = "Could not load the issue: ${e.message ?: "unknown error"}",
                )
            }
        }
    }

    fun refreshIssueDetails(number: Int) = loadIssueDetails(number)

    fun submitReply(appContext: Context, issueNumber: Int, replyText: String, attachmentUri: Uri?, onDone: () -> Unit) {
        if (replyText.isBlank()) {
            _detailsState.value = _detailsState.value.copy(
                replyState = ReplyState.Failure("Reply cannot be empty."),
            )
            return
        }
        if (_detailsState.value.replyState is ReplyState.Submitting) return

        viewModelScope.launch {
            _detailsState.value = _detailsState.value.copy(replyState = ReplyState.Submitting)
            try {
                // The attachment is uploaded worker-side as part of the comment
                // operation, so a failure can never orphan an uploaded file.
                val attachmentBase64 = attachmentUri?.let { uri ->
                    withContext(Dispatchers.IO) { ImageHelper.uriToBase64(appContext, uri) }
                }

                val commentBody = buildString {
                    appendLine("## Reply")
                    appendLine()
                    appendLine(replyText.trim())
                }

                api.postCommentWithAsset(
                    issueNumber,
                    PostCommentWithAssetRequest(
                        body = commentBody,
                        attachmentFileName = attachmentBase64?.let { generateAttachmentFileName("comment-$issueNumber") },
                        attachmentContentBase64 = attachmentBase64,
                    ),
                )
                _detailsState.value = _detailsState.value.copy(replyState = ReplyState.Success("Reply posted."))
                // Clear the draft via the success callback BEFORE refreshing:
                // onDone runs synchronously on the main thread, so it cannot be
                // conflated away the way an observed Success state would be if
                // loadIssueDetails reset replyState first. The refresh that
                // follows swaps in the comment list with the draft already
                // cleared, closing the accidental-duplicate-post window.
                onDone()
                // Only refresh when this issue is still the one displayed: if
                // the user dismissed report A and opened report B meanwhile,
                // refreshing A here would cancel B's load and replace B's
                // state with A's data.
                if (_detailsState.value.issue?.number == issueNumber) {
                    loadIssueDetails(issueNumber)
                }
            } catch (e: ImageAttachmentException) {
                _detailsState.value = _detailsState.value.copy(
                    replyState = ReplyState.Failure("Attachment upload failed: ${e.message}"),
                )
            } catch (e: FeedbackWorkerApi.ApiException) {
                _detailsState.value = _detailsState.value.copy(
                    replyState = ReplyState.Failure(e.message ?: "Could not post the reply."),
                )
            } catch (e: FeedbackWorkerApi.NetworkException) {
                _detailsState.value = _detailsState.value.copy(
                    replyState = ReplyState.Failure("Could not reach the feedback service. Check your connection."),
                )
            } catch (e: Exception) {
                _detailsState.value = _detailsState.value.copy(
                    replyState = ReplyState.Failure("Could not post the reply: ${e.message ?: "unknown error"}"),
                )
            }
        }
    }

    fun resetReplyState() {
        _detailsState.value = _detailsState.value.copy(replyState = ReplyState.Idle)
    }
}
