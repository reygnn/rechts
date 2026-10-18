package com.github.reygnn.rechts.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only [ExternalScheme.isInternal] is unit-tested: it is pure string logic and
 * decides whether a URL is loaded by the WebView or handed to another app.
 * [ExternalScheme.dispatch] and [ExternalScheme.fallbackUrl] need the Android
 * runtime (`Intent` parsing/dispatch) and are left to manual/instrumented
 * verification, like the rest of `web/`.
 */
class ExternalSchemeTest {

    @Test
    fun `http and https are internal`() {
        assertTrue(ExternalScheme.isInternal("http://example.com"))
        assertTrue(ExternalScheme.isInternal("https://example.com/path?q=1"))
    }

    @Test
    fun `about is internal`() {
        assertTrue(ExternalScheme.isInternal("about:blank"))
    }

    @Test
    fun `scheme matching is case-insensitive`() {
        assertTrue(ExternalScheme.isInternal("HTTPS://example.com"))
        assertTrue(ExternalScheme.isInternal("Http://example.com"))
    }

    @Test
    fun `mailto tel sms geo are external`() {
        assertFalse(ExternalScheme.isInternal("mailto:a@b.com"))
        assertFalse(ExternalScheme.isInternal("tel:+49123"))
        assertFalse(ExternalScheme.isInternal("sms:+49123"))
        assertFalse(ExternalScheme.isInternal("geo:52.5,13.4"))
    }

    @Test
    fun `intent and app deep links are external`() {
        assertFalse(ExternalScheme.isInternal("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(ExternalScheme.isInternal("market://details?id=com.example"))
    }

    @Test
    fun `a string without a scheme is not internal`() {
        // No ':' delimiter → empty scheme → not in the internal set.
        assertFalse(ExternalScheme.isInternal("example.com"))
    }

    // ── decideWebContentNavigation ────────────────────────────────────────

    @Test
    fun `same-host internal url is loaded in the webview regardless of gesture`() {
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation(
                "https://example.com/next",
                gesture = true,
                currentUrl = "https://EXAMPLE.com/start",
            ),
        )
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation("https://example.com", gesture = false),
        )
    }

    @Test
    fun `external scheme with a gesture is dispatched`() {
        assertEquals(
            ExternalScheme.WebNavigation.DispatchExternal,
            ExternalScheme.decideWebContentNavigation("intent://x/#Intent;end", gesture = true),
        )
        assertEquals(
            ExternalScheme.WebNavigation.DispatchExternal,
            ExternalScheme.decideWebContentNavigation("mailto:a@b.com", gesture = true),
        )
    }

    @Test
    fun `external scheme without a gesture is ignored`() {
        // A hidden iframe or auto-redirect (no user gesture) must not launch an app.
        assertEquals(
            ExternalScheme.WebNavigation.Ignore,
            ExternalScheme.decideWebContentNavigation("intent://x/#Intent;end", gesture = false),
        )
        assertEquals(
            ExternalScheme.WebNavigation.Ignore,
            ExternalScheme.decideWebContentNavigation("market://details?id=x", gesture = false),
        )
    }

    // ── app links (http(s) handed to a non-browser app) ───────────────────

    @Test
    fun `tapped link to another host is offered to an app first`() {
        // "Add to Google Wallet" on a ticket shop must reach the Wallet app.
        assertEquals(
            ExternalScheme.WebNavigation.TryAppThenWebView,
            ExternalScheme.decideWebContentNavigation(
                "https://pay.google.com/gp/v/save/abc",
                gesture = true,
                currentUrl = "https://tickets.example.com/order/1",
            ),
        )
    }

    @Test
    fun `tapped link with no current page is offered to an app first`() {
        assertEquals(
            ExternalScheme.WebNavigation.TryAppThenWebView,
            ExternalScheme.decideWebContentNavigation("https://pay.google.com/x", gesture = true),
        )
    }

    @Test
    fun `cross-host navigation without a gesture stays in the webview`() {
        // An auto-redirect must not silently fling the user into an app.
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation(
                "https://pay.google.com/x",
                gesture = false,
                currentUrl = "https://tickets.example.com/",
            ),
        )
    }

    @Test
    fun `cross-host subframe navigation stays in the webview`() {
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation(
                "https://pay.google.com/x",
                gesture = true,
                isMainFrame = false,
                currentUrl = "https://tickets.example.com/",
            ),
        )
    }

    @Test
    fun `about urls are never offered to an app`() {
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation(
                "about:blank",
                gesture = true,
                currentUrl = "https://example.com/",
            ),
        )
    }
}
