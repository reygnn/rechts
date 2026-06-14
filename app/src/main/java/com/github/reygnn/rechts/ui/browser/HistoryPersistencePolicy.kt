package com.github.reygnn.rechts.ui.browser

/**
 * Pure decision for whether the WebView's back/forward history may be persisted
 * across process death. Extracted from `BrowserScreen`'s history `Saver` —
 * mirroring [FirstLoadRouter], [CrashReloadGate] and [MinIntervalGate] — so the
 * load-bearing privacy rule ("a private session is never written to disk") is
 * JVM-unit-testable instead of buried in an untestable Compose `Saver`. The
 * caller performs the actual `WebView.saveState`.
 *
 * Two reasons history is dropped:
 *  - **private mode** — persisting its URLs would let a private session outlive
 *    the app process (CLAUDE.md hard rule 5);
 *  - **oversize** — `saveState` serializes the whole list, and a very deep
 *    history could blow the ~1 MB Binder budget for saved instance state
 *    (`TransactionTooLargeException`). Dropping it (→ homepage on restore)
 *    beats crashing.
 */
object HistoryPersistencePolicy {

    /**
     * @param isPrivate      whether the session is private (never persisted).
     * @param marshalledSize serialized size of the saved history, in bytes.
     * @param maxBytes       cap above which history is dropped (Binder budget).
     * @return `true` only when the history may be written to instance state.
     */
    fun shouldPersist(isPrivate: Boolean, marshalledSize: Int, maxBytes: Int): Boolean =
        !isPrivate && marshalledSize <= maxBytes
}
