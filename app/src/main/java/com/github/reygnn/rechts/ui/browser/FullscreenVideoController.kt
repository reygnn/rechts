package com.github.reygnn.rechts.ui.browser

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hosts the WebView's HTML5 fullscreen view (a `<video>` going fullscreen, or
 * any element requesting the Fullscreen API).
 *
 * The platform hands `WebChromeClient.onShowCustomView` a ready-made view; our
 * only job is to display it edge-to-edge over the whole activity and hide the
 * system bars, then tear it all down again on exit. The custom view is added
 * straight to the window's [Activity.getWindow]`.decorView`, so it naturally
 * covers the Compose chrome (address bar) underneath — no Compose visibility
 * state to toggle.
 *
 * Only one fullscreen view exists at a time: a second [show] dismisses the
 * previous one first (notifying its page) before taking over.
 *
 * Not pure — this is inherently View/Window manipulation, so it lives outside
 * the JVM-tested logic. It carries no page data, so nothing here touches the
 * private-mode / persistence invariants.
 *
 * @param onShowingChanged mirrors the fullscreen state into Compose so the back
 *   handler can exit fullscreen instead of navigating page history.
 */
class FullscreenVideoController(
    private val activity: Activity,
    private val onShowingChanged: (Boolean) -> Unit,
) {
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null

    /** Whether a fullscreen view is currently displayed. */
    val isShowing: Boolean get() = customView != null

    /**
     * Displays [view] fullscreen and remembers [cb] so [hide] can tell the page
     * we're done. A pending fullscreen view (degenerate, but possible) is
     * dismissed first so its page isn't left believing it's still fullscreen.
     */
    fun show(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) hide()
        customView = view
        callback = cb
        (activity.window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Keep the screen awake during playback — a fullscreen video is an
        // explicit "I'm watching this" signal, not idle time.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insetsController().apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onShowingChanged(true)
    }

    /**
     * Removes the fullscreen view and restores the normal chrome/system bars.
     * Idempotent — a no-op when nothing is showing, so it's safe to call from
     * both the page's `onHideCustomView`, the back handler, and teardown.
     *
     * Notifies the page via [WebChromeClient.CustomViewCallback.onCustomViewHidden]
     * so a host-initiated exit (back / teardown) also takes the page's video out
     * of its fullscreen state.
     */
    fun hide() {
        val view = customView ?: return
        (activity.window.decorView as ViewGroup).removeView(view)
        customView = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insetsController().show(WindowInsetsCompat.Type.systemBars())
        callback?.onCustomViewHidden()
        callback = null
        onShowingChanged(false)
    }

    private fun insetsController(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
}
