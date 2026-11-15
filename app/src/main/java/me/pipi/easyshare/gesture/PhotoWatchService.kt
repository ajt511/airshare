package me.pipi.easyshare.gesture

import android.accessibilityservice.AccessibilityService
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
 * (galleries put the file name there) happens on a background thread.
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
        if (!PhotoContextMatcher.matches(pkg, className)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggeredMillis < COOLDOWN_MILLIS) return
        if (!Settings.canDrawOverlays(this)) return
        lastTriggeredMillis = now
        if (GestureCaptureService.sessionActive) return

        // getRootInActiveWindow is @MainThread; the bounded BFS costs only
        // a few dozen IPC hops on the already-main-thread event callback.
        CaptureSessionContext.sourcePackage = pkg
        CaptureSessionContext.windowKey = windowKey
        CaptureSessionContext.nodeHints = collectNodeHints()
        GestureCaptureService.start(this)
    }

    /** Bounded BFS over the active window's nodes for descriptive tokens. */
    private fun collectNodeHints(): List<String> {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val hints = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && hints.size < MAX_HINTS && visited < MAX_NODES) {
            val node = queue.removeFirstOrNull() ?: break
            visited++
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(hints::add)
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(hints::add)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return hints
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
