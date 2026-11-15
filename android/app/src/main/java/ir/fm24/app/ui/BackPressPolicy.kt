package ir.fm24.app.ui

/**
 * "Press back again to exit" logic. The first root-level back press shows a hint; a second
 * press within [windowMs] exits. After the window expires the next press shows the hint again.
 */
class BackPressPolicy(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long,
) {
    enum class Action { SHOW_HINT, EXIT }

    private var armedAt: Long? = null

    fun onRootBackPressed(): Action {
        val now = clock()
        val armed = armedAt
        return if (armed != null && now - armed in 0..windowMs) {
            armedAt = null
            Action.EXIT
        } else {
            armedAt = now
            Action.SHOW_HINT
        }
    }

    fun reset() {
        armedAt = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
