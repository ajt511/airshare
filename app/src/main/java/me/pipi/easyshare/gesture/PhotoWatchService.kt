package me.pipi.easyshare.gesture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.GestureTransferActivity
import me.pipi.easyshare.R
import me.pipi.easyshare.utils.TAG

/**
 * Listens for window switches and, when the foreground looks like image
 * browsing or file picking (and the photo-gesture setting is on), shows a
 * tap-to-launch overlay pill. Tapping starts the transparent gesture screen,
 * which is what legally gains camera access (Android 11+ forbids background
 * camera use). The camera and the MediaPipe model only ever run inside that
 * screen; this service itself is a cheap event listener.
 */
class PhotoWatchService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pillView: LinearLayout? = null
    private var lastShownAtMillis = 0L
    /** Last seen foreground window as "package/class" — different windows
     *  inside the same app (list → detail → list) may each trigger. */
    private var lastWindowKey: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "PhotoWatchService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val settings = AppSettings(this)
        if (!settings.photoGestureEnabled) {
            hidePill()
            return
        }
        val pkg = event.packageName?.toString()
        if (pkg == packageName) {
            // Our own screen is up; reset tracking so returning to the same
            // gallery window can trigger again.
            lastWindowKey = null
            hidePill()
            return
        }
        val windowKey = pkg + "/" + (event.className?.toString() ?: "")
        if (windowKey == lastWindowKey) return
        lastWindowKey = windowKey
        if (!PhotoContextMatcher.matches(pkg, event.className?.toString())) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShownAtMillis < COOLDOWN_MILLIS) return
        if (!Settings.canDrawOverlays(this)) return
        lastShownAtMillis = now
        showPill()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hidePill()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showPill() {
        if (pillView != null) {
            rescheduleDismiss()
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
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
            val label = TextView(this).apply {
                text = getString(R.string.photo_gesture_pill_text)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
            }
            container.addView(label)
            container.setOnClickListener {
                hidePill()
                try {
                    startActivity(
                        Intent(this, GestureTransferActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                        },
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to launch gesture screen from overlay", error)
                }
            }
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 48
            wm.addView(container, params)
            pillView = container
            rescheduleDismiss()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to show photo-gesture pill", error)
        }
    }

    private fun rescheduleDismiss() {
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, PILL_AUTO_DISMISS_MILLIS)
    }

    private val dismissRunnable = Runnable { hidePill() }

    private fun hidePill() {
        val view = pillView ?: return
        pillView = null
        mainHandler.removeCallbacks(dismissRunnable)
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(view)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to remove photo-gesture pill", error)
        }
    }

    companion object {
        private const val PILL_AUTO_DISMISS_MILLIS = 8_000L
        private const val COOLDOWN_MILLIS = 10_000L

        /** Whether this service is currently enabled in system accessibility settings. */
        fun isEnabled(context: android.content.Context): Boolean {
            val expected = "${context.packageName}/${PhotoWatchService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
