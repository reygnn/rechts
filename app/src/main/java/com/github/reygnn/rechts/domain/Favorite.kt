package com.github.reygnn.rechts.domain

import kotlinx.serialization.Serializable

/**
 * One saved favorite: the page [url] (the identity — favorites are deduped and
 * matched by URL) and a [title] for display. Kept in [domain] alongside
 * [UrlNormalizer] and serialized straight to the DataStore JSON blob, so it
 * stays a plain, JVM-testable data class with no Android dependency.
 */
@Serializable
data class Favorite(
    val url: String,
    val title: String,
)
