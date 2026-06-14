package com.github.reygnn.rechts.domain

import java.net.URLEncoder

/**
 * Turns whatever the user typed in the address bar into something loadable:
 * either a real URL or a web search.
 *
 * The decision is deliberately simple and pure (no Android dependency, fully
 * JVM-testable):
 *
 *  - Already has a *navigable* scheme (`https://`, `http://`, `about:`,
 *    `mailto:`, …) → use as-is.
 *  - Looks like a bare host (`example.com`, `192.168.0.1`, `localhost:8080`,
 *    `user:pass@host.com`) → prepend `https://`.
 *  - Anything else (contains spaces, has no dot, or is a code scheme like
 *    `javascript:`) → treat as a search query against DuckDuckGo
 *    (privacy-friendly default).
 *
 * Note `javascript:` and `data:` are deliberately NOT passed through: a typed
 * `javascript:` would execute in the context of the current page (self-XSS),
 * so it falls through to search instead — the same thing real browsers do.
 */
object UrlNormalizer {

    /** Privacy-friendly default search engine and homepage. */
    const val HOME_URL = "https://duckduckgo.com"

    /** Search endpoint, derived from [HOME_URL] so the two never diverge. */
    private const val SEARCH_PREFIX = "$HOME_URL/?q="

    /** A `scheme://rest` URL, e.g. `https://…`, `http://…`, `file://…`. */
    private val HIERARCHICAL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")

    /**
     * An opaque-scheme URL without `//` that is safe to navigate to. Code
     * schemes (`javascript:`, `data:`) are intentionally absent — see the
     * class KDoc.
     */
    private val OPAQUE_SCHEME = Regex("^(about|mailto|tel):.*", RegexOption.IGNORE_CASE)

    /**
     * @return the URL to load, or `null` if [rawInput] is blank (nothing to do).
     */
    fun toUrlOrSearch(rawInput: String): String? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null

        if (OPAQUE_SCHEME.matches(input)) return input
        // A hierarchical URL must actually have an authority after "://";
        // a bare "https://" is not loadable, so fall through to search.
        if (HIERARCHICAL_SCHEME.matches(input) && input.substringAfter("://").isNotBlank()) {
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

        // Reduce to the host: drop fragment, query, path, then userinfo. A '#'
        // can legally precede '/' and '?', so it must be stripped first.
        val host = input
            .substringBefore('#')
            .substringBefore('?')
            .substringBefore('/')
            .substringAfterLast('@')
            .substringBefore(':')   // drop the port

        // localhost (with or without a port) is a valid host without a dot.
        if (host.equals("localhost", ignoreCase = true)) return true

        // Otherwise require an interior dot: "a.b" is a host, ".com"/"foo." are not.
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.')) return false
        return true
    }
}
