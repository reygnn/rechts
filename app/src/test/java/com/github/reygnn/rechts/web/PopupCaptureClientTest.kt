package com.github.reygnn.rechts.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the popup-capture hardening (CLAUDE.md hard rule 6): the throwaway
 * `onCreateWindow` WebView must capture a popup's target and route it into the
 * main view, and — crucially — must NEVER load the target itself, even on a
 * WebView build that skips `shouldOverrideUrlLoading` for the popup's first
 * navigation.
 *
 * Robolectric only (real `WebResourceResponse`/`Uri`); the side effects are
 * injected, so no live WebView is needed. `postToMainThread` is captured into a
 * manual queue so the background-thread `shouldInterceptRequest` path is driven
 * deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PopupCaptureClientTest {

    private val routed = mutableListOf<String>()
    private var tornDown = 0
    private val posted = mutableListOf<Runnable>()

    private fun newClient() = PopupCaptureClient(
        routeTarget = { routed += it },
        tearDown = { tornDown++ },
        postToMainThread = { posted += it },
    )

    private fun runPosted() {
        // Copy first: a runnable may enqueue more (it doesn't here, but be safe).
        val pending = posted.toList()
        posted.clear()
        pending.forEach { it.run() }
    }

    private fun request(url: String, mainFrame: Boolean = true): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(url)
        every { isForMainFrame } returns mainFrame
    }

    private val anyWebView: WebView get() = mockk(relaxed = true)

    // ── shouldInterceptRequest: the safety net ────────────────────────────

    @Test
    fun `intercept blocks a main-frame target and captures it`() {
        val client = newClient()

        val response = client.shouldInterceptRequest(anyWebView, request("https://evil.test/"))

        // Blocked: a non-null, empty body instead of the real network load.
        assertTrue("must return a blocking response", response != null)
        assertEquals("text/plain", response!!.mimeType)
        assertEquals("utf-8", response.encoding)
        assertEquals("body must be empty", 0, response.data.available())

        // Routing is marshalled to the main thread, not done inline.
        assertTrue("nothing routed before the post runs", routed.isEmpty())
        runPosted()
        assertEquals(listOf("https://evil.test/"), routed)
        assertEquals(1, tornDown)
    }

    @Test
    fun `intercept ignores sub-resource requests`() {
        val client = newClient()

        val response = client.shouldInterceptRequest(
            anyWebView,
            request("https://evil.test/tracker.js", mainFrame = false),
        )

        assertNull("sub-resources are not the popup target", response)
        assertTrue(posted.isEmpty())
        assertTrue(routed.isEmpty())
    }

    @Test
    fun `intercept lets about blank through`() {
        val client = newClient()

        val response = client.shouldInterceptRequest(anyWebView, request("about:blank"))

        // about:blank is the popup's placeholder, not its destination.
        assertNull(response)
        assertTrue(posted.isEmpty())
        assertTrue(routed.isEmpty())
    }

    // ── shouldOverrideUrlLoading: the normal route ────────────────────────

    @Test
    fun `override routes a real target and consumes the navigation`() {
        val client = newClient()

        val handled = client.shouldOverrideUrlLoading(anyWebView, request("https://target.test/"))

        assertTrue("the popup navigation must be consumed", handled)
        // Routing happens synchronously; only teardown is posted.
        assertEquals(listOf("https://target.test/"), routed)
        assertEquals("teardown is deferred off the callback stack", 0, tornDown)
        runPosted()
        assertEquals(1, tornDown)
    }

    @Test
    fun `override lets about blank load in the capture view`() {
        val client = newClient()

        val handled = client.shouldOverrideUrlLoading(anyWebView, request("about:blank"))

        assertFalse("about:blank must load in the capture view", handled)
        assertTrue(routed.isEmpty())
        assertTrue(posted.isEmpty())
    }

    // ── idempotency between the two paths ─────────────────────────────────

    @Test
    fun `intercept after a successful override does not route twice`() {
        val client = newClient()

        // Normal path wins first.
        client.shouldOverrideUrlLoading(anyWebView, request("https://target.test/"))
        // A late intercept for the same navigation must not re-route or re-tear-down.
        client.shouldInterceptRequest(anyWebView, request("https://target.test/"))
        runPosted()

        assertEquals("routed exactly once", listOf("https://target.test/"), routed)
        assertEquals("torn down exactly once", 1, tornDown)
    }
}
