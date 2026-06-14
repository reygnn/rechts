package com.github.reygnn.rechts.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ExternalScheme.fallbackUrl] parses an `intent:` URL via `Intent.parseUri`,
 * which is real Android framework code — hence Robolectric (the rest of
 * [ExternalScheme] is covered by the pure [ExternalSchemeTest]). `dispatch`
 * stays manual/instrumented: under Robolectric `startActivity` records the
 * intent instead of resolving it, so it can't tell "no app handled it" apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalSchemeFallbackTest {

    @Test
    fun `http fallback url is returned`() {
        val url = "intent://x/#Intent;scheme=fakescheme;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffb;end"
        assertEquals("https://example.com/fb", ExternalScheme.fallbackUrl(url))
    }

    @Test
    fun `about fallback url is allowed`() {
        val url = "intent://x/#Intent;scheme=fakescheme;" +
            "S.browser_fallback_url=about%3Ablank;end"
        assertEquals("about:blank", ExternalScheme.fallbackUrl(url))
    }

    @Test
    fun `non-internal fallback scheme is rejected`() {
        // A fallback that is itself an external/code scheme must not be smuggled
        // back into the WebView — only http(s)/about are accepted.
        val url = "intent://x/#Intent;scheme=fakescheme;" +
            "S.browser_fallback_url=javascript%3Aalert(1);end"
        assertNull(ExternalScheme.fallbackUrl(url))
    }

    @Test
    fun `intent without a fallback returns null`() {
        assertNull(ExternalScheme.fallbackUrl("intent://x/#Intent;scheme=fakescheme;end"))
    }

    @Test
    fun `non-intent url has no fallback`() {
        assertNull(ExternalScheme.fallbackUrl("mailto:a@b.com"))
        assertNull(ExternalScheme.fallbackUrl("https://example.com"))
    }
}
