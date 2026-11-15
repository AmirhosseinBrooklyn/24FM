package ir.fm24.app.network

/**
 * Every condition the bottom status banner can report. Ordered by [priority]: when two
 * conditions are active at once, the higher priority one is shown.
 */
enum class StatusKind(val priority: Int, val retryable: Boolean, val autoDismissMs: Long) {
    NO_INTERNET(priority = 100, retryable = false, autoDismissMs = 0),
    SSL(priority = 90, retryable = true, autoDismissMs = 0),
    DNS(priority = 80, retryable = true, autoDismissMs = 0),
    SERVER(priority = 80, retryable = true, autoDismissMs = 0),
    TIMEOUT(priority = 75, retryable = true, autoDismissMs = 0),
    PAGE_FAILED(priority = 70, retryable = true, autoDismissMs = 0),
    STREAM_UNAVAILABLE(priority = 60, retryable = true, autoDismissMs = 0),
    RETRYING(priority = 50, retryable = false, autoDismissMs = 0),
    DEGRADED(priority = 40, retryable = false, autoDismissMs = 6_000),
    RESTORED(priority = 30, retryable = false, autoDismissMs = 3_000),
    VPN_NOTICE(priority = 20, retryable = false, autoDismissMs = 6_000),
    ;

    val isTransient: Boolean get() = autoDismissMs > 0
}
