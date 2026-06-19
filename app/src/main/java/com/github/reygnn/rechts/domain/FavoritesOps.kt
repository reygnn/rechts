package com.github.reygnn.rechts.domain

import kotlinx.serialization.json.Json

/**
 * Pure favorites list logic — add/remove/reorder plus the JSON codec for the
 * DataStore blob. No Android imports, same contract as [UrlNormalizer]
 * (CLAUDE.md hard rule 2): the one piece of real favorites logic, kept here so
 * it's covered by fast JVM unit tests. [FavoritesStore] is only the thin
 * DataStore wrapper around these functions.
 *
 * Favorites are identified by [Favorite.url]: membership, toggling and dedup all
 * compare URLs verbatim (no normalization — the stored URL is whatever the page
 * reported). Every operation returns a NEW list and never mutates its input.
 */
object FavoritesOps {

    /**
     * Upper bound on the persisted list. A minimal browser doesn't need an
     * unbounded bookmark store; the cap also keeps the JSON blob small (mirrors
     * Kolibri_Launcher's order-list cap). Enforced on [encode], so a list that
     * grows past it is trimmed (oldest first) when written, never silently in
     * the middle of an operation.
     */
    const val MAX_FAVORITES = 100

    private val json = Json {
        // Tolerate older/newer blobs: an unknown field shouldn't wipe favorites.
        ignoreUnknownKeys = true
    }

    /** True if [url] is already saved. */
    fun isFavorite(favorites: List<Favorite>, url: String): Boolean =
        favorites.any { it.url == url }

    /**
     * Toggle [favorite] by its URL: remove it if already present, otherwise
     * append it (newest last). Blank URLs are never added.
     */
    fun toggle(favorites: List<Favorite>, favorite: Favorite): List<Favorite> = when {
        isFavorite(favorites, favorite.url) -> favorites.filterNot { it.url == favorite.url }
        favorite.url.isBlank() -> favorites
        else -> favorites + favorite
    }

    /** Remove the favorite with [url], if any. */
    fun remove(favorites: List<Favorite>, url: String): List<Favorite> =
        favorites.filterNot { it.url == url }

    /**
     * Move the item at [from] to [to], shifting the rest — the reorder a
     * drag-and-drop drop produces. Out-of-range indices (or [from] == [to])
     * return the list unchanged.
     */
    fun move(favorites: List<Favorite>, from: Int, to: Int): List<Favorite> {
        if (from == to) return favorites
        if (from !in favorites.indices || to !in favorites.indices) return favorites
        return favorites.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Encode to the JSON blob stored in DataStore, trimmed to [MAX_FAVORITES]. */
    fun encode(favorites: List<Favorite>): String =
        json.encodeToString(favorites.take(MAX_FAVORITES))

    /**
     * Decode the DataStore blob. Returns an empty list for blank or corrupt
     * input rather than throwing — a bad blob must not crash the browser, just
     * start from no favorites. Blank-URL entries are dropped defensively.
     */
    fun decode(blob: String): List<Favorite> {
        if (blob.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<Favorite>>(blob)
                .filter { it.url.isNotBlank() }
                .take(MAX_FAVORITES)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
