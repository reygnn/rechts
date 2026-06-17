package com.github.reygnn.rechts.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────
// Static teal brand scheme, aligned with the launcher icon background
// (#006A6A). Fully populated per MATERIAL3RULES A4 — every role is set in
// both light and dark so no role falls back to an M3 purple default.
//
// All roles are `internal`: only Theme.kt (same module) wires them into the
// schemes, and the private tints flow out through `privateTintFor` — nothing
// imports a raw `md_*` colour from another package.
// ─────────────────────────────────────────────────────────────────────────

// Light
internal val md_light_primary = Color(0xFF006A6A)
internal val md_light_onPrimary = Color(0xFFFFFFFF)
internal val md_light_primaryContainer = Color(0xFF71F7F7)
internal val md_light_onPrimaryContainer = Color(0xFF002020)
internal val md_light_inversePrimary = Color(0xFF4CDADA)
internal val md_light_secondary = Color(0xFF4A6363)
internal val md_light_onSecondary = Color(0xFFFFFFFF)
internal val md_light_secondaryContainer = Color(0xFFCCE8E8)
internal val md_light_onSecondaryContainer = Color(0xFF051F1F)
internal val md_light_tertiary = Color(0xFF4B607C)
internal val md_light_onTertiary = Color(0xFFFFFFFF)
internal val md_light_tertiaryContainer = Color(0xFFD3E4FF)
internal val md_light_onTertiaryContainer = Color(0xFF041C35)
internal val md_light_error = Color(0xFFBA1A1A)
internal val md_light_onError = Color(0xFFFFFFFF)
internal val md_light_errorContainer = Color(0xFFFFDAD6)
internal val md_light_onErrorContainer = Color(0xFF410002)
internal val md_light_background = Color(0xFFFAFDFD)
internal val md_light_onBackground = Color(0xFF191C1C)
internal val md_light_surface = Color(0xFFFAFDFD)
internal val md_light_onSurface = Color(0xFF191C1C)
internal val md_light_surfaceVariant = Color(0xFFDAE5E5)
internal val md_light_onSurfaceVariant = Color(0xFF3F4949)
internal val md_light_surfaceTint = md_light_primary
internal val md_light_surfaceBright = Color(0xFFFAFDFD)
internal val md_light_surfaceDim = Color(0xFFD7DBDA)
internal val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
internal val md_light_surfaceContainerLow = Color(0xFFF1F4F4)
internal val md_light_surfaceContainer = Color(0xFFEBEEEE)
internal val md_light_surfaceContainerHigh = Color(0xFFE6E9E8)
internal val md_light_surfaceContainerHighest = Color(0xFFE0E3E3)
internal val md_light_inverseSurface = Color(0xFF2D3131)
internal val md_light_inverseOnSurface = Color(0xFFEFF1F0)
internal val md_light_outline = Color(0xFF6F7979)
internal val md_light_outlineVariant = Color(0xFFBEC9C8)
internal val md_light_scrim = Color(0xFF000000)

