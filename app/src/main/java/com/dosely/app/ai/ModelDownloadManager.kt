package com.dosely.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Tracks download state for the AI model. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState {
        val fraction: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Downloads the .litertlm model into app-private external storage with HTTP Range
 * resume support, so a killed download continues where it stopped.
 */
class ModelDownloadManager(private val context: Context) {

    fun modelDir(): File = (
        context.getExternalFilesDir(null)?.let { File(it, "models") }
            ?: File(context.filesDir, "models")
        ).apply { if (!exists()) mkdirs() }

    fun modelFile(option: ModelOption): File = File(modelDir(), option.fileName)

    fun isDownloaded(option: ModelOption): Boolean {
        val f = modelFile(option)
        return f.exists() && f.length() >= option.sizeBytes
    }

    fun onDeviceBytes(option: ModelOption): Long {
        val f = modelFile(option)
        return if (f.exists()) f.length() else 0L
    }

    fun download(option: ModelOption): Flow<DownloadState> = callbackFlow {
        val file = modelFile(option)
        val partFile = File(modelDir(), option.fileName + ".part")
        try {
            trySend(DownloadState.Downloading(partFile.length(), option.sizeBytes))
            var attempt = 0
            var success = false
            while (attempt < 5 && !success) {
                attempt++
                val start = if (partFile.exists()) partFile.length() else 0L
                var connection: HttpURLConnection? = null
                try {
                    val url = URL(GemmaModelCatalog.url(option))
                    connection = url.openConnection() as HttpURLConnection
                    if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    val code = connection.responseCode
                    if (code !in 200..299 && code != 206) {
                        throw RuntimeException("HTTP $code")
                    }
                    val contentLength = connection.getContentLengthLong()
                    val total = when {
                        code == 206 && contentLength > 0 -> start + contentLength
                        contentLength > 0 -> contentLength
                        else -> option.sizeBytes
                    }
                    connection.inputStream.use { input ->
                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(partFile.length())
                            val buf = ByteArray(256 * 1024)
                            var lastEmit = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                raf.write(buf, 0, read)
                                val written = partFile.length()
                                if (written - lastEmit >= 4 * 1024 * 1024) {
                                    lastEmit = written
                                    trySend(DownloadState.Downloading(written, total))
                                }
                            }
                        }
                    }
                    success = true
                } catch (t: Throwable) {
                    if (attempt >= 5) throw t
                    // Backoff then retry with resume.
                    Thread.sleep(2_000L * attempt)
                } finally {
                    connection?.disconnect()
                }
            }
            if (partFile.length() >= option.sizeBytes - 1024) {
                if (file.exists()) file.delete()
                if (!partFile.renameTo(file)) {
                    partFile.copyTo(file, overwrite = true)
                    partFile.delete()
                }
                trySend(DownloadState.Done)
            } else {
                trySend(
                    DownloadState.Failed(
                        "Incomplete download (${partFile.length()}/${option.sizeBytes} bytes)",
                    ),
                )
            }
        } catch (t: Throwable) {
            trySend(DownloadState.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            close()
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    suspend fun deleteModel(option: ModelOption) = withContext(Dispatchers.IO) {
        modelFile(option).delete()
        File(modelDir(), option.fileName + ".part").delete()
        Unit
    }
}
