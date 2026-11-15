package ir.fm24.app.playback

/**
 * Bounded exponential backoff for live-stream reconnection: base, 2x base, 4x base...
 * capped at [maxDelayMs], and giving up after [maxAttempts] so there is never an infinite
 * retry loop.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 4,
) {
    var attempts: Int = 0
        private set

    /** Returns the delay before the next attempt, or null when attempts are exhausted. */
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = (baseDelayMs shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMs)
        attempts++
        return delay
    }

    val isExhausted: Boolean get() = attempts >= maxAttempts

    fun reset() {
        attempts = 0
    }

    private companion object {
        const val MAX_SHIFT = 16
    }
}
