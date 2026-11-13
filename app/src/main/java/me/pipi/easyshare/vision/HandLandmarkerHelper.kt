package me.pipi.easyshare.vision

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import me.pipi.easyshare.gesture.GestureFrame
import me.pipi.easyshare.gesture.HandGesture
import me.pipi.easyshare.gesture.HandGestureAnalyzer
import me.pipi.easyshare.gesture.HandLandmarks

/**
 * Wraps MediaPipe Hand Landmarker in LIVE_STREAM mode and converts each
 * result into a [GestureFrame] via the geometric analyzer. The analyzer only
 * consumes inter-landmark distances, which are invariant to sensor
 * orientation, so frames need no rotation handling for classification.
 */
class HandLandmarkerHelper(
    context: Context,
    private val onFrame: (GestureFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val analyzer = HandGestureAnalyzer()
    private var landmarker: HandLandmarker? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> emit(result) }
                .setErrorListener { error ->
                    Log.e(TAG, "Hand landmarker error", error)
                    onError(error.message ?: "hand landmarker error")
                }
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to create hand landmarker", error)
            onError(error.message ?: "failed to create hand landmarker")
        }
    }

    /** Runs one async detection on a YUV_420_888 camera image. */
    fun detectAsync(image: android.media.Image, timestampMillis: Long) {
        val landmarker = landmarker ?: return
        try {
            landmarker.detectAsync(MediaImageBuilder(image).build(), timestampMillis)
        } catch (error: Exception) {
            Log.w(TAG, "detectAsync failed", error)
        }
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    private fun emit(result: HandLandmarkerResult) {
        val landmarks = result.landmarks().firstOrNull() ?: run {
            onFrame(GestureFrame(HandGesture.OTHER, null, null))
            return
        }
        val points = landmarks.map { HandLandmarks.Point(it.x(), it.y(), it.z()) }
        onFrame(analyzer.analyze(HandLandmarks(points)))
    }

    companion object {
        private const val TAG = "HandLandmarker"
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}
