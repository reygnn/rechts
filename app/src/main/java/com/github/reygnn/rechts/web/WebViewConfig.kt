package com.github.reygnn.rechts.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Applies rechts' "JS on, but private" defaults to a [WebView] and provides the
 * data-wiping used to keep browsing session-only.
 *
 * The deal (see CLAUDE.md → Hard rules):
 *  - JavaScript + DOM storage ON — the modern web needs them.
 *  - First-party cookies allowed, **third-party cookies blocked** — kills the
 *    most common cross-site tracking vector.
 *  - No file/content access from web content — a web page can't reach into the
 *    device's files.
 *  - **Private by default:** cookies, web storage, cache and history are wiped
 *    on a fresh launch and again when the app is closed (see [clearAll] /
 *    [clearCookiesAndStorage]). DOM storage and cookies still work *within* a
 *    session, but nothing survives it.
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

    /**
     * Wipes the persistent stores that need no live [WebView]: cookies and web
     * storage (localStorage / IndexedDB / WebSQL). Safe to call before any
     * WebView exists — used on a cold launch to drop leftovers from a session
     * that was killed without a clean teardown.
     */
    fun clearCookiesAndStorage() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Full wipe used when the app is closing: [clearCookiesAndStorage] plus the
     * [webView]-bound HTTP cache and back/forward history. Call before
     * [WebView.destroy].
     */
    fun clearAll(webView: WebView) {
        clearCookiesAndStorage()
        webView.clearCache(true)
        webView.clearHistory()
    }
}
