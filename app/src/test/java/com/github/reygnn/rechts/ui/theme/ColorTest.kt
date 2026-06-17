package com.github.reygnn.rechts.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the private-mode tint derivation so the documented relationship to the brand
 * teal can't drift silently. The math itself ([rotateHuePreservingSL]) is plain
 * JVM logic; the wired-up [md_light_privateTint] / [md_dark_privateTint] are checked
 * against the hand-verified hex values that shipped before the tints were derived.
 */
class ColorTest {

    // 0x006A6A (light teal) and 0x4CDADA (dark teal) both sit at hue 180° (cyan);
    // PRIVATE_TINT_HUE_SHIFT (125°) lands them on the same warm magenta at 305°.
    @Test
    fun `light teal rotates to the warm purple private tint`() {
        assertEquals(0x6A0061, rotateHuePreservingSL(0x006A6A, PRIVATE_TINT_HUE_SHIFT))
    }

    @Test
    fun `dark teal rotates to the warm purple private tint`() {
        assertEquals(0xDA4CCE, rotateHuePreservingSL(0x4CDADA, PRIVATE_TINT_HUE_SHIFT))
    }

    @Test
    fun `derived tint Colors match the pinned values`() {
        assertEquals(Color(0xFF6A0061), md_light_privateTint)
        assertEquals(Color(0xFFDA4CCE), md_dark_privateTint)
    }

    @Test
    fun `hue rotation preserves saturation and lightness`() {
        // A full turn is the identity, so S/L preservation is exact across the wheel.
        assertEquals(0x006A6A, rotateHuePreservingSL(0x006A6A, 360f))
        assertEquals(0x4CDADA, rotateHuePreservingSL(0x4CDADA, 360f))
    }

    @Test
    fun `greys have no hue and survive any rotation`() {
        // delta == 0: saturation is 0, so rotating the (undefined) hue is a no-op.
        assertEquals(0x808080, rotateHuePreservingSL(0x808080, 125f))
        assertEquals(0x000000, rotateHuePreservingSL(0x000000, 200f))
        assertEquals(0xFFFFFF, rotateHuePreservingSL(0xFFFFFF, 47f))
    }

    @Test
    fun `negative and over-360 rotations normalise`() {
        // -235° ≡ +125° ≡ 485°, so all reach the same magenta from the 180° teal.
        assertEquals(0x6A0061, rotateHuePreservingSL(0x006A6A, -235f))
        assertEquals(0x6A0061, rotateHuePreservingSL(0x006A6A, 485f))
    }
}
