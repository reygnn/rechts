package com.github.reygnn.rechts.ui.browser

/**
 * Lets an event through at most once per [minIntervalMs]. Pure and time-source
 * agnostic — the caller passes the current time — so it's JVM-unit-testable
 * without touching `SystemClock`.
 *
 * Used to throttle the "unsupported" toasts so a page auto-firing downloads /
 * file pickers in a loop can't flood the screen.
 *
 * Not thread-safe: [lastAcceptedAt] is plain mutable state. It is only ever
 * touched from the main thread (the download / file-chooser callbacks), so no
 * synchronization is needed; don't call it off the main thread.
 */
class MinIntervalGate(private val minIntervalMs: Long) {

    private var lastAcceptedAt: Long? = null

    /**
     * @return `true` if an event at [now] is accepted (and records it as the new
     *   reference point), `false` if it falls within [minIntervalMs] of the last
     *   accepted event. The first call is always accepted.
     */
    fun accept(now: Long): Boolean {
        val last = lastAcceptedAt
        if (last != null && now - last < minIntervalMs) return false
        lastAcceptedAt = now
        return true
    }
}
