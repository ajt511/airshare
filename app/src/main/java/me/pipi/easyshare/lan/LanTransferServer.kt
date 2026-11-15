package me.pipi.easyshare.lan

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.IncomingTransferActivity
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile
import me.pipi.easyshare.pairing.AutoPairStore
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.TransferLimits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP receiver for the LAN channel. Reuses [IncomingTransferUiCoordinator]
 * so LAN transfers ride the existing accept/reject sheet, progress UI and
 * gesture open-palm acceptance, and presents the request itself (sheet when
 * the app is visible, notification otherwise) because no other component
 * does that for the LAN path.
 */
class LanTransferServer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private val lifecycleLock = Any()
    private var server: EmbeddedServer<*, *>? = null
    private var starting = false
    private var generation = 0
    private var cleanupJob: kotlinx.coroutines.Job? = null

    private class LanSession(
        val meta: LanPrepareRequest,
        /** Copy-on-write: concurrent file POSTs (untrusted peers) append here. */
        val files: MutableList<ReceivedFile> = java.util.concurrent.CopyOnWriteArrayList(),
        /** Only accepted sessions may write files. */
        @Volatile var accepted: Boolean = false,
        /** Payload bytes across the whole task, for progress math. */
        @Volatile var receivedBytes: Long = 0L,
        /** Received text payload when the task is a text transfer. */
        @Volatile var receivedText: String? = null,
        @Volatile var canceled: Boolean = false,
        @Volatile var lastActivityMillis: Long = SystemClock.elapsedRealtime(),
    )

    private val sessions = ConcurrentHashMap<Int, LanSession>()

    fun start() {
        synchronized(lifecycleLock) {
            if (server != null || starting) return
            starting = true
            val startGeneration = ++generation
            val engine = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
                routing {
                    post("/airshare/prepare") { handlePrepare(call) }
                    post("/airshare/file/{taskId}/{index}") { handleFile(call) }
                    post("/airshare/complete") { handleComplete(call) }
                }
            }
            server = engine
            scope.launch {
                try {
                    if (startGeneration != generation) return@launch
                    engine.start(wait = false)
                    Log.i(TAG, "LAN transfer server listening on $PORT")
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to start LAN transfer server", error)
                    synchronized(lifecycleLock) {
                        if (server === engine) server = null
                    }
                } finally {
                    synchronized(lifecycleLock) { starting = false }
                }
            }
            startSessionCleanup()
        }
    }

    fun stop() {
        val engine = synchronized(lifecycleLock) {
            // Bump the generation so an in-flight start cannot resurrect a
            // server that was already stopped.
            generation++
            starting = false
            server.also { server = null }
        }
        try {
            engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop LAN transfer server", error)
        }
        sessions.clear()
    }

    fun cancelSession(taskId: Int) {
        sessions[taskId]?.canceled = true
    }

    /** Drops sessions whose sender vanished after the prepare handshake. */
    private fun startSessionCleanup() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (isActive) {
                delay(SESSION_CLEANUP_INTERVAL_MILLIS)
                val now = SystemClock.elapsedRealtime()
                val stale = sessions.entries
                    .filter { now - it.value.lastActivityMillis > SESSION_IDLE_TIMEOUT_MILLIS }
                stale.forEach { (taskId, _) ->
                    sessions.remove(taskId)
                    IncomingTransferUiCoordinator.get(taskId)?.let {
                        IncomingTransferUiCoordinator.fail(
                            taskId,
                            context.getString(R.string.recv_fail),
                        )
                    }
                }
            }
        }
    }

    private fun newTaskId(): Int {
        while (true) {
            val candidate = random.nextInt(Int.MAX_VALUE - 1) + 1
            if (!sessions.containsKey(candidate)) return candidate
        }
    }

    private suspend fun handlePrepare(call: ApplicationCall) {
        val body = receiveTextLimited(call, MAX_JSON_BYTES)
        if (body == null) {
            respondPrepare(call, LanPrepareResponse(-1, false, "bad request"))
            return
        }
        val request = try {
            LanProtocol.json.decodeFromString(LanPrepareRequest.serializer(), body)
        } catch (_: Exception) {
            respondPrepare(call, LanPrepareResponse(-1, false, "bad request"))
            return
        }
        val taskId = newTaskId()
        val session = LanSession(request)
        val isText = request.textContent != null
        val fileCount = if (isText) 1 else request.files.size
        val totalSize = if (isText) 0L else request.files.sumOf { it.size }
        try {
            // Same limits as the BLE channel: reject absurd counts/sizes up
            // front instead of discovering them while writing to disk.
            TransferLimits.validateMetadata(
                fileCount = fileCount,
                totalSize = totalSize,
                textSize = request.textContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Rejected LAN prepare with invalid metadata", error)
            respondPrepare(call, LanPrepareResponse(-1, false, "bad request"))
            return
        }
        sessions[taskId] = session
        val senderKey = "lan:${request.senderId}"
        val state = IncomingTransferUiState(
            taskId = taskId,
            senderName = request.senderName,
            fileName = request.files.firstOrNull()?.name ?: request.textContent.orEmpty(),
            fileCount = fileCount,
            totalSize = totalSize,
            brandId = request.senderBrandId.takeIf { it >= 0 },
            status = IncomingTransferUiStatus.REQUESTED,
            isText = isText,
            mimeType = request.files.firstOrNull()?.mimeType,
            lanTask = true,
        )
        val published = IncomingTransferUiCoordinator.beginRequest(
            state,
            // The coordinator compares deadlines with elapsedRealtime();
            // using wall-clock time here would postpone the timeout by decades.
            expiresAtMillis = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MILLIS,
            timeoutMessage = context.getString(R.string.incoming_transfer_timeout),
            senderKey = senderKey,
        )
        if (!published) {
            sessions.remove(taskId)
            respondPrepare(call, LanPrepareResponse(-1, false, "busy"))
            return
        }
        presentRequest(state)
        // Auto-pairing (beta): trust + auto-accept for devices with an open
        // accept-window; every acceptance re-opens the window. With the
        // gesture confirmation disabled, already paired devices are accepted
        // outright (unknown devices still confirm).
        val settings = AppSettings(context)
        if (settings.autoPairEnabled) {
            // Query trust BEFORE writing the entry: trust() would otherwise
            // make the first-contact stranger look paired.
            val alreadyTrusted = AutoPairStore.isTrusted(context, senderKey)
            val hasWindow = AutoPairStore.hasOpenWindow(context, senderKey)
            AutoPairStore.trust(
                context, senderKey, request.senderName, request.senderBrandId.takeIf { it >= 0 },
            )
            if (hasWindow || (!settings.gestureReceiveConfirmEnabled && alreadyTrusted)) {
                IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED)
            }
        }
        val decision = try {
            IncomingTransferUiCoordinator.awaitDecision(taskId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Incoming LAN request vanished before a decision", error)
            IncomingRequestDecision.TIMED_OUT
        }
        if (decision == IncomingRequestDecision.ACCEPTED) {
            session.accepted = true
            session.lastActivityMillis = SystemClock.elapsedRealtime()
            AutoPairStore.extendWindow(context, senderKey)
            respondPrepare(call, LanPrepareResponse(taskId, true))
        } else {
            sessions.remove(taskId)
            respondPrepare(call, LanPrepareResponse(-1, false, "rejected"))
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
        // Bytes must never be accepted before the receiver approved the task.
        if (!session.accepted) {
            call.respondText("not accepted", status = HttpStatusCode.Conflict)
            return
        }
        session.lastActivityMillis = SystemClock.elapsedRealtime()
        // Text tasks carry no file metas; the payload rides index 0 as plain text.
        if (session.meta.textContent != null) {
            val text = receiveTextLimited(call, TransferLimits.MAX_TEXT_BYTES.toInt())
            if (text == null) {
                call.respondText("text too large", status = HttpStatusCode.PayloadTooLarge)
                return
            }
            session.receivedText = text
            IncomingTransferUiCoordinator.markReceiving(taskId, 90, "text")
            call.respondText("ok")
            return
        }
        val meta = session.meta.files.getOrNull(index)
        if (meta == null) {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        val maxBytes = TransferLimits.maxActualBytes(meta.size)
        val declared = call.request.contentLength()
        if (declared != null && declared > maxBytes) {
            call.respondText("file too large", status = HttpStatusCode.PayloadTooLarge)
            IncomingTransferUiCoordinator.fail(
                taskId, context.getString(R.string.recv_fail),
            )
            return
        }
        val channel = call.request.receiveChannel()
        val received = withContext(Dispatchers.IO) {
            try {
                saveStream(session, meta, channel, maxBytes) { progress ->
                    IncomingTransferUiCoordinator.markReceiving(taskId, progress, safeFileName(meta.name))
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save LAN file $index", error)
                IncomingTransferUiCoordinator.fail(
                    taskId,
                    context.getString(
                        if (session.canceled) R.string.cancelled_by_user_local else R.string.recv_fail,
                    ),
                    canceled = session.canceled,
                )
                null
            }
        }
        if (received != null) {
            session.files += received
            session.lastActivityMillis = SystemClock.elapsedRealtime()
            call.respondText("ok")
        } else {
            call.respondText("save failed", status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun handleComplete(call: ApplicationCall) {
        val body = receiveTextLimited(call, MAX_JSON_BYTES)
        val request = body?.let {
            try {
                LanProtocol.json.decodeFromString(LanCompleteRequest.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        if (request == null) {
            call.respondText("bad request", status = HttpStatusCode.BadRequest)
            return
        }
        val session = sessions.remove(request.taskId)
        if (session == null) {
            call.respondText("no session", status = HttpStatusCode.NotFound)
            return
        }
        if (session.canceled) {
            IncomingTransferUiCoordinator.fail(
                request.taskId,
                context.getString(R.string.cancelled_by_user_local),
                canceled = true,
            )
            call.respondText("ok")
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
        if (session.meta.textContent != null && session.receivedText == null) {
            IncomingTransferUiCoordinator.fail(
                request.taskId, context.getString(R.string.recv_fail),
            )
            call.respondText("missing text", status = HttpStatusCode.BadRequest)
            return
        }
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

    // ------------------------------------------------------------ presentation

    /**
     * A LAN request has no notification/activity owner of its own (the BLE
     * path uses P2pReceiverService). Show the sheet when the app is visible,
     * otherwise post a tappable notification; either way the user gets a way
     * to accept or reject.
     */
    @SuppressLint("MissingPermission")
    private fun presentRequest(state: IncomingTransferUiState) {
        val app = context.applicationContext
        if ((app as? MyApplication)?.hasVisibleActivity() == true) {
            // The sheet itself is the presentation; the notification would
            // duplicate it. A failed launch still leaves the 30s request for
            // the gesture/palm hooks.
            mainHandler.post {
                runCatching {
                    app.startActivity(IncomingTransferActivity.createIntent(app, state))
                }.onFailure { Log.w(TAG, "Failed to open incoming LAN sheet", it) }
            }
            return
        }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val notificationId = notificationId(state.taskId)
        val pendingIntent = PendingIntent.getActivity(
            app,
            state.taskId,
            IncomingTransferActivity.createIntent(app, state),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            state.isText -> app.getString(R.string.noti_request_desc_text)
            state.fileName.isNotBlank() -> state.fileName
            else -> app.getString(R.string.incoming_transfer_from, state.senderName)
        }
        val notification = NotificationCompat.Builder(app, NotificationUtils.RECEIVER_CHAN_ID)
            .setSmallIcon(R.drawable.ic_tile_alliance)
            .setContentTitle(app.getString(R.string.incoming_transfer_from, state.senderName))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(notificationId, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission missing for LAN request", error)
        }
        scope.launch {
            IncomingTransferUiCoordinator.states.first { current ->
                current[state.taskId]?.status != IncomingTransferUiStatus.REQUESTED
            }
            runCatching { manager.cancel(notificationId) }
        }
    }

    private fun notificationId(taskId: Int): Int = NOTIFICATION_ID_BASE + (taskId and 0x7fffffff) % 1000

    // ---------------------------------------------------------------- storage

    private suspend fun saveStream(
        session: LanSession,
        meta: LanFileMeta,
        input: ByteReadChannel,
        maxBytes: Long,
        onProgress: (Int) -> Unit,
    ): ReceivedFile? {
        val resolver = context.contentResolver
        val customDir = customDownloadDir()
        val declaredTotal = session.meta.files.sumOf { it.size }.coerceAtLeast(1L)
        // A peer-supplied display name is untrusted input: never let it carry
        // path separators or a hostile MIME type into the storage APIs.
        val displayName = safeFileName(meta.name)
        val mimeType = safeMimeType(meta.mimeType)
        var written = 0L
        val uri: Uri? = if (customDir != null) {
            customDir.createFile(mimeType, displayName)?.uri
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
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
                    if (session.canceled) throw IOException("canceled by receiver")
                    val read = input.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (written > maxBytes) throw IOException("file exceeds declared size")
                        session.receivedBytes += read
                        onProgress(
                            (session.receivedBytes * 100 / declaredTotal).toInt().coerceIn(0, 99),
                        )
                        session.lastActivityMillis = SystemClock.elapsedRealtime()
                    }
                }
                output.flush()
            } ?: return null
            if (customDir == null) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
            return ReceivedFile(displayName, uri, mimeType)
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

    private fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.take(120).ifBlank { "transfer.bin" }
    }

    private fun safeMimeType(mimeType: String?): String =
        mimeType?.trim()?.takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) }
            ?: "application/octet-stream"

    /** Reads a request body with a hard cap so a LAN peer cannot OOM the app. */
    private suspend fun receiveTextLimited(call: ApplicationCall, maxBytes: Int): String? {
        val declared = call.request.contentLength()
        if (declared != null && declared > maxBytes) return null
        val channel = call.request.receiveChannel()
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "LanTransferServer"
        const val PORT = 48889
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val SESSION_IDLE_TIMEOUT_MILLIS = 5 * 60_000L
        private const val SESSION_CLEANUP_INTERVAL_MILLIS = 30_000L
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val NOTIFICATION_ID_BASE = 6200
        private val DEFAULT_DOWNLOAD_RELATIVE_PATH =
            "${Environment.DIRECTORY_DOWNLOADS}/Easy Share"
    }
}
