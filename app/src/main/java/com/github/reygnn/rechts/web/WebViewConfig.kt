package com.github.reygnn.rechts.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Applies rechts' "JS on, but private" defaults to a [WebView].
 *
 * The deal (see CLAUDE.md → Hard rules):
 *  - JavaScript + DOM storage ON — the modern web needs them.
 *  - First-party cookies allowed, **third-party cookies blocked** — kills the
 *    most common cross-site tracking vector.
 *  - No file/content access from web content — a web page can't reach into the
 *    device's files.
 *  - Safe Browsing left at its platform default (on).
 */
object WebViewConfig {

    // JavaScript is enabled deliberately — that's the product decision, not an
    // oversight. Lint would otherwise flag setJavaScriptEnabled(true).
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // Lock the web content out of the local file system. This is the
            // documented hard privacy boundary (CLAUDE.md). The cross-origin
            // file-URL escape hatches (allowFileAccessFromFileURLs /
            // allowUniversalAccessFromFileURLs) are NOT set here on purpose:
            // they default to false on SDK 36 and are deprecated/forced-off for
            // apps targeting R+, so there is nothing to re-enable.
            allowFileAccess = false
            allowContentAccess = false

            // Sensible browsing defaults.
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)                          // first-party: yes
            setAcceptThirdPartyCookies(webView, false)     // third-party: no
        }
    }
}
