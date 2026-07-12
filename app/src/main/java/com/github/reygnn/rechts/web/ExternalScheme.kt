package com.github.reygnn.rechts.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The lowercase scheme of [url] — the part before the first `:`, or `""` when
 * there is none. The one scheme-extraction rule shared by [ExternalScheme] and
 * [DownloadHandler] so their classifications can never drift apart.
 */
internal fun schemeOf(url: String): String =
    url.substringBefore(':', missingDelimiterValue = "").lowercase()

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

    /**
     * Actions a web page must never be able to trigger from an `intent:` URL,
     * even with BROWSABLE — they can act without further user confirmation
     * (placing a call). Such an action is neutralised to `ACTION_VIEW` in
     * [buildIntent] instead of being dispatched as named. String literals for
     * the two that have no public [Intent] constant.
     */
    private val DANGEROUS_ACTIONS = setOf(
        Intent.ACTION_CALL,
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
    )

    /** Whether [url] is something the WebView itself should load. */
    fun isInternal(url: String): Boolean = schemeOf(url) in INTERNAL_SCHEMES

    /** What to do with a navigation a loaded page initiated. */
    enum class WebNavigation {
        /** http(s)/about — let the WebView load it itself. */
        LoadInWebView,

        /** External scheme with a user gesture — hand off to another app. */
        DispatchExternal,

        /** External scheme with no user gesture — swallow it (anti-abuse). */
        Ignore,
    }

    /**
     * Pure decision for `shouldOverrideUrlLoading`: an internal URL is loaded in
     * place; an external scheme is dispatched to another app only when the page
     * navigation carried a user [gesture]. A gesture-less external navigation
     * (hidden iframe, auto-redirect to intent:/market:/tel:) is ignored so a
     * page can't silently fling the user into another app (matching Chrome).
     */
    fun decideWebContentNavigation(url: String, gesture: Boolean): WebNavigation = when {
        isInternal(url) -> WebNavigation.LoadInWebView
        !gesture -> WebNavigation.Ignore
        else -> WebNavigation.DispatchExternal
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

    /**
     * The page an `intent:` URL wants opened when no app can handle it. An
     * `intent:` link commonly carries an `S.browser_fallback_url` extra (a web
     * page to visit if the target app is absent); after a failed [dispatch] the
     * caller loads it instead of leaving the user on a dead tap.
     *
     * Returns the fallback only when it is an `http(s)`/`about` URL the WebView
     * can load itself (so a hostile fallback can't smuggle another external
     * scheme back in), else `null`. Non-`intent:` URLs have no fallback.
     */
    fun fallbackUrl(url: String): String? {
        if (!url.startsWith("intent:", ignoreCase = true)) return null
        val fallback = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
        }.getOrNull() ?: return null
        return fallback.takeIf { isInternal(it) }
    }

    private fun buildIntent(url: String, fromWebContent: Boolean): Intent {
        // `intent:` URLs carry a full intent description; parse it, but strip the
        // component/selector so a hostile page can't target an internal,
        // non-exported activity, require BROWSABLE so only intents meant to be
        // triggered from web content are honoured, and neutralise a dangerous or
        // missing action to VIEW.
        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // Keep the page-supplied action so a legitimate app-switch intent
                // reaches its app — e.g. TWINT's custom `ch.twint.action.
                // TWINT_PAYMENT`, which its LaunchSwitcherActivity matches on and
                // which forcing VIEW would strip, breaking the browser→TWINT
                // hand-off. BROWSABLE (added below) is the real gate: an activity
                // only receives a web-triggered intent if it declared it, and
                // sensitive system actions (CALL, …) live on non-BROWSABLE
                // activities — so requiring BROWSABLE, not clamping every action
                // to VIEW, is what keeps this safe. Belt-and-suspenders, a small
                // denylist of actions that can act without further confirmation
                // (auto-dial) is still forced to VIEW; a missing action likewise
                // (a bare `intent:` is a plain view of its data).
                if (action.isNullOrEmpty() || action in DANGEROUS_ACTIONS) {
                    action = Intent.ACTION_VIEW
                }
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                // Strip URI-permission grants a hostile page could smuggle in via
                // the intent: string; rechts grants no app access to its data.
                // (package= is left intact — app deep links legitimately target a
                // specific package, and component/BROWSABLE already constrain it.)
                flags = flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    ).inv()
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (fromWebContent) addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
