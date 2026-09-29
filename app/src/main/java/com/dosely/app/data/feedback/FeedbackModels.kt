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
)

@Serializable
data class PostCommentRequest(
    val body: String,
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
