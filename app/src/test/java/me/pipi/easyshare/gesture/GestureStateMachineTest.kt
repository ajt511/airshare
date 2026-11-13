package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureStateMachineTest {
    private val events = mutableListOf<String>()
    private val machine = GestureStateMachine(
        GestureStateMachine.Config(stableFrames = 3, stableMillis = 0L),
        object : GestureStateMachine.Listener {
            override fun onPalmHeld() = events.add("palm")
            override fun onGrabbed() = events.add("grab")
            override fun onReleased() = events.add("release")
            override fun onGrabLost() = events.add("lost")
        },
    )

    private fun palm() = GestureFrame(HandGesture.OPEN_PALM, 0.5f, 0.5f)
    private fun fist() = GestureFrame(HandGesture.FIST, 0.5f, 0.5f)
    private fun noise() = GestureFrame(HandGesture.OTHER, null, null)

    private fun feed(count: Int, frame: GestureFrame) {
        repeat(count) { machine.onFrame(frame) }
    }

    @Test
    fun `single noisy frame does not arm`() {
        feed(1, palm())
        feed(1, fist())
        feed(2, palm())
        assertTrue(events.isEmpty())
        assertEquals(GestureStateMachine.State.IDLE, machine.state)
    }

    @Test
    fun `stable palm arms the machine`() {
        feed(3, palm())
        assertEquals(listOf("palm"), events)
        assertEquals(GestureStateMachine.State.PALM, machine.state)
    }

    @Test
    fun `full grab and release cycle fires all events`() {
        feed(3, palm())
        feed(3, fist())
        assertEquals(listOf("palm", "grab"), events)
        assertEquals(GestureStateMachine.State.GRABBED, machine.state)
        feed(3, palm())
        assertEquals(listOf("palm", "grab", "release"), events)
        assertEquals(GestureStateMachine.State.IDLE, machine.state)
    }

    @Test
    fun `brief occlusion during grab is tolerated`() {
        feed(3, palm())
        feed(3, fist())
        events.clear()
        feed(1, noise())
        feed(3, fist())
        assertTrue(events.isEmpty())
        assertEquals(GestureStateMachine.State.GRABBED, machine.state)
    }

    @Test
    fun `persistent occlusion during grab reports lost`() {
        feed(3, palm())
        feed(3, fist())
        events.clear()
        feed(3, noise())
        assertEquals(listOf("lost"), events)
        assertEquals(GestureStateMachine.State.IDLE, machine.state)
    }

    @Test
    fun `fist before palm does not grab`() {
        feed(5, fist())
        assertTrue(events.isEmpty())
        feed(5, palm())
        feed(5, fist())
        assertEquals(listOf("palm", "grab"), events)
    }
}
