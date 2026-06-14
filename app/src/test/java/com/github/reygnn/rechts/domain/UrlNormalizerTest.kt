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
    fun `opaque schemes are kept as-is`() {
        assertEquals("about:blank", UrlNormalizer.toUrlOrSearch("about:blank"))
        assertEquals("mailto:a@b.com", UrlNormalizer.toUrlOrSearch("mailto:a@b.com"))
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
    fun `ip address is treated as host`() {
        assertEquals("https://192.168.0.1", UrlNormalizer.toUrlOrSearch("192.168.0.1"))
    }

    @Test
    fun `localhost is treated as host even without a dot`() {
        assertEquals("https://localhost", UrlNormalizer.toUrlOrSearch("localhost"))
        assertEquals("https://localhost:8080", UrlNormalizer.toUrlOrSearch("localhost:8080"))
    }

    @Test
    fun `single word without a dot is a search`() {
        val result = UrlNormalizer.toUrlOrSearch("kotlin")
        assertEquals("https://duckduckgo.com/?q=kotlin", result)
    }

    @Test
    fun `multi-word input is a search even if it contains a dot`() {
        val result = UrlNormalizer.toUrlOrSearch("what is 2.5 inches")
        assertTrue(result!!.startsWith("https://duckduckgo.com/?q="))
        // Spaces are percent/plus encoded, not left raw.
        assertTrue(!result.contains(" "))
    }

    @Test
    fun `search query is url-encoded`() {
        val result = UrlNormalizer.toUrlOrSearch("c++ & rust")
        assertEquals("https://duckduckgo.com/?q=c%2B%2B+%26+rust", result)
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
