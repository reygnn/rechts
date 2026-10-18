package com.github.reygnn.rechts.web

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * [WebViewClient] for the throwaway WebView that `onCreateWindow` spawns to
 * capture a popup's (`target="_blank"` / `window.open`) destination. rechts has
 * no tabs, so the captured URL is routed back into the main view instead of
 * opening a new window.
 *
 * The capture view carries a popup's first navigation back through two paths,
 * because neither is guaranteed on its own:
 *
 *  - [shouldOverrideUrlLoading] is the normal route. `window.open()` /
 *    `window.open('about:blank')` first navigates the popup to `about:blank`,
 *    which is *not* the real target — redirecting that into the main view would
 *    blank the user's page and discard the URL the opener sets via
 *    `w.location` afterwards. So `about:blank` is allowed to load and we keep
 *    waiting for the real navigation.
 *
 *  - [shouldInterceptRequest] is the safety net. `shouldOverrideUrlLoading` is
 *    NOT guaranteed to fire for a popup's engine-initiated first navigation; on
 *    a WebView build that skips it, the off-screen capture view would otherwise
 *    silently fetch the real target (network + cookies) until its timeout.
 *    Intercepting the main-frame request lets us capture the URL and return an
 *    empty body, so the throwaway view never actually loads the page. The leak
 *    is ruled out by construction regardless of engine behaviour.
 *
 * Side effects are injected so the decision logic stays JVM/Robolectric-testable
 * without a live WebView:
 *
 * @param routeTarget       routes a captured URL into the main view (or off to
 *                          another app). Always invoked on the main thread.
 * @param tearDown          tears the capture view down. The caller guards it
 *                          with the single-owner token (set membership), so the
 *                          screen tearing the view down first can't double-free.
 * @param postToMainThread  marshals a [Runnable] onto the main thread
 *                          (`WebView.post` in production). [shouldInterceptRequest]
 *                          runs on a background thread, so every main-thread
 *                          touch goes through this.
 *
 * Not thread-safe by itself: [captured] is plain mutable state, only ever
 * touched on the main thread (synchronously in [shouldOverrideUrlLoading], and
 * inside the posted runnable in [shouldInterceptRequest]). The `!captured` guard
 * there keeps the two paths idempotent — whichever fires first wins, the other
 * no-ops.
 */
// False positive: androidx.webkit's detector flags the WebViewClient() super
// call even though onRenderProcessGone is overridden below.
@SuppressLint("MissingOnRenderProcessGone")
class PopupCaptureClient(
    private val routeTarget: (String) -> Unit,
    private val tearDown: () -> Unit,
    private val postToMainThread: (Runnable) -> Unit,
) : WebViewClient() {

    private var captured = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // Let the capture view load about:blank; the real navigation follows.
        if (url == ABOUT_BLANK) return false
        captured = true
        routeTarget(url)
        // Tear down off the callback stack (destroy() mid-callback is unsafe).
        postToMainThread(Runnable { tearDown() })
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (!request.isForMainFrame || url == ABOUT_BLANK) return null
        postToMainThread(
            Runnable {
                if (!captured) {
                    captured = true
                    routeTarget(url)
                    tearDown()
                }
            },
        )
        // Block the fetch: hand back an empty body instead of letting the
        // capture view load the real target over the network.
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * The capture view shares the renderer with the main view. If it dies while
     * a capture is pending, returning the default `false` would let the platform
     * kill the whole app — even though the main view recovers on its own. Drop
     * the pending capture and tear the dead view down instead.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        captured = true
        postToMainThread(Runnable { tearDown() })
        return true
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
    }
}
