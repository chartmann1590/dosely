package com.dosely.app.data.feedback

import com.dosely.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * API client for the Cloudflare feedback worker.
 *
 * The app talks ONLY to this worker — never to GitHub directly — and holds no
 * GitHub credentials. The worker owns the repository routing and the GitHub
 * token (as a Cloudflare secret).
 */
class FeedbackWorkerApi(
    private val baseUrl: String = BuildConfig.FEEDBACK_WORKER_URL,
) {

    companion object {
        /** Returns true when a worker URL is configured for this build. */
        fun isConfigured(url: String = BuildConfig.FEEDBACK_WORKER_URL): Boolean =
            url.startsWith("http")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(Android) {
        expectSuccess = false
        install(DefaultRequest) {
            url(baseUrl.trimEnd('/'))
            header("Accept", "application/json")
            header("User-Agent", "Dosely-Android/${BuildConfig.VERSION_NAME}")
        }
    }

    /** Thrown when the worker returns a non-2xx response. */
    class ApiException(val code: Int, message: String) : Exception(message)

    /** Thrown when the worker could not be reached at all. */
    class NetworkException(cause: Throwable) : Exception("Could not reach the feedback service.", cause)

    private suspend fun execute(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: java.io.IOException) {
            throw NetworkException(e)
        }

    private suspend fun errorOrThrow(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val safeMessage = runCatching {
                response.body<ApiError>().error
            }.getOrNull() ?: "Request failed (${response.status.value})"
            throw ApiException(response.status.value, safeMessage)
        }
    }

    /**
     * Shared headers for every write: the configured API key (when present —
     * the worker enforces it only when its own key secret is set) and the
     * client-generated idempotency key so retried submissions never create
     * duplicate issues or comments.
     */
    private fun HttpRequestBuilder.writeHeaders(idempotencyKey: String?) {
        if (BuildConfig.FEEDBACK_API_KEY.isNotEmpty()) {
            header("X-Api-Key", BuildConfig.FEEDBACK_API_KEY)
        }
        if (idempotencyKey != null) {
            header("X-Idempotency-Key", idempotencyKey)
        }
    }

    suspend fun createIssue(request: CreateIssueRequest): FeedbackIssue {
        val response = execute {
            client.post("api/issues") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(request.idempotencyKey)
                setBody(json.encodeToString(request))
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    suspend fun getIssue(number: Int): FeedbackIssue {
        val response = execute {
            client.get("api/issues/$number") {
                // The worker requires the shared API key on every /api route
                // (GETs included) when FEEDBACK_WORKER_API_KEY is set; without
                // it the deployed worker returns 401 and users can't read
                // reports or reach the reply UI. Writes already send it via
                // writeHeaders for idempotency.
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(null)
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    suspend fun getComments(number: Int): List<FeedbackComment> {
        val response = execute {
            client.get("api/issues/$number/comments") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(null)
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    suspend fun postComment(number: Int, request: PostCommentRequest): FeedbackComment {
        val response = execute {
            client.post("api/issues/$number/comments") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(request.idempotencyKey)
                setBody(json.encodeToString(request))
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    suspend fun uploadAsset(fileName: String, contentBase64: String): UploadAssetResponse {
        val response = execute {
            client.post("api/assets") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(null)
                setBody(json.encodeToString(UploadAssetRequest(fileName, contentBase64)))
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    /**
     * Creates an issue and uploads its attachment as one worker-side logical
     * operation (pass null attachment fields for a text-only report). This
     * replaces the client-side upload-then-create sequence so a failure can
     * never leave an uploaded but unreferenced screenshot behind.
     */
    suspend fun createIssueWithAsset(request: CreateIssueWithAssetRequest): FeedbackIssue {
        val response = execute {
            client.post("api/issues-with-asset") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(request.idempotencyKey)
                setBody(json.encodeToString(request))
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    /** See [createIssueWithAsset]; transactional variant of [postComment]. */
    suspend fun postCommentWithAsset(number: Int, request: PostCommentWithAssetRequest): FeedbackComment {
        val response = execute {
            client.post("api/issues/$number/comments-with-asset") {
                contentType(io.ktor.http.ContentType.Application.Json)
                writeHeaders(request.idempotencyKey)
                setBody(json.encodeToString(request))
            }
        }
        errorOrThrow(response)
        return json.decodeFromString(response.body())
    }

    suspend fun health(): Boolean {
        val response = execute { client.get("health") }
        return response.status == HttpStatusCode.OK
    }

    fun close() = client.close()
}
