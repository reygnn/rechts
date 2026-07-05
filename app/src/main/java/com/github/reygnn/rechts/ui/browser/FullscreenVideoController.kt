package com.github.reygnn.rechts.ui.browser

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
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
 * job is to display it edge-to-edge over the whole activity, hide the system
 * bars, and rotate to landscape so the video fills the screen (like Chrome),
 * then tear it all down and restore the prior orientation on exit. The custom
 * view is added straight to the window's [Activity.getWindow]`.decorView`, so it
 * naturally covers the Compose chrome (address bar) underneath — no Compose
 * visibility state to toggle.
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

    // Window state we mutate on show and restore on hide, captured at show time
    // so exit puts back exactly what was there before rather than a hard-coded
    // default: the system-bars behavior and the bar-icon appearance.
    private var previousBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    private var previousLightStatusBars = false
    private var previousLightNavigationBars = false
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * Displays [view] fullscreen and remembers [cb] so [hide] can tell the page
     * we're done. A pending fullscreen view (degenerate, but possible) is
     * dismissed first so its page isn't left believing it's still fullscreen.
     */
    fun show(view: View, cb: WebChromeClient.CustomViewCallback) {
        val firstEntry = customView == null
        if (customView != null) hide()
        // Capture the app's orientation once, before forcing landscape, so a
        // degenerate second show() (customView already set) doesn't overwrite the
        // real pre-fullscreen value with the landscape we just imposed.
        if (firstEntry) previousOrientation = activity.requestedOrientation
        customView = view
        callback = cb
        // Fill the screen like a video player: rotate to landscape so a landscape
        // clip isn't letterboxed in portrait (matches Chrome). SENSOR_LANDSCAPE
        // follows the sensor between the two landscape orientations. Restored to
        // the app's default in hide(). The black background below still backstops
        // a portrait clip (pillarboxed here) and the rotation transition.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Hide the whole page content behind the fullscreen view so nothing bleeds
        // through any transparent region of the platform view — a video SurfaceView
        // punches its own surface, and letterbox / rounded corners can otherwise
        // reveal the page. Driven here (not by the Compose isFullscreen flag) so it
        // pairs exactly with the custom view's real lifecycle: a rotation that
        // re-issues onHide/onShow toggles it in lockstep, and a genuine exit shows
        // the page again. INVISIBLE (not GONE) keeps the WebView attached/laid out
        // so playback and page state are untouched.
        contentView().visibility = View.INVISIBLE
        // Paint the fullscreen container black as well, so its own letterbox area
        // (a clip that doesn't fill the screen) is black rather than the theme's
        // window background now showing behind the hidden content.
        view.setBackgroundColor(Color.BLACK)
        decorView().addView(
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
            previousBarsBehavior = systemBarsBehavior
            previousLightStatusBars = isAppearanceLightStatusBars
            previousLightNavigationBars = isAppearanceLightNavigationBars
            // A fullscreen video surface is black; force light (white) bar icons
            // so the transient bars stay legible over it, whatever the page chrome
            // asked for. Restored in hide().
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onShowingChanged(true)
    }

    /**
     * Removes the fullscreen view and restores the normal chrome/system bars.
     * Idempotent — a no-op when nothing is showing, so it's safe to call from
     * the page's `onHideCustomView`, the back handler, and teardown.
     *
     * Notifies the page via [WebChromeClient.CustomViewCallback.onCustomViewHidden]
     * so a host-initiated exit (back / teardown) also takes the page's video out
     * of its fullscreen state. That callback belongs to the render process and can
     * throw once its WebView is torn down (a renderer crash while fullscreen drives
     * this hide() from teardown), so it runs last, guarded — our own state and the
     * window are already restored by then, otherwise a throw would leave the back
     * handler armed over a no-op hide() with the system bars still hidden.
     */
    fun hide() {
        val view = customView ?: return
        val cb = callback
        customView = null
        callback = null
        decorView().removeView(view)
        contentView().visibility = View.VISIBLE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = previousOrientation
        insetsController().apply {
            show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = previousBarsBehavior
            isAppearanceLightStatusBars = previousLightStatusBars
            isAppearanceLightNavigationBars = previousLightNavigationBars
        }
        onShowingChanged(false)
        runCatching { cb?.onCustomViewHidden() }
    }

    private fun decorView(): ViewGroup = activity.window.decorView as ViewGroup

    /** The activity's content host (Compose chrome + WebView), a sibling of the
     *  custom view added straight to the decorView — hidden while fullscreen. */
    private fun contentView(): View = activity.findViewById(android.R.id.content)

    private fun insetsController(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
}
