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
    fun `internal url is loaded in the webview regardless of gesture`() {
        assertEquals(
            ExternalScheme.WebNavigation.LoadInWebView,
            ExternalScheme.decideWebContentNavigation("https://example.com", gesture = true),
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
}
