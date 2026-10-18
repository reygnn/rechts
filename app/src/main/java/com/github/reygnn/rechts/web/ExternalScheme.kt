package com.github.reygnn.rechts.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URI

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
     * The denylist half of the `intent:` action policy (see [buildIntent]).
     *
     * rechts preserves the page-supplied action so generic app-switch intents
     * work — the whole point of not clamping to VIEW: TWINT and any other app
     * that resolves on its own custom action reach it. That is deliberately a
     * **fail-open denylist, not a fail-safe allowlist**: an allowlist would have
     * to enumerate every app-switch action anyone ever integrates (each bank's
     * TWINT action, wallets, …) and would silently break the next one — exactly
     * the regression that clamping to VIEW caused. So the model is "allow any
     * action, subject to these gates", and the gates — not the action list — are
     * what keep it safe:
     *
     *  1. **BROWSABLE is the real boundary.** [buildIntent] adds
     *     `CATEGORY_BROWSABLE`, and an implicit intent from web content only
     *     resolves to an activity that *declared* BROWSABLE — an app's explicit
     *     opt-in to being launched from the web (the trust model Chrome uses).
     *     Sensitive system surfaces (settings, package install, …) don't declare
     *     BROWSABLE, so they're unreachable whatever the action.
     *  2. **No smuggling.** [buildIntent] clears `component`/`selector` and
     *     strips URI-grant flags, so a page can't target an internal/non-exported
     *     activity or hand over read/write grants on top of the action.
     *
     * What BROWSABLE alone does *not* stop is a web-triggerable action that acts
     * **without any further user confirmation** — in practice, auto-dialing:
     * `ACTION_CALL` and its privileged/emergency variants place the call the
     * moment the dialer activity starts, with no "call?" prompt. Those are the
     * denylist, neutralised to `ACTION_VIEW`. Benign relatives stay allowed and
     * are no worse than a plain link — `DIAL` only opens the dialer, `SENDTO`/
     * `SEND` only open a composer; the user still presses send/call.
     *
     * Residual risk, accepted: a third-party app could expose a BROWSABLE
     * activity for a custom action that does something sensitive on launch. That
     * app opted into web-triggering, so honouring it is the app's contract —
     * identical to every other browser. If a new no-confirmation *standard*
     * action ever ships, add it here.
     *
     * String literals for the two variants with no public [Intent] constant
     * (pinned by `ExternalSchemeDispatchTest` so a typo can't silently drop them).
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

        /**
         * http(s) link tapped to another site — open it in a non-browser app
         * that claims the URL (Google Wallet, YouTube, …) if there is one, else
         * load it in the WebView.
         */
        TryAppThenWebView,

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
     *
     * An `http(s)` link the user tapped in the main frame that leaves the
     * current page's host ([currentUrl]) is offered to an app first
     * ([WebNavigation.TryAppThenWebView]) — that's how "Add to Google Wallet"
     * (`https://pay.google.com/…`) reaches the Wallet app, as in Chrome.
     * Same-host navigation stays in the browser, so browsing a site whose app
     * is installed doesn't keep jumping into that app.
     */
    fun decideWebContentNavigation(
        url: String,
        gesture: Boolean,
        isMainFrame: Boolean = true,
        currentUrl: String? = null,
    ): WebNavigation = when {
        isInternal(url) -> when {
            gesture && isMainFrame && isAppLinkCandidate(url) &&
                !sameHost(url, currentUrl) -> WebNavigation.TryAppThenWebView
            else -> WebNavigation.LoadInWebView
        }
        !gesture -> WebNavigation.Ignore
        else -> WebNavigation.DispatchExternal
    }

    private fun isAppLinkCandidate(url: String): Boolean =
        schemeOf(url) == "http" || schemeOf(url) == "https"

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

    /** Whether [url] and [other] share a host; an unknown host never matches. */
    private fun sameHost(url: String, other: String?): Boolean {
        val host = hostOf(url) ?: return false
        return host == hostOf(other)
    }

    /**
     * Opens the `http(s)` [url] in an installed **non-browser** app that claims
     * it (app link), returning `true` if one was launched. Returns `false` when
     * only browsers (rechts included) handle it — the caller then loads it in
     * the WebView. `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` lets the system decide
     * without a chooser and without rechts needing package visibility.
     */
    fun dispatchToApp(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not launch app for: $url", e)
            false
        }
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