// Dark
internal val md_dark_primary = Color(0xFF4CDADA)
internal val md_dark_onPrimary = Color(0xFF003737)
internal val md_dark_primaryContainer = Color(0xFF005050)
internal val md_dark_onPrimaryContainer = Color(0xFF71F7F7)
internal val md_dark_inversePrimary = Color(0xFF006A6A)
internal val md_dark_secondary = Color(0xFFB1CCCC)
internal val md_dark_onSecondary = Color(0xFF1C3434)
internal val md_dark_secondaryContainer = Color(0xFF324B4B)
internal val md_dark_onSecondaryContainer = Color(0xFFCCE8E8)
internal val md_dark_tertiary = Color(0xFFA4C8FF)
internal val md_dark_onTertiary = Color(0xFF1A314B)
internal val md_dark_tertiaryContainer = Color(0xFF324863)
internal val md_dark_onTertiaryContainer = Color(0xFFD3E4FF)
internal val md_dark_error = Color(0xFFFFB4AB)
internal val md_dark_onError = Color(0xFF690005)
internal val md_dark_errorContainer = Color(0xFF93000A)
internal val md_dark_onErrorContainer = Color(0xFFFFDAD6)
internal val md_dark_background = Color(0xFF191C1C)
internal val md_dark_onBackground = Color(0xFFE0E3E3)
internal val md_dark_surface = Color(0xFF191C1C)
internal val md_dark_onSurface = Color(0xFFE0E3E3)
internal val md_dark_surfaceVariant = Color(0xFF3F4949)
internal val md_dark_onSurfaceVariant = Color(0xFFBFC9C9)
internal val md_dark_surfaceTint = md_dark_primary
internal val md_dark_surfaceBright = Color(0xFF353A39)
internal val md_dark_surfaceDim = Color(0xFF101414)
internal val md_dark_surfaceContainerLowest = Color(0xFF0B0F0F)
internal val md_dark_surfaceContainerLow = Color(0xFF191C1C)
internal val md_dark_surfaceContainer = Color(0xFF1D2120)
internal val md_dark_surfaceContainerHigh = Color(0xFF272B2A)
internal val md_dark_surfaceContainerHighest = Color(0xFF323535)
internal val md_dark_inverseSurface = Color(0xFFE0E3E3)
internal val md_dark_inverseOnSurface = Color(0xFF191C1C)
internal val md_dark_outline = Color(0xFF899393)
internal val md_dark_outlineVariant = Color(0xFF3F4949)
internal val md_dark_scrim = Color(0xFF000000)

// ─────────────────────────────────────────────────────────────────────────
// Private-mode chrome tint. DERIVED from the teal surfaceTint, not picked: keep
// the teal's HSL saturation and lightness and rotate the hue from ~180° (cyan)
// to ~305° (a warm violet-magenta on the opposite side of the wheel). So the
// private bar reads at the same intensity as the normal "dark green" bar — only
// the hue changes — and lands on a warm purple rather than a cool lavender.
//
// Computed at class-init from `md_*_primary` (== the surfaceTints) so the
// relationship can never silently drift when the brand teal is retuned: change
// the teal and the private tint follows. The HSL hue-rotation is pinned by
// ColorTest. One per mode, mirroring how surfaceTint flips light↔dark (each
// keeps its own teal's S/L); the light/dark *selection* lives in
// `privateTintFor` (Theme.kt), the single place callers go through.
// ─────────────────────────────────────────────────────────────────────────

/** Cyan (~180°) → warm violet-magenta (~305°): the hue shift applied to the teal. */
internal const val PRIVATE_TINT_HUE_SHIFT = 125f

internal val md_light_privateTint = md_light_primary.rotateHuePreservingSL(PRIVATE_TINT_HUE_SHIFT)
internal val md_dark_privateTint = md_dark_primary.rotateHuePreservingSL(PRIVATE_TINT_HUE_SHIFT)

/**
 * Rotate this opaque colour's hue by [degrees], preserving its HSL saturation and
 * lightness (and alpha). The core math runs on plain Ints (0xRRGGBB) so it stays
 * JVM-unit-testable without a Compose runtime — see [rotateHuePreservingSL].
 */
internal fun Color.rotateHuePreservingSL(degrees: Float): Color {
    val rotated = rotateHuePreservingSL(toArgb() and 0xFFFFFF, degrees)
    return Color(0xFF000000.toInt() or rotated)
}

/**
 * Pure sRGB hue rotation that preserves HSL saturation and lightness.
 *
 * Input and output are opaque 24-bit colours packed as 0xRRGGBB. Dependency-free
 * (no Compose, no Android) on purpose: this is the one piece of real colour logic
 * and must remain a plain JVM unit test, like [com.github.reygnn.rechts.domain.UrlNormalizer].
 */
internal fun rotateHuePreservingSL(rgb: Int, degrees: Float): Int {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val delta = max - min
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
    var h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    h = (h + degrees) % 360f
    if (h < 0f) h += 360f

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    fun comp(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return (comp(r1) shl 16) or (comp(g1) shl 8) or comp(b1)
}
