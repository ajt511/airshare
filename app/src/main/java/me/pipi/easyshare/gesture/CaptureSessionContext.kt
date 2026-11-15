package me.pipi.easyshare.gesture

/**
 * One-shot handoff from the accessibility service to the capture service:
 * which app/window triggered the session and what hints its photo page's
 * node tree offered (used by [PhotoContentGrabber] to find the viewed image).
 */
object CaptureSessionContext {
    /** Foreground package that triggered the gesture session. */
    @Volatile
    var sourcePackage: String? = null

    /** "package/class" of the triggering window (dedupe/debug aid). */
    @Volatile
    var windowKey: String? = null

    /** contentDescription / text tokens harvested from the photo page. */
    @Volatile
    var nodeHints: List<String> = emptyList()

    fun clear() {
        sourcePackage = null
        windowKey = null
        nodeHints = emptyList()
    }
}
