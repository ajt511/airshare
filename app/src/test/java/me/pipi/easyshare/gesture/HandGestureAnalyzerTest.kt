package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Synthetic hand landmark sets in MediaPipe ordering, built around a wrist at
 * (0.5, 0.9). Extended fingers place the tip far from the wrist; curled
 * fingers fold it back behind the PIP joint, matching the analyzer's
 * distance-based rules.
 */
class HandGestureAnalyzerTest {
    private val analyzer = HandGestureAnalyzer()

    private fun openPalm(): HandLandmarks = buildHand(
        listOf(true, true, true, true),
        thumbExtended = true,
    )

    private fun fist(): HandLandmarks = buildHand(
        listOf(false, false, false, false),
        thumbExtended = false,
    )

    private fun partialCurl(): HandLandmarks = buildHand(
        listOf(true, true, false, false),
        thumbExtended = true,
    )

    /**
     * @param extended one flag per finger (index, middle, ring, pinky)
     */
    private fun buildHand(extended: List<Boolean>, thumbExtended: Boolean): HandLandmarks {
        val wrist = HandLandmarks.Point(0.5f, 0.9f)
        val directions = listOf(-0.45f to 1.15f, -0.15f to 1.2f, 0.15f to 1.18f, 0.45f to 1.1f)
        val points = mutableListOf(wrist)
        // Thumb chain (indices 1..4).
        val thumbReach = if (thumbExtended) 0.30f else 0.12f
        points += HandLandmarks.Point(wrist.x - 0.08f, wrist.y - 0.05f)
        points += HandLandmarks.Point(wrist.x - 0.14f, wrist.y - 0.08f)
        points += HandLandmarks.Point(wrist.x - 0.19f, wrist.y - 0.10f)
        points += HandLandmarks.Point(wrist.x - 0.22f, wrist.y - 0.12f - thumbReach)
        for ((index, dxToDy) in directions.withIndex()) {
            val isExtended = extended[index]
            val (dx, dy) = dxToDy
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val ux = dx / length
            val uy = dy / length
            // MCP always sits at 0.18 from the wrist; the tip either reaches
            // 0.52 (extended) or folds back to 0.16 (curled).
            points += scaled(wrist, ux, uy, 0.18f)
            points += scaled(wrist, ux, uy, 0.30f)
            points += scaled(wrist, ux, uy, if (isExtended) 0.42f else 0.22f)
            points += scaled(wrist, ux, uy, if (isExtended) 0.52f else 0.16f)
        }
        return HandLandmarks(points)
    }

    private fun scaled(wrist: HandLandmarks.Point, ux: Float, uy: Float, distance: Float) =
        HandLandmarks.Point(wrist.x + ux * distance, wrist.y - uy * distance)

    @Test
    fun `open palm classifies as OPEN_PALM`() {
        val frame = analyzer.analyze(openPalm())
        assertEquals(HandGesture.OPEN_PALM, frame.gesture)
        assertEquals(0.5f, frame.centerX!!, 0.05f)
    }

    @Test
    fun `fist classifies as FIST`() {
        val frame = analyzer.analyze(fist())
        assertEquals(HandGesture.FIST, frame.gesture)
    }

    @Test
    fun `partial curl classifies as OTHER`() {
        val frame = analyzer.analyze(partialCurl())
        assertEquals(HandGesture.OTHER, frame.gesture)
    }

    @Test
    fun `rotation does not change classification`() {
        val palm = openPalm()
        val rotated = HandLandmarks(palm.points.map { point ->
            val dx = point.x - 0.5
            val dy = point.y - 0.9
            HandLandmarks.Point(
                x = (0.5f + (dx * cos(1.5708) - dy * sin(1.5708))).toFloat(),
                y = (0.9f + (dx * sin(1.5708) + dy * cos(1.5708))).toFloat(),
            )
        })
        assertEquals(HandGesture.OPEN_PALM, analyzer.analyze(rotated).gesture)
    }
}
