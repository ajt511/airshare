package me.pipi.easyshare.gesture

/** The gestures AirShare recognizes with the front camera. */
enum class HandGesture {
    /** All four fingers fully extended — the "show palm" pose. */
    OPEN_PALM,

    /** All fingers curled in — the "grab" pose. */
    FIST,

    /** Anything in between or off-frame; never triggers a transition. */
    OTHER,
}

/**
 * Normalized hand landmarks in MediaPipe ordering (21 points, x/y in [0,1]
 * relative to the image, origin top-left).
 */
data class HandLandmarks(val points: List<Point>) {
    data class Point(val x: Float, val y: Float, val z: Float = 0f)

    val wrist: Point get() = points[0]

    companion object {
        // MediaPipe hand landmark indices.
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_MCP = 5
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_TIP = 12
        const val RING_MCP = 13
        const val RING_TIP = 16
        const val PINKY_MCP = 17
        const val PINKY_TIP = 20
    }
}

/** A single classified frame with the hand's normalized center, if visible. */
data class GestureFrame(
    val gesture: HandGesture,
    /** Hand center x in [0,1]; null when no hand is visible. */
    val centerX: Float?,
    /** Hand center y in [0,1]; null when no hand is visible. */
    val centerY: Float?,
)
