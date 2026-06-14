package com.github.reygnn.rechts.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Hands URLs the WebView can't render itself (`mailto:`, `tel:`, `sms:`,
 * `geo:`, `intent:`, app deep links, …) off to whichever app the system
 * resolves them to.
 *
 * The platform [android.webkit.WebView] only loads `http`/`https` (and a few
 * pseudo-schemes like `about:`); any other scheme would otherwise surface as a
 * `net::ERR_UNKNOWN_URL_SCHEME` error page. rechts intercepts those — both for
 * in-page link taps (via `shouldOverrideUrlLoading`) and for text typed into
 * the address bar — and dispatches an external intent instead.
 */
object ExternalScheme {

    private const val TAG = "ExternalScheme"

    /** Schemes the WebView loads on its own; everything else is external. */
    private val INTERNAL_SCHEMES = setOf("http", "https", "about")

    /** Whether [url] is something the WebView itself should load. */
    fun isInternal(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in INTERNAL_SCHEMES
    }

    /**
     * Tries to open [url] in another app. Returns `true` if an app was
     * launched, `false` if nothing could handle it (the caller then simply
     * stays on the current page rather than showing an error).
     *
     * Set [fromWebContent] for navigations initiated by the loaded page (a link
     * tap / JS redirect) as opposed to text the user typed: web-initiated
     * `ACTION_VIEW` launches then additionally require `BROWSABLE`, so only
     * activities that opted into being triggered from the web are matched
     * (matching Chrome). Typed input is an explicit user action and keeps the
     * broader match, so e.g. `tel:`/`mailto:` handlers that don't declare
     * `BROWSABLE` still resolve.
     */
    fun dispatch(context: Context, url: String, fromWebContent: Boolean = false): Boolean {
        val intent = runCatching { buildIntent(url, fromWebContent) }.getOrElse {
            Log.w(TAG, "Could not parse external URL: $url", it)
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app to handle: $url", e)
            false
        } catch (e: RuntimeException) {
            // startActivity throws more than ActivityNotFoundException: a file://
            // target raises FileUriExposedException (StrictMode), and a resolved
            // activity that needs a permission rechts lacks raises
            // SecurityException. A bad scheme must never crash the browser —
            // stay on the current page instead.
            Log.w(TAG, "Could not launch external app for: $url", e)
            false
        }
    }

    private fun buildIntent(url: String, fromWebContent: Boolean): Intent {
        // `intent:` URLs carry a full intent description; parse it, but strip
        // the component/selector so a hostile page can't target an internal,
        // non-exported activity, and require BROWSABLE so only intents meant
        // to be triggered from web content are honoured.
        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (fromWebContent) addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
