package com.github.reygnn.rechts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesOpsTest {

    private fun fav(url: String, title: String = url) = Favorite(url, title)

    // ── isFavorite ────────────────────────────────────────────────────────

    @Test
    fun `isFavorite matches by url`() {
        val list = listOf(fav("https://a.com"), fav("https://b.com"))
        assertTrue(FavoritesOps.isFavorite(list, "https://a.com"))
        assertFalse(FavoritesOps.isFavorite(list, "https://c.com"))
    }

    @Test
    fun `isFavorite is exact, not normalized`() {
        val list = listOf(fav("https://a.com"))
        assertFalse(FavoritesOps.isFavorite(list, "https://a.com/"))
    }

    // ── toggle ──────────────────────────────────────────────────────────────

    @Test
    fun `toggle adds an absent favorite at the end`() {
        val result = FavoritesOps.toggle(listOf(fav("https://a.com")), fav("https://b.com", "B"))
        assertEquals(listOf(fav("https://a.com"), fav("https://b.com", "B")), result)
    }

    @Test
    fun `toggle removes a present favorite, matched by url`() {
        val list = listOf(fav("https://a.com"), fav("https://b.com"))
        // Same url, different title still removes.
        val result = FavoritesOps.toggle(list, fav("https://a.com", "different title"))
        assertEquals(listOf(fav("https://b.com")), result)
    }

    @Test
    fun `toggle never adds a duplicate url`() {
        val once = FavoritesOps.toggle(emptyList(), fav("https://a.com"))
        val twice = FavoritesOps.toggle(once, fav("https://a.com"))
        // Second toggle removed it again — never two entries for one url.
        assertTrue(twice.isEmpty())
    }

    @Test
    fun `toggle ignores a blank url`() {
        assertTrue(FavoritesOps.toggle(emptyList(), fav("", "no url")).isEmpty())
    }

    @Test
    fun `toggle does not mutate the input list`() {
        val input = listOf(fav("https://a.com"))
        FavoritesOps.toggle(input, fav("https://b.com"))
        assertEquals(listOf(fav("https://a.com")), input)
    }

    // ── remove ────────────────────────────────────────────────────────────

    @Test
    fun `remove drops the matching url and leaves the rest`() {
        val list = listOf(fav("https://a.com"), fav("https://b.com"))
        assertEquals(listOf(fav("https://b.com")), FavoritesOps.remove(list, "https://a.com"))
    }

    @Test
    fun `remove of an absent url is a no-op`() {
        val list = listOf(fav("https://a.com"))
        assertEquals(list, FavoritesOps.remove(list, "https://x.com"))
    }

    // ── move ──────────────────────────────────────────────────────────────

    @Test
    fun `move shifts an item down`() {
        val list = listOf(fav("a"), fav("b"), fav("c"))
        assertEquals(listOf(fav("b"), fav("c"), fav("a")), FavoritesOps.move(list, 0, 2))
    }

    @Test
    fun `move shifts an item up`() {
        val list = listOf(fav("a"), fav("b"), fav("c"))
        assertEquals(listOf(fav("c"), fav("a"), fav("b")), FavoritesOps.move(list, 2, 0))
    }

    @Test
    fun `move with equal indices is unchanged`() {
        val list = listOf(fav("a"), fav("b"))
        assertEquals(list, FavoritesOps.move(list, 1, 1))
    }

    @Test
    fun `move with an out-of-range index is unchanged`() {
        val list = listOf(fav("a"), fav("b"))
        assertEquals(list, FavoritesOps.move(list, 0, 5))
        assertEquals(list, FavoritesOps.move(list, -1, 1))
    }

    // ── encode / decode round-trip ──────────────────────────────────────────

    @Test
    fun `encode then decode round-trips the list`() {
        val list = listOf(fav("https://a.com", "Alpha"), fav("https://b.com", "Beta"))
        assertEquals(list, FavoritesOps.decode(FavoritesOps.encode(list)))
    }

    @Test
    fun `decode of blank input is empty`() {
        assertTrue(FavoritesOps.decode("").isEmpty())
        assertTrue(FavoritesOps.decode("   ").isEmpty())
    }

    @Test
    fun `decode of corrupt input is empty, not a crash`() {
        assertTrue(FavoritesOps.decode("{not json").isEmpty())
        assertTrue(FavoritesOps.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `decode drops blank-url entries`() {
        val blob = """[{"url":"","title":"x"},{"url":"https://a.com","title":"A"}]"""
        assertEquals(listOf(fav("https://a.com", "A")), FavoritesOps.decode(blob))
    }

    // ── cap ─────────────────────────────────────────────────────────────────

    @Test
    fun `encode trims to the cap, dropping the oldest entries`() {
        // 0..MAX+10 inclusive = MAX+11 entries; the oldest 11 are dropped so a
        // save past the cap still sticks (favorites are appended newest-last).
        val list = (0..FavoritesOps.MAX_FAVORITES + 10).map { fav("https://site$it.com") }
        val decoded = FavoritesOps.decode(FavoritesOps.encode(list))
        assertEquals(FavoritesOps.MAX_FAVORITES, decoded.size)
        assertEquals(fav("https://site11.com"), decoded.first())
        assertEquals(fav("https://site${FavoritesOps.MAX_FAVORITES + 10}.com"), decoded.last())
    }
}
