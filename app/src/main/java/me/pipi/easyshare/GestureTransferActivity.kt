package me.pipi.easyshare

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import java.util.concurrent.Executors

import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.ui.gesture.GestureTransferScreen
import me.pipi.easyshare.ui.gesture.GestureTransferViewModel
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.utils.FileInfoFactory
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.vision.HandLandmarkerHelper

/**
 * 全屏手势传输界面：前摄识别「伸掌→握拳抓取→侧倾选设备→张掌释放」，
 * 目标设备雷达合并互传联盟 BLE 扫描与局域网 UDP 发现。
 */
class GestureTransferActivity : ComponentActivity() {
    private val viewModel: GestureTransferViewModel by viewModels()

    private var landmarker: HandLandmarkerHelper? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var cameraPermissionGranted = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) bindAnalysis() else viewModel.onCameraError()
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            val files = uris.mapNotNull { uri ->
                runCatching { FileInfoFactory.fromUri(this, uri) }.getOrNull()
            }
            if (files.isNotEmpty()) viewModel.setContent(files)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val roll = Math.toDegrees(
                Math.atan2(event.values[0].toDouble(), event.values[2].toDouble()),
            ).toFloat()
            runOnUiThread { viewModel.onRoll(roll) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        extractSharedFiles(intent).takeIf { it.isNotEmpty() }?.let(viewModel::setContent)
        viewModel.startScanning()

        setContent {
            EasyShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GestureTransferScreen(
                        state = viewModel.state.collectAsState().value,
                        onPickFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onClose = ::finish,
                        onCancelSend = viewModel::cancelSend,
                        onRetryCamera = ::ensureCamera,
                        onStepSelection = viewModel::stepSelection,
                    )
                }
                BackHandler { finish() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureCamera()
        registerSensors()
    }

    override fun onStop() {
        unregisterSensors()
        unbindCamera()
        super.onStop()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun ensureCamera() {
        if (cameraPermissionGranted) {
            bindAnalysis()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun registerSensors() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            try {
                manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to register tilt sensor", error)
            }
        }
    }

    private fun unregisterSensors() {
        (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.unregisterListener(sensorListener)
    }

    /** Binds only the analysis use case; the Preview use case lives in [GestureCameraPreview]. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindAnalysis() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                landmarker?.close()
                landmarker = HandLandmarkerHelper(
                    context = this,
                    onFrame = { frame -> runOnUiThread { viewModel.onGestureFrame(frame) } },
                    onError = { message ->
                        Log.e(TAG, "Hand landmarker: $message")
                        runOnUiThread { viewModel.onCameraError() }
                    },
                )
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            // elapsedRealtime is monotonic, as MediaPipe LIVE_STREAM requires.
                            landmarker?.detectAsync(mediaImage, SystemClock.elapsedRealtime())
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Analysis frame failed", error)
                    } finally {
                        imageProxy.close()
                    }
                }
                // No unbindAll here: the Preview use case in GestureCameraPreview
                // shares this lifecycle and CameraX reconciles both bindings.
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (error: Exception) {
                Log.e(TAG, "Camera analysis start failed", error)
                runOnUiThread { viewModel.onCameraError() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        // CameraX unbinds automatically when the lifecycle stops; only the
        // detector needs an explicit release here.
        landmarker?.close()
        landmarker = null
    }

    @Suppress("DEPRECATION")
    private fun extractSharedFiles(intent: Intent): List<FileInfo> {
        val files = intent.getParcelableArrayListExtra<FileInfo>(EXTRA_FILES)
        if (files != null) return files

        val uris = ArrayList<Uri>()
        intent.getParcelableArrayListExtra<Uri>(EXTRA_FILE_URIS)?.let(uris::addAll)
        if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }
        return uris.mapNotNull { uri ->
            runCatching { FileInfoFactory.fromUri(this, uri) }.getOrNull()
        }
    }

    companion object {
        const val EXTRA_FILES = "extraFiles"
        const val EXTRA_FILE_URIS = "extraFileUris"

        /** Opens the screen empty; the user picks files in-screen. */
        fun pickIntent(context: Context): Intent =
            Intent(context, GestureTransferActivity::class.java)

        /** Opens the screen already holding shared content. */
        fun sendIntent(context: Context, files: ArrayList<FileInfo>): Intent =
            Intent(context, GestureTransferActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_FILES, files)
            }
    }
}

/** Front-camera preview surface used as the gesture framing guide. */
@Composable
fun GestureCameraPreview(
    modifier: Modifier,
    lifecycleOwner: LifecycleOwner,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view ->
            if (view.surfaceProvider != null) return@AndroidView
            val future = ProcessCameraProvider.getInstance(view.context)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { useCase ->
                        useCase.surfaceProvider = view.surfaceProvider
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                    )
                } catch (error: Exception) {
                    Log.w("GestureCamera", "Preview bind failed", error)
                }
            }, ContextCompat.getMainExecutor(view.context))
        },
    )
}
