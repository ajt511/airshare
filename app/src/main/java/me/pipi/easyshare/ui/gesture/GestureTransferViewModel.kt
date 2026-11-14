package me.pipi.easyshare.ui.gesture

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.R
import me.pipi.easyshare.gesture.AllianceScanner
import me.pipi.easyshare.gesture.GestureFrame
import me.pipi.easyshare.gesture.GestureStateMachine
import me.pipi.easyshare.gesture.HandGesture
import me.pipi.easyshare.gesture.TiltSelector
import me.pipi.easyshare.lan.LanDiscovery
import me.pipi.easyshare.lan.LanSender
import me.pipi.easyshare.models.DiscoveredDevice
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.services.P2pSenderService
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferUiCoordinator

/** One selectable row of the gesture radar. */
data class GestureTarget(
    val id: String,
    val name: String,
    val brandId: Int?,
    val isLan: Boolean,
    val alliance: DiscoveredDevice? = null,
    val lanPeer: LanDiscovery.LanPeer? = null,
)

sealed interface GesturePhase {
    data object Idle : GesturePhase
    data object PalmReady : GesturePhase
    data object Grabbed : GesturePhase
    data class Sending(val targetName: String, val percent: Int, val viaLan: Boolean) : GesturePhase
    data class Done(val success: Boolean, val message: String?) : GesturePhase
}

data class GestureUiState(
    val phase: GesturePhase = GesturePhase.Idle,
    val targets: List<GestureTarget> = emptyList(),
    val selectedId: String? = null,
    val contentNames: List<String> = emptyList(),
    val hasText: Boolean = false,
    val lastGesture: HandGesture = HandGesture.OTHER,
    val cameraError: Boolean = false,
) {
    val hasContent: Boolean get() = contentNames.isNotEmpty() || hasText
    val selectedIndex: Int get() = targets.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: -1
    val selectedTarget: GestureTarget? get() = targets.firstOrNull { it.id == selectedId }
}

/**
 * Drives the 隔空传送 flow: MediaPipe frames in, gesture events out, radar
 * selection by tilt, and the actual send over the Share-Alliance or LAN
 * channel when the palm opens.
 */
class GestureTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val scanner = AllianceScanner(application)
    private val lanDiscovery = LanDiscovery(application)
    private val lanSender = LanSender(application)

    private val _state = MutableStateFlow(GestureUiState())
    val state: StateFlow<GestureUiState> = _state.asStateFlow()

    private var content: List<FileInfo> = emptyList()
    private var sendJob: Job? = null
    private var phaseResetJob: Job? = null
    private var allianceBusy = false
    private var lanBusy = false

    private val gestureListener = object : GestureStateMachine.Listener {
        override fun onPalmHeld() {
            updatePhase(GesturePhase.PalmReady)
            haptic(Haptic.TICK)
        }

        override fun onGrabbed() {
            updatePhase(GesturePhase.Grabbed)
            haptic(Haptic.GRAB)
        }

        override fun onReleased() {
            haptic(Haptic.RELEASE)
            val snapshot = _state.value
            if (snapshot.hasContent) {
                sendToSelected()
            } else {
                autoAcceptIncoming()
            }
        }

        override fun onGrabLost() {
            updatePhase(GesturePhase.Idle)
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

    private fun updatePhase(phase: GesturePhase) {
        _state.update { it.copy(phase = phase) }
    }

    /** Entry: content shared from ShareActivity (or picked in-screen later). */
    fun setContent(files: List<FileInfo>) {
        content = files
        _state.update {
            it.copy(
                contentNames = files.map { file -> file.textContent ?: file.name },
                hasText = files.any { file -> file.textContent != null },
            )
        }
    }

    fun onGestureFrame(frame: GestureFrame) {
        _state.update { it.copy(lastGesture = frame.gesture) }
        gestureMachine.onFrame(frame)
    }

    fun onCameraError() {
        _state.update { it.copy(cameraError = true) }
    }

    fun onRoll(degrees: Float) = tiltSelector.onRoll(degrees)

    fun stepSelection(steps: Int) {
        _state.update { current ->
            if (current.targets.isEmpty()) return@update current
            val index = current.selectedIndex.takeIf { it >= 0 } ?: 0
            val next = (index + steps).mod(current.targets.size)
            current.copy(selectedId = current.targets[next].id)
        }
    }

    fun startScanning() {
        scanner.start { allianceDevices ->
            mergeTargets(allianceDevices, lanDiscovery.peers.value)
        }
        viewModelScope.launch {
            lanDiscovery.peers.collect { lanPeers ->
                mergeTargets(latestAllianceDevices, lanPeers)
            }
        }
        if (lanDiscovery.isLanEnabled) {
            lanDiscovery.start()
        }
    }

    private var latestAllianceDevices: List<DiscoveredDevice> = emptyList()

    private fun mergeTargets(allianceDevices: List<DiscoveredDevice>, lanPeers: Map<String, LanDiscovery.LanPeer>) {
        latestAllianceDevices = allianceDevices
        val targets = buildList {
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
        _state.update { current ->
            val keepSelection = targets.any { it.id == current.selectedId }
            current.copy(
                targets = targets,
                selectedId = when {
                    targets.isEmpty() -> null
                    keepSelection -> current.selectedId
                    else -> targets.firstOrNull { !it.isLan }?.id ?: targets.first().id
                },
            )
        }
    }

    fun sendToSelected() {
        val snapshot = _state.value
        val target = snapshot.selectedTarget ?: return
        if (sendJob?.isActive == true) return
        if (!snapshot.hasContent) return
        updatePhase(GesturePhase.Sending(target.name, 0, target.isLan))
        sendJob = viewModelScope.launch(Dispatchers.Main) {
            val ok = if (target.isLan) sendLan(target) else sendAlliance(target)
            updatePhase(
                GesturePhase.Done(
                    success = ok,
                    message = if (ok) null else getApplication<Application>().getString(R.string.send_fail),
                )
            )
            schedulePhaseReset()
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
        val started = P2pSenderService.startTaskChecked(getApplication(), task)
        if (!started) {
            allianceBusy = false
            return false
        }
        val deviceId = task.device.id
        var ok = false
        val pollJob = currentCoroutineContext()[Job]
        while (pollJob?.isActive == true && allianceBusy) {
            val live = TransferUiCoordinator.states.value[deviceId]?.takeIf { it.taskId == task.id }
            if (live != null) {
                when (live.status) {
                    TransferUiStatus.WAITING ->
                        updatePhase(GesturePhase.Sending(target.name, 0, false))
                    TransferUiStatus.SENDING ->
                        updatePhase(GesturePhase.Sending(target.name, live.progress, false))
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
                updatePhase(GesturePhase.Sending(target.name, progress.percent, true))
            }
        } catch (error: Exception) {
            Log.e(TAG, "LAN send failed", error)
            false
        }
        lanBusy = false
        return ok
    }

    /** Open palm with empty hands accepts a pending incoming request. */
    private fun autoAcceptIncoming() {
        if (!settings.palmAutoAcceptEnabled) return
        val pending = IncomingTransferUiCoordinator.states.value.values.firstOrNull {
            it.status == IncomingTransferUiStatus.REQUESTED
        } ?: return
        IncomingTransferUiCoordinator.decide(pending.taskId, IncomingRequestDecision.ACCEPTED)
    }

    private fun schedulePhaseReset() {
        phaseResetJob?.cancel()
        phaseResetJob = viewModelScope.launch {
            delay(RESULT_RESET_MILLIS)
            updatePhase(GesturePhase.Idle)
        }
    }

    fun cancelSend() {
        if (lanBusy) {
            sendJob?.cancel()
            lanBusy = false
            updatePhase(GesturePhase.Idle)
            return
        }
        if (allianceBusy) {
            val deviceId = _state.value.selectedTarget?.alliance?.id ?: return
            val live = TransferUiCoordinator.states.value[deviceId] ?: return
            P2pSenderService.cancelTask(getApplication(), live.taskId, deviceId)
            allianceBusy = false
            updatePhase(GesturePhase.Idle)
        }
    }

    private enum class Haptic { TICK, GRAB, RELEASE }

    private fun haptic(kind: Haptic) {
        if (!settings.hapticsEnabled) return
        val context = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    override fun onCleared() {
        scanner.stop()
        lanDiscovery.stop()
        lanSender.close()
        sendJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "GestureTransfer"
        private const val RESULT_RESET_MILLIS = 2_500L
    }
}
