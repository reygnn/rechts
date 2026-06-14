package com.github.reygnn.rechts.web

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Applies rechts' WebView defaults and wires up the optional private mode.
 *
 * The deal (see CLAUDE.md → Hard rules):
 *  - JavaScript + DOM storage ON — the modern web needs them.
 *  - First-party cookies allowed, **third-party cookies blocked** — kills the
 *    most common cross-site tracking vector.
 *  - No file/content access from web content.
 *  - Safe Browsing left at its platform default (on).
 *
 * **Private mode is optional and isolated.** Normal browsing uses the default
 * [ProfileStore] profile, which persists cookies/logins across launches.
 * Private browsing uses a separate [PRIVATE_PROFILE] profile with its own
 * cookie/storage jar; that jar is wiped on a cold launch ([clearPrivateData]),
 * so private sessions don't outlive the app — and the normal profile is never
 * touched, so saved logins survive.
 */
object WebViewConfig {

    private const val TAG = "WebViewConfig"

    /** Name of the isolated profile used while private mode is on. */
    const val PRIVATE_PROFILE = "private"

    /**
     * Configures [webView]. When [privateMode] is on (and the platform WebView
     * supports multiple profiles), the WebView is bound to the isolated
     * [PRIVATE_PROFILE] before it loads anything.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView, privateMode: Boolean) {
        // Bind the isolated profile FIRST — it must be set before the WebView
        // navigates anywhere — and grab *that profile's own* CookieManager, so
        // the cookie policy below applies to the private jar and not to the
        // default profile's. (CookieManager.getInstance() is the default
        // profile's manager.)
        val cookieManager = if (privateMode && multiProfileSupported()) {
            val profile = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            WebViewCompat.setProfile(webView, PRIVATE_PROFILE)
            profile.cookieManager
        } else {
            CookieManager.getInstance()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // Lock the web content out of the local file system. This is the
            // documented hard privacy boundary (CLAUDE.md). The cross-origin
            // file-URL escape hatches default to false on SDK 36 and are
            // deprecated/forced-off for apps targeting R+, so there is nothing
            // to re-enable here.
            allowFileAccess = false
            allowContentAccess = false

            // Route target="_blank" / window.open() through onCreateWindow
            // instead of letting the platform silently no-op them. rechts has no
            // tabs, so the chrome client redirects the requested URL back into
            // this same WebView (see BrowserScreen.onCreateWindow). Without this,
            // a programmatic window.open leaves such links dead.
            setSupportMultipleWindows(true)

            // Sensible browsing defaults.
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        cookieManager.apply {
            setAcceptCookie(true)                          // first-party: yes
            setAcceptThirdPartyCookies(webView, false)     // third-party: no
        }
    }

    /**
     * Deletes the isolated private-browsing profile and all its data. Safe to
     * call when no WebView is using that profile (e.g. on a cold launch). The
     * normal/default profile — and therefore saved logins — is untouched.
     */
    fun clearPrivateData() {
        if (!multiProfileSupported()) return
        runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
            .onFailure { Log.w(TAG, "Could not delete private profile", it) }
    }

    /** Whether this device's WebView supports isolated profiles. */
    fun multiProfileSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
}
