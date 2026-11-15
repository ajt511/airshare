package me.pipi.easyshare.gesture

import android.content.Intent
import android.graphics.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot handoff from the accessibility service to the capture service:
 * which app/window triggered the session, what hints its photo page's node
 * tree offered (used by [PhotoContentGrabber] to find the viewed image),
 * where the largest image view sits on screen (used to crop a screenshot
 * when no Shizuku is available), and the MediaProjection consent signal for
 * the screenshot fallback.
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

    /** Screen bounds of the largest image view on the photo page, if any. */
    @Volatile
    var photoBounds: Rect? = null

    fun clear() {
        sourcePackage = null
        windowKey = null
        nodeHints = emptyList()
        photoBounds = null
    }

    // ------------------------------------------------- projection consent

    private var consentSignal = newSignal()

    private fun newSignal() = CompletableDeferred<Pair<Int, Intent>?>()

    /** Arms a fresh consent wait; callers must await the returned signal. */
    fun beginProjectionConsent(): CompletableDeferred<Pair<Int, Intent>?> {
        consentSignal = newSignal()
        return consentSignal
    }

    /** The consent activity reports the user's choice (null = denied). */
    fun completeProjectionConsent(result: Pair<Int, Intent>?) {
        consentSignal.complete(result)
    }

    suspend fun awaitProjectionConsent(timeoutMillis: Long): Pair<Int, Intent>? =
        withTimeoutOrNull(timeoutMillis) { consentSignal.await() }
}
