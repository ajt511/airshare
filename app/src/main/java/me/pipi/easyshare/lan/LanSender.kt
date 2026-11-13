package me.pipi.easyshare.lan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends files or text to a peer's [LanTransferServer] over HTTP. Progress is
 * reported per whole task (byte-weighted across all files).
 */
class LanSender(private val context: Context) {
    data class Progress(
        val percent: Int,
        val currentFile: String,
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(false)
            }
        }
    }

    suspend fun send(
        host: String,
        port: Int,
        senderName: String,
        senderBrandId: Int,
        files: List<Uri>,
        textContent: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val metas = if (textContent == null) {
            files.mapNotNull { uri -> metaOf(uri)?.let { uri to it } }
        } else {
            emptyList()
        }
        if (textContent == null && metas.isEmpty()) return@withContext false

        val prepare = LanPrepareRequest(
            senderName = senderName,
            senderBrandId = senderBrandId,
            textContent = textContent,
            files = metas.map { it.second },
        )
        val response = postJson(host, port, "prepare", LanProtocol.json.encodeToString(
            LanPrepareRequest.serializer(), prepare))
        val prepared = try {
            LanProtocol.json.decodeFromString(LanPrepareResponse.serializer(), response.bodyAsText())
        } catch (error: Exception) {
            Log.w(TAG, "Bad prepare response", error)
            return@withContext false
        }
        if (!prepared.accepted || prepared.taskId <= 0) return@withContext false
        val taskId = prepared.taskId

        var succeeded = true
        try {
            if (textContent != null) {
                // Text rides one file-less POST so the receiver can copy it.
                client.post("http://$host:$port/airshare/file/$taskId/0") {
                    setBody(textContent)
                    contentType(ContentType.Text.Plain)
                }
            } else {
                val total = metas.sumOf { it.second.size }.coerceAtLeast(1L)
                var sent = 0L
                metas.forEachIndexed { index, (uri, meta) ->
                    val fileName = meta.name
                    client.preparePost("http://$host:$port/airshare/file/$taskId/$index") {
                        setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                            override val contentType: ContentType? =
                                ContentType.parse(meta.mimeType.ifBlank { "application/octet-stream" })
                            override val contentLength: Long? = meta.size.takeIf { it > 0 }
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (read > 0) {
                                            channel.writeFully(buffer, 0, read)
                                            sent += read
                                            onProgress(
                                                Progress(
                                                    (sent * 100 / total).toInt().coerceIn(0, 99),
                                                    fileName,
                                                )
                                            )
                                        }
                                    }
                                } ?: throw java.io.IOException("Cannot open $uri")
                            }
                        })
                    }.execute()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "LAN send failed", error)
            succeeded = false
        }

        try {
            postJson(host, port, "complete", LanProtocol.json.encodeToString(
                LanCompleteRequest.serializer(),
                LanCompleteRequest(taskId = taskId, canceled = !succeeded),
            ))
        } catch (error: Exception) {
            Log.w(TAG, "Complete call failed", error)
        }
        succeeded
    }

    private suspend fun postJson(host: String, port: Int, path: String, body: String): HttpResponse =
        client.post("http://$host:$port/airshare/$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun metaOf(uri: Uri): LanFileMeta? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size < 0L) {
            size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.takeIf { it >= 0L } ?: 0L
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        return LanFileMeta(
            name = resolvedName,
            mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            size = size,
        )
    }

    suspend fun close() {
        client.close()
    }

    companion object {
        private const val TAG = "LanSender"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
