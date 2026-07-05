package com.github.reygnn.rechts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `blank input returns null`() {
        assertNull(UrlNormalizer.toUrlOrSearch(""))
        assertNull(UrlNormalizer.toUrlOrSearch("   "))
    }

    @Test
    fun `input is trimmed before classification`() {
        assertEquals("https://example.com", UrlNormalizer.toUrlOrSearch("  example.com  "))
    }

    @Test
    fun `full https url is kept as-is`() {
        assertEquals("https://example.com/path", UrlNormalizer.toUrlOrSearch("https://example.com/path"))
    }

    @Test
    fun `full http url is kept as-is`() {
        assertEquals("http://example.com", UrlNormalizer.toUrlOrSearch("http://example.com"))
    }

    @Test
    fun `navigable opaque schemes are kept as-is`() {
        assertEquals("about:blank", UrlNormalizer.toUrlOrSearch("about:blank"))
        assertEquals("mailto:a@b.com", UrlNormalizer.toUrlOrSearch("mailto:a@b.com"))
    }

    @Test
    fun `opaque intent scheme is passed through for external dispatch`() {
        // The opaque intent: form (no "://") must be handed on verbatim, like
        // the hierarchical intent://… form, so the web layer can dispatch it —
        // not fall through to a web search.
        val url = "intent:#Intent;action=android.intent.action.VIEW;end"
        assertEquals(url, UrlNormalizer.toUrlOrSearch(url))
    }

    @Test
    fun `hierarchical intent url is passed through`() {
        assertEquals(
            "intent://scan/#Intent;scheme=zxing;end",
            UrlNormalizer.toUrlOrSearch("intent://scan/#Intent;scheme=zxing;end"),
        )
    }

    @Test
    fun `javascript scheme is NOT passed through but searched (self-XSS guard)`() {
        val result = UrlNormalizer.toUrlOrSearch("javascript:alert(document.cookie)")
        assertTrue("expected a search, got: $result", result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `data scheme is NOT passed through but searched`() {
        val result = UrlNormalizer.toUrlOrSearch("data:text/html,<h1>hi</h1>")
        assertTrue("expected a search, got: $result", result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `bare host gets https prefix`() {
        assertEquals("https://example.com", UrlNormalizer.toUrlOrSearch("example.com"))
    }

    @Test
    fun `host with path gets https prefix`() {
        assertEquals("https://github.com/reygnn/rechts", UrlNormalizer.toUrlOrSearch("github.com/reygnn/rechts"))
    }

    @Test
    fun `host with fragment gets https prefix`() {
        assertEquals("https://example.com#section", UrlNormalizer.toUrlOrSearch("example.com#section"))
    }

    @Test
    fun `userinfo host is recognized, not searched`() {
        assertEquals("https://user:pass@host.com", UrlNormalizer.toUrlOrSearch("user:pass@host.com"))
    }

    @Test
    fun `ip address is treated as host`() {
        assertEquals("https://192.168.0.1", UrlNormalizer.toUrlOrSearch("192.168.0.1"))
    }

    @Test
    fun `host with port and path is recognized`() {
        assertEquals("https://192.168.0.1:8080/x", UrlNormalizer.toUrlOrSearch("192.168.0.1:8080/x"))
    }

    @Test
    fun `localhost is treated as host even without a dot`() {
        assertEquals("https://localhost", UrlNormalizer.toUrlOrSearch("localhost"))
        assertEquals("https://localhost:8080", UrlNormalizer.toUrlOrSearch("localhost:8080"))
    }

    @Test
    fun `host with a non-numeric port is a search, not a host`() {
        // An invalid port means this isn't a host:port pair — search it.
        assertTrue(UrlNormalizer.toUrlOrSearch("localhost:abc")!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(UrlNormalizer.toUrlOrSearch("example.com:abc")!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `host with an out-of-range port is a search`() {
        assertTrue(UrlNormalizer.toUrlOrSearch("example.com:99999")!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(UrlNormalizer.toUrlOrSearch("example.com:0")!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `host with a valid port is recognized`() {
        assertEquals("https://example.com:8080", UrlNormalizer.toUrlOrSearch("example.com:8080"))
    }

    @Test
    fun `single word without a dot is a search`() {
        assertEquals("https://duckduckgo.com/?q=kotlin", UrlNormalizer.toUrlOrSearch("kotlin"))
    }

    @Test
    fun `multi-word input is a search even if it contains a dot`() {
        val result = UrlNormalizer.toUrlOrSearch("what is 2.5 inches")
        assertTrue(result!!.startsWith("https://duckduckgo.com/?q="))
        // Spaces are encoded, not left raw.
        assertTrue(!result.contains(" "))
    }

    @Test
    fun `bare word with a dotted fragment is a search, not a host`() {
        // Regression: the fragment's dot must not make this look like a host.
        val result = UrlNormalizer.toUrlOrSearch("ex#frag.dot")
        assertTrue("expected a search, got: $result", result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `bare scheme without host falls through to search`() {
        // "https://" is not loadable; it must not be forwarded verbatim.
        val result = UrlNormalizer.toUrlOrSearch("https://")
        assertTrue("expected a search, got: $result", result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `search query is url-encoded`() {
        assertEquals("https://duckduckgo.com/?q=c%2B%2B+%26+rust", UrlNormalizer.toUrlOrSearch("c++ & rust"))
    }

    @Test
    fun `leading dot is not a host`() {
        val result = UrlNormalizer.toUrlOrSearch(".com")
        assertTrue(result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `trailing dot is not a host`() {
        val result = UrlNormalizer.toUrlOrSearch("example.")
        assertTrue(result!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `numeric dotted token that is not a valid ipv4 is a search`() {
        // "3.14" looks host-shaped (interior dot) but is far more likely a
        // number the user wants to search than a site → search, not https://3.14.
        assertTrue(UrlNormalizer.toUrlOrSearch("3.14")!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(UrlNormalizer.toUrlOrSearch("1.000")!!.startsWith("https://duckduckgo.com/?q="))
        // Too many octets, or an out-of-range octet, is not an IPv4 either.
        assertTrue(UrlNormalizer.toUrlOrSearch("1.2.3.4.5")!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(UrlNormalizer.toUrlOrSearch("256.1.1.1")!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `valid ipv4 is still treated as a host`() {
        assertEquals("https://8.8.8.8", UrlNormalizer.toUrlOrSearch("8.8.8.8"))
        assertEquals("https://0.0.0.0", UrlNormalizer.toUrlOrSearch("0.0.0.0"))
    }

    @Test
    fun `ipv6 literal is treated as a host`() {
        assertEquals("https://[::1]", UrlNormalizer.toUrlOrSearch("[::1]"))
        assertEquals("https://[fe80::1]:8080", UrlNormalizer.toUrlOrSearch("[fe80::1]:8080"))
        assertEquals("https://[::1]/path", UrlNormalizer.toUrlOrSearch("[::1]/path"))
    }

    // --- fromSharedText: ACTION_SEND payloads (link possibly wrapped in prose) ---

    @Test
    fun `shared bare url opens as-is`() {
        assertEquals("https://example.com/x", UrlNormalizer.fromSharedText("https://example.com/x"))
        assertEquals("http://a.com", UrlNormalizer.fromSharedText("http://a.com"))
    }

    @Test
    fun `shared url wrapped in a caption still opens the url`() {
        assertEquals(
            "https://youtu.be/abc",
            UrlNormalizer.fromSharedText("Watch \"Title\" on YouTube https://youtu.be/abc"),
        )
        assertEquals("https://a.com/p", UrlNormalizer.fromSharedText("Title\nhttps://a.com/p"))
    }

    @Test
    fun `trailing sentence punctuation is stripped from the shared url`() {
        assertEquals("https://a.com/p", UrlNormalizer.fromSharedText("See https://a.com/p."))
        assertEquals("https://a.com", UrlNormalizer.fromSharedText("(https://a.com)"))
        assertEquals("https://a.com", UrlNormalizer.fromSharedText("link: https://a.com!"))
    }

    @Test
    fun `balanced parens inside the shared url are kept`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            UrlNormalizer.fromSharedText("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            UrlNormalizer.fromSharedText("See https://en.wikipedia.org/wiki/Foo_(bar)."),
        )
    }

    @Test
    fun `the first url wins when several are shared`() {
        assertEquals("https://a.com", UrlNormalizer.fromSharedText("https://a.com and https://b.com"))
    }

    @Test
    fun `shared text without a url falls back to search or host`() {
        assertTrue(
            UrlNormalizer.fromSharedText("hello world")!!.startsWith("https://duckduckgo.com/?q="),
        )
        assertEquals("https://example.com", UrlNormalizer.fromSharedText("example.com"))
    }

    @Test
    fun `blank shared text returns null`() {
        assertNull(UrlNormalizer.fromSharedText(""))
        assertNull(UrlNormalizer.fromSharedText("   "))
    }
}
