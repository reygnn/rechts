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
 * bars, and rotate the screen to match the video's shape (landscape for a wide
 * clip, portrait for a tall one, like Chrome), then tear it all down and restore
 * the prior orientation on exit. The custom view is added straight to the
 * window's [Activity.getWindow]`.decorView`, so it naturally covers the Compose
 * chrome (address bar) underneath — no Compose visibility state to toggle.
 *
 * The video's aspect ratio isn't known synchronously, so [show] leaves the
 * orientation alone and the caller locks it via [lockOrientation] once an async
 * JS probe ([VIDEO_DIMENSIONS_JS] / [isPortraitVideo]) has read the dimensions.
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
    private var previousContentVisibility = View.VISIBLE

    /**
     * Displays [view] fullscreen and remembers [cb] so [hide] can tell the page
     * we're done. A pending fullscreen view (degenerate, but possible) is
     * dismissed first so its page isn't left believing it's still fullscreen.
     */
    fun show(view: View, cb: WebChromeClient.CustomViewCallback) {
        val firstEntry = customView == null
        if (customView != null) hide()
        // Capture the app's orientation once, before we lock it to the video's
        // shape, so a degenerate second show() (customView already set) doesn't
        // overwrite the real pre-fullscreen value with a lock we just imposed.
        if (firstEntry) previousOrientation = activity.requestedOrientation
        customView = view
        callback = cb
        // Orientation is set later, once the video's aspect ratio is known — see
        // lockOrientation(), called from onShowCustomView after an async JS probe.
        // Until then the current orientation stays: a landscape clip watched in
        // portrait rotates a frame later, a portrait clip never rotates at all.
        // Hide the whole page content behind the fullscreen view so nothing bleeds
        // through any transparent region of the platform view — a video SurfaceView
        // punches its own surface, and letterbox / rounded corners can otherwise
        // reveal the page. Driven here (not by the Compose isFullscreen flag) so it
        // pairs exactly with the custom view's real lifecycle: a rotation that
        // re-issues onHide/onShow toggles it in lockstep, and a genuine exit shows
        // the page again. INVISIBLE (not GONE) keeps the WebView attached/laid out
        // so playback and page state are untouched. Capture-and-restore the prior
        // visibility (like the bars/orientation below) rather than assuming VISIBLE.
        val content = contentView()
        previousContentVisibility = content.visibility
        content.visibility = View.INVISIBLE
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
     * Locks the screen to the fullscreen video's shape — landscape for a wide
     * clip (fills the screen like Chrome), [SCREEN_ORIENTATION_SENSOR_PORTRAIT]
     * for a tall one (no sideways pillarboxing). Called once the aspect ratio is
     * known, which is async, so it no-ops when the user already left fullscreen
     * by the time the probe returns. [hide] restores the pre-fullscreen value.
     */
    fun lockOrientation(portrait: Boolean) {
        if (customView == null) return
        activity.requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
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
        contentView().visibility = previousContentVisibility
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

/**
 * JS that reads the first `<video>`'s intrinsic size as `"<w>x<h>"` (e.g.
 * `1920x1080`), or `""` if there's no video. Fed to [isPortraitVideo]. Kept
 * next to that function so the probe and its parser stay in sync.
 */
internal const val VIDEO_DIMENSIONS_JS: String =
    "(function(){var v=document.querySelector('video');" +
        "return v?v.videoWidth+'x'+v.videoHeight:'';})()"

/**
 * Decides whether a fullscreen video should lock to portrait, from the raw
 * result of [VIDEO_DIMENSIONS_JS] as `evaluateJavascript` delivers it: a
 * JSON-encoded string like `"1080x1920"` (with the quotes), or `"null"` / `""`
 * when the value is absent. Portrait only when the height clearly exceeds the
 * width; anything square, zero, or unparseable falls back to landscape — the
 * sensible default for the common, wide clip. Pure, so it's JVM-unit-tested.
 */
internal fun isPortraitVideo(evaluateJsResult: String?): Boolean {
    val (width, height) = evaluateJsResult
        ?.trim()
        ?.trim('"')
        ?.split('x')
        ?.takeIf { it.size == 2 }
        ?: return false
    val w = width.toIntOrNull() ?: return false
    val h = height.toIntOrNull() ?: return false
    return w > 0 && h > 0 && h > w
}
