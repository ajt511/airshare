package me.pipi.easyshare.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class TiltSelectorTest {
    private val steps = mutableListOf<Int>()
    private val selector = TiltSelector(
        TiltSelector.Config(triggerDegrees = 32f, resetDegrees = 18f),
        object : TiltSelector.Listener {
            override fun onStepLeft() = steps.add(-1)
            override fun onStepRight() = steps.add(1)
        },
    )

    @Test
    fun `tilt past trigger steps left then re-arms`() {
        selector.onRoll(-40f)
        selector.onRoll(-35f)
        assertEquals(listOf(-1), steps)
        selector.onRoll(-40f)
        assertEquals(listOf(-1), steps)
        selector.onRoll(0f)
        selector.onRoll(-40f)
        assertEquals(listOf(-1, -1), steps)
    }

    @Test
    fun `tilt right steps right`() {
        selector.onRoll(35f)
        assertEquals(listOf(1), steps)
    }

    @Test
    fun `tremor below trigger does not step`() {
        selector.onRoll(20f)
        selector.onRoll(-20f)
        selector.onRoll(25f)
        assertEquals(emptyList<Int>(), steps)
    }
}
