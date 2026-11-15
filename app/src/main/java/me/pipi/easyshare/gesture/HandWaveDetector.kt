package me.pipi.easyshare.gesture

/**
 * Detects a horizontal hand wave (side-to-side) from normalized gesture
 * frames — the "reject" answer in the incoming-transfer gesture confirmation.
 * Pure logic with no Android dependencies: feed camera frames as they arrive,
 * with monotonic timestamps (SystemClock.elapsedRealtime).
 */
class HandWaveDetector(
    private val config: Config = Config(),
    private val listener: Listener,
) {
    interface Listener {
        fun onWave()
    }

    data class Config(
        /** Net horizontal travel (normalized 0..1) required within one burst. */
        val travelThreshold: Float = 0.30f,
        /** Direction flips required within one burst. */
        val flips: Int = 2,
        /** Window a burst must complete within. */
        val burstMillis: Long = 900L,
        /** Extra quiet time before the same gesture can fire again. */
        val quietMillis: Long = 700L,
        /** Frame-to-frame jitter below this is ignored. */
        val moveEpsilon: Float = 0.04f,
    )

    private enum class Direction { NONE, LEFT, RIGHT }

    private var lastX: Float? = null
    private var direction = Direction.NONE
    private var flips = 0
    private var minX = 0f
    private var maxX = 0f
    private var burstStartMillis = 0L
    private var lastFireMillis = -Long.MAX_VALUE
    private var fired = false

    fun onFrame(frame: GestureFrame, timestampMillis: Long) {
        val x = frame.centerX ?: run {
            // Hand left the frame — abort any in-progress burst.
            resetBurst(timestampMillis)
            return
        }
        val previous = lastX
        lastX = x
        if (previous == null || timestampMillis - burstStartMillis > config.burstMillis) {
            resetBurst(timestampMillis)
            minX = x
            maxX = x
            lastX = x
            return
        }
        val dx = x - previous
        if (kotlin.math.abs(dx) < config.moveEpsilon) return
        val newDirection = if (dx < 0) Direction.LEFT else Direction.RIGHT
        if (direction != Direction.NONE && newDirection != direction) flips++
        direction = newDirection
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (!fired &&
            flips >= config.flips &&
            (maxX - minX) >= config.travelThreshold &&
            timestampMillis - lastFireMillis >= config.burstMillis + config.quietMillis
        ) {
            fired = true
            lastFireMillis = timestampMillis
            listener.onWave()
        }
    }

    fun reset() {
        resetBurst(0L)
    }

    private fun resetBurst(timestampMillis: Long) {
        lastX = null
        direction = Direction.NONE
        flips = 0
        fired = false
        burstStartMillis = timestampMillis
    }
}
