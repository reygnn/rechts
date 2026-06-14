package com.github.reygnn.rechts.ui.browser

import com.github.reygnn.rechts.domain.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure routing decision for a freshly created WebView's first load. Mirrors the
 * priority order the screen used to express inline: crash-reload first, then an
 * inbound link, then a history restore (disk on first composition, the in-memory
 * normal snapshot on a later mode switch), else the homepage.
 */
class FirstLoadRouterTest {

    private fun decide(
        crashReload: String? = null,
        inbound: String? = null,
        firstComposition: Boolean = true,
        isPrivate: Boolean = false,
        hasSavedHistory: Boolean = false,
        hasNormalModeHistory: Boolean = false,
    ) = FirstLoadRouter.decide(
        crashReload,
        inbound,
        firstComposition,
        isPrivate,
        hasSavedHistory,
        hasNormalModeHistory,
    )

    @Test
    fun `crash reload wins over everything`() {
        val action = decide(
            crashReload = "https://crashed.example",
            inbound = "https://inbound.example",
            hasSavedHistory = true,
        )
        assertEquals(FirstLoadAction.LoadInWebView("https://crashed.example"), action)
    }

    @Test
    fun `an internal inbound link loads in the WebView`() {
        assertEquals(
            FirstLoadAction.LoadInWebView("https://inbound.example"),
            decide(inbound = "https://inbound.example", hasSavedHistory = true),
        )
    }

    @Test
    fun `a non-WebView inbound scheme is dispatched, not loaded`() {
        assertEquals(
            FirstLoadAction.DispatchInbound("mailto:a@b.com"),
            decide(inbound = "mailto:a@b.com"),
        )
    }

    @Test
    fun `an explicit data inbound scheme is dispatched, never loaded in the WebView`() {
        assertEquals(
            FirstLoadAction.DispatchInbound("data:text/html,<script>x</script>"),
            decide(inbound = "data:text/html,<script>x</script>"),
        )
    }

    @Test
    fun `saved history on first composition restores`() {
        assertEquals(
            FirstLoadAction.RestoreHistory,
            decide(firstComposition = true, isPrivate = false, hasSavedHistory = true),
        )
    }

    @Test
    fun `a private session never restores history`() {
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(firstComposition = true, isPrivate = true, hasSavedHistory = true),
        )
    }

    @Test
    fun `disk history is not restored on a mode switch (only first composition)`() {
        // hasSavedHistory is the disk bundle; it must only restore on the first
        // composition (process death), never on a later mode switch.
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(firstComposition = false, hasSavedHistory = true),
        )
    }

    @Test
    fun `switching back to normal restores the in-memory normal snapshot`() {
        assertEquals(
            FirstLoadAction.RestoreHistory,
            decide(firstComposition = false, isPrivate = false, hasNormalModeHistory = true),
        )
    }

    @Test
    fun `switching into private never restores the normal snapshot`() {
        // Private always opens the homepage, even with a normal snapshot present.
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(firstComposition = false, isPrivate = true, hasNormalModeHistory = true),
        )
    }

    @Test
    fun `the normal snapshot is not consulted on the first composition`() {
        // First composition uses the disk bundle, not the in-memory snapshot;
        // with no disk history it falls back to the homepage.
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(firstComposition = true, hasNormalModeHistory = true),
        )
    }

    @Test
    fun `a crash reload still wins over an in-memory normal snapshot`() {
        assertEquals(
            FirstLoadAction.LoadInWebView("https://crashed.example"),
            decide(
                crashReload = "https://crashed.example",
                firstComposition = false,
                hasNormalModeHistory = true,
            ),
        )
    }

    @Test
    fun `no crash, no inbound, no history falls back to the homepage`() {
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(),
        )
    }
}
