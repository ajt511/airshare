package me.pipi.easyshare.gesture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.display.DisplayManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
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
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.R
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.utils.FileInfoFactory
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.vision.HandLandmarkerHelper
import java.io.File
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

        controller = GestureCaptureController(
            this,
            ::onPhase,
            screenFallbackRequested = { startScreenCaptureFallback() },
        )
        controller?.startScanning()
        registerSensors()
        bindCamera()
    }

    private fun idleTimeoutMillis(): Long =
        if (runningMode == AppSettings.CAMERA_MODE_PERSISTENT) {
            PERSISTENT_IDLE_GUARD_MILLIS
        } else {
            IDLE_TIMEOUT_MILLIS
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { runningMode = it }
        // The mode extra only arrives here (after onCreate), so the idle
        // guard is armed per-start here rather than in onCreate.
        armTimeout(idleTimeoutMillis())
        return super.onStartCommand(intent, flags, startId)
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
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildForegroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_alliance)
            .setContentTitle(getString(R.string.photo_gesture_notif_title))
            .setOngoing(true)
            .build()

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
                                armTimeout(idleTimeoutMillis())
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
        preGrabStage = phase is CapturePhase.Waiting ||
            phase is CapturePhase.PalmReady ||
            phase is CapturePhase.Grabbing
        sending = phase is CapturePhase.Sending
        when (phase) {
            CapturePhase.Waiting ->
                showBadge(getString(R.string.photo_gesture_badge_waiting))
            CapturePhase.PalmReady ->
                showBadge(getString(R.string.photo_gesture_badge_palm_detected))
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
                if (runningMode == AppSettings.CAMERA_MODE_PERSISTENT) {
                    if (pendingStopOnIdle) {
                        // The user left the photo page mid-send; now that the
                        // transfer is done, close the session instead of
                        // re-arming the camera off the photo page.
                        pendingStopOnIdle = false
                        mainHandler.postDelayed({ if (!destroyed) stopSelf() }, EXIT_AFTER_DONE_MILLIS)
                    } else {
                        // Stay armed on the photo page: next grab cycle in place.
                        mainHandler.postDelayed({
                            if (!destroyed) controller?.resetSession()
                        }, EXIT_AFTER_DONE_MILLIS)
                    }
                } else {
                    armTimeout(EXIT_AFTER_DONE_MILLIS)
                }
            }
        }
    }

    // ---------------------------------------------------- screenshot fallback

    /**
     * QQ/WeChat without Shizuku: grab the viewed image as a screen capture
     * cropped to the photo view's bounds. MediaProjection consent is
     * mandatory on Android — one system dialog per grab.
     */
    private fun startScreenCaptureFallback() {
        lifecycleScope.launch {
            showBadge(getString(R.string.photo_gesture_badge_screen_consent))
            val signal = CaptureSessionContext.beginProjectionConsent()
            try {
                startActivity(
                    CaptureConsentActivity.intent(this@GestureCaptureService)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to launch consent activity", error)
                controller?.failFromHost(GestureCaptureController.NO_PHOTO_MESSAGE)
                return@launch
            }
            val consent = CaptureSessionContext.awaitProjectionConsent(SCREEN_CONSENT_TIMEOUT_MILLIS)
            if (destroyed) return@launch
            if (consent == null) {
                controller?.failFromHost(GestureCaptureController.NO_PHOTO_MESSAGE)
                return@launch
            }
            val file = runCatching { captureScreenOnce(consent.first, consent.second) }
                .onFailure { Log.w(TAG, "Screen capture failed", it) }
                .getOrNull()
            if (file == null) {
                controller?.failFromHost(GestureCaptureController.NO_PHOTO_MESSAGE)
            } else {
                controller?.acceptCapturedContent(listOf(file))
            }
        }
    }

    @SuppressLint("WrongConstant")
    private suspend fun captureScreenOnce(resultCode: Int, data: Intent): FileInfo? {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        val width = real.widthPixels
        val height = real.heightPixels

        // The mediaProjection FGS type must be active before the projection
        // is created; the overlay badge already gives a visible window.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildForegroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return null
        var reader: ImageReader? = null
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            val frame = CompletableDeferred<android.media.Image>()
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { image ->
                    if (!frame.isCompleted) frame.complete(image)
                }
            }, mainHandler)
            display = projection.createVirtualDisplay(
                "airshare-capture", width, height, real.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, mainHandler,
            )
            val image = withTimeoutOrNull(FRAME_TIMEOUT_MILLIS) { frame.await() } ?: return null
            val bitmap = image.use { img ->
                val plane = img.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                // Row stride may exceed width*4 (device padding): copy into a
                // stride-wide bitmap first, then crop the real columns.
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val strideWidth = rowStride / pixelStride
                val raw = Bitmap.createBitmap(strideWidth, img.height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(buffer)
                if (strideWidth == img.width) raw
                else Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
            }
            val cropped = cropBitmap(bitmap, CaptureSessionContext.photoBounds)
            return saveCaptureToCache(cropped)
        } finally {
            try { display?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            // Drop the mediaProjection FGS type again; the session continues
            // as a plain camera service.
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            }
        }
    }

    /** Crops to the photo view's bounds; falls back to the full screen. */
    private fun cropBitmap(src: Bitmap, bounds: Rect?): Bitmap {
        if (bounds == null) return src
        val r = Rect(bounds)
        r.intersect(0, 0, src.width, src.height)
        if (r.width() < 64 || r.height() < 64) return src
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    private fun saveCaptureToCache(bitmap: Bitmap): FileInfo? {
        return try {
            val dir = File(cacheDir, "photo_grab").apply { mkdirs() }
            val out = File(dir, "screen_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileProvider", out)
            runCatching { FileInfoFactory.fromUri(this, uri) }.getOrNull()
                ?.copy(name = out.name, nameIsFallback = false)
        } catch (error: Exception) {
            Log.w(TAG, "Save screen capture failed", error)
            null
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
        private const val PERSISTENT_IDLE_GUARD_MILLIS = 60_000L
        private const val SCREEN_CONSENT_TIMEOUT_MILLIS = 30_000L
        private const val FRAME_TIMEOUT_MILLIS = 10_000L

        /** True while a capture session is on screen. */
        @Volatile
        var sessionActive: Boolean = false
            private set

        /** Camera mode of the running session (onDemand / persistent). */
        @Volatile
        var runningMode: String = AppSettings.CAMERA_MODE_ON_DEMAND
            private set

        /** True while a grab's transfer is in flight. */
        @Volatile
        var sending: Boolean = false
            private set

        /** A stop was requested mid-send; run it once the transfer ends. */
        @Volatile
        var pendingStopOnIdle: Boolean = false
            private set

        /** Launches a fresh direct-capture session in the given mode. */
        fun start(context: Context, mode: String = AppSettings.CAMERA_MODE_ON_DEMAND) {
            val intent = Intent(context, GestureCaptureService::class.java)
                .putExtra(EXTRA_MODE, mode)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops a running session (persistent mode leaves the photo page);
         *  mid-send stops are deferred until the transfer finishes. */
        fun stop(context: Context) {
            if (sending) {
                pendingStopOnIdle = true
                return
            }
            context.stopService(Intent(context, GestureCaptureService::class.java))
        }

        private const val EXTRA_MODE = "cameraMode"
    }
}
