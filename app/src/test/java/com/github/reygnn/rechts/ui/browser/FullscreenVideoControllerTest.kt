package com.github.reygnn.rechts.ui.browser

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the observable state transitions of [FullscreenVideoController]. It's
 * genuine Window/View wiring (decorView attach, content visibility, window
 * flags, requestedOrientation), so it needs the Android runtime — Robolectric,
 * per app/src/test/CLAUDE.md. The pure aspect-ratio decision is tested
 * separately in FullscreenVideoOrientationTest (fast JVM).
 *
 * The system-bar / insets side of show()/hide() isn't asserted (Robolectric has
 * no real bars); these tests cover the parts a caller depends on: the custom
 * view is hosted and torn down, the page is hidden and restored, the page
 * callback fires exactly once, and orientation is locked and restored correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullscreenVideoControllerTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val showing = mutableListOf<Boolean>()
    private val controller = FullscreenVideoController(activity) { showing += it }

    private fun decorView(): ViewGroup = activity.window.decorView as ViewGroup
    private fun content(): View = activity.findViewById(android.R.id.content)
    private fun keepScreenOn(): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun callback() = mockk<WebChromeClient.CustomViewCallback>(relaxed = true)

    @Test
    fun `show hosts the view, hides the page and mirrors state`() {
        val view = View(activity)

        controller.show(view, callback())

        assertSame("custom view is attached to the decorView", decorView(), view.parent)
        assertEquals("page content is hidden", View.INVISIBLE, content().visibility)
        assertTrue("screen kept awake", keepScreenOn())
        assertEquals(listOf(true), showing)
    }

    @Test
    fun `hide tears down, restores the page, notifies once and mirrors state`() {
        val view = View(activity)
        val cb = callback()
        controller.show(view, cb)

        controller.hide()

        assertNull("custom view is detached", view.parent)
        assertEquals("page content is restored", View.VISIBLE, content().visibility)
        assertTrue("screen-awake flag cleared", !keepScreenOn())
        verify(exactly = 1) { cb.onCustomViewHidden() }
        assertEquals(listOf(true, false), showing)
    }

    @Test
    fun `hide without a live custom view is a no-op`() {
        controller.hide()

        assertEquals("VISIBLE stays VISIBLE", View.VISIBLE, content().visibility)
        assertTrue(showing.isEmpty())
    }

    @Test
    fun `hide is idempotent - the page is notified only once`() {
        val cb = callback()
        controller.show(View(activity), cb)
        controller.hide()

        controller.hide()

        verify(exactly = 1) { cb.onCustomViewHidden() }
        assertEquals(listOf(true, false), showing)
    }

    @Test
    fun `lockOrientation applies portrait or landscape while showing`() {
        controller.show(View(activity), callback())

        controller.lockOrientation(portrait = true)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            activity.requestedOrientation,
        )

        controller.lockOrientation(portrait = false)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            activity.requestedOrientation,
        )
    }

    @Test
    fun `lockOrientation after hide is ignored - the async probe cannot re-rotate`() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        controller.show(View(activity), callback())
        controller.hide()

        controller.lockOrientation(portrait = false)

        // hide() already restored the pre-fullscreen orientation; a late probe
        // result must not flip the (no-longer-fullscreen) screen to landscape.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }

    @Test
    fun `hide restores the pre-fullscreen orientation`() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        controller.show(View(activity), callback())
        controller.lockOrientation(portrait = false)

        controller.hide()

        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }

    @Test
    fun `a second show replaces the first and notifies the first page`() {
        val first = View(activity)
        val firstCb = callback()
        val second = View(activity)
        controller.show(first, firstCb)

        controller.show(second, callback())

        verify(exactly = 1) { firstCb.onCustomViewHidden() }
        assertNull("first view detached", first.parent)
        assertSame("second view hosted", decorView(), second.parent)
    }

    @Test
    fun `a degenerate second show keeps the true pre-fullscreen orientation`() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        controller.show(View(activity), callback())
        controller.lockOrientation(portrait = false)

        // Re-show without an intervening hide: previousOrientation must NOT become
        // the landscape we imposed, so the eventual hide() still restores PORTRAIT.
        controller.show(View(activity), callback())
        controller.hide()

        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
    }
}
