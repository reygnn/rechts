package com.github.reygnn.rechts.ui.browser

import com.github.reygnn.rechts.domain.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure routing decision for a freshly created WebView's first load. Mirrors the
 * priority order the screen used to express inline: crash-reload first, then an
 * inbound link, then a process-death restore, else the homepage.
 */
class FirstLoadRouterTest {

    private fun decide(
        crashReload: String? = null,
        inbound: String? = null,
        firstComposition: Boolean = true,
        isPrivate: Boolean = false,
        hasSavedHistory: Boolean = false,
    ) = FirstLoadRouter.decide(crashReload, inbound, firstComposition, isPrivate, hasSavedHistory)

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
    fun `a mode switch (not first composition) never restores history`() {
        assertEquals(
            FirstLoadAction.LoadInWebView(UrlNormalizer.HOME_URL),
            decide(firstComposition = false, hasSavedHistory = true),
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
