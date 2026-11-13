package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    private var shakes = 0
    private val detector = ShakeDetector(
        ShakeDetector.Config(threshold = 18f, strokes = 3, burstMillis = 600L, quietMillis = 700L),
        object : ShakeDetector.Listener {
            override fun onShake() { shakes++ }
        },
    )

    private fun stroke(startAt: Long): Long {
        var t = startAt
        detector.onSample(t, 20f)
        t += 60
        detector.onSample(t, 2f)
        t += 60
        detector.onSample(t, 21f)
        t += 60
        detector.onSample(t, 3f)
        t += 60
        detector.onSample(t, 19f)
        return t
    }

    @Test
    fun `three quick strokes fire one shake`() {
        stroke(startAt = 1000L)
        assertEquals(1, shakes)
    }

    @Test
    fun `calm movement does not fire`() {
        repeat(10) { t -> detector.onSample(t * 100L, 3f) }
        assertEquals(0, shakes)
    }

    @Test
    fun `one long shake bursts only once`() {
        var t = stroke(1000L)
        stroke(t)
        assertEquals(1, shakes)
    }

    @Test
    fun `second shake allowed after quiet period`() {
        stroke(1000L)
        assertEquals(1, shakes)
        stroke(3000L)
        assertEquals(2, shakes)
    }

    @Test
    fun `magnitude strips gravity`() {
        // A device lying flat reads ~9.81 on z; free-fall magnitude is ~0.
        assertTrue(ShakeDetector.magnitude(0f, 0f, 9.81f) < 0.5f)
        assertEquals(0f, ShakeDetector.magnitude(0f, 0f, 0f), 0.01f)
    }
}
