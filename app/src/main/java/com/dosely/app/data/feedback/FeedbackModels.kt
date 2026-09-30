package com.dosely.app.data.feedback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A locally-tracked submitted feedback report, backed by a real GitHub issue. */
@Serializable
data class BugReport(
    val number: Int,
    val title: String,
    val status: String,
    val createdAt: String,
    val htmlUrl: String,
)

// ---------------------------------------------------------------------------
// Worker API models (normalized by the Cloudflare Worker)
// ---------------------------------------------------------------------------

@Serializable
data class CreateIssueRequest(
    val title: String,
    val body: String,
)

@Serializable
data class FeedbackIssue(
    val number: Int,
    val title: String,
    val state: String,
    @SerialName("htmlUrl") val htmlUrl: String,
    val createdAt: String,
    val body: String? = null,
    /**
     * True when the worker created this issue but failed to attach the
     * screenshot (the image was deleted, the report content is safe). The
     * submission still counts as successful so a retry cannot duplicate the
     * issue; callers can use this flag to inform the user.
     */
    val attachmentFailed: Boolean = false,
)

@Serializable
data class FeedbackUser(
    val login: String,
)

@Serializable
data class FeedbackComment(
    val id: Long,
    val body: String,
    val createdAt: String,
    val user: FeedbackUser,
    /** See [FeedbackIssue.attachmentFailed]. */
    val attachmentFailed: Boolean = false,
)

@Serializable
data class PostCommentRequest(
    val body: String,
)

/**
 * Issue creation with an inline attachment. The worker uploads the image and
 * links it to the new issue as one logical operation, so a failed submission
 * never leaves an orphaned (and potentially sensitive) screenshot in the
 * repository — the client no longer performs a two-step upload+create dance.
 */
@Serializable
data class CreateIssueWithAssetRequest(
    val title: String,
    val body: String,
    val attachmentFileName: String? = null,
    @SerialName("attachmentContentBase64") val attachmentContentBase64: String? = null,
)

/** Comment posting with an inline attachment; see [CreateIssueWithAssetRequest]. */
@Serializable
data class PostCommentWithAssetRequest(
    val body: String,
    val attachmentFileName: String? = null,
    @SerialName("attachmentContentBase64") val attachmentContentBase64: String? = null,
)

@Serializable
data class UploadAssetRequest(
    val fileName: String,
    @SerialName("contentBase64") val contentBase64: String,
)

@Serializable
data class UploadAssetResponse(
    val downloadUrl: String? = null,
    val htmlUrl: String? = null,
)

@Serializable
data class ApiError(
    val error: String,
)
