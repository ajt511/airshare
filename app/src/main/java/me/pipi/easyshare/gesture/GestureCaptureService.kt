package me.pipi.easyshare.gesture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import me.pipi.easyshare.R
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.vision.HandLandmarkerHelper
import java.util.concurrent.Executors

/**
 * Direct-capture gesture session, spawned by PhotoWatchService the moment a
 * photo page shows up: a non-interactive status badge overlays the current
 * app while a camera-type foreground service streams the front camera into
 * MediaPipe, so 伸掌→握拳→侧倾→张掌 completes a transfer without ever
 * leaving the photo page. The overlay window is also what makes the camera
 * FGS launch legal on Android 14+ (the uid then has a visible window).
 */
class GestureCaptureService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarkerHelper? = null
    private var controller: GestureCaptureController? = null
    private var badgeView: android.widget.LinearLayout? = null
    private var badgeLabel: android.widget.TextView? = null
    private var destroyed = false
    /** Only the pre-grab stage extends its idle timeout on fresh hand frames. */
    private var preGrabStage = true

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val roll = Math.toDegrees(
                Math.atan2(event.values[0].toDouble(), event.values[2].toDouble()),
            ).toFloat()
            mainHandler.post { controller?.onRoll(roll) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        sessionActive = true
        // Overlay FIRST: it gives this uid a visible window before the
        // camera-typed startForeground eligibility check runs.
        showBadge(getString(R.string.photo_gesture_badge_camera_start))
        startCameraForeground()

        controller = GestureCaptureController(this, ::onPhase)
        controller?.startScanning()
        registerSensors()
        bindCamera()
        armTimeout(IDLE_TIMEOUT_MILLIS)
    }

    override fun onDestroy() {
        destroyed = true
        sessionActive = false
        mainHandler.removeCallbacksAndMessages(null)
        hideBadge()
        (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.unregisterListener(sensorListener)
        analysisExecutor.shutdown()
        landmarker?.close()
        landmarker = null
        controller?.shutdown()
        controller = null
        super.onDestroy()
    }

    // ----------------------------------------------------------------- FGS

    private fun startCameraForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.photo_gesture_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setSound(null, null) },
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_alliance)
            .setContentTitle(getString(R.string.photo_gesture_notif_title))
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    // --------------------------------------------------------------- camera

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                landmarker = HandLandmarkerHelper(
                    context = this,
                    onFrame = { frame ->
                        // MediaPipe calls back on its own thread.
                        mainHandler.post {
                            if (destroyed) return@post
                            if (preGrabStage && frame.gesture != HandGesture.OTHER) {
                                armTimeout(IDLE_TIMEOUT_MILLIS)
                            }
                            controller?.onGestureFrame(frame)
                        }
                    },
                    onError = { message ->
                        // Runtime landmarker hiccups must not kill the session;
                        // a real bind failure is handled below.
                        Log.w(TAG, "capture landmarker: $message")
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
                            landmarker?.detectAsync(mediaImage, SystemClock.elapsedRealtime())
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Capture analysis frame failed", error)
                    } finally {
                        imageProxy.close()
                    }
                }
                // Analysis-only bind: no Preview use case, no surface needed.
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (error: Exception) {
                Log.e(TAG, "Capture camera bind failed", error)
                mainHandler.post {
                    showBadge(getString(R.string.photo_gesture_fail_camera))
                    stopSelf()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun registerSensors() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            try {
                manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to register capture tilt sensor", error)
            }
        }
    }

    // ------------------------------------------------------------- timeouts

    private var timeoutAction: Runnable? = null

    private fun armTimeout(millis: Long) {
        timeoutAction?.let(mainHandler::removeCallbacks)
        val action = Runnable {
            if (!destroyed) {
                Log.i(TAG, "Capture session timed out")
                stopSelf()
            }
        }
        timeoutAction = action
        mainHandler.postDelayed(action, millis)
    }

    private fun disarmTimeout() {
        timeoutAction?.let(mainHandler::removeCallbacks)
        timeoutAction = null
    }

    // ----------------------------------------------------------- phase flow

    private fun onPhase(phase: CapturePhase) {
        if (destroyed) return
        preGrabStage = phase is CapturePhase.Waiting || phase is CapturePhase.Grabbing
        when (phase) {
            CapturePhase.Waiting ->
                showBadge(getString(R.string.photo_gesture_badge_waiting))
            CapturePhase.Grabbing -> {
                showBadge(getString(R.string.photo_gesture_badge_grabbing))
                armTimeout(GRABBING_TIMEOUT_MILLIS)
            }
            is CapturePhase.Ready -> {
                disarmTimeout()
                armTimeout(READY_TIMEOUT_MILLIS)
                showBadge(
                    phase.targetName?.let { name ->
                        getString(R.string.photo_gesture_badge_ready_with_target, name)
                    } ?: getString(R.string.photo_gesture_badge_ready_no_target),
                )
            }
            is CapturePhase.Sending -> {
                disarmTimeout()
                armTimeout(SENDING_TIMEOUT_MILLIS)
                showBadge(
                    getString(R.string.photo_gesture_badge_sending, phase.targetName, phase.percent),
                )
            }
            is CapturePhase.Done -> {
                disarmTimeout()
                armTimeout(EXIT_AFTER_DONE_MILLIS)
                val text = when (phase.message) {
                    GestureCaptureController.NO_PHOTO_MESSAGE ->
                        getString(R.string.photo_gesture_fail_no_photo)
                    GestureCaptureController.NO_TARGET_MESSAGE ->
                        getString(R.string.photo_gesture_fail_no_target)
                    GestureCaptureController.FAIL_MESSAGE ->
                        getString(R.string.photo_gesture_fail_send)
                    GestureCaptureController.CANCELLED_MESSAGE ->
                        getString(R.string.photo_gesture_fail_cancelled)
                    GestureCaptureController.ACCEPTED_MESSAGE ->
                        getString(R.string.photo_gesture_badge_done_accepted)
                    else ->
                        if (phase.success) getString(R.string.photo_gesture_badge_done_success)
                        else getString(R.string.photo_gesture_fail_send)
                }
                showBadge(text)
            }
        }
    }

    // --------------------------------------------------------------- badge

    private fun showBadge(text: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val existing = badgeView
        if (existing != null) {
            badgeLabel?.text = text
            return
        }
        try {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 28, 48, 28)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 56f
                    setColor(0xEE1B1B1B.toInt())
                    setStroke(2, 0xFF43D077.toInt())
                }
                elevation = 16f
                isClickable = true
                isFocusable = false
            }
            val label = android.widget.TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            }
            container.addView(label)
            // Tapping the badge cancels the session — the only interaction
            // left, and it is an exit, not a confirmation.
            container.setOnClickListener {
                controller?.cancel()
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 48
            wm.addView(container, params)
            badgeView = container
            badgeLabel = label
        } catch (error: Exception) {
            Log.w(TAG, "Failed to show capture badge", error)
        }
    }

    private fun hideBadge() {
        val view = badgeView ?: return
        badgeView = null
        badgeLabel = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to remove capture badge", error)
        }
    }

    companion object {
        private const val CHANNEL_ID = "gesture_capture"
        private const val NOTIFICATION_ID = 4101
        private const val IDLE_TIMEOUT_MILLIS = 8_000L
        private const val GRABBING_TIMEOUT_MILLIS = 12_000L
        private const val READY_TIMEOUT_MILLIS = 30_000L
        private const val SENDING_TIMEOUT_MILLIS = 60_000L
        private const val EXIT_AFTER_DONE_MILLIS = 2_500L

        /** True while a capture session is on screen. */
        @Volatile
        var sessionActive: Boolean = false
            private set

        /** Launches a fresh direct-capture session. */
        fun start(context: Context) {
            val intent = Intent(context, GestureCaptureService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
