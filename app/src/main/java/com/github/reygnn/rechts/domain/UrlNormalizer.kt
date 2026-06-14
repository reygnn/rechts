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
 *    `mailto:`, …) → use as-is. Non-WebView schemes such as `mailto:`/`tel:`
 *    are returned verbatim here; the web layer ([com.github.reygnn.rechts.web.ExternalScheme])
 *    is what hands them off to another app.
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

    /** Privacy-friendly default homepage (the tidier DuckDuckGo start page). */
    const val HOME_URL = "https://start.duckduckgo.com/"

    /** Privacy-friendly search endpoint (canonical DuckDuckGo results page). */
    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    /** A `scheme://rest` URL, e.g. `https://…`, `http://…`, `file://…`. */
    private val HIERARCHICAL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")

    /**
     * An opaque-scheme URL without `//` that is handed on verbatim: `about:` is
     * loaded by the WebView, while `mailto:`/`tel:`/`intent:` are dispatched to
     * another app by [com.github.reygnn.rechts.web.ExternalScheme]. `intent:` is
     * listed so the opaque form (`intent:…#Intent;…end`) is treated the same as
     * the hierarchical `intent://…` form instead of falling through to search.
     * Code schemes (`javascript:`, `data:`) are intentionally absent — see the
     * class KDoc.
     */
    private val OPAQUE_SCHEME = Regex("^(about|mailto|tel|intent):.*", RegexOption.IGNORE_CASE)

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

        // Reduce to the authority: drop fragment, query, path, then userinfo. A
        // '#' can legally precede '/' and '?', so it must be stripped first.
        val authority = input
            .substringBefore('#')
            .substringBefore('?')
            .substringBefore('/')
            .substringAfterLast('@')

        // IPv6 literal: "[::1]", "[fe80::1]:8080". The brackets disambiguate the
        // colons, so the port-stripping below would mangle it — treat any
        // bracketed authority as a host directly.
        if (authority.startsWith('[')) return authority.contains(']')

        // Split off an optional port. A present port must be a valid TCP port
        // (1..65535); otherwise this isn't a real host:port pair, so treat the
        // whole thing as a search query (e.g. "foo:bar", "localhost:abc") —
        // what real browsers do.
        val colon = authority.indexOf(':')
        val host: String
        if (colon >= 0) {
            host = authority.substring(0, colon)
            val port = authority.substring(colon + 1).toIntOrNull()
            if (port == null || port !in 1..65535) return false
        } else {
            host = authority
        }

        // localhost (with or without a port) is a valid host without a dot.
        if (host.equals("localhost", ignoreCase = true)) return true

        // Otherwise require an interior dot: "a.b" is a host, ".com"/"foo." are not.
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.')) return false

        // A purely numeric dotted token ("3.14") is a host only if it is a valid
        // IPv4 address; otherwise it's far more likely a number the user wants to
        // search for than a site to visit. Real browsers do the same.
        if (host.all { it.isDigit() || it == '.' }) return isIpv4(host)
        return true
    }

    /** True for a dotted-quad in range, e.g. "192.168.0.1" but not "3.14" or "256.0.0.1". */
    private fun isIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            val n = octet.toIntOrNull()
            n != null && n in 0..255
        }
    }
}
