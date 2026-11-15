package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class HandWaveDetectorTest {
    private var waves = 0
    private val detector = HandWaveDetector(
        HandWaveDetector.Config(),
        object : HandWaveDetector.Listener {
            override fun onWave() { waves++ }
        },
    )

    private fun frame(x: Float?) = GestureFrame(HandGesture.OTHER, x, 0.5f)

    private fun sweep(from: Float, to: Float, startMillis: Long, steps: Int = 6): Long {
        var t = startMillis
        for (i in 1..steps) {
            t += 40L
            val x = from + (to - from) * i / steps
            detector.onFrame(frame(x), t)
        }
        return t
    }

    @Test
    fun `side to side wave fires once`() {
        var t = sweep(0.5f, 0.15f, 0L)
        t = sweep(0.15f, 0.80f, t)
        t = sweep(0.80f, 0.20f, t)
        assertEquals(1, waves)
    }

    @Test
    fun `straight movement does not fire`() {
        sweep(0.2f, 0.8f, 0L)
        assertEquals(0, waves)
    }

    @Test
    fun `small jitter does not fire`() {
        var t = 0L
        var x = 0.5f
        for (i in 1..12) {
            t += 60L
            x = if (i % 2 == 0) x + 0.02f else x - 0.02f
            detector.onFrame(frame(x), t)
        }
        assertEquals(0, waves)
    }

    @Test
    fun `hand leaving frame aborts the burst`() {
        var t = sweep(0.5f, 0.15f, 0L)
        detector.onFrame(frame(null), t)
        t = sweep(0.15f, 0.80f, t)
        assertEquals(0, waves)
    }

    @Test
    fun `a second wave after the quiet period fires again`() {
        var t = sweep(0.5f, 0.15f, 0L)
        t = sweep(0.15f, 0.80f, t)
        t = sweep(0.80f, 0.20f, t)
        assertEquals(1, waves)
        // Quiet period (burst+quiet = 1600ms) must elapse before re-arming.
        t += 1700L
        t = sweep(0.20f, 0.70f, t)
        t = sweep(0.70f, 0.25f, t)
        t = sweep(0.25f, 0.75f, t)
        assertEquals(2, waves)
    }

    @Test
    fun `reset clears pending burst`() {
        sweep(0.5f, 0.15f, 0L)
        detector.reset()
        var t = sweep(0.15f, 0.80f, 100L)
        t = sweep(0.80f, 0.20f, t)
        // Only one flip happened after the reset; nothing fired.
        assertEquals(0, waves)
    }
}
