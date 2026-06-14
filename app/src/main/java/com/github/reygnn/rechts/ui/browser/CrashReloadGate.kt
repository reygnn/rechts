package com.github.reygnn.rechts.ui.browser

/**
 * Decides whether a WebView whose render process just died should be
 * auto-reloaded, or whether we've hit a crash loop and must give up.
 *
 * A single renderer crash (OOM, a heavy page) is worth recovering from
 * transparently: rebuild and reopen the page. But a page that crashes the
 * renderer on every load would otherwise spin a tight rebuild → crash →
 * rebuild loop (CPU/battery, unusable UI). After [maxReloadsInWindow] crashes
 * within [windowMs] we stop auto-reloading and let the caller fall back to a
 * safe page instead.
 *
 * Pure and time-source agnostic — the caller passes the current time — so it's
 * JVM-unit-testable without touching `SystemClock` (mirrors [MinIntervalGate]).
 */
class CrashReloadGate(
    private val windowMs: Long = 10_000L,
    private val maxReloadsInWindow: Int = 1,
) {

    private val crashesInWindow = ArrayDeque<Long>()

    /**
     * Records a renderer crash at [now] and returns whether the page should be
     * auto-reloaded. Returns `false` once more than [maxReloadsInWindow] crashes
     * have occurred within [windowMs] — the signal to stop reloading the
     * crashing page and show a safe fallback instead.
     */
    fun shouldReload(now: Long): Boolean {
        // Drop crashes that are older than the sliding window.
        while (crashesInWindow.isNotEmpty() && now - crashesInWindow.first() >= windowMs) {
            crashesInWindow.removeFirst()
        }
        crashesInWindow.addLast(now)
        return crashesInWindow.size <= maxReloadsInWindow
    }
}
