package me.pipi.easyshare

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pipi.easyshare.ui.main.MainScreen
import me.pipi.easyshare.ui.main.MainViewModel
import me.pipi.easyshare.ui.main.HomeReceiveRecovery
import me.pipi.easyshare.ui.main.homeReceiveRecovery
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.compat.VendorCompat
import me.pipi.easyshare.gesture.PhotoWatchService
import me.pipi.easyshare.utils.missingTransferPermissions
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var enableReceiverAfterPermission = false
    private var recoverReceiverAfterPermission = false
    private var pendingPhotoGesture = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            continuePhotoGestureSetup()
        } else {
            pendingPhotoGesture = false
            Toast.makeText(this, R.string.photo_gesture_missing_permissions, Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val shouldEnable = enableReceiverAfterPermission
        val shouldRecover = recoverReceiverAfterPermission
        enableReceiverAfterPermission = false
        recoverReceiverAfterPermission = false
        val missingPermissions = missingTransferPermissions(includeNotifications = true)
        viewModel.refreshReceiveState()
        if (missingPermissions.isEmpty()) {
            if (shouldEnable) viewModel.setReceiverEnabled(true)
            MyApplication.getInstance().reconcileReceiver()
        } else if (shouldRecover && missingPermissions.any { !shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        } else if (shouldEnable) {
            Toast.makeText(
                this,
                getString(R.string.permission_not_granted),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableReceiverAfterPermission = savedInstanceState?.getBoolean(PENDING_RECEIVER_PERMISSION) ?: false
        recoverReceiverAfterPermission = savedInstanceState?.getBoolean(PENDING_RECEIVER_RECOVERY) ?: false
        enableEdgeToEdge()

        setContent {
            EasyShareTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val chooseReceivePath = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(uri, grantFlags)
                    } catch (error: SecurityException) {
                        Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    state.receivePath
                        ?.let(android.net.Uri::parse)
                        ?.takeIf { it != uri }
                        ?.let { oldUri ->
                            runCatching {
                                contentResolver.releasePersistableUriPermission(oldUri, grantFlags)
                            }
                        }
                    viewModel.setReceivePath(uri)
                }

                MainScreen(
                    state = state,
                    onReceiverChanged = ::setReceiverEnabled,
                    onRecoverReceive = ::recoverReceive,
                    onDeviceNameChanged = viewModel::setDeviceName,
                    onBrandChanged = viewModel::setBrand,
                    onChooseReceivePath = { chooseReceivePath.launch(null) },
                    onSecureReceiveOnlyChanged = viewModel::setSecureReceiveOnly,
                    onEnhancedModeChanged = viewModel::setEnhancedMode,
                    onCaptureLogs = ::captureAndShareLogs,
                    onOpenGestureTransfer = {
                        startActivity(GestureTransferActivity.pickIntent(this))
                    },
                    onPalmAutoAcceptChanged = viewModel::setPalmAutoAccept,
                    onShakeToShareChanged = viewModel::setShakeToShare,
                    onHapticsChanged = viewModel::setHaptics,
                    onLanEnabledChanged = viewModel::setLanEnabled,
                    onOpenVendorAutoStart = { VendorCompat.openAutoStartSettings(this) },
                    onRequestVendorBattery = {
                        VendorCompat.requestIgnoreBatteryOptimizations(this)
                        viewModel.refreshBatteryExemption()
                    },
                    onPhotoGestureChanged = ::setPhotoGesture,
                    onPhotoGestureSetup = ::startPhotoGestureSetup,
                    onCompatAction = { action ->
                        when (action) {
                            "autostart" -> VendorCompat.openAutoStartSettings(this)
                            "battery" -> {
                                VendorCompat.requestIgnoreBatteryOptimizations(this)
                                viewModel.refreshBatteryExemption()
                            }
                            "popup" -> VendorCompat.openBackgroundPopupSettings(this)
                        }
                    },
                )
            }
        }
        if (savedInstanceState == null) {
            val permissions = missingTransferPermissions(includeNotifications = true)
            if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PENDING_RECEIVER_PERMISSION, enableReceiverAfterPermission)
        outState.putBoolean(PENDING_RECEIVER_RECOVERY, recoverReceiverAfterPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshReceiveState()
        viewModel.refreshPhotoGestureState()
        // Completing the flow: user returns from accessibility settings with
        // the service enabled — finish the pending enable here.
        if (pendingPhotoGesture && photoGesturePermissionsGranted()) {
            pendingPhotoGesture = false
            viewModel.setPhotoGesture(true)
        }
    }

    private fun recoverReceive() {
        viewModel.refreshReceiveState()
        when (homeReceiveRecovery(viewModel.state.value)) {
            HomeReceiveRecovery.PERMISSIONS -> {
                recoverReceiverAfterPermission = true
                permissionLauncher.launch(missingTransferPermissions(includeNotifications = true).toTypedArray())
            }
            HomeReceiveRecovery.WIFI -> startActivity(Intent(Settings.Panel.ACTION_WIFI))
            HomeReceiveRecovery.BLUETOOTH -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            HomeReceiveRecovery.RETRY -> MyApplication.getInstance().reconcileReceiver()
            HomeReceiveRecovery.NONE -> Unit
        }
    }

    private fun setReceiverEnabled(enabled: Boolean) {
        if (!enabled) {
            enableReceiverAfterPermission = false
            viewModel.setReceiverEnabled(false)
            return
        }
        val permissions = missingTransferPermissions(includeNotifications = true)
        if (permissions.isEmpty()) {
            viewModel.setReceiverEnabled(true)
        } else {
            enableReceiverAfterPermission = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun photoGesturePermissionsGranted(): Boolean =
        Settings.canDrawOverlays(this) && PhotoWatchService.isEnabled(this)

    private fun setPhotoGesture(enabled: Boolean) {
        if (!enabled) {
            pendingPhotoGesture = false
            viewModel.setPhotoGesture(false)
            return
        }
        pendingPhotoGesture = true
        continuePhotoGestureSetup()
    }

    /** Walks the user through overlay → accessibility settings, then enables. */
    private fun continuePhotoGestureSetup() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri(),
                    ),
                )
                return
            } catch (_: Exception) {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
            }
        }
        if (!PhotoWatchService.isEnabled(this)) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.photo_gesture_enable_hint, Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
            }
        }
        if (photoGesturePermissionsGranted()) {
            pendingPhotoGesture = false
            viewModel.setPhotoGesture(true)
        }
    }

    private fun startPhotoGestureSetup() {
        pendingPhotoGesture = viewModel.state.value.photoGesture
        continuePhotoGestureSetup()
    }

    private fun captureAndShareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logDir = File(cacheDir, "logs").apply { mkdirs() }
                val logFile = File(logDir, "logcat.txt")
                logFile.outputStream().use { output ->
                    val process = Runtime.getRuntime().exec("logcat -d")
                    try {
                        process.inputStream.copyTo(output)
                    } finally {
                        process.destroy()
                    }
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${BuildConfig.APPLICATION_ID}.fileProvider",
                    logFile,
                )
                val shareIntent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setType("text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.capture_logs)))
                }
            } catch (exception: Exception) {
                Log.e("LogcatCapture", "Failed to save logs", exception)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.log_capture_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val PENDING_RECEIVER_PERMISSION = "pendingReceiverPermission"
        private const val PENDING_RECEIVER_RECOVERY = "pendingReceiverRecovery"
    }
}
