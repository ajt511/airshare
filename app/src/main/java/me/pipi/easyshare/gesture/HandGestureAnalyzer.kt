package me.pipi.easyshare.gesture

import kotlin.math.hypot

/**
 * Classifies a MediaPipe hand-landmark frame into [HandGesture] with plain
 * geometry: a finger counts as extended when its tip sits farther from the
 * wrist than its knuckle (MCP) does by a configurable margin, and curled when
 * the tip falls back behind the middle joint.
 */
class HandGestureAnalyzer(
    private val config: Config = Config(),
) {
    data class Config(
        /** tip must be this many times farther from the wrist than the MCP to count as extended */
        val extendRatio: Float = 1.30f,
        /** tip is curled in when it is closer to the wrist than the PIP by this margin factor */
        val curlRatio: Float = 1.05f,
        /** number of extended fingers (index..pinky) that counts as an open palm */
        val openFingerThreshold: Int = 4,
        /** number of extended fingers at or below which the pose counts as a fist */
        val fistFingerThreshold: Int = 1,
    )

    fun analyze(landmarks: HandLandmarks): GestureFrame {
        val wrist = landmarks.wrist
        var extended = 0
        var curled = 0
        for (finger in intArrayOf(
            HandLandmarks.INDEX_TIP, HandLandmarks.MIDDLE_TIP,
            HandLandmarks.RING_TIP, HandLandmarks.PINKY_TIP,
        )) {
            val tip = landmarks.points[finger]
            val mcp = landmarks.points[finger - 3]
            val pip = landmarks.points[finger - 2]
            val tipDistance = distance(tip, wrist)
            val mcpDistance = distance(mcp, wrist)
            val pipDistance = distance(pip, wrist)
            when {
                tipDistance > mcpDistance * config.extendRatio -> extended++
                tipDistance < pipDistance * config.curlRatio -> curled++
            }
        }
        val gesture = when {
            extended >= config.openFingerThreshold -> HandGesture.OPEN_PALM
            extended <= config.fistFingerThreshold -> HandGesture.FIST
            else -> HandGesture.OTHER
        }
        val center = if (landmarks.points.size >= HandLandmarks.PINKY_MCP + 1) {
            val mcpPoints = listOf(
                landmarks.points[HandLandmarks.INDEX_MCP],
                landmarks.points[HandLandmarks.MIDDLE_MCP],
                landmarks.points[HandLandmarks.RING_MCP],
                landmarks.points[HandLandmarks.PINKY_MCP],
            )
            HandLandmarks.Point(
                x = (mcpPoints.sumOf { it.x.toDouble() } / mcpPoints.size).toFloat(),
                y = (mcpPoints.sumOf { it.y.toDouble() } / mcpPoints.size).toFloat(),
            )
        } else {
            null
        }
        return GestureFrame(gesture, center?.x, center?.y)
    }

    private fun distance(a: HandLandmarks.Point, b: HandLandmarks.Point): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
