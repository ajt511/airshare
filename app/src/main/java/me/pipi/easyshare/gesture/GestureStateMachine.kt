package me.pipi.easyshare.gesture

/**
 * Debounced state machine over raw gesture frames implementing the
 * grab-and-release air-gesture flow: hold an open palm to arm, close into a
 * fist to grab, then open the palm again to release. Every transition needs
 * the same pose held for [Config.stableFrames] consecutive frames (or
 * [Config.stableMillis] when the caller supplies timestamps) so camera noise
 * cannot trigger it.
 */
class GestureStateMachine(
    private val config: Config = Config(),
    private val listener: Listener,
) {
    interface Listener {
        /** Open palm held steady — show the "ready to grab" affordance. */
        fun onPalmHeld()

        /** Fist held steady after a palm — content is now "in hand". */
        fun onGrabbed()

        /** Palm opened again after a grab — release/send now. */
        fun onReleased()

        /** Hand left the frame or poses became ambiguous while grabbing. */
        fun onGrabLost()
    }

    enum class State { IDLE, PALM, GRABBED }

    data class Config(
        val stableFrames: Int = 5,
        /** Fallback stability window when frames carry timestamps; 0 disables it. */
        val stableMillis: Long = 450L,
    )

    var state: State = State.IDLE
        private set

    private var candidatePose: HandGesture? = null
    private var candidateCount = 0
    private var candidateStartMillis = 0L
    private var lastMillis = 0L

    fun reset() {
        val lost = state == State.GRABBED
        state = State.IDLE
        clearCandidate()
        if (lost) listener.onGrabLost()
    }

    /**
     * Feed one classified frame. [timestampMillis] is optional; when given
     * (monotonic, e.g. SystemClock.elapsedRealtime) the millisecond window is
     * used instead of the frame count.
     */
    fun onFrame(frame: GestureFrame, timestampMillis: Long = 0L) {
        if (timestampMillis > lastMillis) lastMillis = timestampMillis

        if (frame.gesture == HandGesture.OTHER) {
            if (state == State.GRABBED) {
                // A brief occlusion must not abort the flow, but a persistent
                // one should: use the same stability window as poses.
                if (isCandidateStable(HandGesture.OTHER, timestampMillis)) {
                    reset()
                }
            } else {
                clearCandidate()
                if (state == State.PALM) state = State.IDLE
            }
            return
        }

        when (state) {
            State.IDLE -> {
                if (frame.gesture == HandGesture.OPEN_PALM && isCandidateStable(HandGesture.OPEN_PALM, timestampMillis)) {
                    state = State.PALM
                    clearCandidate()
                    listener.onPalmHeld()
                } else if (frame.gesture != HandGesture.OPEN_PALM) {
                    clearCandidate()
                }
            }
            State.PALM -> {
                when (frame.gesture) {
                    HandGesture.OPEN_PALM -> clearCandidate()
                    HandGesture.FIST -> if (isCandidateStable(HandGesture.FIST, timestampMillis)) {
                        state = State.GRABBED
                        clearCandidate()
                        listener.onGrabbed()
                    }
                    HandGesture.OTHER -> Unit
                }
            }
            State.GRABBED -> {
                when (frame.gesture) {
                    HandGesture.OPEN_PALM -> if (isCandidateStable(HandGesture.OPEN_PALM, timestampMillis)) {
                        state = State.IDLE
                        clearCandidate()
                        listener.onReleased()
                    }
                    HandGesture.FIST -> clearCandidate()
                    HandGesture.OTHER -> Unit
                }
            }
        }
    }

    private fun isCandidateStable(pose: HandGesture, timestampMillis: Long): Boolean {
        if (candidatePose != pose) {
            candidatePose = pose
            candidateCount = 1
            candidateStartMillis = timestampMillis
            return false
        }
        candidateCount++
        return if (config.stableMillis > 0L && timestampMillis > 0L) {
            timestampMillis - candidateStartMillis >= config.stableMillis
        } else {
            candidateCount >= config.stableFrames
        }
    }

    private fun clearCandidate() {
        candidatePose = null
        candidateCount = 0
        candidateStartMillis = 0L
    }
}
