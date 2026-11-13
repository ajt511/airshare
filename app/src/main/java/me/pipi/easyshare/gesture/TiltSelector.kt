package me.pipi.easyshare.gesture

/**
 * Turns device roll (side tilt) into left/right steps for picking a target on
 * the radar. Hysteresis around [Config.triggerDegrees] avoids double-fires
 * from hand tremor: the tilt must return past [Config.resetDegrees] before
 * the same direction can fire again.
 */
class TiltSelector(
    private val config: Config = Config(),
    private val listener: Listener,
) {
    interface Listener {
        fun onStepLeft()
        fun onStepRight()
    }

    data class Config(
        val triggerDegrees: Float = 32f,
        val resetDegrees: Float = 18f,
    )

    private var armed = true

    fun onRoll(degrees: Float) {
        if (!armed) {
            if (kotlin.math.abs(degrees) < config.resetDegrees) armed = true
            return
        }
        when {
            degrees <= -config.triggerDegrees -> {
                armed = false
                listener.onStepLeft()
            }
            degrees >= config.triggerDegrees -> {
                armed = false
                listener.onStepRight()
            }
        }
    }

    fun reset() {
        armed = true
    }
}
