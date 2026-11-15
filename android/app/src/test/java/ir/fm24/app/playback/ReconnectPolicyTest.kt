package ir.fm24.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `backs off exponentially then gives up`() {
        val policy = ReconnectPolicy(baseDelayMs = 2_000, maxDelayMs = 30_000, maxAttempts = 4)
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(16_000L, policy.nextDelayMs())
        assertTrue(policy.isExhausted)
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun `delay is capped`() {
        val policy = ReconnectPolicy(baseDelayMs = 10_000, maxDelayMs = 30_000, maxAttempts = 10)
        val delays = List(6) { policy.nextDelayMs() }
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `reset restores the full budget`() {
        val policy = ReconnectPolicy(maxAttempts = 2)
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()
        assertFalse(policy.isExhausted)
        assertEquals(2_000L, policy.nextDelayMs())
    }
}
