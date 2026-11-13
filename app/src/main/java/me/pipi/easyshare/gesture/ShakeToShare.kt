package me.pipi.easyshare.gesture

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.GestureTransferActivity
import me.pipi.easyshare.utils.TAG

/**
 * App-wide "shake to share": while any AirShare activity is resumed and the
 * setting is on, a firm shake jumps straight into the gesture screen.
 * Registered from the application's activity lifecycle callbacks.
 */
object ShakeToShare : SensorEventListener {
    private var detector: ShakeDetector? = null
    private var currentActivity: Activity? = null

    fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        if (!AppSettings(activity).shakeToShareEnabled) return
        register(activity)
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
        unregister(activity)
    }

    private fun register(activity: Activity) {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (detector == null) {
            detector = ShakeDetector { onShake() }
        }
        try {
            manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to register shake listener", error)
        }
    }

    private fun unregister(activity: Activity) {
        val manager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        detector?.onSample(
            SystemClock.elapsedRealtime(),
            ShakeDetector.magnitude(event.values[0], event.values[1], event.values[2]),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onShake() {
        val activity = currentActivity ?: return
        if (activity is GestureTransferActivity) return
        activity.startActivity(GestureTransferActivity.pickIntent(activity))
    }
}
