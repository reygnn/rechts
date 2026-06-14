package com.github.reygnn.rechts.ui.browser

import com.github.reygnn.rechts.domain.UrlNormalizer
import com.github.reygnn.rechts.web.ExternalScheme

/** What the first load of a freshly created WebView should do. */
sealed interface FirstLoadAction {
    /** Load this URL straight into the WebView (http(s)/about, or the homepage). */
    data class LoadInWebView(val url: String) : FirstLoadAction

    /**
     * An inbound link carrying a non-WebView scheme (data:, javascript:, an app
     * deep link, …): hand it off to another app, falling back to its page (or the
     * homepage) if nothing handles it.
     */
    data class DispatchInbound(val url: String) : FirstLoadAction

    /** Reconstruct the persisted back/forward history (process-death restore). */
    data object RestoreHistory : FirstLoadAction
}

/**
 * Pure decision for what a newly created WebView should load first. Mirrors the
 * other extracted gates ([CrashReloadGate], [MinIntervalGate],
 * [ExternalScheme.decideWebContentNavigation]) so the routing — which used to
 * live inline in a `LaunchedEffect` — is JVM-unit-testable. The caller performs
 * the side effects ([android.webkit.WebView] loads, intent dispatch, restore).
 */
object FirstLoadRouter {

    /**
     * @param crashReload      URL to reopen after a renderer-crash rebuild, else null.
     * @param inbound          unconsumed inbound ACTION_VIEW URL, else null.
     * @param firstComposition true only on the WebView's very first composition
     *                         (so a mode switch never restores the other profile).
     * @param isPrivate        whether private mode is active (never restored).
     * @param hasSavedHistory  whether process-death history is available to restore.
     */
    fun decide(
        crashReload: String?,
        inbound: String?,
        firstComposition: Boolean,
        isPrivate: Boolean,
        hasSavedHistory: Boolean,
    ): FirstLoadAction = when {
        // Renderer was rebuilt after a crash: reopen the page we were on.
        crashReload != null -> FirstLoadAction.LoadInWebView(crashReload)

        // Inbound link from another app. Route it through the same scheme gate as
        // every other load — only http(s)/about go into the WebView; anything
        // else is handed off to another app.
        inbound != null ->
            if (ExternalScheme.isInternal(inbound)) FirstLoadAction.LoadInWebView(inbound)
            else FirstLoadAction.DispatchInbound(inbound)

        // Process-death restore — normal mode only. A private session is never
        // reconstructed from disk-persisted state.
        firstComposition && !isPrivate && hasSavedHistory -> FirstLoadAction.RestoreHistory

        else -> FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL)
    }
}
