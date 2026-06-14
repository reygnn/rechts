package com.github.reygnn.rechts.domain

import java.net.URLEncoder

/**
 * Turns whatever the user typed in the address bar into something loadable:
 * either a real URL or a web search.
 *
 * The decision is deliberately simple and pure (no Android dependency, fully
 * JVM-testable):
 *
 *  - Already has a scheme (`https://`, `http://`, `about:`, `file:`, …) → use as-is.
 *  - Looks like a bare host (`example.com`, `192.168.0.1`, `localhost:8080`) →
 *    prepend `https://`.
 *  - Anything else (contains spaces, or has no dot) → treat as a search query
 *    against DuckDuckGo (privacy-friendly default).
 */
object UrlNormalizer {

    /** Privacy-friendly default search engine. */
    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    /** A `scheme://rest` URL, e.g. `https://…`, `http://…`, `file://…`. */
    private val HIERARCHICAL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")

    /** An opaque-scheme URL without `//`, e.g. `about:blank`, `mailto:a@b.c`. */
    private val OPAQUE_SCHEME = Regex("^(about|data|mailto|tel|javascript):.*", RegexOption.IGNORE_CASE)

    /**
     * @return the URL to load, or `null` if [rawInput] is blank (nothing to do).
     */
    fun toUrlOrSearch(rawInput: String): String? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null

        if (HIERARCHICAL_SCHEME.matches(input) || OPAQUE_SCHEME.matches(input)) {
            return input
        }
        if (looksLikeHost(input)) {
            return "https://$input"
        }
        return SEARCH_PREFIX + URLEncoder.encode(input, "UTF-8")
    }

    private fun looksLikeHost(input: String): Boolean {
        // A host never contains whitespace; if it does, it's a search query.
        if (input.any { it.isWhitespace() }) return false

        val host = input.substringBefore('/').substringBefore('?')
        val hostNoPort = host.substringBefore(':')

        // localhost (with or without a port) is a valid host without a dot.
        if (hostNoPort.equals("localhost", ignoreCase = true)) return true

        // Otherwise require an interior dot: "a.b" is a host, ".com"/"foo." are not.
        if (!hostNoPort.contains('.')) return false
        if (hostNoPort.startsWith('.') || hostNoPort.endsWith('.')) return false
        return true
    }
}
