package me.pipi.easyshare.gesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.utils.TAG

/**
 * Listens for window switches and, when the foreground looks like image
 * browsing or file picking (and the photo-gesture setting is on), starts a
 * [GestureCaptureService] session right away: the status badge overlays the
 * current app and the grab gesture sends the viewed image without any tap,
 * jump or confirmation. This service itself stays a cheap event listener;
 * harvesting contentDescription hints from the photo page's node tree
 * (galleries put the file name there) happens on the main-thread event
 * callback, along with the largest image view's screen bounds for the
 * screenshot fallback crop.
 *
 * Persistent camera mode: while the setting is on, the capture session
 * stays alive as long as the user remains on a photo page and is stopped
 * as soon as they switch to a non-photo window.
 */
class PhotoWatchService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggeredMillis = 0L
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
        if (!settings.photoGestureEnabled) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) {
            // Our own screen is up; reset tracking so returning to the same
            // gallery window can trigger again.
            lastWindowKey = null
            return
        }
        val className = event.className?.toString()
        val windowKey = pkg + "/" + (className ?: "")
        if (windowKey == lastWindowKey) return
        lastWindowKey = windowKey
        val persistent = settings.photoGestureCameraMode == AppSettings.CAMERA_MODE_PERSISTENT
        if (!PhotoContextMatcher.matches(pkg, className)) {
            // Persistent mode: leaving the photo page ends the session.
            if (persistent && GestureCaptureService.sessionActive &&
                GestureCaptureService.runningMode == AppSettings.CAMERA_MODE_PERSISTENT
            ) {
                GestureCaptureService.stop(this)
                // Allow returning to the same photo window to re-trigger.
                lastWindowKey = null
            }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggeredMillis < COOLDOWN_MILLIS) return
        if (!Settings.canDrawOverlays(this)) return
        if (GestureCaptureService.sessionActive) return
        lastTriggeredMillis = now

        // getRootInActiveWindow is @MainThread; the bounded BFS costs only
        // a few dozen IPC hops on the already-main-thread event callback.
        CaptureSessionContext.sourcePackage = pkg
        CaptureSessionContext.windowKey = windowKey
        val scan = scanPhotoPage()
        CaptureSessionContext.nodeHints = scan.first
        CaptureSessionContext.photoBounds = scan.second
        GestureCaptureService.start(this, settings.photoGestureCameraMode)
    }

    /**
     * Bounded BFS over the active window's nodes: descriptive tokens for the
     * gallery lookup plus the screen bounds of the largest image view (the
     * crop region for the screenshot fallback).
     */
    private fun scanPhotoPage(): Pair<List<String>, Rect?> {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return emptyList<String>() to null
        val hints = ArrayList<String>()
        var bestBounds: Rect? = null
        var bestArea = 0
        val metrics = resources.displayMetrics
        val minArea = metrics.widthPixels * metrics.heightPixels / 12
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && hints.size < MAX_HINTS && visited < MAX_NODES) {
            val node = queue.removeFirstOrNull() ?: break
            visited++
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(hints::add)
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(hints::add)
            if (node.className?.toString()?.contains("ImageView") == true) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val area = bounds.width() * bounds.height()
                if (area > bestArea && area >= minArea) {
                    bestArea = area
                    bestBounds = bounds
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return hints to bestBounds
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val COOLDOWN_MILLIS = 10_000L
        private const val MAX_HINTS = 8
        private const val MAX_NODES = 80

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
