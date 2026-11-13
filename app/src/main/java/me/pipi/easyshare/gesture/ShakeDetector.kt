package me.pipi.easyshare.gesture

import kotlin.math.sqrt

/**
 * Accelerometer-driven shake detector with pure logic — the platform feeds it
 * gravity-compensated magnitude samples. A shake is reported once per burst;
 * the burst ends after [Config.quietMillis] without a strong sample.
 */
class ShakeDetector(
    private val config: Config = Config(),
    private val listener: Listener,
) {
    interface Listener {
        fun onShake()
    }

    data class Config(
        /** Acceleration magnitude (m/s², minus gravity) that counts as a stroke. */
        val threshold: Float = 18f,
        /** Strong strokes required inside [burstMillis] to fire. */
        val strokes: Int = 3,
        val burstMillis: Long = 600L,
        /** Silence after which a new burst may fire again. */
        val quietMillis: Long = 700L,
    )

    private var burstStart = 0L
    private var lastStroke = 0L
    private var strokeCount = 0
    private var fired = false

    fun reset() {
        burstStart = 0L
        lastStroke = 0L
        strokeCount = 0
        fired = false
    }

    /**
     * Feed one accelerometer sample. [timestampMillis] is monotonic
     * (SystemClock.elapsedRealtime), [magnitude] is the gravity-free norm.
     */
    fun onSample(timestampMillis: Long, magnitude: Float) {
        val sinceStroke = timestampMillis - lastStroke
        if (sinceStroke > config.burstMillis) {
            strokeCount = 0
            if (timestampMillis - lastStroke > config.burstMillis + config.quietMillis) {
                fired = false
            }
            if (timestampMillis - burstStart > config.burstMillis) burstStart = 0L
        }
        if (magnitude >= config.threshold) {
            if (burstStart == 0L) burstStart = timestampMillis
            strokeCount++
            lastStroke = timestampMillis
            if (!fired && strokeCount >= config.strokes &&
                timestampMillis - burstStart <= config.burstMillis
            ) {
                fired = true
                listener.onShake()
            }
        }
    }

    companion object {
        /** Gravity-free magnitude for raw (x, y, z) values that still include gravity; never negative. */
        fun magnitude(x: Float, y: Float, z: Float): Float =
            maxOf(0f, sqrt(x * x + y * y + z * z) - 9.81f)
    }
}
