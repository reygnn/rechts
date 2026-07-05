package com.github.reygnn.rechts.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic for [isPortraitVideo]: it turns the raw `evaluateJavascript` result
 * of [VIDEO_DIMENSIONS_JS] into the portrait-vs-landscape lock decision. The
 * View/Window side (FullscreenVideoController.lockOrientation) needs the Android
 * runtime and is left to manual/device verification.
 */
class FullscreenVideoOrientationTest {

    @Test
    fun `wide clip locks landscape`() {
        // evaluateJavascript delivers the JS string result WITH surrounding quotes.
        assertFalse(isPortraitVideo("\"1920x1080\""))
        assertFalse(isPortraitVideo("\"640x480\""))
    }

    @Test
    fun `tall clip locks portrait`() {
        assertTrue(isPortraitVideo("\"1080x1920\""))
        assertTrue(isPortraitVideo("\"720x1280\""))
    }

    @Test
    fun `square falls back to landscape`() {
        assertFalse(isPortraitVideo("\"1000x1000\""))
    }

    @Test
    fun `no video element falls back to landscape`() {
        // The probe returns "" when document.querySelector('video') is null.
        assertFalse(isPortraitVideo("\"\""))
        // A JS null (defensive) arrives as the literal "null".
        assertFalse(isPortraitVideo("null"))
    }

    @Test
    fun `zero dimensions fall back to landscape`() {
        // videoWidth/Height are 0 before metadata loads.
        assertFalse(isPortraitVideo("\"0x0\""))
        assertFalse(isPortraitVideo("\"0x1080\""))
    }

    @Test
    fun `malformed results fall back to landscape`() {
        assertFalse(isPortraitVideo("\"abc\""))
        assertFalse(isPortraitVideo("\"1920x\""))
        assertFalse(isPortraitVideo("\"1920x1080x50\""))
        assertFalse(isPortraitVideo("\"-1080x1920\""))
    }

    @Test
    fun `kotlin null falls back to landscape`() {
        assertFalse(isPortraitVideo(null))
    }

    @Test
    fun `unquoted dimensions still parse`() {
        assertTrue(isPortraitVideo("1080x1920"))
        assertFalse(isPortraitVideo("1920x1080"))
    }
}
