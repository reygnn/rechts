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
}
