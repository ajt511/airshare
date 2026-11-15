package me.pipi.easyshare.gesture

import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.lan.LanDiscovery
import me.pipi.easyshare.lan.LanSender
import me.pipi.easyshare.models.DiscoveredDevice
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.services.P2pSenderService
import me.pipi.easyshare.ui.gesture.GestureTarget
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferUiCoordinator

/** Badge-visible progress of one capture session. */
sealed interface CapturePhase {
    /** No hand yet — waiting for an open palm. */
    data object Waiting : CapturePhase

    /** Open palm recognized — grab gesture is armed. */
    data object PalmReady : CapturePhase

    /** Fist held; resolving the viewed image. */
    data object Grabbing : CapturePhase

    /** Image in hand; tilt picks the target among [targets]. */
    data class Ready(val targetName: String?, val targets: List<GestureTarget>) : CapturePhase

    data class Sending(val targetName: String, val percent: Int, val viaLan: Boolean) : CapturePhase

    data class Done(val success: Boolean, val message: String?) : CapturePhase
}

/**
 * UI-free port of the GestureTransferViewModel flow for the direct
 * (no-jump) capture session: scans nearby targets, selects by side tilt,
 * resolves the viewed image on fist grab, and sends on palm release.
 * Progress is rendered by the service on the overlay badge.
 */
