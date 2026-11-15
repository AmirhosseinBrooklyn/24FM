package ir.fm24.app.ui

import ir.fm24.app.ui.BackPressPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class BackPressPolicyTest {

    private var now = 10_000L
    private val policy = BackPressPolicy(windowMs = 2_000, clock = { now })

    @Test
    fun `second press within the window exits`() {
        assertEquals(Action.SHOW_HINT, policy.onRootBackPressed())
        now += 1_500
        assertEquals(Action.EXIT, policy.onRootBackPressed())
    }

    @Test
    fun `press after the window shows the hint again`() {
        assertEquals(Action.SHOW_HINT, policy.onRootBackPressed())
        now += 2_500
        assertEquals(Action.SHOW_HINT, policy.onRootBackPressed())
        now += 500
        assertEquals(Action.EXIT, policy.onRootBackPressed())
    }

    @Test
    fun `after exiting the cycle starts over`() {
        policy.onRootBackPressed()
        now += 100
        assertEquals(Action.EXIT, policy.onRootBackPressed())
        now += 100
        assertEquals(Action.SHOW_HINT, policy.onRootBackPressed())
    }

    @Test
    fun `reset clears a pending hint`() {
        policy.onRootBackPressed()
        policy.reset()
        now += 100
        assertEquals(Action.SHOW_HINT, policy.onRootBackPressed())
    }
}
