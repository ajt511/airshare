package me.pipi.easyshare.lan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.R
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP receiver for the LAN channel. Reuses [IncomingTransferUiCoordinator]
 * so LAN transfers ride the existing accept/reject sheet, progress UI and
 * gesture open-palm acceptance.
 */
class LanTransferServer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<*, *>? = null

    private class LanSession(
        val meta: LanPrepareRequest,
        val files: MutableList<ReceivedFile> = mutableListOf(),
        /** Received text payload when the task is a text transfer. */
        @Volatile var receivedText: String? = null,
        @Volatile var canceled: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<Int, LanSession>()

    fun start() {
        if (server != null) return
        scope.launch {
            try {
                val engine = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
                    routing {
                        post("/airshare/prepare") { handlePrepare(call) }
                        post("/airshare/file/{taskId}/{index}") { handleFile(call) }
                        post("/airshare/complete") { handleComplete(call) }
                    }
                }
                server = engine
                engine.start(wait = false)
                Log.i(TAG, "LAN transfer server listening on $PORT")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start LAN transfer server", error)
                server = null
            }
        }
    }

    fun stop() {
        try {
            server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop LAN transfer server", error)
        }
        server = null
        sessions.clear()
    }

    fun cancelSession(taskId: Int) {
        sessions[taskId]?.canceled = true
    }

    private suspend fun handlePrepare(call: ApplicationCall) {
        val request = try {
            LanProtocol.json.decodeFromString(LanPrepareRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            respondPrepare(call, LanPrepareResponse(-1, false, "bad request"))
            return
        }
        val taskId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        sessions[taskId] = LanSession(request)
        val isText = request.textContent != null
        val published = IncomingTransferUiCoordinator.beginRequest(
            IncomingTransferUiState(
                taskId = taskId,
                senderName = request.senderName,
                fileName = request.files.firstOrNull()?.name ?: request.textContent.orEmpty(),
                fileCount = if (isText) 1 else request.files.size,
                totalSize = if (isText) 0L else request.files.sumOf { it.size },
                brandId = request.senderBrandId.takeIf { it >= 0 },
                status = IncomingTransferUiStatus.REQUESTED,
                isText = isText,
                mimeType = request.files.firstOrNull()?.mimeType,
            ),
            expiresAtMillis = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS,
            timeoutMessage = context.getString(R.string.incoming_transfer_timeout),
        )
        if (!published) {
            sessions.remove(taskId)
            respondPrepare(call, LanPrepareResponse(-1, false, "busy"))
            return
        }
        when (IncomingTransferUiCoordinator.awaitDecision(taskId)) {
            IncomingRequestDecision.ACCEPTED ->
                respondPrepare(call, LanPrepareResponse(taskId, true))
            else -> {
                sessions.remove(taskId)
                respondPrepare(call, LanPrepareResponse(-1, false, "rejected"))
            }
        }
    }

    private suspend fun handleFile(call: ApplicationCall) {
        val taskId = call.parameters["taskId"]?.toIntOrNull()
        val index = call.parameters["index"]?.toIntOrNull()
        if (taskId == null || index == null) {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        val session = sessions[taskId] ?: run {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        if (session.canceled) {
            call.respondText("canceled", status = HttpStatusCode.Gone)
            return
        }
        // Text tasks carry no file metas; the payload rides index 0 as plain text.
        if (session.meta.textContent != null) {
            session.receivedText = call.receiveText()
            IncomingTransferUiCoordinator.markReceiving(taskId, 90, "text")
            call.respondText("ok")
            return
        }
        val meta = session.meta.files.getOrNull(index)
        if (meta == null) {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        val channel = call.request.receiveChannel()
        val received = withContext(Dispatchers.IO) {
            try {
                saveStream(session, meta, channel) { progress ->
                    IncomingTransferUiCoordinator.markReceiving(taskId, progress, meta.name)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save LAN file $index", error)
                IncomingTransferUiCoordinator.fail(
                    taskId, context.getString(R.string.recv_fail),
                )
                null
            }
        }
        if (received != null) {
            session.files += received
            call.respondText("ok")
        } else {
            call.respondText("save failed", status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun handleComplete(call: ApplicationCall) {
        val request = try {
            LanProtocol.json.decodeFromString(LanCompleteRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respondText("bad request", status = HttpStatusCode.BadRequest)
            return
        }
        val session = sessions.remove(request.taskId)
        if (session == null) {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        if (request.canceled) {
            IncomingTransferUiCoordinator.fail(
                request.taskId,
                context.getString(R.string.cancelled_by_user_remote),
                canceled = true,
            )
            call.respondText("ok")
            return
        }
        // Text task: put the received payload on the clipboard; the incoming
        // sheet already shows "copied to clipboard" for isText tasks.
        session.receivedText?.let { text ->
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("GestureShare", text),
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed to copy LAN text to clipboard", error)
            }
            IncomingTransferUiCoordinator.complete(request.taskId, emptyList(), false)
            call.respondText("ok")
            return
        }
        val files = session.files.toList()
        val partial = session.meta.files.isNotEmpty() && files.size < session.meta.files.size
        IncomingTransferUiCoordinator.complete(
            request.taskId,
            files,
            partial,
            receivedFilesToken = if (files.isNotEmpty()) "lan-${request.taskId}" else null,
        )
        call.respondText("ok")
    }

    private suspend fun respondPrepare(call: ApplicationCall, response: LanPrepareResponse) {
        call.respondText(
            LanProtocol.json.encodeToString(LanPrepareResponse.serializer(), response),
            ContentType.Application.Json,
        )
    }

    private suspend fun saveStream(
        session: LanSession,
        meta: LanFileMeta,
        input: ByteReadChannel,
        onProgress: (Int) -> Unit,
    ): ReceivedFile? {
        val resolver = context.contentResolver
        val customDir = customDownloadDir()
        val declaredTotal = session.meta.files.sumOf { it.size }.coerceAtLeast(1L)
        var written = 0L
        val uri: Uri? = if (customDir != null) {
            customDir.createFile(meta.mimeType.ifBlank { "application/octet-stream" }, meta.name)?.uri
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, meta.name)
                put(MediaStore.Downloads.MIME_TYPE, meta.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, DEFAULT_DOWNLOAD_RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }
        if (uri == null) return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (session.canceled) throw java.io.IOException("canceled by sender")
                    val read = input.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        onProgress((written * 100 / declaredTotal).toInt().coerceIn(0, 99))
                    }
                }
                output.flush()
            } ?: return null
            if (customDir == null) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
            return ReceivedFile(meta.name, uri, meta.mimeType)
        } catch (error: Exception) {
            if (customDir != null) {
                DocumentFile.fromSingleUri(context, uri)?.delete()
            } else {
                resolver.delete(uri, null, null)
            }
            throw error
        }
    }

    private fun customDownloadDir(): DocumentFile? {
        val uri = AppSettings(context).downloadUri?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
    }

    companion object {
        private const val TAG = "LanTransferServer"
        const val PORT = 48889
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private val DEFAULT_DOWNLOAD_RELATIVE_PATH =
            "${Environment.DIRECTORY_DOWNLOADS}/Easy Share"
    }
}