class GestureCaptureController(
    private val context: Context,
    private val callbacks: (CapturePhase) -> Unit,
    /** Fired when the fist grabs but only a screenshot can represent it. */
    private val screenFallbackRequested: () -> Unit = {},
) {
    private val settings = AppSettings(context)
    private val scanner = AllianceScanner(context)
    private val lanDiscovery = LanDiscovery(context)
    private val lanSender = LanSender(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var content: List<FileInfo> = emptyList()
    private var sendJob: Job? = null
    private var grabbingJob: Job? = null
    private var allianceBusy = false
    private var lanBusy = false
    private var finished = false

    private var targets: List<GestureTarget> = emptyList()
    private var selectedId: String? = null
    private var latestAllianceDevices: List<DiscoveredDevice> = emptyList()

    private val gestureListener = object : GestureStateMachine.Listener {
        override fun onPalmHeld() {
            phase(CapturePhase.PalmReady)
            haptic(Haptic.TICK)
        }

        override fun onGrabbed() {
            // Auto-pairing (beta): a fist also agrees to a pending request.
            if (settings.autoPairEnabled) acceptPendingIncoming()
            haptic(Haptic.GRAB)
            phase(CapturePhase.Grabbing)
            grabbingJob = scope.launch(Dispatchers.IO) {
                val result = PhotoContentGrabber.grab(
                    context,
                    CaptureSessionContext.nodeHints,
                    CaptureSessionContext.sourcePackage,
                    System.currentTimeMillis(),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (finished) return@withContext
                    when (result) {
                        is PhotoContentGrabber.GrabResult.Success -> {
                            content = listOf(result.file)
                            phase(readyPhase())
                        }
                        PhotoContentGrabber.GrabResult.ScreenCaptureFallback ->
                            screenFallbackRequested()
                        PhotoContentGrabber.GrabResult.Failure ->
                            finish(false, NO_PHOTO_MESSAGE)
                    }
                }
            }
        }

        override fun onReleased() {
            haptic(Haptic.RELEASE)
            if (content.isNotEmpty()) {
                sendToSelected()
            } else {
                // Nothing grabbed: an open palm still accepts a pending request.
                if (settings.palmAutoAcceptEnabled && acceptPendingIncoming()) {
                    finish(true, ACCEPTED_MESSAGE)
                } else {
                    finish(false, NO_PHOTO_MESSAGE)
                }
            }
        }

        override fun onGrabLost() {
            if (content.isEmpty() && sendJob?.isActive != true) phase(CapturePhase.Waiting)
        }
    }

    private val gestureMachine = GestureStateMachine(
        GestureStateMachine.Config(),
        gestureListener,
    )

    private val tiltSelector = TiltSelector(
        TiltSelector.Config(),
        object : TiltSelector.Listener {
            override fun onStepLeft() = stepSelection(-1)
            override fun onStepRight() = stepSelection(1)
        },
    )

    // ---------------------------------------------------------------- state

    private fun phase(value: CapturePhase) = callbacks(value)

    private fun readyPhase(): CapturePhase.Ready =
        CapturePhase.Ready(targets.firstOrNull { it.id == selectedId }?.name, targets)

    private fun stepSelection(steps: Int) {
        if (targets.isEmpty()) return
        val index = targets.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        val next = (index + steps).mod(targets.size)
        selectedId = targets[next].id
        if (content.isNotEmpty()) phase(readyPhase())
    }

    /** Devices already trusted this session (avoids rewriting prefs each scan). */
    private val trustedIds = mutableSetOf<String>()

    private fun mergeTargets(
        allianceDevices: List<DiscoveredDevice>,
        lanPeers: Map<String, LanDiscovery.LanPeer>,
    ) {
        latestAllianceDevices = allianceDevices
        val merged = buildList {
            allianceDevices.forEach { device ->
                add(
                    GestureTarget(
                        id = "ble:${device.id}",
                        name = device.displayName,
                        brandId = device.brandId,
                        isLan = false,
                        alliance = device,
                    )
                )
            }
            lanPeers.values.forEach { peer ->
                add(
                    GestureTarget(
                        id = "lan:${peer.announcement.id}",
                        name = peer.announcement.name,
                        brandId = peer.announcement.brandId.takeIf { it >= 0 },
                        isLan = true,
                        lanPeer = peer,
                    )
                )
            }
        }
        targets = merged
        // Discovery-based pairing: newly seen devices land in the trust
        // table (no-op while auto-pairing is off; retried once it's on).
        merged.forEach { target ->
            if (target.id !in trustedIds &&
                me.pipi.easyshare.pairing.AutoPairStore.trust(context, target.id, target.name, target.brandId)
            ) {
                trustedIds.add(target.id)
            }
        }
        if (selectedId == null || merged.none { it.id == selectedId }) {
            selectedId = merged.firstOrNull { !it.isLan }?.id ?: merged.firstOrNull()?.id
        }
        if (content.isNotEmpty()) phase(readyPhase())
    }

    // --------------------------------------------------------------- events

    fun startScanning() {
        scanner.start { allianceDevices ->
            mergeTargets(allianceDevices, lanDiscovery.peers.value)
        }
        scope.launch {
            lanDiscovery.peers.collect { lanPeers ->
                mergeTargets(latestAllianceDevices, lanPeers)
            }
        }
        if (lanDiscovery.isLanEnabled) lanDiscovery.start()
    }

    fun onGestureFrame(frame: GestureFrame) {
        gestureMachine.onFrame(frame)
    }

    fun onRoll(degrees: Float) = tiltSelector.onRoll(degrees)

    fun cancel() {
        if (lanBusy) {
            sendJob?.cancel()
            lanBusy = false
        } else if (allianceBusy) {
            val deviceId = targets.firstOrNull { it.id == selectedId }?.alliance?.id
            val live = deviceId?.let { TransferUiCoordinator.states.value[it] }
            if (live != null) P2pSenderService.cancelTask(context, live.taskId, deviceId)
            allianceBusy = false
        }
        finish(false, CANCELLED_MESSAGE)
    }

    // ----------------------------------------------------------------- send

    private fun sendToSelected() {
        val target = targets.firstOrNull { it.id == selectedId }
        if (target == null) {
            finish(false, NO_TARGET_MESSAGE)
            return
        }
        phase(CapturePhase.Sending(target.name, 0, target.isLan))
        sendJob = scope.launch(Dispatchers.Main) {
            val ok = if (target.isLan) sendLan(target) else sendAlliance(target)
            finish(ok, if (ok) null else FAIL_MESSAGE)
        }
    }

    private suspend fun sendAlliance(target: GestureTarget): Boolean {
        val device = target.alliance ?: return false
        val task = TaskInfo(
            id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            device = device,
            files = content,
        )
        allianceBusy = true
        val started = P2pSenderService.startTaskChecked(context, task)
        if (!started) {
            allianceBusy = false
            return false
        }
        val deviceId = task.device.id
        var ok = false
        val pollJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        while (pollJob?.isActive == true && allianceBusy) {
            val live = TransferUiCoordinator.states.value[deviceId]?.takeIf { it.taskId == task.id }
            if (live != null) {
                when (live.status) {
                    TransferUiStatus.WAITING ->
                        phase(CapturePhase.Sending(target.name, 0, false))
                    TransferUiStatus.SENDING ->
                        phase(CapturePhase.Sending(target.name, live.progress, false))
                    TransferUiStatus.SUCCESS, TransferUiStatus.PARTIAL -> {
                        ok = true
                        allianceBusy = false
                    }
                    else -> allianceBusy = false
                }
            }
            delay(150)
        }
        return ok
    }

    private suspend fun sendLan(target: GestureTarget): Boolean {
        val peer = target.lanPeer ?: return false
        val text = content.firstOrNull()?.textContent
        val host = peer.address.hostAddress ?: return false
        lanBusy = true
        val ok = try {
            lanSender.send(
                host = host,
                port = peer.announcement.port,
                senderName = settings.deviceName,
                senderBrandId = DeviceUtils.getLocalBrandId(),
                files = if (text == null) {
                    content.mapNotNull { file -> file.uri.takeIf { it != Uri.EMPTY } }
                } else {
                    emptyList()
                },
                textContent = text,
            ) { progress ->
                phase(CapturePhase.Sending(target.name, progress.percent, true))
            }
        } catch (error: Exception) {
            Log.e(TAG, "LAN send failed", error)
            false
        }
        lanBusy = false
        return ok
    }

    /** Open palm with empty hands accepts a pending incoming request. */
    private fun acceptPendingIncoming(): Boolean {
        val pending = IncomingTransferUiCoordinator.states.value.values.firstOrNull {
            it.status == IncomingTransferUiStatus.REQUESTED
        } ?: return false
        val accepted = IncomingTransferUiCoordinator.decide(
            pending.taskId, IncomingRequestDecision.ACCEPTED,
        )
        if (accepted) {
            IncomingTransferUiCoordinator.senderKeyOf(pending.taskId)?.let { key ->
                me.pipi.easyshare.pairing.AutoPairStore.extendWindow(context, key)
            }
        }
        return accepted
    }

    private fun finish(success: Boolean, message: String?) {
        if (finished) return
        finished = true
        phase(CapturePhase.Done(success, message))
    }

    // ------------------------------------------------------ host callbacks

    /** The service resolved a screenshot into content — continue the flow. */
    fun acceptCapturedContent(files: List<FileInfo>) {
        if (finished) return
        content = files
        phase(readyPhase())
    }

    /** The service failed a fallback step (e.g. denied screen capture). */
    fun failFromHost(messageKey: String) = finish(false, messageKey)

    /** Persistent mode: after a Done, start the next grab cycle in place. */
    fun resetSession() {
        finished = false
        content = emptyList()
        gestureMachine.reset()
        phase(CapturePhase.Waiting)
    }

    // -------------------------------------------------------------- cleanup

    fun shutdown() {
        finished = true
        scanner.stop()
        lanDiscovery.stop()
        lanSender.close()
        grabbingJob?.cancel()
        sendJob?.cancel()
        scope.cancel()
    }

    private enum class Haptic { TICK, GRAB, RELEASE }

    private fun haptic(kind: Haptic) {
        if (!settings.hapticsEnabled) return
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        val effect = when (kind) {
            Haptic.TICK -> VibrationEffect.createOneShot(30, 80)
            Haptic.GRAB -> VibrationEffect.createOneShot(60, 180)
            Haptic.RELEASE -> VibrationEffect.createOneShot(90, 255)
        }
        try {
            vibrator.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val NO_PHOTO_MESSAGE = "no_photo"
        const val NO_TARGET_MESSAGE = "no_target"
        const val FAIL_MESSAGE = "send_fail"
        const val ACCEPTED_MESSAGE = "accepted"
        const val CANCELLED_MESSAGE = "cancelled"
    }
}
